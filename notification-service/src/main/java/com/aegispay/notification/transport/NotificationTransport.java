package com.aegispay.notification.transport;

import com.aegispay.common.domain.SharedEnums.NotificationChannel;
import com.aegispay.notification.template.RenderedMessage;

/**
 * Hands one rendered message to one channel.
 *
 * <h2>What an implementation must guarantee</h2>
 *
 * <ul>
 *   <li><strong>Return only when the message was accepted.</strong> "Accepted" means the receiving
 *       system took responsibility for it, not that it reached a person — no email provider can tell
 *       you that. Returning on anything weaker makes {@code SENT} a lie, and {@code SENT} is what a
 *       dispute is answered with.</li>
 *   <li><strong>Throw {@link TransportException} and say whether retrying could help.</strong> The
 *       distinction is the whole reason this is not a boolean: a connection timeout deserves eight
 *       attempts, and a malformed address deserves none. Retrying a permanent failure burns the
 *       retry budget and delays the dead-letter record an operator needs.</li>
 *   <li><strong>Do not reformat the message.</strong> It arrives escaped, defanged and length-capped.
 *       A transport that re-wrapped or re-encoded it would be a second place escaping could be got
 *       wrong, and the stored record would no longer match what was sent.</li>
 * </ul>
 *
 * <h2>On the simulated implementations</h2>
 *
 * <p>Two of the three implementations are simulated, and they are named {@code Simulated...} so nobody
 * has to read the class to find out. This platform contacts no real SMTP relay and no real SMS gateway,
 * exactly as it contacts no real bank. What is <em>not</em> simulated is everything around them: the
 * rendering, the escaping, the per-channel idempotency, the retry schedule, the dead-letter queue and
 * the audit trail all behave identically whether the send is real or not, because they sit on this side
 * of the interface.
 *
 * <p>{@code InAppTransport} is not simulated at all. An in-app notification is a row this service owns
 * and an API a client reads, so there is nothing to stand in for.
 */
public interface NotificationTransport {

    /** Which channel this transport serves. One transport per channel; the registry enforces it. */
    NotificationChannel channel();

    /**
     * Whether this transport is usable at all right now.
     *
     * <p>Checked before a delivery is created, so a channel that cannot work is <em>suppressed</em>
     * with a reason rather than attempted, failed and retried eight times to reach the same conclusion.
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Sends the message.
     *
     * @param destination the address, already resolved. Never logged in full by an implementation.
     * @return the transport's own reference, for tracing a message into the provider
     * @throws TransportException when the message was not accepted
     */
    String send(String destination, RenderedMessage message);

    /**
     * A message was not accepted.
     *
     * @param retryable whether another attempt could succeed. False for anything about the message or
     *                  the address itself, which will fail identically forever.
     */
    class TransportException extends RuntimeException {

        private final String code;
        private final boolean retryable;

        public TransportException(String code, String message, boolean retryable) {
            super(message);
            this.code = code;
            this.retryable = retryable;
        }

        public TransportException(String code, String message, boolean retryable, Throwable cause) {
            super(message, cause);
            this.code = code;
            this.retryable = retryable;
        }

        /** A short, stable code. Stable because it is a metric tag and an operator's search term. */
        public String code() {
            return code;
        }

        public boolean isRetryable() {
            return retryable;
        }

        /** A fault in the receiving system: worth retrying. */
        public static TransportException transient_(String code, String message) {
            return new TransportException(code, message, true);
        }

        /** A fault in the message or the address: retrying cannot change the outcome. */
        public static TransportException permanent(String code, String message) {
            return new TransportException(code, message, false);
        }
    }
}
