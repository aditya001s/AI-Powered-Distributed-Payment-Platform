package com.aegispay.notification.domain;

/**
 * Where one channel's delivery has got to.
 *
 * <h2>Why this is not {@code SharedEnums.NotificationStatus}</h2>
 *
 * <p>The shared enum describes a <em>notification</em> and has four values. This describes a
 * <em>delivery</em>, and needs a fifth: {@link #DEAD_LETTERED}. Widening the shared enum was
 * rejected — every service on the platform would gain a value that only means something inside this
 * one, and a consumer switching on it would have to handle a state it can never observe.
 *
 * <p>The two are related but not the same thing. A notification whose email was sent and whose SMS
 * died is neither "sent" nor "failed"; asking that question of the notification is the wrong
 * question, which is why status lives here and not there.
 */
public enum DeliveryStatus {

    /**
     * Created, not yet attempted.
     *
     * <p>Brief in practice — the first attempt happens in the same call that creates the row — but a
     * real state rather than a formality: if the process dies between the insert and the send, the
     * row is already durable and the retry worker picks it up. Without a PENDING state that
     * notification would be lost with nothing recording that it should have existed.
     */
    PENDING,

    /** Handed to a transport that accepted it. */
    SENT,

    /**
     * The transport refused or timed out, and a retry is scheduled.
     *
     * <p>Not terminal, which is the whole point of it being distinct from {@link #DEAD_LETTERED}. A
     * single FAILED row is ordinary; a growing count of them means a transport is down.
     */
    FAILED,

    /**
     * Deliberately not attempted, and never will be.
     *
     * <p>Normal, not an error: the user opted the channel out, or has no verified number for SMS.
     * Recorded rather than skipped so that "why did I not get an SMS" has an answer — a missing row
     * and a suppressed row look identical to a customer and completely different to support.
     */
    SUPPRESSED,

    /**
     * Every retry was exhausted.
     *
     * <p><strong>The state that matters.</strong> A customer was never told something the platform
     * decided to tell them, and nothing else in the system reports it — the payment itself succeeded,
     * so no error surfaces anywhere. These rows are the queue an operator works, and they can be
     * replayed once the underlying transport is fixed.
     */
    DEAD_LETTERED;

    /** Whether anything further will happen to this delivery on its own. */
    public boolean isTerminal() {
        return this == SENT || this == SUPPRESSED || this == DEAD_LETTERED;
    }

    /** Whether the retry worker should ever look at this row again. */
    public boolean isRetryable() {
        return this == PENDING || this == FAILED;
    }
}
