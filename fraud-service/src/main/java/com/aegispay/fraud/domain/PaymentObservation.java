package com.aegispay.fraud.domain;

import com.aegispay.common.domain.SharedEnums.PaymentType;
import com.aegispay.common.events.payload.PaymentCreatedPayload;
import com.aegispay.common.events.payload.RiskSignals;
import com.aegispay.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One payment this service has heard about, as the feature pipeline needs it.
 *
 * <p><strong>Why fraud-service keeps its own history.</strong> The model needs behavioural
 * aggregates — payments in the last hour, whether this device is new, how far from the last known
 * location. Fetching those from payment-service and wallet-service at scoring time would put two
 * more network calls on the payment's critical path and would make a fraud decision fail whenever
 * an unrelated service was redeploying. So this service consumes the events it already receives
 * and derives the aggregates from local SQL.
 *
 * <p><strong>Deliberately not a copy of the payment aggregate.</strong> It holds only the columns
 * the features read. A read model that mirrored payment-service's table would become a second
 * source of truth, and somebody would eventually query it for something it is not authoritative
 * about.
 *
 * <p>Every risk-context column is nullable, and that is load-bearing rather than lax: an
 * API-channel payment genuinely has no device and a customer who denied location permission
 * genuinely has no coordinates. ml-service is trained to treat absence as its own signal, so
 * defaulting a null here would feed it a value that is not true.
 */
@Entity
@Table(name = "payment_observations")
public class PaymentObservation {

    /** What became of the payment. The velocity features distinguish these. */
    public enum Outcome {
        /** Announced but not yet resolved. */
        PENDING,
        COMPLETED,
        /** Technical failure. */
        FAILED,
        /** Refused, by every provider or by fraud controls. */
        REJECTED
    }

    @Id
    @Column(name = "payment_id", nullable = false, updatable = false, length = 48)
    private String paymentId;

    @Column(name = "initiator_user_id", nullable = false, updatable = false, length = 40)
    private String initiatorUserId;

    @Column(name = "destination_wallet_id", nullable = false, updatable = false, length = 40)
    private String destinationWalletId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", nullable = false, updatable = false, length = 24)
    private PaymentType paymentType = PaymentType.P2P_TRANSFER;

    @Column(name = "device_id", updatable = false, length = 96)
    private String deviceId;

    @Column(name = "device_fingerprint", updatable = false, length = 128)
    private String deviceFingerprint;

    @Column(name = "country_code", updatable = false, length = 2)
    private String countryCode;

    @Column(name = "latitude", updatable = false)
    private Double latitude;

    @Column(name = "longitude", updatable = false)
    private Double longitude;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 16)
    private Outcome outcome = Outcome.PENDING;

    /**
     * Confirmed fraud, set when an analyst rejects the payment.
     *
     * <p>This is what the {@code prior_chargeback_count} feature is derived from. The platform has
     * no card network and therefore no chargebacks, so inventing a column called
     * {@code chargebacks} would be describing something that cannot happen here. An analyst's
     * REJECT is the honest equivalent: a human confirming a payment was fraudulent.
     */
    @Column(name = "confirmed_fraud", nullable = false)
    private boolean confirmedFraud;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt = Instant.now();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected PaymentObservation() {
        // JPA
    }

    /**
     * Records a payment from the event that announced it.
     *
     * <p>Takes the whole payload rather than fifteen arguments, because the mapping between the
     * event and this row is exactly the thing worth having in one readable place.
     */
    public static PaymentObservation from(PaymentCreatedPayload payment) {
        Money amount = payment.amount();
        if (amount == null || !amount.isPositive()) {
            throw new IllegalArgumentException(
                    "cannot observe payment " + payment.paymentId()
                            + " with a non-positive amount " + amount);
        }

        RiskSignals signals = payment.riskSignals() == null
                ? RiskSignals.unknown()
                : payment.riskSignals();

        PaymentObservation observation = new PaymentObservation();
        observation.paymentId = payment.paymentId();
        observation.initiatorUserId = payment.initiatorUserId();
        observation.destinationWalletId = payment.destinationWalletId();
        observation.amountMinor = amount.amountMinor();
        observation.currency = amount.currency();
        observation.paymentType = payment.paymentType() == null
                ? PaymentType.P2P_TRANSFER
                : payment.paymentType();

        observation.deviceId = signals.deviceId();
        observation.deviceFingerprint = signals.deviceFingerprint();
        observation.countryCode = signals.countryCode();
        // Both coordinates or neither: one alone cannot produce a distance, and the schema
        // refuses the half-populated pair rather than letting it silently disable the feature.
        if (signals.hasGeoLocation()) {
            observation.latitude = signals.latitude();
            observation.longitude = signals.longitude();
        }

        observation.outcome = Outcome.PENDING;
        observation.occurredAt = payment.createdAt() == null ? Instant.now() : payment.createdAt();
        observation.createdAt = Instant.now();
        observation.updatedAt = observation.createdAt;
        return observation;
    }

    // ----------------------------------------------------------------- mutators

    /**
     * Records what became of the payment.
     *
     * <p>Ignores a transition away from a resolved outcome. Kafka delivers at least once and the
     * outcome topics are separate, so a redelivered {@code payment-failed} can arrive after a
     * {@code payment-completed}; the first resolution is the true one.
     */
    public void resolved(Outcome resolution) {
        if (resolution == null || resolution == Outcome.PENDING) {
            return;
        }
        if (this.outcome != Outcome.PENDING) {
            return;
        }
        this.outcome = resolution;
        this.updatedAt = Instant.now();
    }

    /** Marks the payment as confirmed fraud after an analyst rejects it. */
    public void confirmFraud() {
        this.confirmedFraud = true;
        this.updatedAt = Instant.now();
    }

    // ------------------------------------------------------------------ queries

    public Money amount() {
        return Money.ofMinor(amountMinor, currency);
    }

    public boolean hasGeoLocation() {
        return latitude != null && longitude != null;
    }

    /** The device identity used for "is this device new", preferring the more specific one. */
    public String deviceKey() {
        if (deviceFingerprint != null && !deviceFingerprint.isBlank()) {
            return deviceFingerprint;
        }
        return deviceId;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getInitiatorUserId() {
        return initiatorUserId;
    }

    public String getDestinationWalletId() {
        return destinationWalletId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentType getPaymentType() {
        return paymentType;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getDeviceFingerprint() {
        return deviceFingerprint;
    }

    public String getCountryCode() {
        return countryCode;
    }

    public Double getLatitude() {
        return latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public boolean isConfirmedFraud() {
        return confirmedFraud;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "PaymentObservation{" + paymentId + ", " + amount() + ", " + outcome + '}';
    }
}
