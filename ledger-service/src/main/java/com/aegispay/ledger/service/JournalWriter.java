package com.aegispay.ledger.service;

import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.domain.SharedEnums.PostingDirection;
import com.aegispay.common.events.EventEnvelope;
import com.aegispay.common.events.KafkaTopics;
import com.aegispay.common.events.payload.LedgerPostedPayload;
import com.aegispay.common.events.payload.LedgerPostingPayload;
import com.aegispay.common.events.payload.LedgerPostingPayload.PostingLine;
import com.aegispay.common.exception.DomainExceptions.BadRequestException;
import com.aegispay.common.exception.DomainExceptions.ConflictException;
import com.aegispay.common.exception.DomainExceptions.LedgerImbalanceException;
import com.aegispay.common.exception.DomainExceptions.NotFoundException;
import com.aegispay.common.observability.MetricNames;
import com.aegispay.common.outbox.OutboxRecorder;
import com.aegispay.ledger.domain.JournalEntry;
import com.aegispay.ledger.domain.JournalEntry.PostingType;
import com.aegispay.ledger.domain.JournalLine;
import com.aegispay.ledger.domain.LedgerAccount;
import com.aegispay.ledger.repository.JournalEntryRepository;
import com.aegispay.ledger.repository.JournalLineRepository;
import com.aegispay.ledger.repository.LedgerAccountRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The only code that writes to the journal.
 *
 * <p>Everything here happens in one short transaction touching only the database — no HTTP,
 * no hashing — so account row locks are held for microseconds rather than across a network
 * round trip.
 *
 * <h2>Why this is a separate bean from {@link LedgerService}</h2>
 *
 * <p>Idempotency is enforced by the unique constraint on {@code ledger_transaction_id}, and
 * in PostgreSQL a constraint violation aborts the whole transaction. Recovering from it —
 * re-reading the posting the winner committed — is therefore impossible inside the
 * transaction that just failed; it needs a fresh one. {@link LedgerService} is consequently
 * non-transactional and calls across a real bean boundary, because a {@code this.method()}
 * call would bypass Spring's proxy and silently run untransacted.
 *
 * <h2>Locking</h2>
 *
 * <p>Accounts are locked with {@code SELECT ... FOR UPDATE} in <strong>sorted account-ID
 * order</strong>. Sorting is what makes deadlock impossible: two postings that touch the
 * same pair of accounts in opposite orders would otherwise each hold the row the other
 * needs, and PostgreSQL would kill one as a deadlock victim. Every caller in the system
 * acquires locks in the same sequence, so no cycle can form. This is the same rule
 * wallet-service applies to its own multi-wallet transfers, and for the same reason.
 *
 * <h2>Validation</h2>
 *
 * <p>The debit==credit invariant is checked three times, deliberately: once when
 * {@link LedgerPostingPayload} is constructed, once here before insert, and once by a
 * deferred constraint trigger at commit. The first two give a clear error at the right
 * layer; the third is the guarantee, because it holds against a hand-written INSERT as well
 * as against this class.
 */
@Service
public class JournalWriter {

    private static final Logger log = LoggerFactory.getLogger(JournalWriter.class);

    private static final String SOURCE = "ledger-service";
    private static final String AGGREGATE = "LedgerEntry";

    private final LedgerAccountRepository accounts;
    private final JournalEntryRepository entries;
    private final JournalLineRepository lines;
    private final OutboxRecorder outbox;
    private final MeterRegistry meterRegistry;

    public JournalWriter(LedgerAccountRepository accounts,
                         JournalEntryRepository entries,
                         JournalLineRepository lines,
                         OutboxRecorder outbox,
                         MeterRegistry meterRegistry) {
        this.accounts = accounts;
        this.entries = entries;
        this.lines = lines;
        this.outbox = outbox;
        this.meterRegistry = meterRegistry;
    }

    // ------------------------------------------------------------- appending

    /**
     * Appends a balanced posting and applies it to every account it touches.
     *
     * <p>Assumes the accounts already exist and are committed — {@link LedgerAccountProvisioner}
     * runs first, in its own transaction, so a collision over a newly created account row
     * cannot abort this one.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException when the idempotency
     *         key is already taken. The caller recovers by re-reading; that cannot happen
     *         here because the violation has already aborted this transaction.
     */
    @Transactional
    public PostingResult append(LedgerPostingPayload posting, String requestedBy,
                                String correlationId) {
        assertBalanced(posting);

        PostingType postingType = parsePostingType(posting.postingType());
        long total = posting.totalMinor();

        if (postingType == PostingType.REVERSAL) {
            requireReversibleTarget(posting.reversesLedgerTransactionId());
        } else if (posting.reversesLedgerTransactionId() != null) {
            // A refund points at what it partially undoes. The target must exist, or the
            // chain leads nowhere and the FK would fail with a less useful message.
            requireExisting(posting.reversesLedgerTransactionId());
        }

        JournalEntry entry = entries.saveAndFlush(JournalEntry.of(
                posting.ledgerTransactionId(),
                postingType,
                posting.currency(),
                total,
                posting.paymentId(),
                posting.transactionId(),
                posting.narrative(),
                posting.reversesLedgerTransactionId(),
                requestedBy == null ? SOURCE : requestedBy,
                correlationId,
                posting.occurredAt()));

        // Lock every distinct account up front, in sorted order, before applying anything.
        // Locking lazily as each line is processed would acquire them in payload order,
        // which is exactly the deadlock the sort exists to prevent.
        Map<String, LedgerAccount> locked = lockAccountsInOrder(posting);
        assertNoAccountGoesNegative(posting, locked);

        List<JournalLine> written = new ArrayList<>(posting.lines().size());
        int lineNumber = 1;
        for (PostingLine line : posting.lines()) {
            LedgerAccount account = locked.get(line.accountId());
            assertDeclaredTypeMatches(line, account);

            written.add(lines.save(JournalLine.record(
                    entry, lineNumber++, account, line.direction(), line.amountMinor(),
                    line.memo())));
        }
        accounts.saveAll(locked.values());

        // Flush inside the transaction so a constraint violation surfaces here, attributable
        // to this posting, rather than during an opaque commit at the end of the request.
        // The deferred balance trigger still fires at commit; that is its whole purpose.
        lines.flush();
        accounts.flush();

        PostingResult result = PostingResult.posted(entry, written);
        recordPostedEvent(result);

        meterRegistry.counter(MetricNames.LEDGER_POSTING, "postingType", postingType.name())
                .increment();

        log.info("Posted #{} {} {} ({}) touching {} account(s)",
                entry.getJournalSequence(), postingType, entry.total(),
                entry.getLedgerTransactionId(), locked.size());
        return result;
    }

    /**
     * Appends the exact opposite of an existing posting.
     *
     * <p>The only way to undo a posting, because the journal is append-only. Every leg's
     * direction is flipped, so the two entries together net to zero on every account they
     * touch while both remain visible — which is what an audit needs. Editing or deleting
     * the original would destroy the evidence that it ever happened.
     *
     * <p>A posting may be reversed at most once; the schema enforces that with a partial
     * unique index, so two concurrent reversals cannot both succeed.
     */
    @Transactional
    public PostingResult appendReversal(String targetLedgerTransactionId,
                                        String reason,
                                        String requestedBy,
                                        String correlationId) {

        JournalEntry target = requireExisting(targetLedgerTransactionId);

        if (target.isReversal()) {
            // Reversing a reversal would re-apply the original movement by a confusing
            // route. If that is genuinely wanted, post it as a fresh entry that says so.
            throw new ConflictException(ErrorCode.INVALID_PAYMENT_STATE,
                    "Posting " + targetLedgerTransactionId + " is itself a reversal and cannot "
                            + "be reversed; post a new entry instead");
        }
        entries.findReversalOf(targetLedgerTransactionId).ifPresent(existing -> {
            throw new ConflictException(ErrorCode.INVALID_PAYMENT_STATE,
                    "Posting " + targetLedgerTransactionId + " was already reversed by "
                            + existing.getLedgerTransactionId());
        });

        List<JournalLine> original = lines.findByJournalSequenceOrderByLineNumberAsc(
                target.getJournalSequence());
        if (original.isEmpty()) {
            throw new IllegalStateException(
                    "posting " + targetLedgerTransactionId + " has no lines to reverse");
        }

        List<PostingLine> flipped = original.stream()
                .map(line -> new PostingLine(
                        line.getAccountId(),
                        line.getAccountType(),
                        line.getDirection().opposite(),
                        line.getAmountMinor(),
                        "Reversal of #" + target.getJournalSequence()))
                .toList();

        // Derived from the target's sequence number rather than supplied by the caller, and
        // rather than derived from the target's own key: keys may be up to the full column
        // width, so prefixing one could overflow, and truncating an idempotency key risks two
        // distinct postings colliding. The sequence is short, unique and already assigned.
        String reversalKey = "LTX-REV-" + target.getJournalSequence();

        // Constructing the payload re-checks the balance: the reverse of a balanced posting
        // is balanced, and if that ever failed it would mean the original was not.
        LedgerPostingPayload reversal = new LedgerPostingPayload(
                reversalKey,
                target.getPaymentId(),
                target.getTransactionId(),
                PostingType.REVERSAL.name(),
                target.getCurrency(),
                flipped,
                "Reversal of #" + target.getJournalSequence() + ": "
                        + (reason == null ? "no reason given" : reason),
                targetLedgerTransactionId,
                Instant.now());

        return append(reversal, requestedBy, correlationId);
    }

    // ---------------------------------------------------------------- reading

    @Transactional(readOnly = true)
    public Optional<PostingResult> findByLedgerTransactionId(String ledgerTransactionId) {
        return entries.findByLedgerTransactionId(ledgerTransactionId)
                .map(entry -> PostingResult.replayed(entry,
                        lines.findByJournalSequenceOrderByLineNumberAsc(entry.getJournalSequence())));
    }

    @Transactional(readOnly = true)
    public List<JournalLine> linesOf(long journalSequence) {
        return lines.findByJournalSequenceOrderByLineNumberAsc(journalSequence);
    }

    // -------------------------------------------------------------- internals

    /**
     * Locks every distinct account in the posting, in ascending account-ID order.
     *
     * <p>See the class documentation: the sort is the deadlock-avoidance mechanism, not a
     * tidiness choice.
     */
    private Map<String, LedgerAccount> lockAccountsInOrder(LedgerPostingPayload posting) {
        List<String> ordered = posting.lines().stream()
                .map(PostingLine::accountId)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();

        Map<String, LedgerAccount> locked = new LinkedHashMap<>();
        for (String accountId : ordered) {
            LedgerAccount account = accounts.findByAccountIdForUpdate(accountId)
                    .orElseThrow(() -> new NotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                            "Ledger account " + accountId + " does not exist"));
            if (!account.getCurrency().equals(posting.currency())) {
                throw new BadRequestException(
                        "account " + accountId + " is denominated in " + account.getCurrency()
                                + " but the posting is " + posting.currency());
            }
            locked.put(accountId, account);
        }
        return locked;
    }

    /**
     * Re-checks that debits equal credits.
     *
     * <p>Already guaranteed by {@link LedgerPostingPayload}'s constructor, and checked again
     * by the database at commit. Repeated here because this is the layer that can emit a
     * metric and a log line naming the posting, whereas the constraint trigger produces a
     * SQL error and the payload constructor may have run in another service entirely.
     */
    private void assertBalanced(LedgerPostingPayload posting) {
        long debits = 0L;
        long credits = 0L;
        for (PostingLine line : posting.lines()) {
            if (line.direction() == PostingDirection.DEBIT) {
                debits = Math.addExact(debits, line.amountMinor());
            } else {
                credits = Math.addExact(credits, line.amountMinor());
            }
        }
        if (debits != credits) {
            meterRegistry.counter(MetricNames.LEDGER_IMBALANCE_REJECTED).increment();
            log.error("Rejected unbalanced posting {}: debits={} credits={}",
                    posting.ledgerTransactionId(), debits, credits);
            throw new LedgerImbalanceException(debits, credits);
        }
        if (debits != posting.totalMinor()) {
            meterRegistry.counter(MetricNames.LEDGER_IMBALANCE_REJECTED).increment();
            throw new LedgerImbalanceException(posting.totalMinor(), debits);
        }
    }

    /**
     * Refuses a posting that would drive an account below zero when its type forbids that.
     *
     * <p>{@code ck_ledger_accounts_negative_allowed} already makes such a posting
     * unpersistable, so this check exists for the error message rather than for the
     * guarantee: the constraint reports a SQL violation naming a constraint, while this
     * reports which account, what it holds, and what the posting tried to take. For an
     * over-debited customer wallet that difference is the difference between a usable
     * incident report and a grep.
     *
     * <p>Computed as the <strong>net</strong> effect per account, because the account row is
     * updated once at the end of the transaction. A posting that debits and then credits the
     * same account never exposes the intermediate value to the constraint, so rejecting on an
     * intermediate dip would refuse a legitimate entry.
     */
    private void assertNoAccountGoesNegative(LedgerPostingPayload posting,
                                             Map<String, LedgerAccount> locked) {
        Map<String, Long> netEffect = new LinkedHashMap<>();
        for (PostingLine line : posting.lines()) {
            LedgerAccount account = locked.get(line.accountId());
            netEffect.merge(line.accountId(),
                    account.signedEffectOf(line.direction(), line.amountMinor()), Long::sum);
        }

        netEffect.forEach((accountId, effect) -> {
            LedgerAccount account = locked.get(accountId);
            if (account.isAllowsNegative()) {
                return;
            }
            long resulting = Math.addExact(account.getBalanceMinor(), effect);
            if (resulting < 0L) {
                log.warn("Refusing posting {}: account {} holds {} and the posting would take {}",
                        posting.ledgerTransactionId(), accountId, account.balance(), -effect);
                throw new ConflictException(ErrorCode.INVALID_PAYMENT_STATE,
                        "Posting would leave " + accountId + " at "
                                + resulting + " minor units; this account may not go negative. "
                                + "It currently holds " + account.balance() + ".");
            }
        });
    }

    /**
     * Refuses a line whose declared account type disagrees with the account it names.
     *
     * <p>The line is persisted with the <em>account's</em> type, so the stored data stays
     * consistent either way — but the caller computed its debit and credit directions from
     * the type it believed, so a mismatch means the posting means something other than what
     * was intended. Silently correcting it would book the opposite of the caller's intent.
     */
    private void assertDeclaredTypeMatches(PostingLine line, LedgerAccount account) {
        if (line.accountType() != account.getAccountType()) {
            throw new BadRequestException(
                    "posting declares account " + line.accountId() + " as " + line.accountType()
                            + " but it is a " + account.getAccountType()
                            + "; the debit/credit direction was therefore chosen on a false premise");
        }
    }

    private JournalEntry requireExisting(String ledgerTransactionId) {
        return entries.findByLedgerTransactionId(ledgerTransactionId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Posting " + ledgerTransactionId + " does not exist"));
    }

    private void requireReversibleTarget(String targetLedgerTransactionId) {
        JournalEntry target = requireExisting(targetLedgerTransactionId);
        if (target.isReversal()) {
            throw new ConflictException(ErrorCode.INVALID_PAYMENT_STATE,
                    "Posting " + targetLedgerTransactionId + " is itself a reversal");
        }
    }

    private static PostingType parsePostingType(String raw) {
        try {
            return PostingType.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new IllegalArgumentException(
                    "unknown posting type '" + raw + "'; expected one of "
                            + java.util.Arrays.toString(PostingType.values()));
        }
    }

    /**
     * Records {@code ledger-posted} on the outbox, inside the posting's own transaction.
     *
     * <p>That atomicity is the point: "the posting is committed" and "the world was told the
     * posting is committed" land in one commit. Publishing to Kafka directly would either
     * lose the event on a crash after commit, or announce a posting that then rolled back —
     * and a saga waiting on this event would hang forever in the first case and capture
     * funds against a non-existent posting in the second.
     */
    private void recordPostedEvent(PostingResult result) {
        JournalEntry entry = result.entry();

        LedgerPostedPayload payload = new LedgerPostedPayload(
                entry.getLedgerTransactionId(),
                entry.getPaymentId(),
                entry.getTransactionId(),
                entry.getJournalSequence(),
                entry.getTotalMinor(),
                entry.getCurrency(),
                result.resultingBalancesMinor(),
                false,
                Instant.now());

        outbox.record(KafkaTopics.LEDGER_POSTED, AGGREGATE, entry.getLedgerTransactionId(),
                EventEnvelope.of(KafkaTopics.LEDGER_POSTED, AGGREGATE,
                        entry.getLedgerTransactionId(), SOURCE, payload));
    }
}
