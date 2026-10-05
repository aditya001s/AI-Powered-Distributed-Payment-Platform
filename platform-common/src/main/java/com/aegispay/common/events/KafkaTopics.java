package com.aegispay.common.events;

import java.util.List;

/**
 * The platform's Kafka topic catalogue.
 *
 * <p><strong>Partition key is always the payment/aggregate ID.</strong> Kafka
 * only guarantees ordering within a partition, so keying by payment ID is what
 * makes {@code payment-approved} strictly precede {@code payment-completed} for
 * a given payment while still allowing different payments to be processed in
 * parallel across partitions.
 *
 * <p><strong>Every topic has a dead-letter twin</strong> named
 * {@code <topic>.DLT}. Consumers retry with exponential backoff and, once
 * attempts are exhausted, publish the record plus its failure metadata there
 * rather than either dropping it or blocking the partition forever.
 *
 * <p>Consumers must be <strong>idempotent</strong>: Kafka delivery is at-least
 * once, so any handler can legitimately see the same event twice (rebalance,
 * retry, redelivery after a crash between processing and commit).
 */
public final class KafkaTopics {

    /** Suffix Spring Kafka's {@code DeadLetterPublishingRecoverer} appends by default. */
    public static final String DLT_SUFFIX = ".DLT";

    // ---- Payment lifecycle ----

    /** Emitted by payment-service via the outbox. Starts the orchestration saga. */
    public static final String PAYMENT_CREATED = "payment-created";

    /** Emitted by fraud-service with the probability, risk bucket and decision. */
    public static final String FRAUD_CHECKED = "fraud-checked";

    /**
     * An analyst released or refused a payment that fraud controls held.
     *
     * <p>Consumed by payment-orchestrator, and the <strong>only</strong> thing that unparks a saga
     * sitting in {@code RISK_HELD}. Emitted through fraud-service's outbox so the decision and the
     * intent to resume commit together: a lost notification would leave the alert resolved with the
     * customer's payment parked forever, and nothing would have failed to point at.
     */
    public static final String FRAUD_DECISION_APPLIED = "fraud-decision-applied";

    /** Fraud cleared the payment; a provider charge is next. */
    public static final String PAYMENT_APPROVED = "payment-approved";

    /** Refused by fraud controls or an analyst. Terminal. */
    public static final String PAYMENT_REJECTED = "payment-rejected";

    /** Provider charged, ledger posted, funds captured. Terminal success. */
    public static final String PAYMENT_COMPLETED = "payment-completed";

    /** Could not complete; compensations have run. Terminal. */
    public static final String PAYMENT_FAILED = "payment-failed";

    /** A completed payment was reversed in whole or in part. */
    public static final String PAYMENT_REFUNDED = "payment-refunded";

    // ---- Wallet ----

    /**
     * A balance movement that crossed the platform boundary: a deposit in or a
     * withdrawal out. Consumed by ledger-service, which books the matching
     * double entry against the external-funding asset account.
     *
     * <p>Deliberately <strong>not</strong> emitted for internal transfers. A
     * payment's transfer leg is already described by {@code payment-completed},
     * and emitting it here as well would book the same movement twice — the
     * obvious failure mode for a ledger fed from two event streams. The dividing
     * line is ownership: wallet-service owns movements at the platform's edge,
     * payment-service owns movements between two wallets inside it.
     */
    public static final String WALLET_MOVEMENT_RECORDED = "wallet-movement-recorded";

    // ---- Ledger ----

    /** Balanced double-entry posting request consumed by ledger-service. */
    public static final String LEDGER_POSTING_REQUESTED = "ledger-posting-requested";

    /** Confirmation that a posting was durably committed to the journal. */
    public static final String LEDGER_POSTED = "ledger-posted";

    // ---- Intelligence ----

    /** A HIGH/CRITICAL risk transaction needs an AI investigation. */
    public static final String INVESTIGATION_REQUESTED = "investigation-requested";

    /** The AI investigation narrative and recommendation are ready. */
    public static final String INVESTIGATION_COMPLETED = "investigation-completed";

    // ---- Supporting ----

    /** Fan-out topic consumed by notification-service. */
    public static final String NOTIFICATION_REQUIRED = "notification-required";

    /**
     * Every topic the platform owns. Used by the topic-provisioning bean so a
     * fresh cluster comes up fully configured instead of relying on Kafka's
     * auto-create (which would apply defaults we did not choose).
     */
    public static final List<String> ALL = List.of(
            PAYMENT_CREATED,
            FRAUD_CHECKED,
            FRAUD_DECISION_APPLIED,
            PAYMENT_APPROVED,
            PAYMENT_REJECTED,
            PAYMENT_COMPLETED,
            PAYMENT_FAILED,
            PAYMENT_REFUNDED,
            WALLET_MOVEMENT_RECORDED,
            LEDGER_POSTING_REQUESTED,
            LEDGER_POSTED,
            INVESTIGATION_REQUESTED,
            INVESTIGATION_COMPLETED,
            NOTIFICATION_REQUIRED);

    private KafkaTopics() {
    }

    /** The dead-letter topic paired with {@code topic}. */
    public static String deadLetterFor(String topic) {
        return topic.endsWith(DLT_SUFFIX) ? topic : topic + DLT_SUFFIX;
    }

    public static boolean isDeadLetter(String topic) {
        return topic != null && topic.endsWith(DLT_SUFFIX);
    }
}
