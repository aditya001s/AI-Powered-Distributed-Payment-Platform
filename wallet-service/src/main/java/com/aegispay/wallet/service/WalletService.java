package com.aegispay.wallet.service;

import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.api.PageResponse;
import com.aegispay.common.exception.DomainExceptions.BadRequestException;
import com.aegispay.common.exception.DomainExceptions.ConflictException;
import com.aegispay.common.exception.DomainExceptions.NotFoundException;
import com.aegispay.common.idempotency.IdempotencyService;
import com.aegispay.common.idempotency.RequestFingerprint;
import com.aegispay.common.money.Money;
import com.aegispay.wallet.api.WalletDtos.FundsMovementRequest;
import com.aegispay.wallet.api.WalletDtos.InternalWalletView;
import com.aegispay.wallet.api.WalletDtos.MovementResponse;
import com.aegispay.wallet.api.WalletDtos.ReconciliationResponse;
import com.aegispay.wallet.api.WalletDtos.TransactionResponse;
import com.aegispay.wallet.api.WalletDtos.WalletLiabilitySnapshot;
import com.aegispay.wallet.api.WalletDtos.WalletResponse;
import com.aegispay.wallet.domain.Wallet;
import com.aegispay.wallet.repository.WalletHoldRepository;
import com.aegispay.wallet.repository.WalletRepository;
import com.aegispay.wallet.repository.WalletTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;

/**
 * Wallet queries and the orchestration of customer-initiated funds movement.
 *
 * <p><strong>Deliberately split from {@link WalletMutationService}.</strong> Writes here
 * are wrapped in idempotency, whose claim must be committed <em>before</em> the balance
 * changes so a concurrent duplicate can be detected. That forces the write path to be
 * non-transactional at this level, with the actual balance mutation happening in a short
 * transaction behind a real bean boundary — a {@code this.method()} call would bypass the
 * proxy and leave the mutation untransacted.
 *
 * <p>Read methods are {@code readOnly = true}, which lets the driver and Hibernate skip
 * dirty-checking and flush on a path that never writes.
 */
@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    /** Idempotency scopes, namespaced per operation so one client key cannot collide. */
    private static final String SCOPE_DEPOSIT = "wallet.deposit";
    private static final String SCOPE_WITHDRAW = "wallet.withdraw";

    private final WalletRepository wallets;
    private final WalletTransactionRepository transactions;
    private final WalletHoldRepository holds;
    private final WalletMutationService mutations;
    private final IdempotencyService idempotency;
    private final SecureRandom secureRandom = new SecureRandom();

    public WalletService(WalletRepository wallets,
                         WalletTransactionRepository transactions,
                         WalletHoldRepository holds,
                         WalletMutationService mutations,
                         IdempotencyService idempotency) {
        this.wallets = wallets;
        this.transactions = transactions;
        this.holds = holds;
        this.mutations = mutations;
        this.idempotency = idempotency;
    }

    // ------------------------------------------------------------- lifecycle

    /**
     * Opens a wallet for a user in a currency.
     *
     * <p>The unique constraint on {@code (user_id, currency)} is the guarantee, not the
     * pre-check: two concurrent requests can both find nothing and one loses at insert.
     * That matters more than usual, because two wallets in one currency would let a user
     * split funds and defeat per-wallet balance checks.
     */
    @Transactional
    public WalletResponse openWallet(String userId, String currency) {
        String normalised = normaliseCurrency(currency);

        if (wallets.existsByUserIdAndCurrency(userId, normalised)) {
            throw walletAlreadyExists(normalised);
        }
        try {
            Wallet wallet = wallets.save(Wallet.open(generateWalletId(), userId, normalised));
            log.info("Opened wallet {} for user {} in {}", wallet.getWalletId(), userId, normalised);
            return WalletResponse.from(wallet);
        } catch (DataIntegrityViolationException ex) {
            // Lost the race; reported identically so the outcome does not depend on timing.
            throw walletAlreadyExists(normalised);
        }
    }

    @Transactional(readOnly = true)
    public List<WalletResponse> listWallets(String userId) {
        return wallets.findByUserIdOrderByCurrencyAsc(userId).stream()
                .map(WalletResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public WalletResponse getWallet(String walletId, String requestingUserId) {
        return WalletResponse.from(requireOwnedWallet(walletId, requestingUserId));
    }

    /**
     * The caller's balance in one currency.
     *
     * <p>Resolved from the authenticated user plus a currency rather than a
     * client-supplied wallet ID, so this endpoint cannot be aimed at another user's wallet.
     */
    @Transactional(readOnly = true)
    public WalletResponse getBalance(String userId, String currency) {
        String normalised = normaliseCurrency(currency);
        return wallets.findByUserIdAndCurrency(userId, normalised)
                .map(WalletResponse::from)
                .orElseThrow(() -> new NotFoundException(ErrorCode.WALLET_NOT_FOUND,
                        "No " + normalised + " wallet exists for this user"));
    }

    @Transactional(readOnly = true)
    public PageResponse<TransactionResponse> statement(String walletId, String requestingUserId,
                                                       Pageable pageable) {
        requireOwnedWallet(walletId, requestingUserId);
        return PageResponse.of(
                transactions.findByWalletIdOrderByCreatedAtDesc(walletId, pageable),
                TransactionResponse::from);
    }

    // -------------------------------------------------------- funds movement

    /**
     * Credits a wallet, at most once per idempotency key.
     *
     * <p>Simulates an inbound top-up; this platform never contacts a real bank. A retried
     * deposit whose response was lost must not credit twice, which is what the idempotency
     * wrapper guarantees.
     */
    public MovementResponse deposit(String userId, String walletId,
                                    FundsMovementRequest request, String idempotencyKey) {
        Money amount = request.toMoney();
        // The fingerprint covers every business-significant field, so replaying this key
        // with a different amount or wallet is rejected instead of returning this response.
        String fingerprint = RequestFingerprint.of(
                userId, walletId, amount.amountMinor(), amount.currency(), "DEPOSIT");

        return idempotency.execute(SCOPE_DEPOSIT, idempotencyKey, fingerprint,
                MovementResponse.class,
                () -> mutations.deposit(walletId, userId, amount, request.description(),
                        SCOPE_DEPOSIT + ":" + idempotencyKey, fingerprint));
    }

    /**
     * Debits a wallet, at most once per idempotency key.
     *
     * <p>Validated against <em>available</em> funds, so a withdrawal cannot spend money
     * already reserved by an in-flight payment.
     */
    public MovementResponse withdraw(String userId, String walletId,
                                     FundsMovementRequest request, String idempotencyKey) {
        Money amount = request.toMoney();
        String fingerprint = RequestFingerprint.of(
                userId, walletId, amount.amountMinor(), amount.currency(), "WITHDRAWAL");

        return idempotency.execute(SCOPE_WITHDRAW, idempotencyKey, fingerprint,
                MovementResponse.class,
                () -> mutations.withdraw(walletId, userId, amount, request.description(),
                        SCOPE_WITHDRAW + ":" + idempotencyKey, fingerprint));
    }

    @Transactional(readOnly = true)
    public WalletLiabilitySnapshot liabilitySnapshot(String currency) {
        String normalised = normaliseCurrency(currency);
        return new WalletLiabilitySnapshot(
                normalised,
                wallets.totalBalanceMinor(normalised),
                wallets.totalHeldMinor(normalised),
                Instant.now());
    }

    // ------------------------------------------------------------- internal

    /** Minimal wallet view for service-to-service callers. */
    @Transactional(readOnly = true)
    public InternalWalletView internalView(String walletId) {
        return InternalWalletView.from(wallets.findByWalletId(walletId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.WALLET_NOT_FOUND,
                        "Wallet " + walletId + " was not found")));
    }

    /**
     * Resolves a user's wallet in a currency, for callers that know the user but not the
     * wallet ID — which is the normal case when a payment names a payee by user.
     */
    @Transactional(readOnly = true)
    public InternalWalletView internalViewByUser(String userId, String currency) {
        String normalised = normaliseCurrency(currency);
        return InternalWalletView.from(wallets.findByUserIdAndCurrency(userId, normalised)
                .orElseThrow(() -> new NotFoundException(ErrorCode.WALLET_NOT_FOUND,
                        "No " + normalised + " wallet exists for user " + userId)));
    }

    // ------------------------------------------------------- administration

    /**
     * Verifies a wallet's stored balance against its own history.
     *
     * <p>Two independent checks: the stored balance must equal the signed sum of every
     * recorded movement, and the stored held total must equal the sum of its ACTIVE holds.
     *
     * <p>A mismatch means either a balance was mutated without recording a movement, or a
     * movement was recorded that never applied. Both are silent failures that otherwise
     * surface only as a customer complaint, which is why this is queryable on demand
     * rather than only computed by a nightly job.
     */
    @Transactional(readOnly = true)
    public ReconciliationResponse reconcile(String walletId) {
        Wallet wallet = wallets.findByWalletId(walletId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.WALLET_NOT_FOUND,
                        "Wallet " + walletId + " was not found"));

        long replayed = transactions.replayBalanceMinor(walletId);
        long summedHolds = holds.sumActiveHoldsMinor(walletId);

        boolean balanceMatches = wallet.getBalanceMinor() == replayed;
        boolean heldMatches = wallet.getHeldMinor() == summedHolds;

        if (!balanceMatches || !heldMatches) {
            // ERROR because this should be impossible; it needs a human, not a dashboard.
            log.error("""
                            RECONCILIATION MISMATCH on wallet {}: stored balance={} replayed={} \
                            | stored held={} summed active holds={}""",
                    walletId, wallet.getBalanceMinor(), replayed,
                    wallet.getHeldMinor(), summedHolds);
        }

        return new ReconciliationResponse(walletId,
                wallet.getBalanceMinor(), replayed,
                wallet.getHeldMinor(), summedHolds,
                balanceMatches, heldMatches,
                balanceMatches && heldMatches);
    }

    // ------------------------------------------------------------- internals

    private Wallet requireOwnedWallet(String walletId, String requestingUserId) {
        Wallet wallet = wallets.findByWalletId(walletId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.WALLET_NOT_FOUND,
                        "Wallet " + walletId + " was not found"));

        if (!wallet.getUserId().equals(requestingUserId)) {
            log.warn("User {} attempted to access wallet {} owned by {}",
                    requestingUserId, walletId, wallet.getUserId());
            // Not-found rather than forbidden: confirming a wallet exists but belongs to
            // someone else is itself an information leak, and wallet IDs are random
            // precisely so they cannot be enumerated.
            throw new NotFoundException(ErrorCode.WALLET_NOT_FOUND,
                    "Wallet " + walletId + " was not found");
        }
        return wallet;
    }

    private ConflictException walletAlreadyExists(String currency) {
        return new ConflictException(ErrorCode.WALLET_ALREADY_EXISTS,
                "A " + currency + " wallet already exists for this user");
    }

    private String normaliseCurrency(String currency) {
        String normalised = currency == null ? "" : currency.trim().toUpperCase();
        try {
            java.util.Currency.getInstance(normalised);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException(ErrorCode.UNSUPPORTED_CURRENCY,
                    normalised + " is not a recognised ISO-4217 currency");
        }
        return normalised;
    }

    /** Opaque, random wallet ID. Non-sequential so IDs cannot be enumerated. */
    private String generateWalletId() {
        byte[] bytes = new byte[12];
        secureRandom.nextBytes(bytes);
        StringBuilder builder = new StringBuilder("WAL-");
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString().toUpperCase();
    }
}
