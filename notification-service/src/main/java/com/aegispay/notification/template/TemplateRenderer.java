package com.aegispay.notification.template;

import com.aegispay.common.domain.SharedEnums.NotificationChannel;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a template and a set of variables into a message.
 *
 * <h2>The threat model, because it is not obvious</h2>
 *
 * <p>Notification variables are not trusted input in the usual sense — they come from another AegisPay
 * service over Kafka, not from the internet. But one of them originates with a customer and travels
 * intact: a payment {@code description}. On {@code payment.received} that description is written by the
 * <em>payer</em> and delivered to the <em>payee</em>, which makes it the only path on this platform
 * where one customer can put chosen text in front of another under the platform's own name.
 *
 * <p>That is a phishing channel, and a good one: an email from the payment provider carrying "Your
 * account needs verification, sign in at ..." is more convincing than anything an attacker could send
 * directly. So:
 *
 * <ul>
 *   <li><strong>Every interpolated value is escaped and defanged, not just the ones believed to be
 *       untrusted.</strong> A per-variable allow-list was rejected: it would have to be updated
 *       whenever a producer adds a field, and the failure mode of forgetting is a live phishing
 *       vector. Escaping a platform-generated value like {@code 2000.00} costs nothing, because there
 *       is nothing in it to escape.</li>
 *   <li><strong>Links are removed from values.</strong> The rule is that template text may contain a
 *       link and an interpolated value may not — templates are written by this team and reviewed;
 *       values are not. That is a complete rule with no judgement calls in it.</li>
 *   <li><strong>Newlines are flattened.</strong> A multi-line value can forge structure: a description
 *       ending with a blank line and "-- AegisPay Security Team" reads as a second, official section
 *       of the email.</li>
 * </ul>
 *
 * <h2>Why an unresolved placeholder refuses to render</h2>
 *
 * <p>Sending "You sent {{amount}} INR" is worse than sending nothing. The notification is immutable, so
 * the broken version is the permanent record of what the customer was told, and it cannot be corrected
 * — only followed by a second message admitting the first was wrong. A required placeholder with no
 * value therefore fails the render, and the delivery is suppressed and counted.
 */
@Component
public class TemplateRenderer {

    /**
     * Anything that could become a clickable link.
     *
     * <p>Broader than {@code https?://} deliberately. Mail clients linkify bare domains, so
     * {@code evil.example.com} is a link in practice even though it is not a URL in form. The pattern
     * also catches {@code www.}, a scheme-relative {@code //host}, and any {@code scheme:} prefix so
     * {@code data:} and {@code javascript:} cannot slip through.
     */
    private static final Pattern LINK_LIKE = Pattern.compile("(?i)"
            // A scheme with an authority: http://, https://, ftp://, anything://.
            + "\\b[a-z][a-z0-9+.-]*://\\S*"
            // Schemes that are actionable with no authority at all. Listed explicitly rather than
            // matching "any word followed by a colon", which was the first attempt and mangled every
            // ordinary description: "Invoice: 4471" became "[link removed] 4471", and a customer
            // reading it would reasonably conclude the platform had corrupted their note.
            + "|\\b(?:javascript|data|vbscript|file|mailto|tel):\\S*"
            // Scheme-relative, which a mail client resolves against its own base.
            + "|//[a-z0-9.-]+\\S*"
            + "|\\bwww\\.\\S+"
            // A domain with a path. Any TLD, because the slash is what makes this a link rather than a
            // sentence -- and a TLD allow-list can never be complete, as bit.ly demonstrates.
            + "|\\b[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*"
            + "\\.[a-z]{2,24}/\\S*"
            // A bare domain with no path. Restricted to TLDs a mail client will linkify, because
            // without a slash this alternative would otherwise match ordinary prose containing a
            // full stop.
            + "|\\b[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*"
            + "\\.(?:com|net|org|io|co|ru|cn|xyz|top|link|app|dev|info|biz|me|ly|gg|to|sh|be|tk)"
            + "\\b\\S*");

    /** Replaces a removed link. Visible on purpose: silent removal makes the copy read oddly. */
    private static final String LINK_REMOVED = "[link removed]";

    /**
     * Variable names whose values are dropped entirely rather than escaped.
     *
     * <p>Producers are told not to put secrets in notification variables. This is the defence for when
     * one does anyway: the value never reaches a message, a log or the stored record. Matching is on
     * the name because the value of a leaked credential is not recognisable, and a notification is
     * read by support staff as well as by the customer.
     */
    private static final Set<String> SECRET_LIKE = Set.of(
            "password", "passcode", "pin", "otp", "token", "secret", "apikey", "api_key",
            "credential", "cvv", "cvc", "privatekey", "private_key", "sessionid", "session_id");

    private static final String REDACTED = "[redacted]";

    /** SMS segment size in GSM-7. Beyond this a message is billed and delivered as several parts. */
    static final int SMS_MAX_LENGTH = 160;

    /** Cap on any single interpolated value, so one long field cannot dominate a message. */
    static final int MAX_VALUE_LENGTH = 140;

    /**
     * Renders one channel's message.
     *
     * @param variables the producer's values, plus any the service supplies
     * @throws TemplateRenderException when a required placeholder has no value, or the template has no
     *                                 copy for this channel
     */
    public RenderedMessage render(NotificationTemplate template,
                                 NotificationChannel channel,
                                 Map<String, String> variables) {

        String body = template.bodyFor(channel).orElseThrow(() -> new TemplateRenderException(
                "template " + template.templateKey() + " has no copy for " + channel));

        Map<String, String> safe = sanitiseAll(variables == null ? Map.of() : variables, channel);

        String renderedBody = interpolate(body, safe, template.templateKey(), channel);
        String renderedSubject = null;

        if (channel == NotificationChannel.EMAIL) {
            renderedSubject = flattenSubject(
                    interpolate(template.subject(), safe, template.templateKey(), channel));
        }

        if (channel == NotificationChannel.SMS) {
            renderedBody = capSms(renderedBody);
        }
        return new RenderedMessage(channel, renderedSubject, renderedBody);
    }

    // ------------------------------------------------------------ interpolation

    private static String interpolate(String text, Map<String, String> variables,
                                      String templateKey, NotificationChannel channel) {
        if (text == null) {
            throw new TemplateRenderException(
                    "template " + templateKey + " has no text to render for " + channel);
        }
        Set<String> missing = new LinkedHashSet<>();
        Matcher matcher = NotificationTemplate.PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder(text.length() + 64);

        while (matcher.find()) {
            String name = matcher.group(1);
            boolean optional = matcher.group(2) != null;
            String value = variables.get(name);

            if (value == null || value.isBlank()) {
                if (!optional) {
                    missing.add(name);
                }
                value = "";
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);

        if (!missing.isEmpty()) {
            throw new TemplateRenderException(
                    "template " + templateKey + " needs " + missing + " for " + channel
                            + ", and the producer did not supply "
                            + (missing.size() == 1 ? "it" : "them"));
        }
        return tidy(out.toString());
    }

    /**
     * Removes the gaps an absent optional value leaves behind.
     *
     * <p>Without this, a payment with no description renders "Note: " on a line of its own, which reads
     * as though something failed to load. Collapsing repeated blank lines and trailing label
     * punctuation is cosmetic, but the customer cannot tell cosmetic from broken.
     */
    private static String tidy(String text) {
        return text
                // A label whose value vanished: "Note: \n" -> gone.
                .replaceAll("(?m)^[A-Za-z][A-Za-z ]{0,30}:[ \\t]*$\\n?", "")
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    private static String flattenSubject(String subject) {
        // A newline in a subject line is header injection in any real mail transport. Flattened here
        // rather than trusted to the transport, so the stored record and the sent message agree.
        String flat = subject.replaceAll("[\\r\\n]+", " ").replaceAll("\\s{2,}", " ").strip();
        return flat.length() <= 180 ? flat : flat.substring(0, 177) + "...";
    }

    private static String capSms(String body) {
        String flat = body.replaceAll("\\s+", " ").strip();
        if (flat.length() <= SMS_MAX_LENGTH) {
            return flat;
        }
        // Truncated with a marker rather than split into parts. A silently split message arrives out
        // of order often enough to be worse than a short one, and the important content — the amount
        // and the reference — is at the front of every SMS template for exactly this reason.
        return flat.substring(0, SMS_MAX_LENGTH - 3) + "...";
    }

    // -------------------------------------------------------------- sanitising

    private Map<String, String> sanitiseAll(Map<String, String> variables,
                                            NotificationChannel channel) {
        Map<String, String> safe = new java.util.LinkedHashMap<>();
        variables.forEach((name, value) -> safe.put(name, sanitise(name, value, channel)));
        return safe;
    }

    /**
     * Makes one value safe to place in a message.
     *
     * <p>Order matters. Secrets are dropped before anything else looks at them; links are removed
     * before escaping, so an escaped {@code &lt;a href&gt;} cannot hide a URL from the link pattern;
     * escaping happens last so the removal marker itself is not double-escaped.
     */
    static String sanitise(String name, String value, NotificationChannel channel) {
        if (value == null) {
            return null;
        }
        if (looksSecret(name)) {
            return REDACTED;
        }
        String cleaned = stripControlCharacters(value);
        cleaned = LINK_LIKE.matcher(cleaned).replaceAll(LINK_REMOVED);
        cleaned = cleaned.replaceAll("\\s{2,}", " ").strip();

        if (cleaned.length() > MAX_VALUE_LENGTH) {
            cleaned = cleaned.substring(0, MAX_VALUE_LENGTH - 3) + "...";
        }
        // Escaped for the one channel that interprets markup. Applied per channel rather than
        // universally so an SMS does not arrive containing "&amp;" where the customer wrote "&".
        return channel == NotificationChannel.EMAIL ? escapeHtml(cleaned) : cleaned;
    }

    /**
     * Makes one value safe to <em>store</em>, as opposed to safe to render.
     *
     * <p>Channel-neutral, because the stored copy is not going into a message: it is a diagnostic record
     * read by support staff and returned by an admin API. So it is redacted and flattened but not
     * HTML-escaped — escaping here would mean an operator reading the variables sees {@code &amp;} where
     * the customer typed {@code &}, and would eventually lead somebody to conclude the customer's input
     * had been corrupted.
     *
     * <p>Secret redaction happens on the way in rather than only at render time, deliberately. A leaked
     * credential that was merely un-rendered would still be sitting in the database and in the admin API
     * response, which are read by more people than the message is.
     */
    public static String sanitiseForStorage(String name, String value) {
        if (value == null) {
            return null;
        }
        if (looksSecret(name)) {
            return REDACTED;
        }
        String cleaned = stripControlCharacters(value).replaceAll("\\s{2,}", " ").strip();
        return cleaned.length() <= MAX_VALUE_LENGTH
                ? cleaned
                : cleaned.substring(0, MAX_VALUE_LENGTH - 3) + "...";
    }

    private static boolean looksSecret(String name) {
        if (name == null) {
            return false;
        }
        String normalised = name.toLowerCase(Locale.ROOT).replace("-", "");
        return SECRET_LIKE.stream().anyMatch(normalised::contains);
    }

    /**
     * Flattens newlines and removes control characters.
     *
     * <p>Newlines become spaces rather than being deleted, so words either side do not run together —
     * and a multi-line description cannot forge a new section of the email.
     */
    private static String stripControlCharacters(String value) {
        StringBuilder out = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (codePoint == '\n' || codePoint == '\r' || codePoint == '\t') {
                out.append(' ');
            } else if (!Character.isISOControl(codePoint)) {
                out.appendCodePoint(codePoint);
            }
        });
        return out.toString();
    }

    /**
     * Escapes the five characters that matter in HTML.
     *
     * <p>Hand-written rather than pulled from a web framework: this runs on a value that has already
     * had links removed, the set is closed and well known, and adding a dependency for five
     * replacements would be a larger surface than the problem.
     */
    private static String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /** A message could not be rendered. Recoverable: the caller suppresses and counts. */
    public static class TemplateRenderException extends RuntimeException {

        public TemplateRenderException(String message) {
            super(message);
        }
    }
}
