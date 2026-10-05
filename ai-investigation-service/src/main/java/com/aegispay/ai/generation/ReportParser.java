package com.aegispay.ai.generation;

import com.aegispay.ai.domain.Recommendation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a language model's reply into a report.
 *
 * <h2>Why this is section-based rather than JSON</h2>
 *
 * <p>Asking for JSON would make parsing trivial and generation worse. A model producing a several-hundred
 * word narrative inside a JSON string field has to escape quotes and newlines correctly for the whole
 * length, and a single unescaped quote invalidates the entire response — so a small formatting slip costs
 * the whole report rather than one field. Labelled sections degrade gracefully: a malformed
 * {@code CONFIDENCE:} line loses the confidence, not the narrative.
 *
 * <p>Structured-output modes that guarantee valid JSON exist, and they are provider-specific. Depending
 * on one would tie this service to a single vendor's API shape, which is what the generator seam exists
 * to avoid.
 *
 * <h2>What this refuses to do</h2>
 *
 * <p>It never guesses a recommendation. If the {@code RECOMMENDATION:} line is missing, unparseable, or
 * names something outside the closed vocabulary, the caller is told and the verdict escalates. Fuzzy
 * matching to the "closest" value would be the single most dangerous convenience available here: it is
 * exactly how an injected instruction, or a confused model, turns into a decision about a customer's
 * money.
 */
public final class ReportParser {

    private static final Logger log = LoggerFactory.getLogger(ReportParser.class);

    /**
     * Section headers, matched at the start of a line.
     *
     * <p>Case-insensitive and tolerant of a missing colon, because a model asked for {@code NARRATIVE:}
     * will occasionally produce {@code **Narrative**} or {@code ## NARRATIVE}. Being strict about that
     * would discard perfectly good reports over formatting.
     */
    private static final Pattern SECTION = Pattern.compile(
            "(?im)^\\s*[*#\\s]*(NARRATIVE|FINDINGS|RECOMMENDATION|RATIONALE|CITATIONS|CONFIDENCE)"
                    + "[*\\s]*:?[*\\s]*$|^\\s*[*#\\s]*(RECOMMENDATION|CITATIONS|CONFIDENCE)"
                    // The markdown-stripping after the colon is what handles `**RECOMMENDATION:** VALUE`,
                    // which is one of the two shapes models most often produce. Without it the captured
                    // value starts with `** ` and no longer matches the vocabulary.
                    + "[*\\s]*:[*\\s]*(.+)$");

    private ReportParser() {
    }

    /** What the parse produced, plus whether the recommendation could be trusted. */
    public record Parsed(
            String narrative,
            List<String> findings,
            /** Empty when the model gave no usable recommendation. The caller must then escalate. */
            Optional<Recommendation> recommendation,
            String rationale,
            List<String> citedDocumentIds,
            double confidence,
            /** The raw recommendation text, retained for the log when it could not be parsed. */
            String rawRecommendation
    ) {
    }

    /**
     * Parses a reply.
     *
     * @throws ReportGenerator.GenerationException when there is no narrative at all. Everything else
     *                                             degrades: a report with prose and no rationale is still
     *                                             worth showing an analyst, but a report with no prose is
     *                                             an empty page carrying a recommendation.
     */
    public static Parsed parse(String reply) {
        if (reply == null || reply.isBlank()) {
            throw new ReportGenerator.GenerationException("the model returned an empty reply");
        }

        Sections sections = splitSections(reply);

        String narrative = sections.get("NARRATIVE");
        if (narrative == null || narrative.isBlank()) {
            // Some models ignore the format entirely and just write the report. Falling back to the whole
            // reply is better than discarding a usable narrative over a missing header — but only for the
            // narrative, because the other sections carry values that must not be guessed at.
            log.debug("No NARRATIVE section found; treating the whole reply as the narrative");
            narrative = reply.trim();
        }

        String rawRecommendation = sections.get("RECOMMENDATION");
        Optional<Recommendation> recommendation = Recommendation.parse(rawRecommendation);

        return new Parsed(
                narrative.trim(),
                parseBullets(sections.get("FINDINGS")),
                recommendation,
                orDefault(sections.get("RATIONALE"),
                        "The model did not separate a rationale from its narrative."),
                parseCitations(sections.get("CITATIONS")),
                parseConfidence(sections.get("CONFIDENCE")),
                rawRecommendation);
    }

    // --------------------------------------------------------------- internals

    private record Sections(java.util.Map<String, String> values) {

        String get(String name) {
            String value = values.get(name);
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    /**
     * Splits the reply at section headers.
     *
     * <p>Handles both shapes a model produces: a header alone on its own line with the content following,
     * and a header with its value on the same line ({@code RECOMMENDATION: MONITOR_ACCOUNT}). The second
     * is far more common for the short single-value sections, and a parser that only understood the first
     * would lose the recommendation most of the time.
     */
    private static Sections splitSections(String reply) {
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        Matcher matcher = SECTION.matcher(reply);

        String currentSection = null;
        int contentStart = 0;

        while (matcher.find()) {
            if (currentSection != null) {
                append(values, currentSection, reply.substring(contentStart, matcher.start()));
            }
            String inlineName = matcher.group(2);
            if (inlineName != null) {
                // Header and value on one line. Recorded immediately, and the section does not stay open:
                // whatever follows belongs to the next header, not to this one.
                append(values, inlineName.toUpperCase(java.util.Locale.ROOT), matcher.group(3));
                currentSection = null;
            } else {
                currentSection = matcher.group(1).toUpperCase(java.util.Locale.ROOT);
            }
            contentStart = matcher.end();
        }
        if (currentSection != null) {
            append(values, currentSection, reply.substring(contentStart));
        }
        return new Sections(values);
    }

    /**
     * Records a section value, keeping the first occurrence.
     *
     * <p>First rather than last, deliberately. A model that emits {@code RECOMMENDATION:} twice — once in
     * its own reasoning and once in the required output — is more likely to have meant the earlier one, and
     * more importantly, a duplicate section is exactly what a successful injection produces when it
     * persuades the model to append a second answer. Taking the last would hand it the win.
     */
    private static void append(java.util.Map<String, String> values, String section, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        values.putIfAbsent(section, content.trim());
    }

    private static List<String> parseBullets(String section) {
        if (section == null) {
            return List.of();
        }
        List<String> bullets = new ArrayList<>();
        for (String line : section.split("\\r?\\n")) {
            String trimmed = line.trim().replaceFirst("^[-*\u2022]\\s*", "").trim();
            if (!trimmed.isEmpty()) {
                bullets.add(trimmed);
            }
        }
        return List.copyOf(bullets);
    }

    /**
     * Parses the claimed citations.
     *
     * <p>Only shape is normalised here — splitting, trimming, dropping the "NONE" sentinel. Whether these
     * ids were actually retrieved is decided by {@code CitationValidator}, because that is a question about
     * the retrieval set rather than about the text, and answering it here would mean this parser had to
     * know what was retrieved.
     */
    private static List<String> parseCitations(String section) {
        if (section == null) {
            return List.of();
        }
        String cleaned = section.replaceAll("(?i)\\bNONE\\b", "").trim();
        if (cleaned.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(cleaned.split("[,;\\n]"))
                .map(String::trim)
                // A list marker, then bracket and markdown decoration. The prompt asks for a
                // comma-separated list, but the output format is not under this platform's control and
                // models routinely render one section in the style of its neighbour — FINDINGS directly
                // above is a bullet list, so CITATIONS arrives as one too.
                //
                // Getting this wrong is expensive and quiet: "- POLICY-BUST-OUT" does not match
                // "POLICY-BUST-OUT", so CitationValidator would record every correctly-cited document as
                // a fabrication. The hallucination alert would fire continuously, the completed event
                // would carry no verified sources at all, and the narrative would read perfectly.
                .map(ReportParser::stripDecoration)
                .filter(id -> !id.isEmpty())
                .distinct()
                .toList();
    }

    /** Removes list markers and markdown or bracket decoration from around a claimed document id. */
    private static String stripDecoration(String value) {
        return value.replaceFirst("^[-*\u2022\u2013\u2014]+\\s*", "")
                .replaceAll("^[\\[`*(\"']+|[]`*).,\"']+$", "")
                .trim();
    }

    /**
     * Parses the confidence, defaulting low.
     *
     * <p>0.5 when absent or unparseable, not 0.0 and not 1.0. Zero would make a perfectly good report look
     * worthless and could sort it out of a queue; one would assert a certainty the model never claimed.
     * A model that returns a percentage ({@code 85%}) is understood, because that is a common shape.
     */
    private static double parseConfidence(String section) {
        if (section == null) {
            return 0.5;
        }
        Matcher number = Pattern.compile("([0-9]*\\.?[0-9]+)\\s*(%)?").matcher(section);
        if (!number.find()) {
            return 0.5;
        }
        try {
            double value = Double.parseDouble(number.group(1));
            if (number.group(2) != null || value > 1.0) {
                value = value / 100.0;
            }
            return Math.clamp(value, 0.0, 1.0);
        } catch (NumberFormatException ex) {
            return 0.5;
        }
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
