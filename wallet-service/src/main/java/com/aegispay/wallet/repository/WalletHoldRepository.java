package com.aegispay.wallet.repository;

import com.aegispay.wallet.domain.WalletHold;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletHoldRepository extends JpaRepository<WalletHold, UUID> {

    /**
     * Finds the reservation belonging to a payment.
     *
     * <p>The unique constraint on {@code payment_id} is what makes {@code reserve}
     * idempotent: a retried reservation finds the existing hold here and returns it
     * rather than reserving the amount a second time.
     */
    Optional<WalletHold> findByPaymentId(String paymentId);

    /**
     * Loads a reservation with its row locked, for an extension.
     *
     * <p>Extending is read-modify-write on {@code expires_at}. Without the lock a concurrent release
     * could interleave and leave a RELEASED hold carrying a future expiry — a row that claims funds
     * are reserved when the customer can already spend them.
     *
     * <p>Three-second timeout rather than waiting indefinitely, matching every other lock in the
     * platform: a caller stuck behind a long transaction should fail fast and retry.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("SELECT h FROM WalletHold h WHERE h.paymentId = :paymentId")
    Optional<WalletHold> findByPaymentIdForUpdate(@Param("paymentId") String paymentId);

    Optional<WalletHold> findByHoldId(String holdId);

    List<WalletHold> findByWalletIdAndState(String walletId, WalletHold.State state);

    /**
     * Reservations that have outlived their expiry and are still holding funds.
     *
     * <p>Batched via {@link Limit} so one sweep cannot lock an unbounded number of
     * wallet rows at once — a sweep that tried to release ten thousand holds in a single
     * transaction would block live payments on every wallet involved.
     */
    @Query("""
            SELECT h FROM WalletHold h
            WHERE h.state = 'ACTIVE' AND h.expiresAt < :now
            ORDER BY h.expiresAt
            """)
    List<WalletHold> findExpired(@Param("now") Instant now, Limit limit);

    /**
     * Sum of active reservations on a wallet.
     *
     * <p>Cross-checks the denormalised {@code wallets.held_minor} against the holds that
     * justify it. The two must agree; a divergence means a capture or release updated one
     * without the other.
     */
    @Query("""
            SELECT COALESCE(SUM(h.amountMinor), 0) FROM WalletHold h
            WHERE h.walletId = :walletId AND h.state = 'ACTIVE'
            """)
    long sumActiveHoldsMinor(@Param("walletId") String walletId);

    long countByState(WalletHold.State state);
}
