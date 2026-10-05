package com.aegispay.common.events.payload;

import com.aegispay.common.domain.SharedEnums.Channel;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Contextual signals captured at the moment a payment is initiated.
 *
 * <p>These are the raw inputs the fraud model turns into features. They are
 * captured once, at the edge, and carried on the {@code payment-created} event
 * so that every later stage scores the <em>same</em> context. Re-deriving device
 * or IP data later in the pipeline would produce a different answer than the one
 * the user actually transacted with.
 *
 * <p>Privacy: this is personal data. It is retained on the payment aggregate for
 * the fraud-investigation window only, is never rendered to another customer,
 * and IP addresses are truncated before they reach analytics aggregates. See
 * the Security section in README.md.
 *
 * <p>All fields are nullable: an API-channel payment has no device fingerprint,
 * and a user who denied location permission has no coordinates. The feature
 * pipeline treats absence as its own signal rather than imputing a value.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RiskSignals(

        /** Stable per-installation device identifier, if the client supplies one. */
        String deviceId,

        /** Hash of device characteristics; changes when the environment changes. */
        String deviceFingerprint,

        /** Source IP as observed at the gateway (X-Forwarded-For aware). */
        String ipAddress,

        String userAgent,

        /** ISO-3166 alpha-2 country resolved from the IP. */
        String countryCode,

        String city,

        /** Coarse coordinates used only for distance-from-last-transaction. */
        Double latitude,

        Double longitude,

        Channel channel,

        /** Present for merchant payments. */
        String merchantId,

        /** Merchant category code; some categories carry far higher fraud rates. */
        String merchantCategory
) {

    /** Signals for a request where no client context was available. */
    public static RiskSignals unknown() {
        return new RiskSignals(null, null, null, null, null, null, null, null,
                Channel.UNKNOWN, null, null);
    }

    /** True when we have enough context for the geo-velocity features to be meaningful. */
    public boolean hasGeoLocation() {
        return latitude != null && longitude != null;
    }

    public boolean hasDevice() {
        return (deviceId != null && !deviceId.isBlank())
                || (deviceFingerprint != null && !deviceFingerprint.isBlank());
    }

    /**
     * IP with the final octet zeroed, for use in analytics and logs. Keeps the
     * network-level signal while reducing the ability to single out an individual.
     */
    public String truncatedIp() {
        if (ipAddress == null || ipAddress.isBlank()) {
            return null;
        }
        int lastDot = ipAddress.lastIndexOf('.');
        if (lastDot > 0) {
            return ipAddress.substring(0, lastDot) + ".0";
        }
        // IPv6: keep the first three hextets (the routing prefix).
        String[] parts = ipAddress.split(":");
        if (parts.length > 3) {
            return String.join(":", parts[0], parts[1], parts[2]) + "::";
        }
        return ipAddress;
    }
}
