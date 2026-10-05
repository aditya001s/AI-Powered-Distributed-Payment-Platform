package com.aegispay.notification.domain;

import com.aegispay.common.domain.SharedEnums.NotificationChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * What a user has asked to receive, and how.
 *
 * <h2>Why the phone number is here and the email address is not</h2>
 *
 * <p>An email address is an identity attribute: the user registered with it, it is verified at
 * signup, and auth-service is its source of truth. This service resolves it per delivery instead of
 * copying it, so a changed address takes effect on the next notification rather than whenever
 * somebody remembers to sync.
 *
 * <p>A phone number is not an identity attribute on this platform — nobody logs in with one. It
 * exists for exactly one purpose, sending an SMS, and that requires the user to have asked. Keeping
 * the number beside the consent that justifies it means the two cannot become separated, and it keeps
 * a phone number out of the service every other service queries for identity.
 *
 * <h2>What a preference cannot do</h2>
 *
 * <p>Silence a HIGH-priority notification. The check lives in {@link Notification} rather than here,
 * because it is a property of the message rather than of the user: a preference exists to stop routine
 * noise, and letting "tell me nothing" cover a security alert would turn a convenience setting into a
 * way of hiding an account takeover from its victim.
 */
@Entity
@Table(name = "notification_preferences")
public class NotificationPreferences {

    /**
     * E.164 only.
     *
     * <p>A local-format number is ambiguous about country, and an SMS to the wrong country is both a
     * cost and a message to a stranger. The same pattern is a database CHECK, so a number written
     * directly into the table cannot bypass it either.
     */
    private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{7,18}$");

    @Id
    @Column(name = "user_id", nullable = false, updatable = false, length = 40)
    private String userId;

    @Column(name = "email_enabled", nullable = false)
    private boolean emailEnabled = true;

    /**
     * Off by default, unlike the others.
     *
     * <p>Defaulting SMS on would mean the platform texts anyone whose number it happens to learn,
     * which is the shape of the complaint regulators act on. Email is different: the user gave the
     * address in order to sign up, and transactional email to it is what they expect.
     */
    @Column(name = "sms_enabled", nullable = false)
    private boolean smsEnabled;

    @Column(name = "in_app_enabled", nullable = false)
    private boolean inAppEnabled = true;

    @Column(name = "phone_e164", length = 20)
    private String phoneE164;

    /** When the user consented. Not derivable from the flag, and the thing a regulator asks for. */
    @Column(name = "sms_opt_in_at")
    private Instant smsOptInAt;

    @Column(name = "locale", nullable = false, length = 16)
    private String locale = "en";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected NotificationPreferences() {
        // JPA
    }

    /**
     * The defaults for a user who has never set a preference.
     *
     * <p>Returned rather than persisted on read. Writing a row the first time a notification is sent
     * would mean the delivery path takes a write lock on a table it only needs to read, and would fill
     * the table with rows recording that nobody chose anything.
     */
    public static NotificationPreferences defaultsFor(String userId) {
        NotificationPreferences preferences = new NotificationPreferences();
        preferences.userId = userId;
        preferences.emailEnabled = true;
        preferences.smsEnabled = false;
        preferences.inAppEnabled = true;
        preferences.locale = "en";
        preferences.createdAt = Instant.now();
        preferences.updatedAt = preferences.createdAt;
        return preferences;
    }

    /** Whether the user has opted this channel in. Ignores priority; the caller applies that. */
    public boolean allows(NotificationChannel channel) {
        return switch (channel) {
            case EMAIL -> emailEnabled;
            case SMS -> smsEnabled;
            case IN_APP -> inAppEnabled;
            // Not a preference question. There is no device-token registry on this platform, so PUSH
            // has nowhere to go regardless of what the user would prefer.
            case PUSH -> false;
        };
    }

    /** Turns a channel on or off. SMS is handled separately because it needs a number and consent. */
    public void setChannelEnabled(NotificationChannel channel, boolean enabled) {
        switch (channel) {
            case EMAIL -> this.emailEnabled = enabled;
            case IN_APP -> this.inAppEnabled = enabled;
            case SMS -> {
                if (enabled) {
                    // Refused rather than silently ignored. A caller that flipped the flag and saw no
                    // error would reasonably believe SMS was on, and the customer would then not
                    // understand why nothing arrives.
                    throw new IllegalArgumentException(
                            "SMS is enabled by opting in with a phone number, not by setting a flag");
                }
                disableSms();
            }
            case PUSH -> throw new IllegalArgumentException(
                    "push notifications are not available on this platform: there is no mobile "
                            + "application and no device-token registry to send to");
        }
        touch();
    }

    /**
     * Records an SMS opt-in.
     *
     * <p>The number and the consent are set together and cannot be set apart — the same pairing the
     * database CHECKs. A flow that stored a number now and enabled SMS later would leave a window in
     * which the platform holds a phone number for no stated purpose.
     */
    public void optInToSms(String phoneE164) {
        if (phoneE164 == null || !E164.matcher(phoneE164).matches()) {
            throw new IllegalArgumentException(
                    "a phone number must be in E.164 form, e.g. +919876543210: a local-format "
                            + "number is ambiguous about country, and an SMS to the wrong country "
                            + "is a message to a stranger");
        }
        this.phoneE164 = phoneE164;
        this.smsEnabled = true;
        this.smsOptInAt = Instant.now();
        touch();
    }

    /**
     * Withdraws SMS consent and forgets the number.
     *
     * <p>The number is cleared rather than kept for convenience. Its only justification was the
     * consent, and holding personal data whose stated purpose has been withdrawn is the thing the
     * consent record exists to prevent.
     */
    public void disableSms() {
        this.smsEnabled = false;
        this.phoneE164 = null;
        this.smsOptInAt = null;
        touch();
    }

    public void setLocale(String locale) {
        this.locale = locale == null || locale.isBlank() ? "en" : locale;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public String getUserId() {
        return userId;
    }

    public boolean isEmailEnabled() {
        return emailEnabled;
    }

    public boolean isSmsEnabled() {
        return smsEnabled;
    }

    public boolean isInAppEnabled() {
        return inAppEnabled;
    }

    public String getPhoneE164() {
        return phoneE164;
    }

    public Instant getSmsOptInAt() {
        return smsOptInAt;
    }

    public String getLocale() {
        return locale;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        // No phone number: this reaches logs.
        return "NotificationPreferences{" + userId + ", email=" + emailEnabled
                + ", sms=" + smsEnabled + ", inApp=" + inAppEnabled + '}';
    }
}
