package com.aegispay.wallet.repository;

import com.aegispay.wallet.domain.Wallet;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

    Optional<Wallet> findByWalletId(String walletId);

    Optional<Wallet> findByUserIdAndCurrency(String userId, String currency);

    List<Wallet> findByUserIdOrderByCurrencyAsc(String userId);

    boolean existsByUserIdAndCurrency(String userId, String currency);

    /**
     * Loads a wallet with its row locked for update.
     *
     * <p><strong>This is where balance correctness actually comes from.</strong> Every
     * mutation is read-modify-write: read the balance, check it is sufficient, write the
     * new value. Without a lock, two concurrent payments both read a balance of ₹100,
     * both decide ₹80 is affordable, and both write ₹20 — the customer spends ₹160 they
     * did not have. {@code SELECT ... FOR UPDATE} makes the second transaction wait for
     * the first to commit, so it sees the true ₹20 and correctly refuses.
     *
     * <p>The Redis distributed lock in the service layer sits in front of this to reduce
     * contention, but it is <em>not</em> the guarantee: Redis failover can hand the same
     * lease to two holders. The database is the arbiter.
     *
     * <p>A 3-second timeout is set rather than waiting indefinitely. A caller blocked
     * behind a stuck transaction should fail fast and return 409 so the client can
     * retry, instead of holding a request thread until the connection times out.
     *
     * <p>Must be called inside a transaction; a lock outside one is released immediately
     * and therefore meaningless.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("SELECT w FROM Wallet w WHERE w.walletId = :walletId")
    Optional<Wallet> findByWalletIdForUpdate(@Param("walletId") String walletId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("SELECT w FROM Wallet w WHERE w.userId = :userId AND w.currency = :currency")
    Optional<Wallet> findByUserAndCurrencyForUpdate(@Param("userId") String userId,
                                                    @Param("currency") String currency);

    /**
     * Platform-wide total of customer funds, per currency.
     *
     * <p>Used by the reconciliation check: this total must equal the sum of wallet
     * liability balances in the double-entry ledger. A divergence means money has been
     * created or destroyed somewhere, which is the single most important thing to be
     * able to detect.
     */
    @Query("""
            SELECT COALESCE(SUM(w.balanceMinor), 0) FROM Wallet w
            WHERE w.currency = :currency AND w.status <> 'CLOSED'
            """)
    long totalBalanceMinor(@Param("currency") String currency);

    @Query("""
            SELECT COALESCE(SUM(w.heldMinor), 0) FROM Wallet w
            WHERE w.currency = :currency AND w.status <> 'CLOSED'
            """)
    long totalHeldMinor(@Param("currency") String currency);

    long countByStatus(Wallet.Status status);
}
