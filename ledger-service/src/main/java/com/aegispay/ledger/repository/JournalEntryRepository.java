package com.aegispay.ledger.repository;

import com.aegispay.ledger.domain.JournalEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {

    /**
     * Looks up a posting by its idempotency key.
     *
     * <p>The first thing the posting path does. A hit means this movement was already
     * booked and the caller is retrying, so the existing entry is returned rather than a
     * second one created.
     */
    Optional<JournalEntry> findByLedgerTransactionId(String ledgerTransactionId);

    boolean existsByLedgerTransactionId(String ledgerTransactionId);

    /** Every posting produced by one payment: the transfer, its fee, any later refund. */
    List<JournalEntry> findByPaymentIdOrderByJournalSequenceAsc(String paymentId);

    /**
     * The full reversal of a posting, if one exists.
     *
     * <p>Scoped to {@code REVERSAL} because a partial refund also points at the original
     * via {@code reversesLedgerTransactionId} without undoing it. Treating a partial refund
     * as a reversal would wrongly refuse a subsequent, legitimate one.
     */
    @Query("""
            SELECT e FROM JournalEntry e
            WHERE e.reversesLedgerTransactionId = :ledgerTransactionId
              AND e.postingType = com.aegispay.ledger.domain.JournalEntry$PostingType.REVERSAL
            """)
    Optional<JournalEntry> findReversalOf(@Param("ledgerTransactionId") String ledgerTransactionId);

    /** Everything that references a posting, reversal or partial refund alike. */
    List<JournalEntry> findByReversesLedgerTransactionIdOrderByJournalSequenceAsc(
            String ledgerTransactionId);

    Page<JournalEntry> findAllByOrderByJournalSequenceDesc(Pageable pageable);

    Page<JournalEntry> findByCurrencyOrderByJournalSequenceDesc(String currency, Pageable pageable);

    @Query("""
            SELECT e FROM JournalEntry e
            WHERE e.recordedAt >= :from AND e.recordedAt < :to
            ORDER BY e.journalSequence
            """)
    List<JournalEntry> findRecordedBetween(@Param("from") Instant from, @Param("to") Instant to);

    // ------------------------------------------------------- reconciliation

    /**
     * Sum of every posting's declared total, per currency.
     *
     * <p>Compared against the sum of the lines. The two are written by the same
     * transaction, so a divergence means the declared total was computed from something
     * other than the lines it claims to describe.
     */
    @Query("""
            SELECT COALESCE(SUM(e.totalMinor), 0) FROM JournalEntry e
            WHERE e.currency = :currency
            """)
    long declaredTotalMinor(@Param("currency") String currency);

    long countByCurrency(String currency);

    @Query("SELECT COALESCE(MAX(e.journalSequence), 0) FROM JournalEntry e")
    long highestJournalSequence();
}
