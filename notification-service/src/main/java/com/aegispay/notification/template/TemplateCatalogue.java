package com.aegispay.notification.template;

import com.aegispay.common.domain.SharedEnums.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The copy this service can render, loaded from the classpath at startup.
 *
 * <h2>Why plain text files rather than rows in a table</h2>
 *
 * <p>Copy is prose that people edit and reviewers read. A template in a database row cannot be
 * reviewed in a diff, cannot be rolled back with the deployment that changed it, and makes "what did
 * this message say last March" a question about backups. A template in a migration is worse: fixing a
 * typo would need a new migration, and the one that already ran could not be corrected.
 *
 * <h2>Why a missing template suppresses rather than improvises</h2>
 *
 * <p>An unknown template key means a producer is emitting a notification nobody wrote copy for. The
 * tempting fallback is a generic "there was an update to your payment" so the customer gets
 * <em>something</em>. That is rejected: the message would be about somebody's money, and a vague
 * message about money either alarms a customer for no reason or reassures them when it should not.
 * Nothing is sent, the delivery is recorded as suppressed with a reason, and it is counted loudly so
 * an operator finds out. A late notification can be replayed once the copy exists; a wrong one cannot
 * be recalled.
 *
 * <h2>Why loading is fatal and rendering is not</h2>
 *
 * <p>A malformed file means the shipped artefact is broken, and every notification using it would be
 * suppressed silently for as long as nobody looked — so startup fails. A missing key at render time is
 * a producer that shipped ahead of its copy, which is a different fault with a different owner, and
 * refusing to start would take down notifications for every other template as well.
 */
@Component
public class TemplateCatalogue {

    private static final Logger log = LoggerFactory.getLogger(TemplateCatalogue.class);

    private static final String LOCATION = "classpath:notification-templates/*.template";
    private static final String SUFFIX = ".template";

    private static final String SECTION_SUBJECT = "SUBJECT";

    private final Map<String, NotificationTemplate> templates;

    public TemplateCatalogue() {
        this(LOCATION);
    }

    TemplateCatalogue(String location) {
        this.templates = load(location);
        log.info("Loaded {} notification template(s): {}",
                templates.size(), String.join(", ", templates.keySet()));
    }

    /**
     * The copy for a key, if any exists.
     *
     * <p>An {@code Optional} rather than a throw, because "no copy for this key" is a routine
     * condition the caller has to handle by suppressing and counting — not an exception to propagate
     * into a Kafka error handler, which would retry the record four times and then dead-letter it for
     * a fault that no retry can fix.
     */
    public Optional<NotificationTemplate> find(String templateKey) {
        return Optional.ofNullable(templates.get(templateKey));
    }

    public boolean supports(String templateKey, NotificationChannel channel) {
        return find(templateKey).map(template -> template.supports(channel)).orElse(false);
    }

    /** Every key that has copy, sorted, for the admin view and for tests. */
    public List<String> keys() {
        return templates.keySet().stream().sorted().toList();
    }

    public int size() {
        return templates.size();
    }

    // --------------------------------------------------------------- loading

    private static Map<String, NotificationTemplate> load(String location) {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(location);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Could not scan " + location + " for notification templates. Without copy, "
                            + "every notification would be suppressed.", ex);
        }

        List<Resource> ordered = new ArrayList<>(List.of(resources));
        // Sorted so the startup log is identical between machines and between a jar and an exploded
        // directory. Filesystem enumeration order is not stable, and an unstable log is one nobody
        // can diff.
        ordered.sort(Comparator.comparing(resource ->
                resource.getFilename() == null ? "" : resource.getFilename()));

        Map<String, NotificationTemplate> loaded = new LinkedHashMap<>();
        for (Resource resource : ordered) {
            String filename = resource.getFilename();
            if (filename == null || !filename.endsWith(SUFFIX)) {
                continue;
            }
            String key = filename.substring(0, filename.length() - SUFFIX.length());
            try {
                loaded.put(key, parse(key, read(resource)));
            } catch (RuntimeException | IOException ex) {
                throw new IllegalStateException(
                        "Could not load notification template " + filename + ": " + ex.getMessage(),
                        ex);
            }
        }

        if (loaded.isEmpty()) {
            throw new IllegalStateException(
                    "No notification templates were found at " + location + ". Every notification "
                            + "would be suppressed for want of copy, and the service would report "
                            + "itself healthy while doing it.");
        }
        return Map.copyOf(loaded);
    }

    private static String read(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Splits a file into its {@code --- SECTION ---} parts.
     *
     * <p>The parser is deliberately strict about section names. Silently ignoring a section it does not
     * recognise would let somebody add {@code --- PUSH ---} copy, see no error, and believe push
     * notifications had been enabled.
     */
    static NotificationTemplate parse(String templateKey, String content) {
        String normalised = content.replace("\r\n", "\n");

        Map<String, String> sections = new LinkedHashMap<>();
        String currentSection = null;
        StringBuilder current = new StringBuilder();

        for (String line : normalised.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("---") && trimmed.endsWith("---") && trimmed.length() > 6) {
                if (currentSection != null) {
                    sections.put(currentSection, current.toString().trim());
                }
                currentSection = trimmed.substring(3, trimmed.length() - 3).trim()
                        .toUpperCase(java.util.Locale.ROOT);
                current = new StringBuilder();
                continue;
            }
            if (currentSection != null) {
                current.append(line).append('\n');
            } else if (!trimmed.isEmpty()) {
                throw new IllegalArgumentException(
                        "content before the first '--- SECTION ---' line: " + trimmed);
            }
        }
        if (currentSection != null) {
            sections.put(currentSection, current.toString().trim());
        }

        if (sections.isEmpty()) {
            throw new IllegalArgumentException(
                    "no sections found; expected at least one of '--- EMAIL ---', "
                            + "'--- SMS ---' or '--- IN_APP ---'");
        }

        String subject = sections.remove(SECTION_SUBJECT);
        Map<NotificationChannel, String> bodies = new EnumMap<>(NotificationChannel.class);

        for (Map.Entry<String, String> section : sections.entrySet()) {
            NotificationChannel channel = channelFor(templateKey, section.getKey());
            if (section.getValue().isBlank()) {
                throw new IllegalArgumentException(
                        "section '" + section.getKey() + "' is empty, so this channel would report "
                                + "a delivery that said nothing");
            }
            bodies.put(channel, section.getValue());
        }

        if (bodies.containsKey(NotificationChannel.EMAIL) && (subject == null || subject.isBlank())) {
            // An email with no subject is filed by every client as "(no subject)", which reads as
            // spam. Required rather than defaulted, because a generated subject would be worse copy
            // than a human would ever have written.
            throw new IllegalArgumentException(
                    "an email body needs a '--- SUBJECT ---' section");
        }
        return new NotificationTemplate(templateKey, subject, bodies);
    }

    private static NotificationChannel channelFor(String templateKey, String sectionName) {
        return switch (sectionName) {
            case "EMAIL" -> NotificationChannel.EMAIL;
            case "SMS" -> NotificationChannel.SMS;
            case "IN_APP", "IN-APP", "INAPP" -> NotificationChannel.IN_APP;
            case "PUSH" -> throw new IllegalArgumentException(
                    "template " + templateKey + " declares PUSH copy, but push notifications are "
                            + "not available on this platform: there is no mobile application and no "
                            + "device-token registry. Remove the section rather than shipping copy "
                            + "that can never be delivered.");
            default -> throw new IllegalArgumentException(
                    "unknown section '" + sectionName + "' in template " + templateKey
                            + "; expected SUBJECT, EMAIL, SMS or IN_APP");
        };
    }
}
