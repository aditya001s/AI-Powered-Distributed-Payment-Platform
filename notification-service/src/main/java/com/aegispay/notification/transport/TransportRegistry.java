package com.aegispay.notification.transport;

import com.aegispay.common.domain.SharedEnums.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The transport for each channel.
 *
 * <p>Built from every {@link NotificationTransport} bean on the classpath, which is what makes replacing
 * the simulated email transport with a real one a matter of adding a bean rather than editing the
 * delivery service.
 *
 * <p>Two transports claiming one channel is a startup failure. It is the kind of mistake that would
 * otherwise resolve by whichever bean the component scan happened to reach second, so the same build
 * could send through a real relay on one machine and a simulator on another — and both would look fine.
 */
@Component
public class TransportRegistry {

    private static final Logger log = LoggerFactory.getLogger(TransportRegistry.class);

    private final Map<NotificationChannel, NotificationTransport> byChannel;

    public TransportRegistry(List<NotificationTransport> transports) {
        Map<NotificationChannel, NotificationTransport> resolved =
                new EnumMap<>(NotificationChannel.class);

        for (NotificationTransport transport : transports) {
            NotificationTransport existing = resolved.put(transport.channel(), transport);
            if (existing != null) {
                throw new IllegalStateException(
                        "two transports claim the " + transport.channel() + " channel: "
                                + existing.getClass().getName() + " and "
                                + transport.getClass().getName()
                                + ". Which one sends would depend on component-scan order, so the "
                                + "same build could behave differently on two machines.");
            }
        }
        this.byChannel = Map.copyOf(resolved);

        log.info("Notification transports registered: {}",
                this.byChannel.entrySet().stream()
                        .map(entry -> entry.getKey() + " -> "
                                + entry.getValue().getClass().getSimpleName()
                                + (entry.getValue().isAvailable() ? "" : " (unavailable)"))
                        .sorted()
                        .toList());

        // Not fatal, and worth saying out loud. PUSH has no transport because this platform has no
        // mobile application and no device-token registry, and NotificationPayload.security(...) asks
        // for it -- so the absence is reached in normal operation and must be explicable.
        if (!this.byChannel.containsKey(NotificationChannel.PUSH)) {
            log.info("""
                    No PUSH transport is registered. Push notifications are not available on this \
                    platform: there is no mobile application and no device-token registry to send to. \
                    Requests for PUSH are recorded as suppressed with reason CHANNEL_UNAVAILABLE \
                    rather than dropped.""");
        }
    }

    /** The transport for a channel, if one exists. */
    public Optional<NotificationTransport> forChannel(NotificationChannel channel) {
        return Optional.ofNullable(byChannel.get(channel));
    }

    /**
     * Whether a channel can be attempted at all.
     *
     * <p>False when no transport is registered, and also when the registered one reports itself
     * unavailable — a deployment with email switched off should suppress the channel with a reason, not
     * accumulate a dead-letter queue full of messages it was never going to send.
     */
    public boolean isDeliverable(NotificationChannel channel) {
        return forChannel(channel).map(NotificationTransport::isAvailable).orElse(false);
    }

    /** Which channels are usable right now, for the admin view. */
    public List<NotificationChannel> deliverableChannels() {
        return byChannel.keySet().stream()
                .filter(this::isDeliverable)
                .sorted()
                .toList();
    }
}
