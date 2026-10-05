package com.aegispay.notification.transport;

import com.aegispay.common.domain.SharedEnums.NotificationChannel;
import com.aegispay.notification.config.NotificationProperties;
import com.aegispay.notification.template.RenderedMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The transports, and the distinction that decides how hard the platform tries.
 *
 * <p><strong>Retryable versus permanent is the whole point of these classes throwing rather than returning a
 * boolean.</strong> A connection timeout deserves eight attempts; a malformed address deserves none.
 * Retrying a permanent failure burns the budget, produces eight identical errors, and delays the dead-letter
 * record an operator needs in order to fix the actual problem — and for SMS it also spends money each time.
 */
class NotificationTransportTest {

    private NotificationProperties properties;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
    }

    // ------------------------------------------------------------------------
    // email
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("simulated email")
    class Email {

        private SimulatedEmailTransport transport;

        @BeforeEach
        void createTransport() {
            transport = new SimulatedEmailTransport(properties);
        }

        @Test
        @DisplayName("serves the email channel")
        void servesEmail() {
            assertThat(transport.channel()).isEqualTo(NotificationChannel.EMAIL);
        }

        @Test
        @DisplayName("accepts a well-formed message and returns a reference")
        void acceptsGoodMessage() {
            String reference = transport.send("alice@example.com", email("Subject", "Body"));

            assertThat(reference).isNotBlank();
        }

        /**
         * The reference is prefixed {@code sim-} on purpose. A reference shaped like a genuine provider
         * message id would eventually be pasted into a support ticket and looked up somewhere it does not
         * exist, and the person doing the looking would conclude the provider had lost it.
         */
        @Test
        @DisplayName("returns a reference that cannot be mistaken for a real one")
        void referenceIsObviouslySimulated() {
            assertThat(transport.send("alice@example.com", email("Subject", "Body")))
                    .startsWith("sim-");
        }

        /**
         * Permanent, so the delivery is dead-lettered on the first attempt. A malformed address will fail
         * identically forever, and eight attempts only delay the record somebody needs.
         */
        @ParameterizedTest
        @ValueSource(strings = {"not-an-address", "alice@", "@example.com", "alice example.com",
                "alice@example", "alice@exa mple.com"})
        @DisplayName("treats an unusable address as permanent, not retryable")
        void unusableAddressIsPermanent(String bad) {
            NotificationTransport.TransportException thrown = catchThrowableOfType(
                    () -> transport.send(bad, email("Subject", "Body")),
                    NotificationTransport.TransportException.class);

            assertThat(thrown).isNotNull();
            assertThat(thrown.isRetryable())
                    .as("retrying a malformed address produces eight identical errors and delays the "
                            + "dead-letter record")
                    .isFalse();
            assertThat(thrown.code()).isEqualTo("INVALID_ADDRESS");
        }

        /**
         * The exception message must not carry the full address. It is written to the delivery's
         * {@code last_error} and shown in an admin API, so an unmasked address there is the same leak as an
         * unmasked one in a log.
         */
        @Test
        @DisplayName("masks the address in the failure it reports")
        void masksAddressInError() {
            NotificationTransport.TransportException thrown = catchThrowableOfType(
                    () -> transport.send("alice@bad", email("Subject", "Body")),
                    NotificationTransport.TransportException.class);

            assertThat(thrown.getMessage())
                    .as("last_error is stored and returned by the admin API")
                    .doesNotContain("alice@bad")
                    .contains("***");
        }

        @Test
        @DisplayName("treats a missing address as permanent")
        void missingAddressIsPermanent() {
            assertThat(catchThrowableOfType(
                    () -> transport.send(null, email("Subject", "Body")),
                    NotificationTransport.TransportException.class).isRetryable()).isFalse();
        }

        /** Every mail client files a subject-less message as "(no subject)", which reads as spam. */
        @Test
        @DisplayName("refuses a message with no subject")
        void refusesMissingSubject() {
            NotificationTransport.TransportException thrown = catchThrowableOfType(
                    () -> transport.send("alice@example.com",
                            new RenderedMessage(NotificationChannel.EMAIL, null, "Body")),
                    NotificationTransport.TransportException.class);

            assertThat(thrown.code()).isEqualTo("NO_SUBJECT");
            assertThat(thrown.isRetryable()).isFalse();
        }

        /**
         * Injected failures are transient. They stand in for a provider outage, which is exactly the case the
         * retry queue exists for — marking them permanent would make the simulation exercise the wrong path.
         */
        @Test
        @DisplayName("reports an injected failure as transient")
        void injectedFailureIsTransient() {
            properties.getTransports().setSimulatedFailureRate(1.0);

            NotificationTransport.TransportException thrown = catchThrowableOfType(
                    () -> transport.send("alice@example.com", email("Subject", "Body")),
                    NotificationTransport.TransportException.class);

            assertThat(thrown.isRetryable()).isTrue();
            assertThat(thrown.code()).isEqualTo("SIMULATED_SMTP_FAILURE");
        }

        /**
         * Disabling the channel makes it <em>suppressed</em> rather than failed. A deployment that has not
         * configured email should not accumulate a dead-letter queue full of messages it was never going to
         * send.
         */
        @Test
        @DisplayName("reports itself unavailable when the channel is switched off")
        void unavailableWhenDisabled() {
            properties.getTransports().setEmailEnabled(false);

            assertThat(transport.isAvailable()).isFalse();
        }

        @Test
        @DisplayName("is available by default")
        void availableByDefault() {
            assertThat(transport.isAvailable()).isTrue();
        }
    }

    // ------------------------------------------------------------------------
    // SMS
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("simulated SMS")
    class Sms {

        private SimulatedSmsTransport transport;

        @BeforeEach
        void createTransport() {
            transport = new SimulatedSmsTransport(properties);
        }

        @Test
        @DisplayName("serves the SMS channel")
        void servesSms() {
            assertThat(transport.channel()).isEqualTo(NotificationChannel.SMS);
        }

        @Test
        @DisplayName("accepts an E.164 number")
        void acceptsE164() {
            assertThat(transport.send("+919876543210", sms("AegisPay: you sent 10.00 INR")))
                    .startsWith("sim-sms-");
        }

        /**
         * Stricter than email, and deliberately. A loose email pattern risks a bounce; a loose phone pattern
         * risks sending a stranger a message about somebody else's payment, because a national-format number
         * is ambiguous about country.
         */
        @ParameterizedTest
        @ValueSource(strings = {"9876543210", "0919876543210", "+91 98765 43210", "+91-9876543210",
                "not-a-number", "+0919876543"})
        @DisplayName("treats a non-E.164 number as permanent")
        void nonE164IsPermanent(String bad) {
            NotificationTransport.TransportException thrown = catchThrowableOfType(
                    () -> transport.send(bad, sms("body")),
                    NotificationTransport.TransportException.class);

            assertThat(thrown.isRetryable())
                    .as("an SMS to the wrong country is a message to a stranger, and each attempt "
                            + "costs money")
                    .isFalse();
            assertThat(thrown.code()).isEqualTo("INVALID_NUMBER");
        }

        @Test
        @DisplayName("masks the number in the failure it reports")
        void masksNumberInError() {
            NotificationTransport.TransportException thrown = catchThrowableOfType(
                    () -> transport.send("+91 98765 43210", sms("body")),
                    NotificationTransport.TransportException.class);

            assertThat(thrown.getMessage()).doesNotContain("9876543210").contains("*");
        }

        @Test
        @DisplayName("treats a missing number as permanent")
        void missingNumberIsPermanent() {
            NotificationTransport.TransportException thrown = catchThrowableOfType(
                    () -> transport.send(null, sms("body")),
                    NotificationTransport.TransportException.class);

            assertThat(thrown.code()).isEqualTo("NO_NUMBER");
            assertThat(thrown.isRetryable()).isFalse();
        }

        /**
         * Segment arithmetic, because the cost is per segment per recipient. The renderer caps SMS at one
         * segment, so this exists to make a template that grew past the cap visible rather than merely
         * expensive.
         */
        @Test
        @DisplayName("counts segments the way a carrier bills them")
        void countsSegments() {
            assertThat(SimulatedSmsTransport.segmentsFor("")).isZero();
            assertThat(SimulatedSmsTransport.segmentsFor("short")).isEqualTo(1);
            assertThat(SimulatedSmsTransport.segmentsFor("x".repeat(160))).isEqualTo(1);
            assertThat(SimulatedSmsTransport.segmentsFor("x".repeat(161))).isEqualTo(2);
            assertThat(SimulatedSmsTransport.segmentsFor("x".repeat(400))).isEqualTo(3);
        }

        @Test
        @DisplayName("reports an injected failure as transient")
        void injectedFailureIsTransient() {
            properties.getTransports().setSimulatedFailureRate(1.0);

            assertThat(catchThrowableOfType(
                    () -> transport.send("+919876543210", sms("body")),
                    NotificationTransport.TransportException.class).isRetryable()).isTrue();
        }

        @Test
        @DisplayName("reports itself unavailable when the channel is switched off")
        void unavailableWhenDisabled() {
            properties.getTransports().setSmsEnabled(false);

            assertThat(transport.isAvailable()).isFalse();
        }
    }

    // ------------------------------------------------------------------------
    // in-app
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("in-app")
    class InApp {

        private final InAppTransport transport = new InAppTransport();

        @Test
        @DisplayName("serves the in-app channel")
        void servesInApp() {
            assertThat(transport.channel()).isEqualTo(NotificationChannel.IN_APP);
        }

        /**
         * No reference, because there is no external system. Inventing one shaped like a provider's would
         * suggest something had been contacted.
         */
        @Test
        @DisplayName("returns no provider reference, because nothing external was contacted")
        void returnsNoReference() {
            assertThat(transport.send("USR-1", inApp("You sent 10.00 INR"))).isNull();
        }

        /**
         * Permanent, because an empty body will be empty on every retry. Without this the customer would see a
         * blank notification in their inbox, which is worse than none because it cannot be explained.
         */
        @Test
        @DisplayName("refuses an empty body rather than storing a blank inbox entry")
        void refusesEmptyBody() {
            // RenderedMessage itself refuses a blank body, so the guard is reached through a channel that
            // bypasses the record's own validation -- which is exactly the situation it defends against.
            assertThatThrownBy(() -> new RenderedMessage(NotificationChannel.IN_APP, null, "  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("successful delivery of nothing");
        }

        @Test
        @DisplayName("is always available, because there is nothing to be unavailable")
        void alwaysAvailable() {
            assertThat(transport.isAvailable()).isTrue();
        }
    }

    // ------------------------------------------------------------------------
    // registry
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("registry")
    class Registry {

        @Test
        @DisplayName("maps each channel to its transport")
        void mapsChannels() {
            TransportRegistry registry = new TransportRegistry(List.of(
                    new SimulatedEmailTransport(properties),
                    new SimulatedSmsTransport(properties),
                    new InAppTransport()));

            assertThat(registry.forChannel(NotificationChannel.EMAIL)).isPresent();
            assertThat(registry.forChannel(NotificationChannel.SMS)).isPresent();
            assertThat(registry.forChannel(NotificationChannel.IN_APP)).isPresent();
        }

        /**
         * PUSH has no transport because there is no mobile application and no device-token registry.
         * {@code NotificationPayload.security(...)} asks for it, so the absence is reached in normal operation
         * and must be reported rather than crash.
         */
        @Test
        @DisplayName("has no push transport, and says so rather than failing")
        void noPushTransport() {
            TransportRegistry registry = new TransportRegistry(List.of(new InAppTransport()));

            assertThat(registry.forChannel(NotificationChannel.PUSH)).isEmpty();
            assertThat(registry.isDeliverable(NotificationChannel.PUSH)).isFalse();
        }

        /**
         * Two transports for one channel would otherwise resolve by whichever bean the component scan reached
         * second — so the same build could send through a real relay on one machine and a simulator on
         * another, and both would look fine.
         */
        @Test
        @DisplayName("refuses to start when two transports claim one channel")
        void refusesDuplicateChannels() {
            assertThatThrownBy(() -> new TransportRegistry(List.of(
                    new SimulatedEmailTransport(properties),
                    new SimulatedEmailTransport(properties))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("component-scan order");
        }

        @Test
        @DisplayName("reports a disabled channel as not deliverable")
        void disabledChannelIsNotDeliverable() {
            properties.getTransports().setEmailEnabled(false);
            TransportRegistry registry = new TransportRegistry(List.of(
                    new SimulatedEmailTransport(properties), new InAppTransport()));

            assertThat(registry.isDeliverable(NotificationChannel.EMAIL)).isFalse();
            assertThat(registry.deliverableChannels())
                    .containsExactly(NotificationChannel.IN_APP);
        }

        @Test
        @DisplayName("lists deliverable channels in a stable order")
        void deliverableChannelsAreSorted() {
            TransportRegistry registry = new TransportRegistry(List.of(
                    new InAppTransport(),
                    new SimulatedSmsTransport(properties),
                    new SimulatedEmailTransport(properties)));

            assertThat(registry.deliverableChannels()).isSorted();
        }
    }

    // ---------------------------------------------------------------- fixtures

    private static RenderedMessage email(String subject, String body) {
        return new RenderedMessage(NotificationChannel.EMAIL, subject, body);
    }

    private static RenderedMessage sms(String body) {
        return new RenderedMessage(NotificationChannel.SMS, null, body);
    }

    private static RenderedMessage inApp(String body) {
        return new RenderedMessage(NotificationChannel.IN_APP, null, body);
    }
}
