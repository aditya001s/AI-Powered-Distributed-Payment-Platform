package com.aegispay.common.events.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Map;

/**
 * Confirmation that a posting was durably committed to the journal.
 *
 * <p>The orchestrator waits for this before capturing the wallet reservation, so
 * the books are written before the customer-visible balance changes. If the
 * process dies between the two, the saga replays and the posting is skipped as a
 * duplicate rather than double-applied.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LedgerPostedPayload(

        String ledgerTransactionId,

        String paymentId,

        String transactionId,

        /** Monotonic journal sequence number assigned at commit. */
        long journalSequence,

        long totalMinor,

        String currency,

        /** Resulting balance per account touched, for reconciliation checks. */
        Map<String, Long> resultingBalancesMinor,

        /**
         * True when this event is the acknowledgement of an already-applied
         * posting (a replay). Consumers use it to avoid re-emitting notifications.
         */
        boolean duplicate,

        Instant postedAt
) {
}
