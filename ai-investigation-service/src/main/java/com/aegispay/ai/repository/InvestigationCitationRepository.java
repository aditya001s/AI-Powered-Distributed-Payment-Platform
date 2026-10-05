package com.aegispay.ai.repository;

import com.aegispay.ai.domain.InvestigationCitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * Reads and appends citations.
 *
 * <p>Deliberately exposes no update or delete beyond what {@link JpaRepository} inherits, and the table
 * refuses both by trigger regardless. A deletable citation would let a hallucination be tidied away
 * after the fact, which is precisely the record that has to survive.
 */
public interface InvestigationCitationRepository extends JpaRepository<InvestigationCitation, Long> {

    /** Every citation on one report, strongest match first. */
    List<InvestigationCitation> findByInvestigationIdOrderByRankAsc(String investigationId);

    /**
     * Which policies are actually being cited.
     *
     * <p>The coverage question. A document that is never cited is either redundant or unreachable by
     * retrieval, and the two need opposite responses — rewrite it, or delete it. Without this, a corpus
     * accumulates documents nobody has ever read.
     */
    @Query("""
            SELECT c.documentId, COUNT(c) FROM InvestigationCitation c
            WHERE c.hallucinated = false AND c.createdAt >= :since
            GROUP BY c.documentId
            ORDER BY COUNT(c) DESC
            """)
    List<Object[]> countCitationsByDocumentSince(@Param("since") Instant since);

    /**
     * Citations to documents that were never retrieved.
     *
     * <p>Expected to be empty. Listed rather than merely counted because the specific invented id
     * matters: a generator that reliably invents {@code POLICY-AML} is telling you the corpus is
     * missing a document somebody expects to exist.
     */
    @Query("""
            SELECT c FROM InvestigationCitation c
            WHERE c.hallucinated = true AND c.createdAt >= :since
            ORDER BY c.createdAt DESC
            """)
    List<InvestigationCitation> findHallucinatedSince(@Param("since") Instant since);

    long countByHallucinatedAndCreatedAtAfter(boolean hallucinated, Instant since);
}
