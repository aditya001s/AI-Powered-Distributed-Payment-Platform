package com.aegispay.notification.template;

import com.aegispay.common.domain.SharedEnums.NotificationChannel;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The copy for one notification, in every channel that has any.
 *
 * <h2>Why one template per channel and not one rendered once</h2>
 *
 * <p>An SMS is not a shortened email. It has no subject, no greeting and no sign-off, it costs money
 * per 160 characters, and it is read on a lock screen — so it says the one thing that matters and
 * stops. Truncating an email to fit would cut the sentence that carried the amount.
 *
 * <p>They also differ in what they may safely contain. The email version of
 * {@code payment.received} includes the sender's note and a warning about treating instructions in it
 * with suspicion; the SMS version omits the note entirely, because there is no room for the warning
 * and a bare attacker-written sentence arriving as a bank text is a phishing message the platform
 * paid to send.
 */
public record NotificationTemplate(

        String templateKey,

        /** Email subject. Absent for a template with no email body. */
        String subject,

        /** Body per channel. A channel with no entry has no copy and must not be invented. */
        Map<NotificationChannel, String> bodies
) {

    /**
     * A placeholder. {@code {{name}}} is required; {@code {{name?}}} renders empty when absent.
     *
     * <p>Two forms rather than one because the distinction is real and the failure modes are opposite.
     * A missing <em>required</em> value means the producer and the copy disagree, and sending
     * "You sent {{amount}} INR" to a customer is worse than sending nothing — the notification is
     * immutable, so the embarrassing version is the permanent record. A missing <em>optional</em>
     * value is ordinary: a payment description is often empty.
     */
    public static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z][a-zA-Z0-9_]*)(\\?)?}}");

    public NotificationTemplate {
        if (templateKey == null || templateKey.isBlank()) {
            throw new IllegalArgumentException("a template must have a key");
        }
        if (bodies == null || bodies.isEmpty()) {
            // A template with no body could be selected, counted and reported as delivered while
            // saying nothing. Worse than a missing template, which at least suppresses loudly.
            throw new IllegalArgumentException(
                    "template " + templateKey + " has no body for any channel");
        }
        bodies = Map.copyOf(bodies);
    }

    public boolean supports(NotificationChannel channel) {
        return bodies.containsKey(channel);
    }

    public Optional<String> bodyFor(NotificationChannel channel) {
        return Optional.ofNullable(bodies.get(channel));
    }

    /**
     * Every variable this template requires, across all channels.
     *
     * <p>Used by the startup check and by the test that asserts the platform actually supplies them.
     * Optional placeholders are excluded: they are allowed to be missing by definition.
     */
    public Set<String> requiredVariables() {
        return namesAcrossEveryRenderedString(false);
    }

    /** Every variable named anywhere in this template, required or optional. */
    public Set<String> allVariables() {
        return namesAcrossEveryRenderedString(true);
    }

    /**
     * Collects placeholder names from the bodies <em>and the subject</em>.
     *
     * <p>The subject is easy to forget and is exactly where an unresolved placeholder does the most
     * damage: a body defect is buried in a paragraph, while a subject line reading
     * "You sent {{amount}} INR" is the first and sometimes only thing the customer reads.
     */
    private Set<String> namesAcrossEveryRenderedString(boolean includeOptional) {
        Set<String> names = new java.util.LinkedHashSet<>();
        if (subject != null && !subject.isBlank()) {
            names.addAll(placeholdersIn(subject, includeOptional));
        }
        bodies.values().forEach(body -> names.addAll(placeholdersIn(body, includeOptional)));
        return Set.copyOf(names);
    }

    static Set<String> placeholdersIn(String body, boolean includeOptional) {
        Set<String> names = new java.util.LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(body);
        while (matcher.find()) {
            boolean optional = matcher.group(2) != null;
            if (includeOptional || !optional) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }
}
