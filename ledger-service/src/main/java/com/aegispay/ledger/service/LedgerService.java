package com.aegispay.ledger.service;

import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.correlation.CorrelationContext;
import com.aegispay.common.events.payload.LedgerPostingPayload;
import com.aegispay.common.exception.DomainExceptions.ConflictException;
import com.aegispay.common.exception.DomainExceptions.NotFoundException;
import com.aegispay.common.observability.MetricNames;
import com.aegispay.ledger.domain.JournalEntry;
import com.aegispay.ledger.domain.JournalLine;
import com.aegispay.ledger.domain.LedgerAccount;
import com.aegispay.ledger.repository.JournalEntryRepository;
import com.aegispay.ledger.repository.JournalLineRepository;
import com.aegispay.ledger.repository.LedgerAccountRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Posting orchestration and journal queries.
 *
 * <h2>Why this class has no transaction</h2>
 *
 * <p>Idempotency is enforced by the unique constraint on {@code ledger_transaction_id}, and
 * a constraint violation in PostgreSQL aborts the entire transaction — every subsequent
 * statement on that connection fails until it rolls back. Recovering from a lost race by
 * re-reading the posting the winner committed therefore <em>cannot</em> happen inside the
 * transaction that just failed. This class stays non-transactional so the failed attempt
 * rolls back cleanly and the re-read runs in a fresh transaction.
 *
 * <p>That is also why {@link JournalWriter} is a separate bean rather than a private method
 * here: Spring applies transaction advice through a proxy, so {@code this.append(...)} would
 * silently run with no transaction at all.
 *
 * <h2>Idempotency</h2>
 *
 * <p>Two layers, and only one of them is the guarantee:
 *
 * <ol>
 *   <li>A lookup on the idempotency key, which answers the overwhelmingly common retry
 *       cheaply and without taking any locks.</li>
 *   <li>The unique constraint, which is what actually makes double posting impossible when
 *       two identical requests arrive close enough together that both pass the lookup.</li>
 * </ol>
 *
 * <p>Note what is <em>not</em> used: the shared {@code idempotency_keys} table the payment
 * and wallet services rely on. A posting already carries a caller-supplied identifier that
 * is unique per logical movement, so a side table would add a second thing to keep in sync
 * while being strictly weaker — it can be bypassed, whereas a unique constraint on the
 * journal itself cannot.
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final JournalWriter writer;
    private final LedgerAccountProvisioner provisioner;
    private final LedgerAccountRepository accounts;
    private final JournalEntryRepository entries;
    private final JournalLineRepository lines;
    private final MeterRegistry meterRegistry;

    public LedgerService(JournalWriter writer,
                         LedgerAccountProvisioner provisioner,
                         LedgerAccountRepository accounts,
                         JournalEntryRepository entries,
                         JournalLineRepository lines,
                         MeterRegistry meterRegistry) {
        this.writer = writer;
        this.provisioner = provisioner;
        this.accounts = accounts;
        this.entries = entries;
        this.lines = lines;
        this.meterRegistry = meterRegistry;
    }

    // --------------------------------------------------------------- posting

    /**
     * Posts a balanced double entry, at most once per {@code ledgerTransactionId}.
     *
     * <p>Safe to call repeatedly with the same payload: a redelivered Kafka record or a
     * retried HTTP request returns the original posting with {@code duplicate = true} rather
     * than booking the movement again.
     *
     * @param requestedBy the service or operator asking for the posting, recorded on the
     *                    entry. "Who booked this" is the first question asked about an
     *                    unexpected journal entry.
     */
    public PostingResult post(LedgerPostingPayload posting, String requestedBy) {
        String key = posting.ledgerTransactionId();

        // Layer 1: the cheap lookup that answers a normal retry.
        Optional<PostingResult> alreadyPosted = writer.findByLedgerTransactionId(key);
        if (alreadyPosted.isPresent()) {
            return replay(alreadyPosted.get(), "lookup");
        }

        // Committed in its own transaction before the posting opens, so a collision over a
        // newly created account row cannot abort the posting itself.
        provisioner.ensureAccountsFor(posting);

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            PostingResult result = writer.append(posting, requestedBy, CorrelationContext.get());
            sample.stop(meterRegistry.timer(MetricNames.LEDGER_POSTING_LATENCY,
                    MetricNames.TAG_OUTCOME, MetricNames.OUTCOME_SUCCESS));
            return result;

        } catch (DataIntegrityViolationException ex) {
            sample.stop(meterRegistry.timer(MetricNames.LEDGER_POSTING_LATENCY,
                    MetricNames.TAG_OUTCOME, MetricNames.OUTCOME_FAILURE));

            // Layer 2: either we lost the race on the idempotency key, or a different
            // constraint failed. Re-reading in a fresh transaction distinguishes them: if
            // the posting is now there, someone else booked it and this is a replay.
            Optional<PostingResult> winner = writer.findByLedgerTransactionId(key);
            if (winner.isPresent()) {
                return replay(winner.get(), "constraint");
            }
            throw translate(posting, ex);
        }
    }

    /**
     * Reverses a posting by appending its exact opposite.
     *
     * <p>The only way to undo a posting: the journal is append-only, so a correction is a new
     * entry and both remain visible. Idempotent through the schema's single-reversal index —
     * a second attempt is refused rather than double-reversing the movement.
     */
    public PostingResult reverse(String targetLedgerTransactionId, String reason,
                                 String requestedBy) {
        return writer.appendReversal(targetLedgerTransactionId, reason, requestedBy,
                CorrelationContext.get());
    }

    // --------------------------------------------------------------- queries

    public PostingResult getPosting(String ledgerTransactionId) {
        return writer.findByLedgerTransactionId(ledgerTransactionId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No posting exists with ledger transaction id " + ledgerTransactionId));
    }

    @Transactional(readOnly = true)
    public PostingResult getPostingBySequence(long journalSequence) {
        JournalEntry entry = entries.findById(journalSequence)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No posting exists at journal sequence " + journalSequence));
        return PostingResult.replayed(entry, writer.linesOf(journalSequence));
    }

    /** Every posting produced by one payment: its transfer, and any later refunds. */
    @Transactional(readOnly = true)
    public List<PostingResult> getPostingsForPayment(String paymentId) {
        return entries.findByPaymentIdOrderByJournalSequenceAsc(paymentId).stream()
                .map(entry -> PostingResult.replayed(entry,
                        writer.linesOf(entry.getJournalSequence())))
                .toList();
    }

    @Transactional(readOnly = true)
    public LedgerAccount getAccount(String accountId) {
        return accounts.findByAccountId(accountId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Ledger account " + accountId + " does not exist"));
    }

    @Transactional(readOnly = true)
    public List<LedgerAccount> listAccounts(String currency) {
        return accounts.findByCurrencyOrderByAccountIdAsc(currency.toUpperCase());
    }

    /**
     * An account statement: every line touching one account, newest first.
     *
     * <p>Each line carries the balance that resulted from it, so the statement is readable
     * without replaying the whole history.
     */
    @Transactional(readOnly = true)
    public Page<JournalLine> accountStatement(String accountId, Pageable pageable) {
        // Verifies the account exists, so an unknown ID reports 404 rather than an
        // empty page that looks like an account with no activity.
        getAccount(accountId);
        return lines.findByAccountIdOrderByJournalSequenceDesc(accountId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<JournalEntry> journal(String currency, Pageable pageable) {
        return currency == null || currency.isBlank()
                ? entries.findAllByOrderByJournalSequenceDesc(pageable)
                : entries.findByCurrencyOrderByJournalSequenceDesc(currency.toUpperCase(), pageable);
    }

    /** The reversal of a posting, if one has been made. */
    @Transactional(readOnly = true)
    public Optional<JournalEntry> findReversalOf(String ledgerTransactionId) {
        return entries.findReversalOf(ledgerTransactionId);
    }

    // ------------------------------------------------------------- internals

    private PostingResult replay(PostingResult result, String layer) {
        meterRegistry.counter(MetricNames.PAYMENT_IDEMPOTENT_REPLAY,
                "scope", "ledger.post", "layer", layer).increment();
        log.debug("Posting {} already exists at #{}; returning it as a replay",
                result.entry().getLedgerTransactionId(), result.entry().getJournalSequence());
        // duplicate = true, so a caller can tell "I booked this" from "this was already
        // booked" and not re-emit downstream side effects. No second ledger-posted event is
        // recorded: the original was already delivered at-least-once by the outbox, and
        // emitting one per retry would multiply events without adding information.
        return PostingResult.replayed(result.entry(), result.lines());
    }

    /**
     * Turns a constraint violation that is <em>not</em> a duplicate key into a meaningful
     * error.
     *
     * <p>The realistic case is an account that would be driven negative. {@link JournalWriter}
     * checks for that before writing and reports it with the actual numbers, so reaching here
     * means the pre-check and the constraint disagree — worth logging loudly, because it
     * implies one of them is wrong.
     */
    private RuntimeException translate(LedgerPostingPayload posting,
                                       DataIntegrityViolationException ex) {
        String cause = ex.getMostSpecificCause().getMessage();
        log.error("Posting {} was rejected by a database constraint: {}",
                posting.ledgerTransactionId(), cause);

        if (cause != null && cause.contains("ck_ledger_accounts_negative_allowed")) {
            return new ConflictException(ErrorCode.INVALID_PAYMENT_STATE,
                    "The posting would drive an account below zero, which its type does not permit");
        }
        if (cause != null && cause.contains("uq_journal_entries_single_reversal")) {
            return new ConflictException(ErrorCode.INVALID_PAYMENT_STATE,
                    "That posting has already been reversed");
        }
        // Constraint names describe the schema, so the detail stays in the log.
        return new ConflictException(ErrorCode.RESOURCE_ALREADY_EXISTS,
                "The posting conflicts with the current state of the journal");
    }
}
