package com.aegispay.common.events.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * A human's decision on a payment that fraud controls held, published on
 * {@code fraud-decision-applied}.
 *
 * <p><strong>This is what unparks a held saga.</strong> A {@code HOLD} verdict stops the
 * orchestration with the customer's funds still reserved; nothing else will move that payment until
 * an analyst decides, and this event is how the decision reaches the orchestrator.
 *
 * <p><strong>Why an event rather than fraud-service calling the orchestrator directly.</strong> The
 * decision and the intent to resume land in one commit through the outbox, so a network failure
 * cannot leave an alert resolved with the payment still parked — which would be invisible, because
 * nothing failed and the payment would simply never move again. It also means an orchestrator
 * redeployment does not stop analysts working the queue: the event waits.
 *
 * <p>The consumer must be idempotent. Kafka delivers at least once, and the orchestrator's saga
 * state machine is what makes a redelivery a no-op rather than a second provider charge.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalystDecisionPayload(

        String paymentId,

        String transactionId,

        String alertId,

        String assessmentId,

        /**
         * {@code RELEASE} lets the payment continue and charge a provider; {@code REJECT} refuses it
         * terminally and releases the reserved funds.
         *
         * <p>A string rather than an enum shared with fraud-service, deliberately. The payload lives
         * in platform-common where every service can read it, and putting fraud-service's internal
         * workflow enum here would make one service's private lifecycle part of the shared kernel.
         */
        String action,

        /**
         * Why, in the analyst's own words.
         *
         * <p>Carried on the event rather than left in fraud-service's database because it is what
         * reaches the customer: "your payment was released after we confirmed it with you" is a
         * different message from "your payment was refused".
         */
        String reason,

        /** Taken from the analyst's token, never from a request field. */
        String decidedBy,

        Instant decidedAt
) {

    public static final String ACTION_RELEASE = "RELEASE";
    public static final String ACTION_REJECT = "REJECT";

    public boolean isRelease() {
        return ACTION_RELEASE.equalsIgnoreCase(action);
    }

    public boolean isReject() {
        return ACTION_REJECT.equalsIgnoreCase(action);
    }
}
