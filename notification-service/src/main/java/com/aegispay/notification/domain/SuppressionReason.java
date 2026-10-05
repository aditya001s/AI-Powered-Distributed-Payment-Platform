package com.aegispay.notification.domain;

/**
 * Why a channel was not attempted.
 *
 * <p>A closed vocabulary rather than free text, because these are counted. "How many customers are
 * not getting SMS because they never added a number" is an answerable question with an enum and a
 * grep-and-hope with a string, and the difference decides whether anybody notices
 * {@link #NO_TEMPLATE} happening.
 */
public enum SuppressionReason {

    /**
     * The user turned this channel off.
     *
     * <p>Only reachable for LOW and NORMAL priority. A customer cannot switch off being told that
     * their payment failed.
     */
    USER_OPTED_OUT,

    /** No contact address for the channel: no email on the account, or no phone number. */
    NO_DESTINATION,

    /**
     * A phone number exists but the user never opted in to SMS.
     *
     * <p>Distinct from {@link #NO_DESTINATION} on purpose. Both produce no message, but one is
     * fixed by the user adding a number and the other by them agreeing to be texted, and an
     * operator looking at a spike needs to know which.
     */
    SMS_NOT_OPTED_IN,

    /**
     * No copy exists for this template key on this channel.
     *
     * <p><strong>Never normal.</strong> It means a producer is emitting a notification nobody wrote
     * copy for, and those customers are silently receiving nothing. Suppressing is still the right
     * response — see {@code TemplateCatalogue} for why a guessed generic message about somebody's
     * money is worse than none — but it is suppression that should page somebody.
     */
    NO_TEMPLATE,

    /**
     * The channel is not implemented on this platform.
     *
     * <p>Currently PUSH: there is no mobile application and no device-token registry, so there is
     * nowhere to send one. Recorded honestly rather than dropped, because
     * {@code NotificationPayload.security(...)} asks for PUSH and something has to explain where it
     * went.
     */
    CHANNEL_UNAVAILABLE
}
