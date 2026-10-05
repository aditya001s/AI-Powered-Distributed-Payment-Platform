package com.aegispay.notification.transport;

import com.aegispay.common.domain.SharedEnums.NotificationChannel;
import com.aegispay.notification.template.RenderedMessage;
import org.springframework.stereotype.Component;

/**
 * The in-app inbox.
 *
 * <p><strong>Not simulated.</strong> Unlike email and SMS there is nothing to stand in for: an in-app
 * notification is a row this service owns and an API a client reads, and by the time this transport is
 * called the row already exists with the rendered body on it. Delivery is complete because the message
 * is stored — the customer reads it when they next open the app, and {@code read_at} records that
 * separately.
 *
 * <p>So this class does almost nothing, and that is the point. It exists so the in-app channel goes
 * through the same idempotency, the same per-channel status, the same attempt record and the same
 * dead-letter path as the others. The alternative — special-casing in-app in the delivery service —
 * would leave the one channel that always works as the one channel with no audit trail.
 *
 * <p>It can still fail, and the check below is not a formality: an in-app delivery whose body failed to
 * render would otherwise be stored as an empty inbox entry, which a customer sees as a notification
 * that says nothing.
 */
@Component
public class InAppTransport implements NotificationTransport {

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.IN_APP;
    }

    @Override
    public String send(String destination, RenderedMessage message) {
        if (message.body() == null || message.body().isBlank()) {
            // Permanent: an empty body will be empty on every retry. Without this the customer would
            // see a blank notification, which is worse than none because it cannot be explained.
            throw TransportException.permanent("EMPTY_BODY",
                    "an in-app notification with no body would appear in the inbox saying nothing");
        }
        // No reference to return. There is no external system, and inventing an id shaped like a
        // provider's would suggest one had been contacted.
        return null;
    }
}
