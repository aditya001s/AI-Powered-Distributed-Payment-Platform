package com.aegispay.fraud.repository;

import com.aegispay.fraud.domain.PaymentObservation;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The behavioural feature pipeline, expressed as SQL.
 *
 * <p><strong>Every query excludes the payment being scored.</strong> The saga calls fraud-service
 * milliseconds after {@code payment-created} is published, so the observation for the current
 * payment may or may not have been consumed yet. Excluding it explicitly by id is what makes the
 * score independent of that race — without it, the same payment would score differently depending on
 * which consumer won, and the feature contract ml-service was trained against says these windows are
 * <em>prior</em> to the payment.
 *
 * <p>Aggregates are counted over <em>attempts</em>, not successes, except where a query says
 * otherwise. A burst of declined attempts is one of the strongest card-testing signals there is, and
 * counting only what succeeded would discard exactly the evidence that matters.
 */
public interface PaymentObservationRepository extends JpaRepository<PaymentObservation, String> {

    Optional<PaymentObservation> findByPaymentId(String paymentId);

    boolean existsByPaymentId(String paymentId);

    // ----------------------------------------------------------------- velocity

    /**
     * Prior attempts by this account in a window.
     *
     * <p>Serves both the 1-hour and the 24-hour feature: one query with two windows rather than two
     * near-identical queries that could drift in their exclusion logic.
     */
    @Query("""
            SELECT COUNT(o) FROM PaymentObservation o
            WHERE o.initiatorUserId = :userId
              AND o.paymentId <> :excludedPaymentId
              AND o.occurredAt >= :since
            """)
    long countAttemptsSince(@Param("userId") String userId,
                            @Param("excludedPaymentId") String excludedPaymentId,
                            @Param("since") Instant since);

    /**
     * Prior total attempted in a window, in minor units.
     *
     * <p>{@code COALESCE} so an account with no history gets zero rather than null. Zero is the
     * truthful answer here — "nothing attempted yet" — unlike the average amount, where zero would
     * be a lie and null is correct.
     */
    @Query("""
            SELECT COALESCE(SUM(o.amountMinor), 0) FROM PaymentObservation o
            WHERE o.initiatorUserId = :userId
              AND o.paymentId <> :excludedPaymentId
              AND o.occurredAt >= :since
            """)
    long sumAmountSince(@Param("userId") String userId,
                        @Param("excludedPaymentId") String excludedPaymentId,
                        @Param("since") Instant since);

    /**
     * Prior attempts in a window that did not succeed.
     *
     * <p>Counts {@code FAILED} and {@code REJECTED} but not {@code PENDING}: a payment still in
     * flight has not failed, and counting it would make a burst of concurrent legitimate payments
     * look like a burst of declines.
     */
    @Query("""
            SELECT COUNT(o) FROM PaymentObservation o
            WHERE o.initiatorUserId = :userId
              AND o.paymentId <> :excludedPaymentId
              AND o.occurredAt >= :since
              AND o.outcome IN (
                    com.aegispay.fraud.domain.PaymentObservation$Outcome.FAILED,
                    com.aegispay.fraud.domain.PaymentObservation$Outcome.REJECTED)
            """)
    long countFailuresSince(@Param("userId") String userId,
                            @Param("excludedPaymentId") String excludedPaymentId,
                            @Param("since") Instant since);

    // ------------------------------------------------------------ account norms

    /**
     * How this account normally behaves: attempt count, mean amount, largest amount, first seen.
     *
     * <p>One projection rather than four queries, because all four feed the same scoring request and
     * four round trips on the critical path would cost more than the scoring itself.
     *
     * <p>Restricted to {@code COMPLETED} payments deliberately. The account's "norm" must be what it
     * actually transacted, not what somebody attempted on it — otherwise a card-testing burst would
     * raise the account's own baseline and make the next fraudulent payment look proportionate.
     *
     * @return {@code [count, avgAmountMinor, maxAmountMinor, firstSeenAt]}; the averages are null for
     *         an account with no completed history, which the feature contract requires to stay null
     *         rather than becoming zero
     */
    @Query("""
            SELECT COUNT(o), AVG(o.amountMinor), MAX(o.amountMinor), MIN(o.occurredAt)
            FROM PaymentObservation o
            WHERE o.initiatorUserId = :userId
              AND o.paymentId <> :excludedPaymentId
              AND o.outcome = com.aegispay.fraud.domain.PaymentObservation$Outcome.COMPLETED
            """)
    List<Object[]> summariseCompletedHistory(@Param("userId") String userId,
                                             @Param("excludedPaymentId") String excludedPaymentId);

    /**
     * When this account was first seen at all.
     *
     * <p>Separate from the completed-history projection because account age must count from the
     * first *attempt*: an account created an hour ago whose only payments failed is a day-zero
     * account, and reading its age from completed payments alone would report no age at all.
     */
    @Query("""
            SELECT MIN(o.occurredAt) FROM PaymentObservation o
            WHERE o.initiatorUserId = :userId
            """)
    Optional<Instant> firstSeenAt(@Param("userId") String userId);

    /** Confirmed fraud on this account, the platform's honest stand-in for chargebacks. */
    @Query("""
            SELECT COUNT(o) FROM PaymentObservation o
            WHERE o.initiatorUserId = :userId
              AND o.paymentId <> :excludedPaymentId
              AND o.confirmedFraud = true
            """)
    long countConfirmedFraud(@Param("userId") String userId,
                             @Param("excludedPaymentId") String excludedPaymentId);

    // ------------------------------------------------------- device and location

    /**
     * Whether this account has used this device before.
     *
     * <p>Matches on either identifier, because a client may supply a fingerprint, a device id, or
     * both, and treating a fingerprint-only match as a new device would flag every returning
     * customer on a channel that omits the id.
     */
    @Query("""
            SELECT COUNT(o) FROM PaymentObservation o
            WHERE o.initiatorUserId = :userId
              AND o.paymentId <> :excludedPaymentId
              AND (
                    (:deviceFingerprint IS NOT NULL AND o.deviceFingerprint = :deviceFingerprint)
                 OR (:deviceId IS NOT NULL AND o.deviceId = :deviceId))
            """)
    long countPriorUsesOfDevice(@Param("userId") String userId,
                                @Param("excludedPaymentId") String excludedPaymentId,
                                @Param("deviceFingerprint") String deviceFingerprint,
                                @Param("deviceId") String deviceId);

    @Query("""
            SELECT COUNT(DISTINCT COALESCE(o.deviceFingerprint, o.deviceId))
            FROM PaymentObservation o
            WHERE o.initiatorUserId = :userId
              AND o.paymentId <> :excludedPaymentId
              AND o.occurredAt >= :since
              AND (o.deviceFingerprint IS NOT NULL OR o.deviceId IS NOT NULL)
            """)
    long countDistinctDevicesSince(@Param("userId") String userId,
                                   @Param("excludedPaymentId") String excludedPaymentId,
                                   @Param("since") Instant since);

    @Query("""
            SELECT COUNT(o) FROM PaymentObservation o
            WHERE o.initiatorUserId = :userId
              AND o.paymentId <> :excludedPaymentId
              AND o.countryCode = :countryCode
            """)
    long countPriorPaymentsFromCountry(@Param("userId") String userId,
                                       @Param("excludedPaymentId") String excludedPaymentId,
                                       @Param("countryCode") String countryCode);

    @Query("""
            SELECT COUNT(DISTINCT o.countryCode) FROM PaymentObservation o
            WHERE o.initiatorUserId = :userId
              AND o.paymentId <> :excludedPaymentId
              AND o.occurredAt >= :since
              AND o.countryCode IS NOT NULL
            """)
    long countDistinctCountriesSince(@Param("userId") String userId,
                                     @Param("excludedPaymentId") String excludedPaymentId,
                                     @Param("since") Instant since);

    /**
     * The most recent prior payment that had coordinates, for the distance feature.
     *
     * <p>"Most recent <em>with</em> geo" rather than "most recent": if the last three payments came
     * from a channel that supplies no location, the distance from the last *known* location is still
     * the meaningful comparison, and reporting no distance at all would silently drop the feature
     * for that customer.
     */
    @Query("""
            SELECT o FROM PaymentObservation o
            WHERE o.initiatorUserId = :userId
              AND o.paymentId <> :excludedPaymentId
              AND o.latitude IS NOT NULL
              AND o.longitude IS NOT NULL
            ORDER BY o.occurredAt DESC
            """)
    List<PaymentObservation> findRecentWithLocation(@Param("userId") String userId,
                                                    @Param("excludedPaymentId") String excludedPaymentId,
                                                    Limit limit);

    /** The previous attempt, for time-since-last-payment. */
    @Query("""
            SELECT o.occurredAt FROM PaymentObservation o
            WHERE o.initiatorUserId = :userId
              AND o.paymentId <> :excludedPaymentId
            ORDER BY o.occurredAt DESC
            """)
    List<Instant> findRecentAttemptTimes(@Param("userId") String userId,
                                         @Param("excludedPaymentId") String excludedPaymentId,
                                         Limit limit);

    // ------------------------------------------------------------- counterparty

    @Query("""
            SELECT COUNT(o) FROM PaymentObservation o
            WHERE o.initiatorUserId = :userId
              AND o.paymentId <> :excludedPaymentId
              AND o.destinationWalletId = :destinationWalletId
            """)
    long countPriorPaymentsToPayee(@Param("userId") String userId,
                                   @Param("excludedPaymentId") String excludedPaymentId,
                                   @Param("destinationWalletId") String destinationWalletId);
}
