package com.aegispay.ledger.service;

import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.domain.SharedEnums.AccountType;
import com.aegispay.common.domain.SharedEnums.PostingDirection;
import com.aegispay.common.exception.DomainExceptions.NotFoundException;
import com.aegispay.common.money.Money;
import com.aegispay.ledger.api.LedgerDtos.AccountBalanceView;
import com.aegispay.ledger.api.LedgerDtos.AccountReconciliationResponse;
import com.aegispay.ledger.api.LedgerDtos.LedgerReconciliationResponse;
import com.aegispay.ledger.api.LedgerDtos.MoneyView;
import com.aegispay.ledger.api.LedgerDtos.TrialBalanceResponse;
import com.aegispay.ledger.api.LedgerDtos.WalletLiabilitySnapshot;
import com.aegispay.ledger.domain.LedgerAccount;
import com.aegispay.ledger.repository.JournalEntryRepository;
import com.aegispay.ledger.repository.JournalLineRepository;
import com.aegispay.ledger.repository.LedgerAccountRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Verifies that the ledger is internally consistent.
 *
 * <p>Four independent checks, deliberately overlapping. Each could catch a different failure,
 * and a report that only ever confirms what the write path already believes is worse than no
 * report at all, because it manufactures confidence:
 *
 * <ol>
 *   <li><strong>Trial balance.</strong> Total debits must equal total credits across every
 *       account in a currency. This is the fundamental accounting identity; if it fails, no
 *       report built on these books means anything.</li>
 *   <li><strong>Accounts against the journal.</strong> The cached totals on
 *       {@code ledger_accounts} are recomputed from {@code journal_lines}. These are written
 *       by different statements in the same transaction, so a divergence means one of them is
 *       wrong.</li>
 *   <li><strong>Per-posting integrity.</strong> Every posting must have at least two legs and
 *       balance. A deferred constraint trigger already makes the alternative impossible to
 *       commit — so anything found here means the trigger was dropped, disabled or bypassed,
 *       which is itself the finding worth reporting.</li>
 *   <li><strong>Running balances.</strong> Each line stores the balance that resulted from
 *       it. Replayed in order, those must form a consistent chain; a break is the signature
 *       of a lost update.</li>
 * </ol>
 *
 * <p>Read-only throughout. Reconciliation must never repair what it finds: a check that
 * silently corrects a discrepancy destroys the evidence of how it arose, and the cause
 * matters far more than the symptom.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    /** Counter incremented whenever the scheduled check fails. Should stay at zero forever. */
    private static final String METRIC_TRIAL_BALANCE_FAILED = "aegis.ledger.trial.balance.failed";
    private static final String METRIC_RECONCILIATION_DRIFT = "aegis.ledger.reconciliation.drift";

    /**
     * Cap on accounts reconciled per whole-ledger report.
     *
     * <p>Bounded because the per-account check is a query each, so an unbounded report would
     * degrade into a self-inflicted outage as the chart of accounts grows. A production
     * deployment would sweep accounts in partitions on a schedule; this endpoint is for
     * answering "are the books sound right now" on demand.
     */
    private static final int MAX_ACCOUNTS_PER_REPORT = 2_000;

    private final LedgerAccountRepository accounts;
    private final JournalEntryRepository entries;
    private final JournalLineRepository lines;
    private final MeterRegistry meterRegistry;

    public ReconciliationService(LedgerAccountRepository accounts,
                                 JournalEntryRepository entries,
                                 JournalLineRepository lines,
                                 MeterRegistry meterRegistry) {
        this.accounts = accounts;
        this.entries = entries;
        this.lines = lines;
        this.meterRegistry = meterRegistry;
    }

    // --------------------------------------------------------- trial balance

    /**
     * The trial balance for one currency.
     *
     * <p>Computed twice from different sources — once from the cached account totals, once by
     * summing the journal lines — because agreement between two independently written
     * representations is far stronger evidence than either one being self-consistent.
     */
    @Transactional(readOnly = true)
    public TrialBalanceResponse trialBalance(String currency) {
        String normalised = currency.toUpperCase();

        long storedDebits = accounts.totalDebitsMinor(normalised);
        long storedCredits = accounts.totalCreditsMinor(normalised);

        long replayedDebits = lines.totalByDirectionMinor(normalised, PostingDirection.DEBIT);
        long replayedCredits = lines.totalByDirectionMinor(normalised, PostingDirection.CREDIT);

        long difference = storedDebits - storedCredits;
        boolean balanced = difference == 0L;
        boolean accountsAgree = storedDebits == replayedDebits && storedCredits == replayedCredits;

        List<LedgerAccount> chart = accounts.findByCurrencyOrderByAccountIdAsc(normalised);

        if (!balanced) {
            // ERROR, not WARN: an unbalanced trial balance means money was created or
            // destroyed. It needs a human immediately, not a dashboard someone might look at.
            log.error("""
                            TRIAL BALANCE FAILED for {}: debits={} credits={} difference={}. \
                            The books do not balance; no report derived from them is trustworthy.""",
                    normalised, storedDebits, storedCredits, difference);
        }
        if (!accountsAgree) {
            log.error("""
                            LEDGER DRIFT for {}: cached account totals (debits={} credits={}) \
                            disagree with the journal lines (debits={} credits={}).""",
                    normalised, storedDebits, storedCredits, replayedDebits, replayedCredits);
        }

        return new TrialBalanceResponse(
                normalised,
                MoneyView.of(Money.ofMinor(storedDebits, normalised)),
                MoneyView.of(Money.ofMinor(storedCredits, normalised)),
                difference,
                balanced,
                MoneyView.of(Money.ofMinor(replayedDebits, normalised)),
                MoneyView.of(Money.ofMinor(replayedCredits, normalised)),
                accountsAgree,
                MoneyView.of(Money.ofMinor(
                        accounts.totalCustomerLiabilityMinor(normalised), normalised)),
                chart.size(),
                entries.countByCurrency(normalised),
                chart.stream().map(AccountBalanceView::from).toList(),
                Instant.now());
    }

    // ------------------------------------------------------ per-account check

    /**
     * Reconciles one account against the journal lines that touch it.
     *
     * <p>The stored balance is compared with a replay of every {@code signedEffectMinor}, and
     * the stored debit/credit totals with a replay of the raw amounts. The running-balance
     * chain is checked separately, because a lost update can leave the endpoints correct while
     * the intermediate history is wrong.
     */
    @Transactional(readOnly = true)
    public AccountReconciliationResponse reconcileAccount(String accountId) {
        LedgerAccount account = accounts.findByAccountId(accountId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Ledger account " + accountId + " does not exist"));

        long replayedBalance = lines.replayBalanceMinor(accountId);
        long replayedDebits = lines.replayDirectionTotalMinor(accountId, PostingDirection.DEBIT);
        long replayedCredits = lines.replayDirectionTotalMinor(accountId, PostingDirection.CREDIT);

        boolean balanceMatches = account.getBalanceMinor() == replayedBalance;
        boolean totalsMatch = account.getDebitTotalMinor() == replayedDebits
                && account.getCreditTotalMinor() == replayedCredits;

        List<Long> brokenChain = lines.findLinesWithInconsistentRunningBalance(accountId);

        boolean consistent = balanceMatches && totalsMatch && brokenChain.isEmpty();
        if (!consistent) {
            meterRegistry.counter(METRIC_RECONCILIATION_DRIFT, "accountId", "redacted").increment();
            log.error("""
                            ACCOUNT RECONCILIATION FAILED for {}: stored balance={} replayed={} \
                            | stored debits={} replayed={} | stored credits={} replayed={} \
                            | broken running balance at {}""",
                    accountId, account.getBalanceMinor(), replayedBalance,
                    account.getDebitTotalMinor(), replayedDebits,
                    account.getCreditTotalMinor(), replayedCredits, brokenChain);
        }

        return new AccountReconciliationResponse(
                accountId,
                account.getAccountType(),
                account.getCurrency(),
                account.getBalanceMinor(),
                replayedBalance,
                account.getDebitTotalMinor(),
                replayedDebits,
                account.getCreditTotalMinor(),
                replayedCredits,
                balanceMatches,
                totalsMatch,
                brokenChain,
                consistent,
                lines.countByAccountId(accountId));
    }

    // ---------------------------------------------------- whole-ledger report

    /**
     * Runs every check across every currency and reports what is wrong.
     *
     * <p>{@code findings} is empty when the ledger is sound. Each entry names a specific
     * problem in terms an operator can act on, rather than leaving them to compare numbers
     * across four lists.
     */
    @Transactional(readOnly = true)
    public LedgerReconciliationResponse reconcileLedger() {
        List<String> findings = new ArrayList<>();

        List<TrialBalanceResponse> trialBalances = new ArrayList<>();
        for (String currency : accounts.distinctCurrencies()) {
            TrialBalanceResponse balance = trialBalance(currency);
            trialBalances.add(balance);

            if (!balance.balanced()) {
                findings.add("Trial balance for %s is out by %d minor units (debits %d, credits %d)"
                        .formatted(currency, balance.differenceMinor(),
                                balance.totalDebits().amountMinor(),
                                balance.totalCredits().amountMinor()));
            }
            if (!balance.accountsAgreeWithJournal()) {
                findings.add(("Cached account totals for %s disagree with the journal lines "
                        + "(accounts: %d/%d, journal: %d/%d debits/credits)")
                        .formatted(currency,
                                balance.totalDebits().amountMinor(),
                                balance.totalCredits().amountMinor(),
                                balance.replayedDebits().amountMinor(),
                                balance.replayedCredits().amountMinor()));
            }
        }

        List<Long> unbalanced = lines.findUnbalancedJournalSequences(PostingDirection.DEBIT);
        if (!unbalanced.isEmpty()) {
            // The deferred constraint trigger makes this impossible. Its presence means the
            // trigger is gone, so say that rather than only listing the sequences.
            findings.add(("%d posting(s) are unbalanced: %s. This should be impossible while "
                    + "trg_journal_lines_balanced exists, so that trigger has been dropped, "
                    + "disabled or bypassed.")
                    .formatted(unbalanced.size(), unbalanced));
        }

        List<Long> singleSided = lines.findSingleSidedJournalSequences();
        if (!singleSided.isEmpty()) {
            findings.add("%d posting(s) have fewer than two legs: %s"
                    .formatted(singleSided.size(), singleSided));
        }

        List<AccountReconciliationResponse> drifted = new ArrayList<>();
        int checked = 0;
        for (String currency : accounts.distinctCurrencies()) {
            for (LedgerAccount account : accounts.findByCurrencyOrderByAccountIdAsc(currency)) {
                if (checked >= MAX_ACCOUNTS_PER_REPORT) {
                    findings.add(("Stopped after %d accounts; %s and later were not checked. "
                            + "Reconcile them individually or run the scheduled sweep.")
                            .formatted(MAX_ACCOUNTS_PER_REPORT, account.getAccountId()));
                    break;
                }
                checked++;
                AccountReconciliationResponse result = reconcileAccount(account.getAccountId());
                if (!result.consistent()) {
                    drifted.add(result);
                    findings.add("Account %s: stored balance %d but the journal replays to %d"
                            .formatted(result.accountId(), result.storedBalanceMinor(),
                                    result.replayedBalanceMinor()));
                }
            }
        }

        boolean consistent = findings.isEmpty();
        if (!consistent) {
            log.error("LEDGER RECONCILIATION FAILED with {} finding(s): {}",
                    findings.size(), findings);
        } else {
            log.info("Ledger reconciliation passed across {} currency/currencies and {} accounts",
                    trialBalances.size(), checked);
        }

        return new LedgerReconciliationResponse(
                consistent,
                trialBalances,
                unbalanced,
                singleSided,
                drifted,
                findings,
                checked,
                Instant.now());
    }

    /**
     * Wallet liability balances as the ledger sees them.
     *
     * <p>Exists so wallet-service's own totals can be cross-checked against the book of
     * record. Equality is only meaningful because the ledger books deposits and withdrawals
     * as well as transfers: if it saw internal transfers alone, these balances would be
     * missing every deposit ever made and the comparison would be guaranteed to fail.
     */
    @Transactional(readOnly = true)
    public WalletLiabilitySnapshot walletLiabilities(String currency) {
        String normalised = currency.toUpperCase();

        Map<String, Long> balances = new LinkedHashMap<>();
        for (LedgerAccount account : accounts.findByCurrencyOrderByAccountIdAsc(normalised)) {
            if (account.isSystemAccount() || account.getAccountType() != AccountType.LIABILITY) {
                continue;
            }
            String accountId = account.getAccountId();
            String walletId = accountId.startsWith(LedgerAccountProvisioner.WALLET_PREFIX)
                    ? accountId.substring(LedgerAccountProvisioner.WALLET_PREFIX.length())
                    : accountId;
            balances.put(walletId, account.getBalanceMinor());
        }

        return new WalletLiabilitySnapshot(
                normalised,
                MoneyView.of(Money.ofMinor(
                        accounts.totalCustomerLiabilityMinor(normalised), normalised)),
                balances,
                Instant.now());
    }

    // ------------------------------------------------------------- scheduled

    /**
     * Periodically verifies the trial balance.
     *
     * <p>Reconciliation that only runs when someone remembers to ask is not a control. This
     * makes the check continuous, so a break is detected in minutes rather than at the next
     * audit.
     *
     * <p>Only the trial balance runs on a schedule, not the full per-account sweep: this is
     * cheap — two aggregates per currency — and it is the check that catches money being
     * created or destroyed. The expensive per-account replay stays on demand.
     *
     * <p>{@code fixedDelay} rather than {@code fixedRate}, so a slow run cannot stack up
     * overlapping checks against the same tables.
     */
    @Scheduled(fixedDelayString = "${aegis.ledger.trial-balance-check-interval:300000}",
            initialDelayString = "${aegis.ledger.trial-balance-check-initial-delay:60000}")
    public void verifyTrialBalance() {
        try {
            for (String currency : accounts.distinctCurrencies()) {
                TrialBalanceResponse balance = trialBalance(currency);
                if (!balance.balanced() || !balance.accountsAgreeWithJournal()) {
                    meterRegistry.counter(METRIC_TRIAL_BALANCE_FAILED, "currency", currency)
                            .increment();
                }
            }
        } catch (RuntimeException ex) {
            // A failing monitor must not take the service down, but it must be loud: a silent
            // monitor is indistinguishable from a passing one.
            log.error("Scheduled trial-balance verification could not complete", ex);
        }
    }
}
