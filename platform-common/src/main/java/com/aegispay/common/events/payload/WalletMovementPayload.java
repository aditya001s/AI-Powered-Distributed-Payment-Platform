package com.aegispay.common.events.payload;

import com.aegispay.common.domain.SharedEnums.PostingDirection;
import com.aegispay.common.money.Money;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * A wallet balance movement that crossed the platform boundary.
 *
 * <p>Emitted by wallet-service through its transactional outbox, in the same
 * transaction as the balance change, and consumed by ledger-service which books the
 * matching double entry.
 *
 * <p><strong>Only edge movements.</strong> This event describes a deposit into the
 * platform or a withdrawal out of it — the cases where the counterparty is outside
 * the system and only wallet-service knows the movement happened. Internal
 * wallet-to-wallet transfers are <em>not</em> emitted here: they are already fully
 * described by {@code payment-completed} and {@code payment-refunded}, and emitting
 * them twice would double-book them. Feeding a ledger from two event streams makes
 * double-counting the single most likely way to corrupt the books, so the boundary
 * is drawn explicitly rather than left to a filter somewhere downstream.
 *
 * <p>Carries facts, not accounts. Which ledger accounts a deposit touches is an
 * accounting decision, and it lives in ledger-service with the chart of accounts
 * rather than being decided by whichever service happened to move the money. That
 * keeps every mapping from a business event to a posting reviewable in one place.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WalletMovementPayload(

        /**
         * The wallet transaction ID. Used verbatim to derive the posting's
         * idempotency key, so a redelivered event cannot book the movement twice.
         */
        String walletTransactionId,

        String walletId,

        String userId,

        /** {@code CREDIT} for a deposit, {@code DEBIT} for a withdrawal. */
        PostingDirection direction,

        Money amount,

        /**
         * The wallet's balance immediately after the movement. Lets a consumer
         * detect a gap without replaying the whole statement.
         */
        Money balanceAfter,

        /** {@code DEPOSIT} or {@code WITHDRAWAL}. */
        String movementType,

        String description,

        /** When the balance actually changed, as opposed to when this was consumed. */
        Instant occurredAt
) {

    public WalletMovementPayload {
        if (walletTransactionId == null || walletTransactionId.isBlank()) {
            throw new IllegalArgumentException("walletTransactionId is required as the posting key");
        }
        if (amount == null || !amount.isPositive()) {
            // Direction carries the sign. A negative or zero movement would give two
            // ways to express one thing and make the ledger mapping ambiguous.
            throw new IllegalArgumentException("amount must be positive, got " + amount);
        }
    }

    /** True when this movement brought funds into the platform. */
    public boolean isDeposit() {
        return direction == PostingDirection.CREDIT;
    }
}
