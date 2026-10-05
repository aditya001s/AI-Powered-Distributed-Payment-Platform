package com.aegispay.notification.template;

import com.aegispay.common.domain.SharedEnums.NotificationChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shipped copy, and the rules for reading it.
 *
 * <p>Two different things are being tested, and they fail in opposite directions. The parser tests check
 * that a malformed template is <em>rejected</em> at startup, because a template that loaded with a missing
 * section would suppress a channel silently for as long as nobody looked. The corpus tests check that the
 * templates actually shipped are well-formed and complete — they are content rather than code, and nothing
 * else compiles them.
 */
class TemplateCatalogueTest {

    /**
     * Exactly the template keys payment-service emits.
     *
     * <p>Duplicated from {@code PaymentMutationService} deliberately: this module does not depend on
     * payment-service and should not start now — the coupling between them is an event contract, not a
     * classpath.
     *
     * <p>The limitation is worth being honest about. Because this is a copy, the test cannot notice a
     * <em>new</em> template key added in payment-service with no copy written for it. What it does catch is
     * the likelier mistake: a key here that has drifted from the one actually emitted, which would suppress
     * every notification of that kind with reason NO_TEMPLATE while the service reported itself healthy.
     */
    private static final Set<String> KEYS_EMITTED_BY_PAYMENT_SERVICE = Set.of(
            "payment.sent",
            "payment.received",
            "payment.failed",
            "payment.rejected",
            "payment.refunded",
            "payment.partially-refunded");

    /**
     * Every variable a template can rely on.
     *
     * <p>The first four come from {@code PaymentMutationService.recordNotification}; the last two are
     * supplied by {@code NotificationDeliveryService} before rendering. A template referencing anything
     * else would fail to render at runtime and suppress the delivery.
     */
    private static final Set<String> AVAILABLE_VARIABLES = Set.of(
            "transactionId", "amount", "currency", "description",
            "greeting", "paymentId");

    private final TemplateCatalogue catalogue = new TemplateCatalogue();

    // ------------------------------------------------------------------------
    // the shipped copy
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("shipped copy")
    class ShippedCopy {

        @Test
        @DisplayName("loads every bundled template")
        void loadsEveryTemplate() {
            assertThat(catalogue.keys()).containsAll(KEYS_EMITTED_BY_PAYMENT_SERVICE);
        }

        /**
         * <strong>The assertion this class exists for.</strong> A key emitted with no copy is suppressed
         * with reason NO_TEMPLATE — the correct behaviour, but it means the customer is told nothing while
         * the payment succeeded and nothing else reports a fault.
         */
        @ParameterizedTest
        @ValueSource(strings = {"payment.sent", "payment.received", "payment.failed",
                "payment.rejected", "payment.refunded", "payment.partially-refunded"})
        @DisplayName("has copy for every key payment-service emits")
        void coversEveryEmittedKey(String key) {
            assertThat(catalogue.find(key))
                    .as("""
                            payment-service emits '%s'. Without copy the notification is suppressed, the \
                            customer hears nothing, and the payment itself succeeded so no error appears \
                            anywhere""", key)
                    .isPresent();
        }

        /**
         * Email and in-app, because {@code NotificationPayload.transactional} requests exactly those two.
         * A template with only email copy would leave the inbox empty for a payment the customer was
         * emailed about, which reads as the platform having lost the notification.
         */
        @ParameterizedTest
        @ValueSource(strings = {"payment.sent", "payment.received", "payment.failed",
                "payment.rejected", "payment.refunded", "payment.partially-refunded"})
        @DisplayName("covers the channels the transactional factory asks for")
        void coversRequestedChannels(String key) {
            NotificationTemplate template = catalogue.find(key).orElseThrow();

            assertThat(template.supports(NotificationChannel.EMAIL))
                    .as("EMAIL copy for " + key).isTrue();
            assertThat(template.supports(NotificationChannel.IN_APP))
                    .as("IN_APP copy for " + key).isTrue();
        }

        /**
         * SMS copy exists for every template even though almost nobody has opted in. Writing it now means
         * enabling SMS is a preference change rather than a content project — and a template gaining its
         * first SMS body later, unreviewed, is how a 400-character email body ends up being billed as three
         * segments to every customer.
         */
        @ParameterizedTest
        @ValueSource(strings = {"payment.sent", "payment.received", "payment.failed",
                "payment.rejected", "payment.refunded", "payment.partially-refunded"})
        @DisplayName("has SMS copy that fits one segment")
        void smsFitsOneSegment(String key) {
            String sms = catalogue.find(key).orElseThrow()
                    .bodyFor(NotificationChannel.SMS).orElseThrow();

            // Measured before interpolation, so the placeholders stand in for their values. Generous
            // enough to allow for that, and tight enough to catch a body that has grown a paragraph.
            assertThat(sms.length())
                    .as("SMS is billed per 160-character segment, per recipient, forever")
                    .isLessThanOrEqualTo(200);
        }

        /**
         * <strong>The other half of the coverage question.</strong> A template referencing a variable no
         * producer sends renders nothing: the required-placeholder check fails and the delivery is
         * suppressed. Every notification of that kind, silently.
         */
        @ParameterizedTest
        @ValueSource(strings = {"payment.sent", "payment.received", "payment.failed",
                "payment.rejected", "payment.refunded", "payment.partially-refunded"})
        @DisplayName("references only variables the platform actually supplies")
        void referencesOnlyAvailableVariables(String key) {
            NotificationTemplate template = catalogue.find(key).orElseThrow();

            assertThat(template.allVariables())
                    .as("""
                            a template referencing a variable nobody sends fails its required-placeholder \
                            check and suppresses every delivery of this kind""")
                    .isSubsetOf(AVAILABLE_VARIABLES);
        }

        /**
         * The amount and the currency are the point of the message. A template that rendered without them
         * would be a notification about a payment that does not say how much.
         */
        @ParameterizedTest
        @ValueSource(strings = {"payment.sent", "payment.received", "payment.failed",
                "payment.rejected", "payment.refunded", "payment.partially-refunded"})
        @DisplayName("always states the amount and the currency")
        void alwaysStatesTheAmount(String key) {
            assertThat(catalogue.find(key).orElseThrow().requiredVariables())
                    .contains("amount", "currency");
        }

        /**
         * The customer-controlled note is optional in every template. Required, it would fail to render for
         * every payment made without a description — which is most of them.
         */
        @ParameterizedTest
        @ValueSource(strings = {"payment.sent", "payment.received", "payment.failed",
                "payment.rejected", "payment.refunded", "payment.partially-refunded"})
        @DisplayName("never requires the customer-supplied description")
        void descriptionIsOptional(String key) {
            assertThat(catalogue.find(key).orElseThrow().requiredVariables())
                    .as("most payments carry no description, and a required placeholder would suppress "
                            + "all of them")
                    .doesNotContain("description")
                    .doesNotContain("greeting");
        }

        /** Every email needs a subject, or a mail client files it as "(no subject)" and it reads as spam. */
        @ParameterizedTest
        @ValueSource(strings = {"payment.sent", "payment.received", "payment.failed",
                "payment.rejected", "payment.refunded", "payment.partially-refunded"})
        @DisplayName("gives every email a subject")
        void everyEmailHasASubject(String key) {
            assertThat(catalogue.find(key).orElseThrow().subject()).isNotBlank();
        }

        /**
         * A payment failure or rejection must say what happened to the money. "Your payment failed" without
         * "nothing has been taken" is the message that generates the support call, because the customer
         * cannot tell whether they have been charged.
         */
        @ParameterizedTest
        @ValueSource(strings = {"payment.failed", "payment.rejected"})
        @DisplayName("tells a customer their money is safe when a payment does not go through")
        void failureCopyReassuresAboutTheMoney(String key) {
            NotificationTemplate template = catalogue.find(key).orElseThrow();

            for (NotificationChannel channel : List.of(NotificationChannel.EMAIL,
                    NotificationChannel.SMS, NotificationChannel.IN_APP)) {

                String body = template.bodyFor(channel).orElseThrow().toLowerCase();
                assertThat(body)
                        .as("""
                                %s copy for %s must say what happened to the money; without it the \
                                customer cannot tell whether they have been charged""", channel, key)
                        .containsAnyOf("nothing was taken", "nothing has been taken",
                                "released", "not been sent");
            }
        }

        /**
         * {@code payment.received} carries a note written by <em>another customer</em>. The email version
         * warns the reader not to act on instructions in it, which is the only mitigation available for the
         * residual phishing risk once links have been stripped.
         */
        @Test
        @DisplayName("warns the payee not to trust instructions in a sender's note")
        void receivedCopyWarnsAboutTheSendersNote() {
            String email = catalogue.find("payment.received").orElseThrow()
                    .bodyFor(NotificationChannel.EMAIL).orElseThrow().toLowerCase();

            assertThat(email)
                    .as("this is the one path where one customer's chosen text reaches another under "
                            + "the platform's name")
                    .contains("sender")
                    .containsAnyOf("suspicion", "never ask");
        }

        /**
         * The SMS version of {@code payment.received} omits the note entirely. There is no room for the
         * warning in 160 characters, and a bare attacker-written sentence arriving as a bank text is a
         * phishing message the platform paid to send.
         */
        @Test
        @DisplayName("keeps the sender's note out of the SMS entirely")
        void receivedSmsOmitsTheNote() {
            String sms = catalogue.find("payment.received").orElseThrow()
                    .bodyFor(NotificationChannel.SMS).orElseThrow();

            assertThat(sms)
                    .as("""
                            160 characters leaves no room for the warning, and an unwarned attacker \
                            sentence delivered as a bank text is worse than omitting the note""")
                    .doesNotContain("{{description");
        }
    }

    // ------------------------------------------------------------------------
    // parsing
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("parsing")
    class Parsing {

        @Test
        @DisplayName("splits sections and trims each one")
        void parsesSections() {
            NotificationTemplate template = TemplateCatalogue.parse("test.key", """
                    --- SUBJECT ---
                    A subject

                    --- EMAIL ---
                    An email body.

                    --- SMS ---
                    An SMS body.

                    --- IN_APP ---
                    An in-app body.
                    """);

            assertThat(template.subject()).isEqualTo("A subject");
            assertThat(template.bodyFor(NotificationChannel.EMAIL)).contains("An email body.");
            assertThat(template.bodyFor(NotificationChannel.SMS)).contains("An SMS body.");
            assertThat(template.bodyFor(NotificationChannel.IN_APP)).contains("An in-app body.");
        }

        @Test
        @DisplayName("keeps a multi-line body intact")
        void keepsMultiLineBodies() {
            NotificationTemplate template = TemplateCatalogue.parse("test.key", """
                    --- IN_APP ---
                    First line.

                    Second line.
                    """);

            assertThat(template.bodyFor(NotificationChannel.IN_APP).orElseThrow())
                    .isEqualTo("First line.\n\nSecond line.");
        }

        /** The repository is checked out on Windows as often as not. */
        @Test
        @DisplayName("reads a file with Windows line endings")
        void handlesCrlf() {
            NotificationTemplate template = TemplateCatalogue.parse("test.key",
                    "--- IN_APP ---\r\nA body.\r\n");

            assertThat(template.bodyFor(NotificationChannel.IN_APP)).contains("A body.");
        }

        @Test
        @DisplayName("accepts IN-APP and INAPP as aliases")
        void acceptsInAppAliases() {
            assertThat(TemplateCatalogue.parse("k", "--- IN-APP ---\nbody\n")
                    .supports(NotificationChannel.IN_APP)).isTrue();
            assertThat(TemplateCatalogue.parse("k", "--- INAPP ---\nbody\n")
                    .supports(NotificationChannel.IN_APP)).isTrue();
        }

        // -------------------------------------------------------------- rejection

        /**
         * Silently ignoring an unknown section would let somebody add copy, see no error, and believe a
         * channel had been enabled.
         */
        @Test
        @DisplayName("rejects an unknown section")
        void rejectsUnknownSection() {
            assertThatThrownBy(() -> TemplateCatalogue.parse("k", """
                    --- IN_APP ---
                    body
                    --- WHATSAPP ---
                    body
                    """))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("WHATSAPP");
        }

        /**
         * PUSH is rejected specifically rather than falling through to "unknown". Somebody adding push copy
         * is making a reasonable assumption — the channel is in the shared enum — and deserves to be told
         * why it cannot work rather than that the section name is wrong.
         */
        @Test
        @DisplayName("rejects PUSH copy with an explanation")
        void rejectsPushCopy() {
            assertThatThrownBy(() -> TemplateCatalogue.parse("k", """
                    --- IN_APP ---
                    body
                    --- PUSH ---
                    body
                    """))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("device-token registry");
        }

        @Test
        @DisplayName("rejects an email body with no subject")
        void rejectsEmailWithoutSubject() {
            assertThatThrownBy(() -> TemplateCatalogue.parse("k", """
                    --- EMAIL ---
                    body
                    """))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SUBJECT");
        }

        /**
         * An empty section is the worst malformed case, which is why it is rejected rather than skipped: the
         * channel would be reported as delivered while the customer received a blank message.
         */
        @Test
        @DisplayName("rejects an empty section")
        void rejectsEmptySection() {
            assertThatThrownBy(() -> TemplateCatalogue.parse("k", """
                    --- IN_APP ---

                    --- SMS ---
                    body
                    """))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("empty");
        }

        @Test
        @DisplayName("rejects a file with no sections at all")
        void rejectsSectionlessFile() {
            assertThatThrownBy(() -> TemplateCatalogue.parse("k", "just some prose"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects content before the first section")
        void rejectsContentBeforeFirstSection() {
            assertThatThrownBy(() -> TemplateCatalogue.parse("k", """
                    a stray line
                    --- IN_APP ---
                    body
                    """))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("before the first");
        }

        @Test
        @DisplayName("rejects a template with a subject and no body")
        void rejectsSubjectOnly() {
            assertThatThrownBy(() -> TemplateCatalogue.parse("k", """
                    --- SUBJECT ---
                    only a subject
                    """))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no body");
        }
    }

    // ------------------------------------------------------------------------
    // lookup
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("lookup")
    class Lookup {

        /**
         * An {@code Optional}, not a throw. "No copy for this key" is a routine condition the caller handles
         * by suppressing and counting — an exception would propagate into the Kafka error handler, which
         * would retry the record four times and dead-letter it for a fault no retry can fix.
         */
        @Test
        @DisplayName("returns empty for an unknown key rather than throwing")
        void unknownKeyIsEmpty() {
            assertThat(catalogue.find("payment.invented")).isEmpty();
            assertThat(catalogue.supports("payment.invented", NotificationChannel.EMAIL)).isFalse();
        }

        @Test
        @DisplayName("reports keys in a stable order")
        void keysAreSorted() {
            assertThat(catalogue.keys()).isSorted();
        }

        /**
         * A corpus that cannot be read is fatal. Every notification would be suppressed for want of copy,
         * and the service would report itself healthy while doing it.
         */
        /**
         * A location that resolves to nothing. Reaches the emptiness check itself: the directory exists, so
         * the scan succeeds and simply returns no files.
         */
        @Test
        @DisplayName("refuses to start when the location resolves to no templates")
        void emptyCorpusIsFatal() {
            assertThatThrownBy(() ->
                    new TemplateCatalogue("classpath:notification-templates/*.nothing-matches-this"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No notification templates");
        }

        /**
         * A location that cannot be scanned at all. A different branch from the one above, and both must be
         * fatal — a service that came up with no copy would suppress every notification and report itself
         * healthy while doing it.
         */
        @Test
        @DisplayName("refuses to start when the location cannot be read")
        void unreadableCorpusIsFatal() {
            assertThatThrownBy(() -> new TemplateCatalogue("file:///no/such/path/**/*.template"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("suppressed");
        }
    }
}
