package com.aegispay.orchestrator.provider;

import com.aegispay.common.domain.SharedEnums.PaymentType;
import com.aegispay.common.money.Money;

import java.util.Optional;
import java.util.Set;

/**
 * A payment rail the platform can route a charge through.
 *
 * <p><strong>Every implementation in this project is simulated.</strong> Nothing here connects to
 * a real bank, card network or UPI rail, and no implementation should ever be described as doing
 * so. What is real is the <em>shape</em> of the integration: authorise-then-void semantics,
 * declines that are distinct from faults, per-provider fees, capability limits, and latency that
 * the caller has to defend against.
 *
 * <h2>The contract that matters: declines are not failures</h2>
 *
 * <p>An implementation signals the two outcomes differently, and the difference drives everything
 * downstream:
 *
 * <ul>
 *   <li>A <strong>decline</strong> is returned as {@link AuthorizationResult.Declined}. The
 *       provider worked correctly and said no. It must not count against the provider's health,
 *       must not trip its circuit breaker, and must not be retried — the answer would be the
 *       same.</li>
 *   <li>A <strong>fault</strong> is thrown as {@link ProviderFaultException}. The provider failed
 *       to answer. It counts against health, may trip the breaker, and is worth retrying or
 *       falling back from.</li>
 * </ul>
 *
 * <p>Conflating them is how a payment platform ends up retrying a decline until it accidentally
 * succeeds, or taking a perfectly healthy provider out of service because a run of customers
 * happened to be short of funds.
 *
 * <h2>Idempotency</h2>
 *
 * <p>{@link #authorize} is <em>not</em> assumed idempotent. The saga therefore never retries it
 * blindly: a retry happens only where the fault is known to have occurred before the request
 * reached the provider, and otherwise the saga falls back to a different provider and voids
 * anything the first one may have left behind. That is why {@link #voidAuthorization} must
 * tolerate an unknown authorisation id.
 */
public interface PaymentProvider {

    /** Stable identifier, used in the chart of accounts and in every metric tag. */
    String id();

    /** Human-readable name for the admin view. */
    String displayName();

    /** What this provider will and will not accept. */
    ProviderCapabilities capabilities();

    /**
     * Attempts to authorise a charge.
     *
     * @return {@link AuthorizationResult.Approved} with a handle that can later be voided, or
     *         {@link AuthorizationResult.Declined} when the provider refuses
     * @throws ProviderFaultException when the provider failed to answer at all. Thrown rather
     *         than returned so Resilience4j records it as a failure; a returned decline is
     *         deliberately invisible to the breaker.
     */
    AuthorizationResult authorize(AuthorizationRequest request);

    /**
     * Releases an authorisation.
     *
     * <p>Must tolerate an id it does not recognise, and must be safe to call twice. Compensation
     * legitimately runs more than once, and it also runs when the saga cannot tell whether the
     * original authorisation succeeded — so a void for something that never existed is a normal
     * occurrence, not an error.
     *
     * @throws ProviderFaultException only when the provider could not be reached. An unknown id is
     *         a no-op, not a fault.
     */
    void voidAuthorization(String authorizationId);

    /**
     * Looks up an authorisation by the idempotency key that was submitted with it.
     *
     * <p><strong>This is what makes a timed-out authorisation recoverable.</strong> When a provider
     * accepts a charge and the response is lost, the saga holds no authorisation id and therefore
     * has nothing to void — the charge would sit there indefinitely, invisible. Every real rail
     * offers some form of lookup by merchant reference for exactly this reason, and modelling it is
     * the difference between compensation that works and compensation that hopes.
     *
     * <p>The saga calls this after a timeout, before falling back to another provider, so an orphaned
     * authorisation is discovered and released rather than left behind.
     *
     * @return the authorisation id if this provider is holding one for that key
     * @throws ProviderFaultException when the provider could not be reached. A key it does not know
     *         is an empty result, not a fault.
     */
    Optional<String> findAuthorizationId(String idempotencyKey);

    /**
     * Liveness probe.
     *
     * <p>Deliberately does not move money and does not need a payment. The health monitor calls it
     * on a schedule so a provider that has gone dark is discovered before a customer's payment
     * discovers it.
     *
     * @throws ProviderFaultException when the provider is not answering
     */
    void probe();

    /** What a provider will accept, checked before it is ever considered for routing. */
    record ProviderCapabilities(

            /** ISO-4217 codes this provider settles. */
            Set<String> supportedCurrencies,

            /** Largest single charge, in minor units. */
            long maxAmountMinor,

            /** Smallest single charge, in minor units. */
            long minAmountMinor,

            /** Payment kinds this provider handles. */
            Set<PaymentType> supportedPaymentTypes,

            /**
             * Fee in basis points (1 bp = 0.01%).
             *
             * <p>Charged to the platform, not to either customer. See the ledger's
             * {@code V3__provider_settlement_accounts.sql} for why that choice was made.
             */
            int feeBasisPoints
    ) {

        public ProviderCapabilities {
            supportedCurrencies = Set.copyOf(supportedCurrencies);
            supportedPaymentTypes = Set.copyOf(supportedPaymentTypes);
            if (feeBasisPoints < 0) {
                throw new IllegalArgumentException("feeBasisPoints must not be negative");
            }
            if (minAmountMinor < 0 || maxAmountMinor < minAmountMinor) {
                throw new IllegalArgumentException(
                        "amount limits are inverted: min=" + minAmountMinor
                                + " max=" + maxAmountMinor);
            }
        }

        /** Whether this provider can even be considered for a given charge. */
        public boolean supports(Money amount, PaymentType paymentType) {
            return supportedCurrencies.contains(amount.currency())
                    && amount.amountMinor() >= minAmountMinor
                    && amount.amountMinor() <= maxAmountMinor
                    && supportedPaymentTypes.contains(paymentType);
        }

        /** The fee this provider would charge on an amount, rounded half-up to the minor unit. */
        public Money feeFor(Money amount) {
            return amount.applyBasisPoints(feeBasisPoints);
        }
    }

    /** What the saga asks a provider to authorise. */
    record AuthorizationRequest(
            String paymentId,
            String transactionId,
            Money amount,
            PaymentType paymentType,
            String sourceWalletId,
            String destinationWalletId,
            String description,

            /**
             * The saga's own idempotency key for this charge, passed through to the provider.
             *
             * <p>A real rail would use it to collapse duplicate submissions. It is carried here
             * because omitting it from the interface would make the simulation misleading about
             * what an integration actually has to send.
             */
            String idempotencyKey
    ) {
        public AuthorizationRequest {
            if (amount == null || !amount.isPositive()) {
                throw new IllegalArgumentException("cannot authorise a non-positive amount");
            }
        }
    }

    /** The two answers a working provider can give. */
    sealed interface AuthorizationResult {

        long latencyMs();

        /**
         * The provider accepted the charge.
         *
         * @param authorizationId handle for voiding it later. Never null: without it the
         *                        compensation path would have nothing to release.
         * @param fee             what the provider charged the platform
         */
        record Approved(String authorizationId, String providerReference, Money fee, long latencyMs)
                implements AuthorizationResult {

            public Approved {
                if (authorizationId == null || authorizationId.isBlank()) {
                    throw new IllegalArgumentException(
                            "an approval must return an authorization id, or it cannot be voided");
                }
            }
        }

        /**
         * The provider refused. A correct answer from a working system.
         *
         * @param declineCode stable machine code, e.g. {@code LIMIT_EXCEEDED}
         */
        record Declined(String declineCode, String reason, long latencyMs)
                implements AuthorizationResult {
        }

        default boolean isApproved() {
            return this instanceof Approved;
        }
    }
}
