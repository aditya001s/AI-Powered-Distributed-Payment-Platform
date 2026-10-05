package com.aegispay.analytics.domain;

import com.aegispay.common.domain.PaymentStatus;

/**
 * A monotonic ordering over {@link PaymentStatus}, so a projection can survive out-of-order events.
 *
 * <h2>The problem this solves</h2>
 *
 * <p>Kafka orders records within one topic partition and promises nothing across topics.
 * {@code payment-created} and {@code payment-completed} are different topics, so a completion can be
 * applied before the creation it belongs to — routinely when replaying history into a fresh read model,
 * and occasionally under normal load.
 *
 * <p>Without a guard, the late {@code payment-created} would overwrite {@code COMPLETED} with
 * {@code CREATED}, and the dashboard would report a settled payment as in-flight. Forever: nothing
 * would ever correct it, because every event for that payment has already been consumed. No error, no
 * log line, just a number that is quietly wrong and a payment that appears stuck.
 *
 * <h2>Why strictly greater, not greater-or-equal</h2>
 *
 * <p>The three terminal outcomes — {@code COMPLETED}, {@code REJECTED}, {@code FAILED} — deliberately
 * share a rank. Combined with a strictly-greater test, that makes them mutually exclusive: whichever
 * arrives first wins, and a second one cannot flip the payment to a different ending.
 *
 * <p>That is the correct behaviour rather than a convenience. A payment reaches exactly one of those
 * three, so a second terminal event for the same payment is either a redelivery or a producer defect,
 * and in both cases changing the recorded outcome would be wrong.
 *
 * <p>Refunds rank <em>above</em> completion, because a refund legitimately follows a settled payment
 * and must be allowed through the same guard.
 */
public final class StatusRank {

    private StatusRank() {
    }

    /**
     * The rank of a status.
     *
     * <p>Values are spaced by ten so a state can be inserted between two existing ones later without
     * renumbering — and renumbering would be a migration over every existing row, not just a code
     * change.
     */
    public static int of(PaymentStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("a payment fact must have a status to rank");
        }
        return switch (status) {
            // ---- in flight ----
            case CREATED -> 10;
            case FUNDS_RESERVED -> 20;
            case RISK_ASSESSED -> 30;
            case ON_HOLD -> 40;
            case AUTHORIZED -> 50;
            case CAPTURED -> 60;

            // ---- the three mutually exclusive endings ----
            // Equal on purpose. With a strictly-greater guard, the first to arrive wins and a second
            // cannot rewrite the outcome.
            case COMPLETED, REJECTED, FAILED -> 70;

            // ---- what can legitimately follow a completed payment ----
            case PARTIALLY_REFUNDED -> 80;
            case REFUNDED -> 90;
        };
    }

    /**
     * Whether {@code candidate} should replace {@code current}.
     *
     * <p>The single decision the whole guard reduces to. Strictly greater, for the reason above.
     */
    public static boolean advances(PaymentStatus current, PaymentStatus candidate) {
        if (candidate == null) {
            return false;
        }
        if (current == null) {
            return true;
        }
        return of(candidate) > of(current);
    }
}
