package com.aegispay.notification.domain;

import com.aegispay.common.domain.SharedEnums.NotificationChannel;
import com.aegispay.common.domain.SharedEnums.NotificationPriority;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Channel preferences, SMS consent, and the one thing a preference cannot do.
 */
class NotificationPreferencesTest {

    @Nested
    @DisplayName("defaults")
    class Defaults {

        /**
         * Email and in-app on, SMS off. Defaulting SMS on would mean the platform texts anyone whose number
         * it happens to learn, which is the shape of the complaint a regulator acts on. Email is different:
         * the customer supplied the address in order to sign up, and transactional email to it is what they
         * expect.
         */
        @Test
        @DisplayName("enables email and in-app but not SMS")
        void sensibleDefaults() {
            NotificationPreferences preferences = NotificationPreferences.defaultsFor("USR-1");

            assertThat(preferences.isEmailEnabled()).isTrue();
            assertThat(preferences.isInAppEnabled()).isTrue();
            assertThat(preferences.isSmsEnabled())
                    .as("texting anyone whose number the platform happens to learn is the shape of "
                            + "the complaint regulators act on")
                    .isFalse();
            assertThat(preferences.getPhoneE164()).isNull();
            assertThat(preferences.getLocale()).isEqualTo("en");
        }

        /**
         * PUSH is never allowed, regardless of preference. There is no mobile application and no
         * device-token registry, so there is nowhere to send one — it is not a preference question.
         */
        @Test
        @DisplayName("never allows push, which has nowhere to go")
        void pushIsNeverAllowed() {
            assertThat(NotificationPreferences.defaultsFor("USR-1")
                    .allows(NotificationChannel.PUSH)).isFalse();
        }
    }

    @Nested
    @DisplayName("SMS consent")
    class SmsConsent {

        /**
         * The number and the consent are set together and cannot be set apart. A flow that stored a number
         * now and enabled SMS later would leave a window in which the platform holds a phone number for no
         * stated purpose.
         */
        @Test
        @DisplayName("records the number and the consent together")
        void optInRecordsBoth() {
            NotificationPreferences preferences = NotificationPreferences.defaultsFor("USR-1");

            preferences.optInToSms("+919876543210");

            assertThat(preferences.isSmsEnabled()).isTrue();
            assertThat(preferences.getPhoneE164()).isEqualTo("+919876543210");
            assertThat(preferences.getSmsOptInAt())
                    .as("the consent timestamp is what a regulator asks for and is not derivable "
                            + "from the flag")
                    .isNotNull();
            assertThat(preferences.allows(NotificationChannel.SMS)).isTrue();
        }

        /**
         * A national-format number is ambiguous about country, and the wrong guess sends a stranger a
         * message about somebody else's payment.
         */
        @ParameterizedTest
        @ValueSource(strings = {"9876543210", "0919876543210", "+0919876543", "+91 98765 43210",
                "+91-9876543210", "abc", "+", "+1"})
        @DisplayName("refuses anything that is not E.164")
        void rejectsNonE164(String bad) {
            assertThatThrownBy(() -> NotificationPreferences.defaultsFor("USR-1").optInToSms(bad))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("E.164");
        }

        @Test
        @DisplayName("refuses a null number")
        void rejectsNull() {
            assertThatThrownBy(() -> NotificationPreferences.defaultsFor("USR-1").optInToSms(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /**
         * The number is deleted rather than kept for convenience. Its only justification was the consent, and
         * holding personal data whose stated purpose has been withdrawn is exactly what the consent record
         * exists to prevent.
         */
        @Test
        @DisplayName("forgets the number when consent is withdrawn")
        void optOutForgetsTheNumber() {
            NotificationPreferences preferences = NotificationPreferences.defaultsFor("USR-1");
            preferences.optInToSms("+919876543210");

            preferences.disableSms();

            assertThat(preferences.isSmsEnabled()).isFalse();
            assertThat(preferences.getPhoneE164())
                    .as("keeping it 'in case they come back' is holding personal data with no "
                            + "stated purpose")
                    .isNull();
            assertThat(preferences.getSmsOptInAt()).isNull();
        }

        /**
         * Refused rather than silently ignored. A caller that flipped the flag and saw no error would
         * reasonably believe SMS was on, and the customer would then not understand why nothing arrives.
         */
        @Test
        @DisplayName("refuses to enable SMS by flag alone")
        void smsCannotBeEnabledByFlag() {
            assertThatThrownBy(() -> NotificationPreferences.defaultsFor("USR-1")
                    .setChannelEnabled(NotificationChannel.SMS, true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("opting in with a phone number");
        }

        @Test
        @DisplayName("allows SMS to be switched off by flag")
        void smsCanBeDisabledByFlag() {
            NotificationPreferences preferences = NotificationPreferences.defaultsFor("USR-1");
            preferences.optInToSms("+919876543210");

            preferences.setChannelEnabled(NotificationChannel.SMS, false);

            assertThat(preferences.isSmsEnabled()).isFalse();
            assertThat(preferences.getPhoneE164()).isNull();
        }

        @Test
        @DisplayName("refuses to configure push at all")
        void pushCannotBeConfigured() {
            assertThatThrownBy(() -> NotificationPreferences.defaultsFor("USR-1")
                    .setChannelEnabled(NotificationChannel.PUSH, true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no mobile application");
        }
    }

    @Nested
    @DisplayName("channel switches")
    class ChannelSwitches {

        @Test
        @DisplayName("turns email and in-app on and off")
        void togglesChannels() {
            NotificationPreferences preferences = NotificationPreferences.defaultsFor("USR-1");

            preferences.setChannelEnabled(NotificationChannel.EMAIL, false);
            preferences.setChannelEnabled(NotificationChannel.IN_APP, false);

            assertThat(preferences.allows(NotificationChannel.EMAIL)).isFalse();
            assertThat(preferences.allows(NotificationChannel.IN_APP)).isFalse();
        }

        @Test
        @DisplayName("falls back to English for a blank locale")
        void localeDefaults() {
            NotificationPreferences preferences = NotificationPreferences.defaultsFor("USR-1");

            preferences.setLocale("  ");
            assertThat(preferences.getLocale()).isEqualTo("en");

            preferences.setLocale(null);
            assertThat(preferences.getLocale()).isEqualTo("en");
        }
    }

    @Nested
    @DisplayName("what a preference cannot silence")
    class AlwaysOn {

        /**
         * <strong>The rule that matters.</strong> A preference exists to stop routine noise. Letting "tell me
         * nothing" cover a security or money-movement notification would turn a convenience setting into a
         * way of hiding an account takeover from its victim — so HIGH priority ignores the opt-out entirely.
         *
         * <p>The check lives on {@link Notification} rather than here because it is a property of the message,
         * not of the user.
         */
        @Test
        @DisplayName("ignores a channel opt-out for a high-priority notification")
        void highPriorityIgnoresOptOut() {
            assertThat(notification(NotificationPriority.HIGH).respectsChannelOptOut())
                    .as("a customer must not be able to switch off being told their payment failed")
                    .isFalse();
        }

        @Test
        @DisplayName("honours a channel opt-out for routine notifications")
        void routinePriorityHonoursOptOut() {
            assertThat(notification(NotificationPriority.NORMAL).respectsChannelOptOut()).isTrue();
            assertThat(notification(NotificationPriority.LOW).respectsChannelOptOut()).isTrue();
        }

        private static Notification notification(NotificationPriority priority) {
            return Notification.of("PAY-1:sent:USR-1", "USR-1", "payment.sent", priority,
                    "PAY-1", "{}", "evt-1", "payment-service");
        }
    }

    @Test
    @DisplayName("keeps the phone number out of toString, which reaches logs")
    void toStringOmitsTheNumber() {
        NotificationPreferences preferences = NotificationPreferences.defaultsFor("USR-1");
        preferences.optInToSms("+919876543210");

        assertThat(preferences.toString()).doesNotContain("9876543210");
    }
}
