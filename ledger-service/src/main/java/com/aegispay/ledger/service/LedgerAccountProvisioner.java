package com.aegispay.ledger.service;

import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.domain.SharedEnums.AccountType;
import com.aegispay.common.events.payload.LedgerPostingPayload;
import com.aegispay.common.exception.DomainExceptions.BadRequestException;
import com.aegispay.common.exception.DomainExceptions.NotFoundException;
import com.aegispay.ledger.domain.LedgerAccount;
import com.aegispay.ledger.repository.LedgerAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Creates customer wallet accounts on first use.
 *
 * <p>System accounts (fee revenue, external funding, clearing, provider expense) are created
 * by the migration, because their type and sign behaviour are accounting decisions that
 * belong in a reviewed change rather than in whichever code path touches them first.
 * Customer wallet accounts cannot work that way: they are unbounded in number and appear
 * whenever someone opens a wallet, so they are provisioned here the first time a posting
 * needs one.
 *
 * <p><strong>Why {@link Propagation#REQUIRES_NEW}, and why this is a separate bean.</strong>
 * Two concurrent postings involving the same new wallet will both find no account and both
 * try to insert one. One loses on the unique constraint — and in PostgreSQL a constraint
 * violation aborts the entire transaction, so if that insert shared the posting's
 * transaction the whole posting would be lost to a collision over an empty account row.
 * Committing provisioning separately, before the posting starts, keeps that failure
 * contained: the loser simply re-reads the winner's row.
 *
 * <p>A separate bean is mandatory rather than tidy. Spring applies transaction advice
 * through a proxy, so {@code this.ensureExists(...)} from inside the posting service would
 * silently run in the caller's transaction and the propagation would be ignored — the same
 * trap that made the auth service's refresh-token revocation a no-op.
 *
 * <p>An account created for a posting that then fails is harmless: it has a zero balance,
 * contributes nothing to the trial balance, and will be used by the retry.
 */
@Service
public class LedgerAccountProvisioner {

    private static final Logger log = LoggerFactory.getLogger(LedgerAccountProvisioner.class);

    private final LedgerAccountRepository accounts;

    public LedgerAccountProvisioner(LedgerAccountRepository accounts) {
        this.accounts = accounts;
    }

    /**
     * Prefix identifying a customer wallet account. Accounts under this prefix are created
     * on demand; everything else must have been declared by a migration.
     */
    public static final String WALLET_PREFIX = "wallet:";

    /**
     * Ensures every account a posting names exists and is committed, before the posting
     * transaction opens.
     *
     * <p>The split is by account ID prefix rather than by the type the caller declared on
     * the line, because the caller's declaration is exactly what must not be trusted to
     * bring a new account into existence: a line that mislabelled {@code wallet:X} as an
     * ASSET would otherwise create it as one and invert every future posting against it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureAccountsFor(LedgerPostingPayload posting) {
        Set<String> seen = new LinkedHashSet<>();
        for (LedgerPostingPayload.PostingLine line : posting.lines()) {
            if (!seen.add(line.accountId())) {
                continue;
            }
            if (line.accountId().startsWith(WALLET_PREFIX)) {
                if (line.accountType() != AccountType.LIABILITY) {
                    // A wallet is what the platform owes a customer. Booking it as anything
                    // else reverses the meaning of every debit and credit against it.
                    throw new BadRequestException(
                            "account " + line.accountId() + " is a customer wallet and must be a "
                                    + "LIABILITY, but the posting declares it "
                                    + line.accountType());
                }
                ensureWalletAccount(line.accountId(), posting.currency());
            } else {
                requireSystemAccount(line.accountId());
            }
        }
    }

    /**
     * Creates a customer wallet liability account if it does not exist yet.
     *
     * <p>The native insert uses PostgreSQL {@code ON CONFLICT DO NOTHING}, so losing a
     * concurrent creation race does not poison this transaction. Both callers can safely
     * read and validate the canonical row afterward.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LedgerAccount ensureWalletAccount(String accountId, String currency) {
        int inserted = accounts.insertWalletAccountIfAbsent(accountId, currency);
        if (inserted == 1) {
            log.info("Opened ledger account {} ({})", accountId, currency);
        }

        LedgerAccount account = accounts.findByAccountId(accountId).orElseThrow(() ->
                new IllegalStateException("ledger account " + accountId
                        + " could neither be created nor found"));
        if (account.getAccountType() != AccountType.LIABILITY) {
            throw new IllegalStateException("wallet account " + accountId + " is stored as "
                    + account.getAccountType() + " instead of LIABILITY");
        }
        if (!account.getCurrency().equalsIgnoreCase(currency)) {
            throw new BadRequestException("account " + accountId + " is denominated in "
                    + account.getCurrency() + " but the posting is " + currency);
        }
        return account;
    }

    /**
     * Asserts that a system account exists.
     *
     * <p>Deliberately does <em>not</em> create one. A missing system account means the chart
     * of accounts is incomplete — a currency was enabled in the API without a corresponding
     * migration, say — and inventing it here would guess at its type and its sign behaviour.
     * A wrong account type silently inverts every posting that touches it, so failing is the
     * only safe response.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public LedgerAccount requireSystemAccount(String accountId) {
        return accounts.findByAccountId(accountId).orElseThrow(() -> new NotFoundException(
                ErrorCode.RESOURCE_NOT_FOUND,
                "Ledger account " + accountId + " does not exist. System accounts are created by "
                        + "migration, never inferred: guessing its account type would invert every "
                        + "posting that touches it."));
    }
}
