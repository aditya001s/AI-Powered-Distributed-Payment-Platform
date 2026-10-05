package com.aegispay.notification.util;

import com.aegispay.common.domain.SharedEnums.NotificationChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Masking of delivery addresses.
 *
 * <p>This service handles an email address or a phone number on every delivery and logs on every delivery,
 * so the two meet constantly. One {@code log.info("sending to {}", email)} added during a debugging session
 * would be a permanent leak of every customer's address into whatever aggregates the logs — and would never
 * be noticed, because the log line looks helpful.
 *
 * <p>Enough is kept to stay useful: support can confirm {@code a***@example.com} against what a customer
 * tells them without the log being a list of addresses.
 */
class ContactMaskingTest {

    @Test
    @DisplayName("keeps the first character and the whole domain of an email")
    void masksEmail() {
        assertThat(ContactMasking.maskEmail("alice@example.com")).isEqualTo("a***@example.com");
    }

    /**
     * The domain is kept whole on purpose. It is not personal data on its own, and it is the part that
     * matters when diagnosing delivery: an entire corporate domain bouncing looks completely different from
     * one customer's address being wrong.
     */
    @Test
    @DisplayName("does not hide the domain, which is what makes a bounce diagnosable")
    void keepsDomain() {
        assertThat(ContactMasking.maskEmail("bob@corp.example.co.uk"))
                .isEqualTo("b***@corp.example.co.uk");
    }

    @Test
    @DisplayName("hides the whole value when it is not an address shape")
    void masksNonAddressEntirely() {
        // Whatever it is, it came from a user record, so it is masked rather than passed through.
        assertThat(ContactMasking.maskEmail("not-an-address")).isEqualTo("***");
        assertThat(ContactMasking.maskEmail("@example.com")).isEqualTo("***");
    }

    @Test
    @DisplayName("reports an absent address as absent rather than as masked")
    void absentEmail() {
        assertThat(ContactMasking.maskEmail(null)).isEqualTo("(none)");
        assertThat(ContactMasking.maskEmail("  ")).isEqualTo("(none)");
    }

    /**
     * Country code and last four digits kept: the country code tells an operator whether a routing problem
     * is regional, and the last four are what a customer can confirm over the phone. The middle is what
     * makes the number reachable, so the middle is what goes.
     */
    @Test
    @DisplayName("keeps the country code and last four digits of a phone number")
    void masksPhone() {
        String masked = ContactMasking.maskPhone("+919876543210");

        assertThat(masked).startsWith("+91").endsWith("3210").contains("*");
        assertThat(masked).doesNotContain("98765");
    }

    @Test
    @DisplayName("never reveals the middle of a number")
    void hidesTheReachablePart() {
        assertThat(ContactMasking.maskPhone("+14155550123"))
                .doesNotContain("5550")
                .endsWith("0123");
    }

    @ParameterizedTest
    @ValueSource(strings = {"+1234", "12345", "+1"})
    @DisplayName("hides a number too short to mask usefully")
    void masksShortNumbersEntirely(String tooShort) {
        // Any prefix plus any suffix would be most of the value, so nothing is kept.
        assertThat(ContactMasking.maskPhone(tooShort)).isEqualTo("***");
    }

    @Test
    @DisplayName("reports an absent number as absent")
    void absentPhone() {
        assertThat(ContactMasking.maskPhone(null)).isEqualTo("(none)");
    }

    @Test
    @DisplayName("masks by channel so a caller does not have to branch")
    void masksByChannel() {
        assertThat(ContactMasking.mask(NotificationChannel.EMAIL, "alice@example.com"))
                .isEqualTo("a***@example.com");
        assertThat(ContactMasking.mask(NotificationChannel.SMS, "+919876543210"))
                .startsWith("+91");
    }

    /**
     * An in-app notification is addressed by user id, which is already an opaque identifier and is not
     * personal data on its own — masking it would remove the only thing that makes the log line useful.
     */
    @Test
    @DisplayName("leaves an opaque user id readable for in-app")
    void doesNotMaskUserIds() {
        assertThat(ContactMasking.mask(NotificationChannel.IN_APP, "USR-91AB"))
                .isEqualTo("USR-91AB");
    }
}
