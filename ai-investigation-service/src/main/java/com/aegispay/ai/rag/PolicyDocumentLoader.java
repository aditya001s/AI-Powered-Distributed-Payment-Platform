package com.aegispay.ai.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the bundled fraud policies off the classpath.
 *
 * <h2>Why markdown files rather than SQL inserts or a JSON blob</h2>
 *
 * <p>Policy text is prose that people edit, and the format should suit the author rather than the
 * loader. A SQL migration would mean escaping several pages of text with embedded apostrophes; JSON
 * would mean {@code \n} on every line break, which makes a diff unreadable and an edit error-prone.
 * Markdown with front matter is what the content actually is, and a reviewer can read the diff.
 *
 * <p>It also keeps the corpus out of Flyway. A policy is <em>content</em>, not schema: editing one
 * should not require a new migration, and a migration that had already run could not be changed to fix
 * a typo in a sentence.
 *
 * <h2>Why the document id comes from the filename</h2>
 *
 * <p>{@code POLICY-BUST-OUT.md} becomes {@code POLICY-BUST-OUT}. That makes the id visible in the
 * repository listing, impossible to duplicate by accident — the filesystem enforces it — and
 * unchanged by an edit to the front matter. Since the id appears in every citation an analyst reads
 * and a dispute quotes, having it be the filename means the file and the citation cannot drift apart.
 */
@Component
public class PolicyDocumentLoader {

    private static final Logger log = LoggerFactory.getLogger(PolicyDocumentLoader.class);

    private static final String LOCATION = "classpath:knowledge-base/*.md";
    private static final String FRONT_MATTER_DELIMITER = "---";
    private static final Set<String> EXPECTED_DOCUMENT_IDS = Set.of(
            "POLICY-ACCOUNT-TAKEOVER",
            "POLICY-AUTHORITY-BOUNDARY",
            "POLICY-BUST-OUT",
            "POLICY-CARD-TESTING",
            "POLICY-CONFIRMED-FRAUD-HISTORY",
            "POLICY-DEGRADED-VERDICT",
            "POLICY-EVIDENCE-STANDARD",
            "POLICY-HOLD-HANDLING",
            "POLICY-IMPOSSIBLE-TRAVEL",
            "POLICY-MULE-CASHOUT",
            "POLICY-NEW-ACCOUNT");

    /**
     * One authored policy, as read from disk.
     *
     * @param body the markdown after the front matter, which is what gets chunked, embedded and shown
     */
    public record LoadedPolicy(
            String documentId,
            String title,
            String category,
            List<String> relatedRuleIds,
            int version,
            String source,
            String body
    ) {
    }

    /**
     * Loads every bundled policy, in a stable order.
     *
     * <p>Sorted by document id rather than left in whatever order the classpath scan produced.
     * Filesystem enumeration order differs between machines and between a jar and an exploded
     * directory, and an unstable order would make the seeder's log — and any test asserting on the
     * corpus — differ between environments for no reason.
     *
     * @throws IllegalStateException if the corpus cannot be read at all. Deliberately fatal: a service
     *                               that started with no policies would answer every investigation
     *                               ungrounded and report no error, which is the worst kind of broken.
     */
    public List<LoadedPolicy> loadAll() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources;
        try {
            resources = resolver.getResources(LOCATION);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Could not scan " + LOCATION + " for fraud policies. Without a corpus every "
                            + "investigation would be ungrounded.", ex);
        }

        List<LoadedPolicy> policies = new ArrayList<>();
        for (Resource resource : resources) {
            String filename = resource.getFilename();
            if (filename == null || !filename.endsWith(".md")) {
                continue;
            }
            try {
                policies.add(parse(documentIdFrom(filename), read(resource)));
            } catch (RuntimeException | IOException ex) {
                // One malformed file must not silently reduce the corpus. Rethrown as fatal, because a
                // policy that fails to load is a policy that cannot be cited, and the absence would
                // show up only as an investigation that never mentions it.
                throw new IllegalStateException(
                        "Could not load fraud policy " + filename + ": " + ex.getMessage(), ex);
            }
        }

        if (policies.isEmpty()) {
            throw new IllegalStateException(
                    "No fraud policies were found at " + LOCATION + ". The knowledge base would be "
                            + "empty and every investigation would report no grounding.");
        }

        Set<String> loadedIds = policies.stream()
                .map(LoadedPolicy::documentId)
                .collect(java.util.stream.Collectors.toSet());
        if (!loadedIds.equals(EXPECTED_DOCUMENT_IDS)) {
            Set<String> missing = new java.util.TreeSet<>(EXPECTED_DOCUMENT_IDS);
            missing.removeAll(loadedIds);
            Set<String> unexpected = new java.util.TreeSet<>(loadedIds);
            unexpected.removeAll(EXPECTED_DOCUMENT_IDS);
            throw new IllegalStateException("Bundled fraud-policy corpus is incomplete or unexpected. "
                    + "Missing=" + missing + ", unexpected=" + unexpected
                    + ". A partial corpus must not report ready.");
        }

        policies.sort(Comparator.comparing(LoadedPolicy::documentId));
        log.info("Loaded {} fraud policy document(s) from the classpath", policies.size());
        return List.copyOf(policies);
    }

    // --------------------------------------------------------------- internals

    private static String documentIdFrom(String filename) {
        return filename.substring(0, filename.length() - ".md".length());
    }

    private static String read(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Splits front matter from body and validates both.
     *
     * <p>The front-matter parser is deliberately minimal: {@code key: value} pairs between two
     * {@code ---} lines, no nesting, no lists, no quoting rules. A full YAML parser would handle more
     * and would also silently accept a nested structure this loader ignores, so an author could add a
     * field and see no effect. Rejecting anything it does not understand is more useful than tolerating
     * it.
     *
     * <p>Package-private rather than private so the front-matter rules can be exercised directly. The
     * interesting cases are all malformed documents, and reaching them through {@link #loadAll()} would
     * mean planting broken files on the classpath — which would test the resource resolver rather than
     * these rules, and would make the shipped corpus contain deliberately invalid policies.
     */
    static LoadedPolicy parse(String documentId, String content) {
        String normalised = content.replace("\r\n", "\n").stripLeading();

        if (!normalised.startsWith(FRONT_MATTER_DELIMITER)) {
            throw new IllegalArgumentException(
                    "missing front matter: the file must begin with a '---' line followed by "
                            + "title, category, version and source");
        }
        int endOfOpening = normalised.indexOf('\n');
        int closing = normalised.indexOf("\n" + FRONT_MATTER_DELIMITER, endOfOpening);
        if (endOfOpening < 0 || closing < 0) {
            throw new IllegalArgumentException("front matter is not closed by a '---' line");
        }

        String frontMatter = normalised.substring(endOfOpening + 1, closing);
        int bodyStart = normalised.indexOf('\n', closing + 1);
        String body = bodyStart < 0 ? "" : normalised.substring(bodyStart + 1).trim();

        Map<String, String> fields = parseFrontMatter(frontMatter);

        String title = required(fields, "title", documentId);
        String category = fields.getOrDefault("category", "GENERAL");
        String source = fields.getOrDefault("source", "AegisPay fraud policy");
        int version = parseVersion(fields.get("version"), documentId);
        List<String> ruleIds = parseRuleIds(fields.get("relatedRuleIds"));

        if (body.isBlank()) {
            // A document with front matter and no body would be retrievable and cite-able while saying
            // nothing, which is worse than its absence: it looks like evidence.
            throw new IllegalArgumentException(
                    "the body is empty, so this policy could be cited but not read");
        }

        return new LoadedPolicy(documentId, title, category, ruleIds, version, source, body);
    }

    private static Map<String, String> parseFrontMatter(String frontMatter) {
        Map<String, String> fields = new HashMap<>();
        for (String line : frontMatter.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException(
                        "front-matter line is not 'key: value': " + trimmed);
            }
            fields.put(trimmed.substring(0, colon).trim(), trimmed.substring(colon + 1).trim());
        }
        return fields;
    }

    private static String required(Map<String, String> fields, String key, String documentId) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "front matter is missing '" + key + "', which a citation of " + documentId
                            + " would display");
        }
        return value;
    }

    private static int parseVersion(String raw, String documentId) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                    "front matter is missing 'version'. A citation records the version it read, so "
                            + "without one an edit to " + documentId + " would make older "
                            + "investigations appear to have cited today's text.");
        }
        try {
            int version = Integer.parseInt(raw.trim());
            if (version < 1) {
                throw new IllegalArgumentException("version must be at least 1, got " + version);
            }
            return version;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("version must be an integer, got '" + raw + "'", ex);
        }
    }

    /** Comma-separated, and legitimately empty: a procedural policy explains no specific rule. */
    private static List<String> parseRuleIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
    }
}
