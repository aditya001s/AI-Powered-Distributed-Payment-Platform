package com.aegispay.notification.util;

/**
 * Masks a delivery address for logging.
 *
 * <h2>Why this exists as a named helper</h2>
 *
 * <p>Because the alternative is remembering. This service handles an email address or a phone number on
 * every single delivery, and it logs on every single delivery, so the two meet constantly. One
 * {@code log.info("sending to {}", email)} added during a debugging session is a permanent PII leak into
 * whatever aggregates the logs — and it will never be noticed, because the log line looks helpful.
 *
 * <p>Enough is kept to be useful. Support asking "which address did it go to" can confirm
 * {@code a***@example.com} against what the customer tells them, without the log itself being a list of
 * every customer's email address.
 */
public final class ContactMasking {

    private ContactMasking() {
    }

    private static final String REDACTED = "***";

    /**
     * {@code alice@example.com} becomes {@code a***@example.com}.
     *
     * <p>The domain is kept whole. It is not personal data on its own, and it is the part that matters
     * when diagnosing a delivery problem: an entire corporate domain bouncing looks completely
     * different from one customer's address being wrong.
     */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "(none)";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            // Not an address shape. Masked entirely rather than passed through, because whatever it is,
            // it came from a user record.
            return REDACTED;
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        return local.charAt(0) + REDACTED + domain;
    }

    /**
     * {@code +919876543210} becomes {@code +91******3210}.
     *
     * <p>Country code and last four digits kept: the country code tells an operator whether a routing
     * problem is regional, and the last four are what a customer can confirm over the phone. The middle
     * is what makes the number reachable, so it is the part removed.
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return "(none)";
        }
        String digitsOnly = phone.replaceAll("[^+0-9]", "");
        if (digitsOnly.length() < 7) {
            // Too short to mask usefully: any prefix plus any suffix would be most of it.
            return REDACTED;
        }
        String prefix = digitsOnly.startsWith("+")
                ? digitsOnly.substring(0, Math.min(3, digitsOnly.length()))
                : digitsOnly.substring(0, 2);
        String suffix = digitsOnly.substring(digitsOnly.length() - 4);
        int hidden = Math.max(1, digitsOnly.length() - prefix.length() - suffix.length());
        return prefix + "*".repeat(hidden) + suffix;
    }

    /** Masks by channel, so a caller does not have to branch on it. */
    public static String mask(com.aegispay.common.domain.SharedEnums.NotificationChannel channel,
                              String destination) {
        return switch (channel) {
            case EMAIL -> maskEmail(destination);
            case SMS -> maskPhone(destination);
            // An in-app notification is addressed by user id, which is already an opaque identifier
            // and is not personal data on its own.
            case IN_APP, PUSH -> destination == null ? "(none)" : destination;
        };
    }
}
