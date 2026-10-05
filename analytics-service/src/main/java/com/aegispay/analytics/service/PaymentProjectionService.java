package com.aegispay.analytics.service;

import com.aegispay.analytics.domain.PaymentFact;
import com.aegispay.analytics.domain.StreamState;
import com.aegispay.analytics.repository.PaymentFactRepository;
import com.aegispay.analytics.repository.StreamStateRepository;
import com.aegispay.common.domain.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.function.Consumer;

/**
 * The only thing that writes the read model.
 *
 * <h2>Why every listener goes through one method</h2>
 *
 * <p>Six listeners build one row. Each needs the same three things: find-or-create the fact, apply its
 * own fields, and advance the stream's high-water mark. Done six times, that is five opportunities to
 * forget the high-water mark or to create the row slightly differently — and the second would show up
 * as a payment whose creation time depends on which event happened to arrive first.
 *
 * <p>So {@link #apply} takes the per-event part as a lambda and owns everything around it.
 *
 * <h2>Why losing an insert race is not an error</h2>
 *
 * <p>Two listeners can process two events for the same payment concurrently — different topics,
 * different partitions, different consumer threads. Both find no row and both insert. The unique
 * constraint on {@code payment_id} decides, and the loser re-reads and applies its own fields to the
 * winner's row.
 *
 * <p>That is the expected path rather than an exception path, which is why the constraint exists at all:
 * a check-then-insert in application code cannot be made safe here, because the check and the insert are
 * separate statements and nothing serialises the two threads between them.
 */
@Service
public class PaymentProjectionService {

    private static final Logger log = LoggerFactory.getLogger(PaymentProjectionService.class);

    private final PaymentFactRepository facts;
    private final StreamStateRepository streams;

    public PaymentProjectionService(PaymentFactRepository facts, StreamStateRepository streams) {
        this.facts = facts;
        this.streams = streams;
    }

    /**
     * Applies one event to the read model.
     *
     * @param topic       the topic it arrived on, for the per-topic freshness figure
     * @param paymentId   the payment it concerns
     * @param seedStatus  the status to create the row with if this event is the first one seen for the
     *                    payment. Every listener supplies its own, because any of them can arrive first
     *                    — assuming {@code payment-created} comes first is the mistake this parameter
     *                    exists to prevent.
     * @param occurredAt  the event's own timestamp, not now(). Lag is measured against the platform's
     *                    clock, so using the processing time would report zero lag while hours behind.
     * @param mutation    the per-event field updates
     */
    @Transactional
    public void apply(String topic, String paymentId, PaymentStatus seedStatus, Instant occurredAt,
                      Consumer<PaymentFact> mutation) {

        if (paymentId == null || paymentId.isBlank()) {
            // Non-retryable on purpose: the shared Kafka error handler dead-letters
            // IllegalArgumentException on the first attempt. An event with no payment id will be just
            // as unusable on the fourth, and retrying only delays the dead-letter record somebody needs
            // in order to fix the producer.
            throw new IllegalArgumentException(
                    "an event on " + topic + " carried no payment id, so there is nothing to project");
        }

        PaymentFact fact = facts.findByPaymentId(paymentId)
                .orElseGet(() -> insert(paymentId, seedStatus, occurredAt));

        mutation.accept(fact);
        facts.save(fact);

        advanceStream(topic, occurredAt);
    }

    /**
     * Inserts the row, tolerating a concurrent insert of the same payment.
     *
     * <p>{@code saveAndFlush} rather than {@code save}: the constraint violation has to surface here,
     * inside the {@code try}, rather than at the end of the transaction where it would be an
     * unrecoverable rollback instead of a re-read.
     */
    private PaymentFact insert(String paymentId, PaymentStatus seedStatus, Instant occurredAt) {
        try {
            return facts.saveAndFlush(PaymentFact.seed(paymentId, seedStatus, occurredAt));

        } catch (DataIntegrityViolationException ex) {
            PaymentFact winner = facts.findByPaymentId(paymentId).orElseThrow(() -> ex);
            log.debug("Payment {} was projected concurrently; merging into the existing fact",
                    paymentId);
            return winner;
        }
    }

    /**
     * Moves a topic's high-water mark.
     *
     * <p>Runs on every applied event, including one that changed nothing. That is deliberate: a stream
     * delivering only redeliveries is still a live stream, and reporting it as stale would send somebody
     * investigating a consumer that is working perfectly.
     */
    private void advanceStream(String topic, Instant occurredAt) {
        streams.findById(topic).ifPresentOrElse(
                state -> {
                    state.observe(occurredAt);
                    streams.save(state);
                },
                () -> {
                    try {
                        streams.saveAndFlush(StreamState.of(topic, occurredAt));
                    } catch (DataIntegrityViolationException ex) {
                        // Two threads first-seeing the same topic at once. The row exists either way,
                        // and the next event will advance it, so there is nothing to recover.
                        log.debug("Stream state for {} was created concurrently", topic);
                    }
                });
    }

    /** The newest event applied to any payment. Null when nothing has been projected yet. */
    @Transactional(readOnly = true)
    public Instant newestEventApplied() {
        return facts.newestEventApplied().orElse(null);
    }
}
