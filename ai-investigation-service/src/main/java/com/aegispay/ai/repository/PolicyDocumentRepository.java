package com.aegispay.ai.repository;

import com.aegispay.ai.domain.PolicyDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PolicyDocumentRepository extends JpaRepository<PolicyDocument, String> {

    Optional<PolicyDocument> findByDocumentId(String documentId);

    List<PolicyDocument> findAllByOrderByDocumentIdAsc();

    List<PolicyDocument> findByCategoryOrderByDocumentIdAsc(String category);

    /**
     * Policies that explain a given fraud rule.
     *
     * <p><strong>Why this exists alongside vector search.</strong> A rule id is a machine token —
     * {@code RULE_BUST_OUT_PATTERN} — and expecting an embedder to have learned that it means the same
     * thing as a paragraph about accounts emptying themselves is optimistic even for a hosted
     * embedding model, and hopeless for a lexical one. The association is written down in
     * {@code related_rule_ids} instead, so retrieval can find the right policy by fact rather than by
     * resemblance.
     *
     * <p>The two are combined rather than either being sufficient: this query finds the policy that
     * <em>defines</em> the rule that fired, and vector search finds policy that is merely
     * <em>relevant</em> to the payment. An investigation wants both.
     *
     * <p>{@code LIKE} with comma delimiters on both sides so {@code RULE_CARD_TESTING} cannot match
     * {@code RULE_CARD_TESTING_VELOCITY} by prefix. A bare {@code LIKE '%' || :ruleId || '%'} would,
     * and the citation would point at the wrong policy — which is worse than no citation, because it
     * looks correct.
     */
    @Query(value = """
            SELECT * FROM policy_documents
            WHERE related_rule_ids IS NOT NULL
              AND ',' || replace(related_rule_ids, ' ', '') || ',' LIKE '%,' || :ruleId || ',%'
            ORDER BY document_id
            """, nativeQuery = true)
    List<PolicyDocument> findByRelatedRule(@Param("ruleId") String ruleId);

    boolean existsByDocumentId(String documentId);
}
