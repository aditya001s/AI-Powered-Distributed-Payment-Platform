package com.aegispay.notification.template;

import com.aegispay.common.domain.SharedEnums.NotificationChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rendering, and the defences that live in it.
 *
 * <h2>Why this class carries the most weight in the module</h2>
 *
 * <p>One notification variable originates with a customer and travels intact: a payment
 * {@code description}. On {@code payment.received} it is written by the <em>payer</em> and delivered to
 * the <em>payee</em>, which makes it the only path on this platform where one customer can put chosen text
 * in front of another under the platform's own name.
 *
 * <p>That is a phishing channel, and a good one — an email from the payment provider saying "your account
 * needs verification, sign in at ..." is more convincing than anything an attacker could send directly.
 * Every failure mode here also produces a message that looks completely normal, which is why it is tested
 * rather than reviewed.
 */
class TemplateRendererTest {

    private final TemplateRenderer renderer = new TemplateRenderer();

    // ------------------------------------------------------------------------
    // interpolation
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("interpolation")
    class Interpolation {

        @Test
        @DisplayName("substitutes required and optional values")
        void substitutes() {
            RenderedMessage message = renderer.render(
                    template("Sent {{amount}} {{currency}}",
                            "You sent {{amount}} {{currency}}. Ref {{transactionId}}. {{description?}}"),
                    NotificationChannel.EMAIL,
                    Map.of("amount", "2000.00", "currency", "INR", "transactionId", "TXN-1",
                            // Deliberately padded: a value is stripped, so the spacing in the output
                            // comes from the template rather than from whatever the customer typed.
                            "description", "  Rent  "));

            assertThat(message.subject()).isEqualTo("Sent 2000.00 INR");
            assertThat(message.body()).isEqualTo("You sent 2000.00 INR. Ref TXN-1. Rent");
        }

        @Test
        @DisplayName("renders an absent optional value as nothing")
        void optionalMayBeAbsent() {
            RenderedMessage message = renderer.render(
                    inAppTemplate("You sent {{amount}}.{{description?}}"),
                    NotificationChannel.IN_APP,
                    Map.of("amount", "10.00"));

            assertThat(message.body()).isEqualTo("You sent 10.00.");
        }

        /**
         * <strong>The assertion that stops an embarrassing permanent record.</strong> A notification is
         * immutable, so a message reading "You sent {{amount}} INR" is not a glitch that can be corrected
         * — it is what the customer was told, forever, and the only remedy is a second message admitting
         * the first was wrong.
         */
        @Test
        @DisplayName("refuses to render when a required value is missing")
        void refusesMissingRequired() {
            assertThatThrownBy(() -> renderer.render(
                    template("Sent {{amount}}", "You sent {{amount}} {{currency}}."),
                    NotificationChannel.EMAIL,
                    Map.of("currency", "INR")))
                    .isInstanceOf(TemplateRenderer.TemplateRenderException.class)
                    .hasMessageContaining("amount");
        }

        /** A blank value is as unusable as an absent one, and passes a null check. */
        @Test
        @DisplayName("treats a blank required value as missing")
        void blankIsMissing() {
            assertThatThrownBy(() -> renderer.render(
                    inAppTemplate("You sent {{amount}}."),
                    NotificationChannel.IN_APP,
                    Map.of("amount", "   ")))
                    .isInstanceOf(TemplateRenderer.TemplateRenderException.class)
                    .hasMessageContaining("amount");
        }

        @Test
        @DisplayName("reports every missing value at once, not just the first")
        void reportsAllMissing() {
            assertThatThrownBy(() -> renderer.render(
                    inAppTemplate("{{amount}} {{currency}} {{transactionId}}"),
                    NotificationChannel.IN_APP,
                    Map.of()))
                    .isInstanceOf(TemplateRenderer.TemplateRenderException.class)
                    .hasMessageContaining("amount")
                    .hasMessageContaining("currency")
                    .hasMessageContaining("transactionId");
        }

        /**
         * A value containing {@code $} or {@code \} is ordinary in a payment note and is a replacement
         * escape in {@code Matcher.appendReplacement}. Unquoted, {@code $1} would either throw or splice
         * in a capture group.
         */
        @Test
        @DisplayName("handles a value containing regex replacement characters")
        void handlesReplacementCharacters() {
            RenderedMessage message = renderer.render(
                    inAppTemplate("Note: {{description?}}"),
                    NotificationChannel.IN_APP,
                    Map.of("description", "cost $5 \\ 50% off $1"));

            assertThat(message.body()).contains("$5").contains("$1");
        }

        /**
         * A label whose optional value vanished would otherwise render as "Note:" alone on a line, which
         * reads to a customer as something having failed to load.
         */
        @Test
        @DisplayName("removes a label left empty by an absent optional value")
        void tidiesEmptyLabels() {
            RenderedMessage message = renderer.render(
                    template("Sent", "You sent {{amount}}.\n\nNote: {{description?}}\n\nThanks."),
                    NotificationChannel.EMAIL,
                    Map.of("amount", "10.00"));

            assertThat(message.body())
                    .doesNotContain("Note:")
                    .contains("You sent 10.00.")
                    .contains("Thanks.");
        }

        @Test
        @DisplayName("refuses a channel the template has no copy for")
        void refusesUnsupportedChannel() {
            assertThatThrownBy(() -> renderer.render(
                    template("Sent", "body"), NotificationChannel.SMS, Map.of()))
                    .isInstanceOf(TemplateRenderer.TemplateRenderException.class)
                    .hasMessageContaining("SMS");
        }
    }

    // ------------------------------------------------------------------------
    // the phishing defence
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("customer-controlled text")
    class UntrustedText {

        /**
         * <strong>The most important test here.</strong> Template text may contain a link; an interpolated
         * value may not. That rule is complete and has no judgement calls in it, which is why it was
         * chosen over a per-variable allow-list — a list has to be updated whenever a producer adds a
         * field, and the cost of forgetting is a live phishing vector.
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "https://evil.example.com/login",
                "http://evil.example.com",
                "//evil.example.com/x",
                "www.evil.example.com",
                "evil.example.com/verify",
                "javascript:alert(1)",
                "data:text/html;base64,PHNjcmlwdD4=",
                "Verify at bit.ly/x9f2"
        })
        @DisplayName("removes anything that could become a link")
        void defangsLinks(String hostile) {
            RenderedMessage message = renderer.render(
                    template("Sent", "Note from the sender: {{description?}}"),
                    NotificationChannel.EMAIL,
                    Map.of("description", hostile));

            assertThat(message.body())
                    .as("""
                            an email from the payment provider carrying an attacker's link is more \
                            convincing than anything they could send directly""")
                    .contains("[link removed]")
                    .doesNotContain("evil.example.com")
                    .doesNotContain("javascript:")
                    .doesNotContain("bit.ly");
        }

        /**
         * A newline lets a description forge structure: a blank line followed by
         * "-- AegisPay Security Team" reads as a second, official section of the email rather than as part
         * of the sender's note.
         */
        /**
         * <strong>Regression test for a real defect.</strong> The first link pattern treated
         * "any word followed by a colon" as a URI scheme, on the reasoning that {@code javascript:} and
         * {@code data:} have no {@code //}. It therefore matched ordinary prose: "Invoice: 4471" rendered
         * as "[link removed] 4471".
         *
         * <p>The damage would have been quiet and one-directional. Notifications are immutable, so every
         * affected message was permanently mangled; the customer would see their own note corrupted and
         * conclude the platform had lost it; and no error, metric or log line would have reported anything,
         * because from the renderer's point of view it had successfully defanged a link.
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "Invoice: 4471",
                "Note: rent for January",
                "Ref: ABC-123",
                "Split: 50/50 with Sam",
                "Reason: overpaid last month"
        })
        @DisplayName("leaves ordinary text containing a colon alone")
        void doesNotDefangOrdinaryProse(String innocent) {
            RenderedMessage message = renderer.render(
                    inAppTemplate("Note: {{description?}}"),
                    NotificationChannel.IN_APP,
                    Map.of("description", innocent));

            assertThat(message.body())
                    .as("a customer whose own note came back mangled would conclude the platform "
                            + "had lost it, and nothing would have reported a fault")
                    .doesNotContain("[link removed]")
                    .contains(innocent);
        }

        /** Amounts contain full stops and slashes and must survive both link alternatives. */
        @ParameterizedTest
        @ValueSource(strings = {"2000.00", "3.50/month", "N/A", "e.g. rent", "50% off"})
        @DisplayName("leaves amounts and everyday punctuation alone")
        void doesNotDefangAmounts(String innocent) {
            RenderedMessage message = renderer.render(
                    inAppTemplate("Note: {{description?}}"),
                    NotificationChannel.IN_APP,
                    Map.of("description", innocent));

            assertThat(message.body()).doesNotContain("[link removed]").contains(innocent);
        }

        @Test
        @DisplayName("flattens newlines so a value cannot forge a new section")
        void flattensNewlines() {
            RenderedMessage message = renderer.render(
                    template("Sent", "Note: {{description?}} END"),
                    NotificationChannel.EMAIL,
                    Map.of("description",
                            "thanks\n\n--\nAegisPay Security: confirm your PIN by replying"));

            assertThat(message.body())
                    .doesNotContain("\n")
                    .contains("AegisPay Security");
        }

        @Test
        @DisplayName("escapes markup in an email body")
        void escapesHtmlForEmail() {
            RenderedMessage message = renderer.render(
                    template("Sent", "Note: {{description?}}"),
                    NotificationChannel.EMAIL,
                    Map.of("description", "<script>steal()</script> & \"quoted\""));

            assertThat(message.body())
                    .doesNotContain("<script>")
                    .contains("&lt;script&gt;")
                    .contains("&amp;")
                    .contains("&quot;");
        }

        /**
         * The opposite mistake, and easy to make by escaping everywhere. An SMS is plain text, so a
         * customer who wrote "Rent & bills" must not receive "Rent &amp; bills".
         */
        @Test
        @DisplayName("does not HTML-escape a plain-text channel")
        void doesNotEscapeSms() {
            RenderedMessage message = renderer.render(
                    smsTemplate("Note {{description?}}"),
                    NotificationChannel.SMS,
                    Map.of("description", "Rent & bills"));

            assertThat(message.body()).contains("Rent & bills").doesNotContain("&amp;");
        }

        @Test
        @DisplayName("does not HTML-escape an in-app body")
        void doesNotEscapeInApp() {
            RenderedMessage message = renderer.render(
                    inAppTemplate("Note {{description?}}"),
                    NotificationChannel.IN_APP,
                    Map.of("description", "A & B"));

            assertThat(message.body()).contains("A & B").doesNotContain("&amp;");
        }

        /**
         * A long value must not be able to push the platform's own copy out of the message — in particular
         * the warning on {@code payment.received} telling the reader not to trust instructions in the
         * sender's note.
         */
        @Test
        @DisplayName("caps a single value so it cannot dominate the message")
        void capsValueLength() {
            RenderedMessage message = renderer.render(
                    template("Sent", "Note: {{description?}} -- AegisPay"),
                    NotificationChannel.EMAIL,
                    Map.of("description", "x".repeat(5_000)));

            assertThat(message.body())
                    .as("the platform's own words must survive a hostile value")
                    .endsWith("-- AegisPay");
            assertThat(message.body().length()).isLessThan(400);
        }

        @Test
        @DisplayName("strips control characters")
        void stripsControlCharacters() {
            RenderedMessage message = renderer.render(
                    inAppTemplate("Note {{description?}} END"),
                    NotificationChannel.IN_APP,
                    Map.of("description", "a\u0000b\u0007c"));

            assertThat(message.body()).doesNotContain("\u0000").doesNotContain("\u0007");
        }
    }

    // ------------------------------------------------------------------------
    // secrets
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("secret redaction")
    class Secrets {

        /**
         * Producers are told not to put secrets in notification variables. This is the defence for when one
         * does anyway. Matching is on the <em>name</em>, because the value of a leaked credential is not
         * recognisable, and a notification is read by support staff as well as by the customer.
         */
        @ParameterizedTest
        @ValueSource(strings = {"password", "otp", "oneTimePasscode", "apiKey", "api_key",
                "sessionId", "cardCvv", "resetToken", "clientSecret", "privateKey"})
        @DisplayName("redacts a value whose name looks like a credential")
        void redactsSecretLikeNames(String name) {
            RenderedMessage message = renderer.render(
                    inAppTemplate("Value: {{" + name + "}}"),
                    NotificationChannel.IN_APP,
                    Map.of(name, "hunter2-the-actual-secret"));

            assertThat(message.body())
                    .contains("[redacted]")
                    .doesNotContain("hunter2");
        }

        @Test
        @DisplayName("leaves an ordinary variable alone")
        void doesNotOverReach() {
            RenderedMessage message = renderer.render(
                    inAppTemplate("Ref {{transactionId}} for {{amount}}"),
                    NotificationChannel.IN_APP,
                    Map.of("transactionId", "TXN-123", "amount", "10.00"));

            assertThat(message.body()).isEqualTo("Ref TXN-123 for 10.00");
        }

        /**
         * Redaction happens on the way <em>in</em>, not only at render time. A credential that was merely
         * un-rendered would still be sitting in the database and in the admin API response, which are read
         * by more people than the message is.
         */
        @Test
        @DisplayName("redacts before storage as well as before rendering")
        void redactsForStorage() {
            assertThat(TemplateRenderer.sanitiseForStorage("otp", "123456")).isEqualTo("[redacted]");
            assertThat(TemplateRenderer.sanitiseForStorage("amount", "10.00")).isEqualTo("10.00");
        }

        /**
         * The stored copy is channel-neutral and is <em>not</em> HTML-escaped, unlike the rendered one. An
         * operator reading the variables must see what the customer actually typed — {@code &amp;} where
         * they wrote {@code &} would eventually lead somebody to conclude the input had been corrupted.
         */
        @Test
        @DisplayName("does not HTML-escape the stored copy")
        void storageIsNotEscaped() {
            assertThat(TemplateRenderer.sanitiseForStorage("description", "A & B <c>"))
                    .isEqualTo("A & B <c>");
        }

        @Test
        @DisplayName("flattens and caps the stored copy")
        void storageIsFlattened() {
            assertThat(TemplateRenderer.sanitiseForStorage("description", "a\nb\t c"))
                    .isEqualTo("a b c");
            assertThat(TemplateRenderer.sanitiseForStorage("description", "x".repeat(500)))
                    .hasSizeLessThanOrEqualTo(140);
        }
    }

    // ------------------------------------------------------------------------
    // per-channel shaping
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("channel shaping")
    class ChannelShaping {

        /**
         * SMS is billed per 160-character segment, per recipient. A template that grew past one segment
         * would silently double the cost of every notification.
         */
        @Test
        @DisplayName("caps an SMS at one segment")
        void capsSms() {
            RenderedMessage message = renderer.render(
                    smsTemplate("AegisPay: " + "detail ".repeat(60) + "{{amount}}"),
                    NotificationChannel.SMS,
                    Map.of("amount", "10.00"));

            assertThat(message.body()).hasSizeLessThanOrEqualTo(160);
            assertThat(message.body()).endsWith("...");
        }

        @Test
        @DisplayName("collapses whitespace in an SMS")
        void flattensSms() {
            RenderedMessage message = renderer.render(
                    smsTemplate("Sent\n\n{{amount}}"),
                    NotificationChannel.SMS,
                    Map.of("amount", "10.00"));

            assertThat(message.body()).isEqualTo("Sent 10.00");
        }

        @Test
        @DisplayName("gives SMS and in-app no subject")
        void noSubjectForPlainChannels() {
            assertThat(renderer.render(smsTemplate("x {{amount}}"), NotificationChannel.SMS,
                    Map.of("amount", "1.00")).subject()).isNull();
            assertThat(renderer.render(inAppTemplate("x {{amount}}"), NotificationChannel.IN_APP,
                    Map.of("amount", "1.00")).subject()).isNull();
        }

        /**
         * A newline in a subject line is header injection in any real mail transport. Flattened here rather
         * than left to the transport, so the stored record and the sent message agree — and so a future
         * real transport cannot be the only thing standing in the way.
         */
        @Test
        @DisplayName("flattens a subject line so it cannot carry a header")
        void flattensSubject() {
            RenderedMessage message = renderer.render(
                    template("Sent {{description?}}", "body {{amount}}"),
                    NotificationChannel.EMAIL,
                    Map.of("amount", "1.00",
                            "description", "x\nBcc: victim@example.com"));

            assertThat(message.subject())
                    .doesNotContain("\n")
                    .doesNotContain("\r");
        }

        @Test
        @DisplayName("caps a subject line")
        void capsSubject() {
            RenderedMessage message = renderer.render(
                    template("Sent {{description?}}", "body {{amount}}"),
                    NotificationChannel.EMAIL,
                    Map.of("amount", "1.00", "description", "y".repeat(400)));

            assertThat(message.subject()).hasSizeLessThanOrEqualTo(180);
        }
    }

    // ---------------------------------------------------------------- fixtures

    private static NotificationTemplate template(String subject, String emailBody) {
        Map<NotificationChannel, String> bodies = new LinkedHashMap<>();
        bodies.put(NotificationChannel.EMAIL, emailBody);
        return new NotificationTemplate("test.key", subject, bodies);
    }

    private static NotificationTemplate smsTemplate(String body) {
        return new NotificationTemplate("test.key", null,
                Map.of(NotificationChannel.SMS, body));
    }

    private static NotificationTemplate inAppTemplate(String body) {
        return new NotificationTemplate("test.key", null,
                Map.of(NotificationChannel.IN_APP, body));
    }
}
