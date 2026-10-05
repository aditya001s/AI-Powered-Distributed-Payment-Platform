package com.aegispay.ledger.repository;

import com.aegispay.common.domain.SharedEnums.AccountType;
import com.aegispay.ledger.domain.LedgerAccount;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, Long> {

    Optional<LedgerAccount> findByAccountId(String accountId);

    boolean existsByAccountId(String accountId);

    /** Atomically creates a zero-balance wallet account without aborting on a concurrent winner. */
    @Modifying
    @Query(value = """
            INSERT INTO ledger_accounts
                (account_id, account_type, currency, allows_negative, system_account, description)
            VALUES (:accountId, 'LIABILITY', upper(:currency), false, false,
                    'Customer wallet liability')
            ON CONFLICT (account_id) DO NOTHING
            """, nativeQuery = true)
    int insertWalletAccountIfAbsent(@Param("accountId") String accountId,
                                    @Param("currency") String currency);

    List<LedgerAccount> findByCurrencyOrderByAccountIdAsc(String currency);

    List<LedgerAccount> findByAccountTypeAndCurrencyOrderByAccountIdAsc(AccountType accountType,
                                                                       String currency);

    /**
     * Loads an account with its row locked for update.
     *
     * <p>Posting to an account is read-modify-write on its running totals. Without the
     * lock, two concurrent postings both read a balance of ₹100, both add their own
     * movement to that stale value, and one increment is lost — the cached balance then
     * disagrees with the journal that justifies it. The
     * {@code ck_ledger_accounts_balance_derived} CHECK constraint would eventually catch
     * that, but as an opaque commit failure rather than as correct serialisation, so the
     * lock is what makes concurrent postings actually work rather than merely fail safely.
     *
     * <p>A 3-second timeout rather than waiting indefinitely: a caller stuck behind a
     * long-running transaction should fail fast and let the client retry instead of
     * holding a request thread until the connection gives up.
     *
     * <p>Must be called inside a transaction. A lock taken outside one is released
     * immediately and is therefore meaningless.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("SELECT a FROM LedgerAccount a WHERE a.accountId = :accountId")
    Optional<LedgerAccount> findByAccountIdForUpdate(@Param("accountId") String accountId);

    // ------------------------------------------------------- reconciliation

    /**
     * Total debits recorded across every account in one currency.
     *
     * <p>Half of the trial balance. Stated in raw debit/credit terms rather than in natural
     * balances so the check does not depend on the per-account-type sign rules it is
     * partly there to validate.
     */
    @Query("""
            SELECT COALESCE(SUM(a.debitTotalMinor), 0) FROM LedgerAccount a
            WHERE a.currency = :currency
            """)
    long totalDebitsMinor(@Param("currency") String currency);

    @Query("""
            SELECT COALESCE(SUM(a.creditTotalMinor), 0) FROM LedgerAccount a
            WHERE a.currency = :currency
            """)
    long totalCreditsMinor(@Param("currency") String currency);

    /**
     * Total of all customer wallet liability balances in one currency.
     *
     * <p>This is the figure that must equal wallet-service's own sum of wallet balances.
     * A divergence means money was created or destroyed on one side of the platform
     * without the other knowing, which is the single most important discrepancy to be able
     * to detect.
     */
    @Query("""
            SELECT COALESCE(SUM(a.balanceMinor), 0) FROM LedgerAccount a
            WHERE a.currency = :currency AND a.systemAccount = false
              AND a.accountType = com.aegispay.common.domain.SharedEnums.AccountType.LIABILITY
            """)
    long totalCustomerLiabilityMinor(@Param("currency") String currency);

    /** Every currency the chart of accounts currently uses, so reconciliation can iterate. */
    @Query("SELECT DISTINCT a.currency FROM LedgerAccount a ORDER BY a.currency")
    List<String> distinctCurrencies();

    long countBySystemAccount(boolean systemAccount);
}
