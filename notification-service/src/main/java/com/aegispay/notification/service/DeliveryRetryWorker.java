package com.aegispay.notification.service;

import com.aegispay.common.observability.MetricNames;
import com.aegispay.notification.config.NotificationProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drains the delivery retry queue.
 *
 * <h2>Why this exists rather than leaning on Kafka's retry</h2>
 *
 * <p>The platform already has a retry-and-dead-letter path for events: the shared error handler retries a
 * failed record and then publishes it to {@code notification-required.DLT}. That is the right mechanism
 * for a record that could not be <em>processed</em>, and this service still relies on it.
 *
 * <p>It is the wrong mechanism for a message that could not be <em>sent</em>:
 *
 * <ul>
 *   <li><strong>A partition is ordered.</strong> Backing off a record because one SMTP host is slow stops
 *       every later notification on that partition — other customers, other payments.</li>
 *   <li><strong>Redelivery replays the whole event.</strong> Channels that already succeeded would be
 *       attempted again, so a transient SMS failure would re-send the email that already arrived.</li>
 *   <li><strong>A DLT is a topic, not a work queue.</strong> Nothing retries it on a schedule, and
 *       nothing can answer "which customers are currently un-notified".</li>
 * </ul>
 *
 * <h2>Safe on every instance</h2>
 *
 * <p>Work is claimed with {@code FOR UPDATE SKIP LOCKED}, so two pods take different rows rather than
 * duplicating a message or serialising behind each other. Nothing here is leader-elected and nothing needs
 * to be.
 */
@Component
public class DeliveryRetryWorker {

    private static final Logger log = LoggerFactory.getLogger(DeliveryRetryWorker.class);

    private final NotificationMutationService mutations;
    private final NotificationDeliveryService deliveries;
    private final NotificationProperties properties;

    /**
     * Backs a gauge rather than being read from the database on scrape.
     *
     * <p>Refreshed on the sweep the worker was going to run anyway, so a metrics scrape costs nothing. A
     * gauge that queried on demand would let a monitoring system's poll interval drive database load
     * against a table that is already the busiest thing here.
     */
    private final AtomicLong backlog = new AtomicLong();

    public DeliveryRetryWorker(NotificationMutationService mutations,
                               NotificationDeliveryService deliveries,
                               NotificationProperties properties,
                               MeterRegistry meterRegistry) {
        this.mutations = mutations;
        this.deliveries = deliveries;
        this.properties = properties;

        meterRegistry.gauge(MetricNames.NOTIFICATION_RETRY_BACKLOG, backlog, AtomicLong::get);
    }

    /**
     * Claims a batch of due deliveries and attempts each one.
     *
     * <p>{@code fixedDelay} rather than {@code fixedRate}, so a sweep that takes longer than the interval
     * does not have the next one start on top of it — overlapping rounds would contend for the same rows
     * and turn a slow provider into a queue of workers all waiting on it.
     *
     * <p>Not transactional. The claim is its own short transaction inside
     * {@link NotificationMutationService#claimDue}, and each attempt commits separately, so a batch of
     * fifty deliveries is fifty independent outcomes rather than one all-or-nothing block.
     */
    @Scheduled(fixedDelayString = "${aegis.notification.retry.poll-interval:5s}",
            initialDelayString = "${aegis.notification.retry.initial-delay:15s}")
    public void drain() {
        try {
            List<String> claimed = mutations.claimDue(properties.getRetry().getBatchSize());

            if (!claimed.isEmpty()) {
                log.debug("Retrying {} due notification deliver{}",
                        claimed.size(), claimed.size() == 1 ? "y" : "ies");

                for (String deliveryId : claimed) {
                    // Each attempt is already exception-free by contract: every outcome becomes committed
                    // state. Guarded anyway, because one unexpected fault must not abandon the rest of a
                    // claimed batch -- those rows have been unlocked by the commit and would simply sit
                    // until the next sweep, but the sweep after a thrown exception would start from
                    // scratch and the delay compounds.
                    try {
                        deliveries.attempt(deliveryId);
                    } catch (RuntimeException ex) {
                        log.error("Retry of delivery {} failed unexpectedly; continuing with the rest "
                                + "of the batch", deliveryId, ex);
                    }
                }
            }
            backlog.set(mutations.retryBacklog());

        } catch (RuntimeException ex) {
            // Swallowed on purpose. An exception escaping a @Scheduled method cancels nothing in Spring,
            // but it does log a stack trace per sweep and provides no recovery -- whereas a database blip
            // resolves on the next round. Logged at ERROR so a persistent failure is still visible.
            log.error("Notification retry sweep failed; the backlog will be retried on the next round",
                    ex);
        }
    }

    /** The current backlog, for the admin view. */
    public long backlog() {
        return backlog.get();
    }
}
