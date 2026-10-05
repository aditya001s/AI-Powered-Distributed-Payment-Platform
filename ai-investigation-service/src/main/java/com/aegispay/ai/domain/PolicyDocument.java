package com.aegispay.ai.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * One fraud policy an investigation is allowed to reason from.
 *
 * <p><strong>The corpus is closed.</strong> An investigation may cite nothing outside this table, and
 * a citation that names a document not present here is recorded as a hallucination rather than
 * accepted. That is the difference between a narrative grounded in written policy and a language
 * model's recollection of fraud in general.
 *
 * <p><strong>{@code documentId} is chosen, not generated.</strong> Every other public identifier on
 * the platform is opaque and random, deliberately, so it cannot be enumerated. This one is the
 * exception: it appears in a citation an analyst reads and a dispute quotes, so
 * {@code POLICY-BUST-OUT} is worth far more than {@code DOC-7f3c2a}. It is also stable across a
 * reseed, which means an investigation written last year still points at the same policy today.
 *
 * <p><strong>{@code relatedRuleIds} is the bridge between two vocabularies.</strong> fraud-service
 * speaks in rule ids; a policy is written for people. Recording the association explicitly means an
 * investigation for {@code RULE_BUST_OUT_PATTERN} finds the document explaining bust-out directly,
 * rather than depending on an embedder having learned that the two are related — which for a
 * machine-readable token like a rule id is a lot to hope for.
 */
@Entity
@Table(name = "policy_documents")
public class PolicyDocument {

    @Id
    @Column(name = "document_id", nullable = false, updatable = false, length = 64)
    private String documentId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "category", nullable = false, length = 48)
    private String category;

    @Column(name = "related_rule_ids", length = 512)
    private String relatedRuleIds;

    /**
     * The authored text, kept whole.
     *
     * <p>Retrieval works on chunks, but the full body is retained so a citation can be expanded to
     * its surrounding context, and so re-chunking with a different window size needs no
     * re-authoring.
     */
    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    /**
     * Bumped by whoever edits the policy.
     *
     * <p>A citation records the version it read. Without that, a policy edited after an investigation
     * was written would make the investigation appear to have cited text it never saw — which is the
     * quiet way an audit trail stops being one.
     */
    @Column(name = "version", nullable = false)
    private int version = 1;

    @Column(name = "source", nullable = false, length = 200)
    private String source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected PolicyDocument() {
        // JPA
    }

    public static PolicyDocument of(String documentId, String title, String category,
                                    List<String> relatedRuleIds, String body, int version,
                                    String source) {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("a policy document must have a stable id");
        }
        if (body == null || body.isBlank()) {
            // A document with no body would be retrievable and cite-able while saying nothing,
            // which is worse than its absence: it looks like evidence.
            throw new IllegalArgumentException(
                    "policy document " + documentId + " has no body, so it could be cited but not read");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException(
                    "policy document " + documentId + " needs a title: a citation shows the title");
        }
        if (version < 1) {
            throw new IllegalArgumentException(
                    "policy document " + documentId + " must start at version 1");
        }

        PolicyDocument document = new PolicyDocument();
        document.documentId = documentId;
        document.title = title;
        document.category = category == null ? "GENERAL" : category;
        document.relatedRuleIds = relatedRuleIds == null || relatedRuleIds.isEmpty()
                ? null
                : String.join(",", relatedRuleIds);
        document.body = body;
        document.version = version;
        document.source = source == null ? "AegisPay fraud policy" : source;
        document.createdAt = Instant.now();
        document.updatedAt = document.createdAt;
        return document;
    }

    /** Replaces the authored content, for a reseed of an edited policy. */
    public void revise(String title, String category, List<String> relatedRuleIds, String body,
                       int version, String source) {
        this.title = title;
        this.category = category == null ? "GENERAL" : category;
        this.relatedRuleIds = relatedRuleIds == null || relatedRuleIds.isEmpty()
                ? null
                : String.join(",", relatedRuleIds);
        this.body = body;
        this.version = version;
        this.source = source;
        this.updatedAt = Instant.now();
    }

    public List<String> relatedRuleIdList() {
        if (relatedRuleIds == null || relatedRuleIds.isBlank()) {
            return List.of();
        }
        return Arrays.stream(relatedRuleIds.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
    }

    /** Whether this policy explains the given fraud rule. */
    public boolean explainsRule(String ruleId) {
        return ruleId != null && relatedRuleIdList().contains(ruleId);
    }

    public String getDocumentId() {
        return documentId;
    }

    public String getTitle() {
        return title;
    }

    public String getCategory() {
        return category;
    }

    public String getRelatedRuleIds() {
        return relatedRuleIds;
    }

    public String getBody() {
        return body;
    }

    public int getVersion() {
        return version;
    }

    public String getSource() {
        return source;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "PolicyDocument{" + documentId + " v" + version + ", " + title + '}';
    }
}
