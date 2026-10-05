package com.aegispay.ledger.repository;

import com.aegispay.common.domain.SharedEnums.PostingDirection;
import com.aegispay.ledger.domain.JournalLine;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface JournalLineRepository extends JpaRepository<JournalLine, Long> {

    /** Every leg of one posting, in the order they were applied. */
    List<JournalLine> findByJournalSequenceOrderByLineNumberAsc(Long journalSequence);

    List<JournalLine> findByJournalSequenceInOrderByJournalSequenceAscLineNumberAsc(
            List<Long> journalSequences);

    /** An account statement: one account, newest posting first. */
    Page<JournalLine> findByAccountIdOrderByJournalSequenceDesc(String accountId, Pageable pageable);

    long countByAccountId(String accountId);

    // ---------------------------------------------------------------- replay

    /**
     * Recomputes an account's natural-direction balance by replaying every line.
     *
     * <p>{@code signedEffectMinor} is already expressed in the account's own direction, so
     * summing it is exactly the balance the account row caches. Comparing the two is the
     * per-account half of reconciliation: a mismatch means either a balance was mutated
     * without a line to justify it, or a line was written that never reached the balance.
     */
    @Query("""
            SELECT COALESCE(SUM(l.signedEffectMinor), 0) FROM JournalLine l
            WHERE l.accountId = :accountId
            """)
    long replayBalanceMinor(@Param("accountId") String accountId);

    @Query("""
            SELECT COALESCE(SUM(l.amountMinor), 0) FROM JournalLine l
            WHERE l.accountId = :accountId AND l.direction = :direction
            """)
    long replayDirectionTotalMinor(@Param("accountId") String accountId,
                                  @Param("direction") PostingDirection direction);

    // ----------------------------------------------------- journal integrity

    /**
     * Postings whose debits do not equal their credits.
     *
     * <p><strong>Must always return empty.</strong> A deferred constraint trigger makes an
     * unbalanced posting impossible to commit, so a non-empty result here means the trigger
     * was dropped, disabled, or bypassed — which is itself the finding. Checking anyway is
     * the point of a reconciliation report: it verifies the guarantee rather than assuming
     * it.
     *
     * <p>Note the debit-positive convention. Summing {@code signedEffectMinor} would be
     * wrong: those are natural-direction values, and a perfectly balanced deposit
     * (debit an asset, credit a liability) increases both, so its signed effects sum to
     * twice the amount rather than to zero.
     */
    @Query("""
            SELECT l.journalSequence FROM JournalLine l
            GROUP BY l.journalSequence
            HAVING SUM(CASE WHEN l.direction = :debit THEN l.amountMinor ELSE -l.amountMinor END) <> 0
            """)
    List<Long> findUnbalancedJournalSequences(@Param("debit") PostingDirection debit);

    /** Postings with fewer than two legs, which cannot be a double entry. */
    @Query("""
            SELECT l.journalSequence FROM JournalLine l
            GROUP BY l.journalSequence
            HAVING COUNT(l) < 2
            """)
    List<Long> findSingleSidedJournalSequences();

    /**
     * Total debits recorded as lines, per currency. Compared against the same figure
     * aggregated from the accounts table, which is written by different code in the same
     * transaction.
     */
    @Query("""
            SELECT COALESCE(SUM(l.amountMinor), 0) FROM JournalLine l
            WHERE l.currency = :currency AND l.direction = :direction
            """)
    long totalByDirectionMinor(@Param("currency") String currency,
                               @Param("direction") PostingDirection direction);

    /**
     * Lines whose stored {@code balanceAfterMinor} disagrees with the running total implied
     * by the lines before it on the same account.
     *
     * <p>Expressed as a window function, so this is a native query: JPQL has no window
     * functions. It catches a corrupted statement — one where each line looks individually
     * valid but the sequence of balances does not add up, which is what a lost update
     * produces.
     */
    @Query(value = """
            SELECT journal_sequence FROM (
                SELECT journal_sequence,
                       balance_after_minor,
                       SUM(signed_effect_minor) OVER (
                           PARTITION BY account_id
                           ORDER BY journal_sequence, line_number
                           ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
                       ) AS running_balance
                  FROM journal_lines
                 WHERE account_id = :accountId
            ) replayed
            WHERE balance_after_minor <> running_balance
            ORDER BY journal_sequence
            """, nativeQuery = true)
    List<Long> findLinesWithInconsistentRunningBalance(@Param("accountId") String accountId);
}
