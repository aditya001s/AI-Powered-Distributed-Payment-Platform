package com.aegispay.wallet.repository;

import com.aegispay.wallet.domain.WalletTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {

    /** The customer's statement: one wallet, newest first. */
    Page<WalletTransaction> findByWalletIdOrderByCreatedAtDesc(String walletId, Pageable pageable);

    /** Every movement caused by one payment, for support and dispute handling. */
    List<WalletTransaction> findByReferenceIdOrderByCreatedAtAsc(String referenceId);

    boolean existsByReferenceIdAndType(String referenceId, WalletTransaction.Type type);

    Optional<WalletTransaction> findByOperationKey(String operationKey);

    /**
     * Recomputes a wallet's balance from its movement history.
     *
     * <p>The reconciliation control for this service: the result must equal the
     * denormalised {@code wallets.balance_minor}. If they diverge, either a balance was
     * mutated without recording a movement, or a movement was recorded that never
     * applied — both silent classes of failure that are otherwise invisible until a
     * customer complains.
     *
     * <p>Signed by direction, so credits add and debits subtract.
     */
    @Query("""
            SELECT COALESCE(SUM(CASE WHEN t.direction = 'CREDIT'
                                     THEN t.amountMinor ELSE -t.amountMinor END), 0)
            FROM WalletTransaction t
            WHERE t.walletId = :walletId
            """)
    long replayBalanceMinor(@Param("walletId") String walletId);
}
