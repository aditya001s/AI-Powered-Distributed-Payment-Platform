package com.aegispay.ai.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A link from a report to the policy passage that supported it. Append-only.
 *
 * <p><strong>Why citations are rows and not a JSON field on the report.</strong> Two questions have to
 * be answerable by query. An analyst reading a claim needs to open the exact passage behind it, and a
 * reviewer needs to ask "did this generator ever cite something that was not retrieved" across every
 * investigation. Both are queries, so both need rows.
 *
 * <p><strong>A hallucinated citation is recorded, not discarded.</strong> When a generator names a
 * policy that retrieval never returned, the obvious move is to drop it — the citation is worthless as
 * evidence, after all. That would also destroy the only trace of the generator having invented
 * evidence, which is the most important thing this table can tell anyone. So it is stored with
 * {@link #hallucinated} set, zero similarity, and no claim to authority. The table deliberately has
 * <em>no</em> foreign key to {@code policy_documents} for exactly this reason: an FK would make it
 * impossible to record a citation to a document that does not exist.
 */
@Entity
@Table(name = "investigation_citations")
public class InvestigationCitation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "investigation_id", nullable = false, updatable = false, length = 48)
    private String investigationId;

    @Column(name = "document_id", nullable = false, updatable = false, length = 64)
    private String documentId;

    @Column(name = "chunk_id", updatable = false, length = 80)
    private String chunkId;

    /**
     * The document version that was actually read.
     *
     * <p>Without it, a policy edited after an investigation was written would make the investigation
     * appear to have cited today's text. That is the quiet way an audit trail stops being one: nothing
     * is falsified, the reference simply resolves to something else.
     */
    @Column(name = "document_version", nullable = false, updatable = false)
    private int documentVersion;

    /**
     * Cosine similarity at retrieval time.
     *
     * <p>Retained so a weak citation is visibly weak. A passage that scraped past the floor at 0.31
     * and one that matched at 0.88 look identical in a narrative, and an analyst deciding how much
     * weight to give a claim needs to be able to tell them apart.
     */
    @Column(name = "similarity", nullable = false, updatable = false)
    private double similarity;

    @Column(name = "rank", nullable = false, updatable = false)
    private int rank;

    @Column(name = "hallucinated", nullable = false, updatable = false)
    private boolean hallucinated;

    /** True only when the report explicitly named this document as support. */
    @Column(name = "cited_in_report", nullable = false, updatable = false)
    private boolean citedInReport;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected InvestigationCitation() {
        // JPA
    }

    /** A citation the generator made to a passage that was genuinely retrieved. */
    public static InvestigationCitation grounded(String investigationId, String documentId,
                                                 String chunkId, int documentVersion,
                                                 double similarity, int rank,
                                                 boolean citedInReport) {
        if (investigationId == null || investigationId.isBlank()) {
            throw new IllegalArgumentException("a citation must belong to an investigation");
        }
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("a citation must name a document");
        }
        if (!Double.isFinite(similarity) || similarity < -1.0 || similarity > 1.0) {
            throw new IllegalArgumentException(
                    "cosine similarity must be in [-1,1], got " + similarity);
        }
        if (rank < 1) {
            throw new IllegalArgumentException("retrieval rank starts at 1, got " + rank);
        }

        InvestigationCitation citation = new InvestigationCitation();
        citation.investigationId = investigationId;
        citation.documentId = documentId;
        citation.chunkId = chunkId;
        citation.documentVersion = documentVersion;
        citation.similarity = similarity;
        citation.rank = rank;
        citation.hallucinated = false;
        citation.citedInReport = citedInReport;
        citation.createdAt = Instant.now();
        return citation;
    }

    /**
     * A citation to something retrieval never returned.
     *
     * <p>Similarity is zero because there is nothing to measure: no vector was compared, and giving it
     * any other number would let an invented citation look ranked. Version is zero for the same
     * reason — no document was read.
     *
     * @param rank where in the generator's own citation list it appeared, purely so the order of what
     *             it claimed is preserved
     */
    public static InvestigationCitation hallucinated(String investigationId, String documentId,
                                                     int rank) {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("even an invented citation names something");
        }
        InvestigationCitation citation = new InvestigationCitation();
        citation.investigationId = investigationId;
        citation.documentId = documentId;
        citation.chunkId = null;
        citation.documentVersion = 0;
        citation.similarity = 0.0;
        citation.rank = Math.max(1, rank);
        citation.hallucinated = true;
        citation.citedInReport = true;
        citation.createdAt = Instant.now();
        return citation;
    }

    /** Whether this citation can be opened and read by an analyst. */
    public boolean isVerifiable() {
        return !hallucinated;
    }

    public Long getId() {
        return id;
    }

    public String getInvestigationId() {
        return investigationId;
    }

    public String getDocumentId() {
        return documentId;
    }

    public String getChunkId() {
        return chunkId;
    }

    public int getDocumentVersion() {
        return documentVersion;
    }

    public double getSimilarity() {
        return similarity;
    }

    public int getRank() {
        return rank;
    }

    public boolean isHallucinated() {
        return hallucinated;
    }

    public boolean isCitedInReport() {
        return citedInReport;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "InvestigationCitation{" + documentId
                + (hallucinated ? " HALLUCINATED" : " @" + String.format("%.3f", similarity)) + '}';
    }
}
