package com.aegispay.ledger.service;

import com.aegispay.ledger.domain.JournalEntry;
import com.aegispay.ledger.domain.JournalLine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A committed posting and its legs.
 *
 * @param entry     the journal entry, carrying its assigned sequence number
 * @param lines     the legs, in the order they were applied
 * @param duplicate true when this describes a posting that already existed and the caller
 *                  was retrying. The movement happened exactly once either way; the flag
 *                  exists so a caller can tell "I booked this" from "this was already
 *                  booked" and avoid re-emitting notifications for it.
 */
public record PostingResult(JournalEntry entry, List<JournalLine> lines, boolean duplicate) {

    public PostingResult {
        lines = List.copyOf(lines);
    }

    public static PostingResult posted(JournalEntry entry, List<JournalLine> lines) {
        return new PostingResult(entry, lines, false);
    }

    public static PostingResult replayed(JournalEntry entry, List<JournalLine> lines) {
        return new PostingResult(entry, lines, true);
    }

    /**
     * Resulting balance per account touched, for the {@code ledger-posted} event.
     *
     * <p>Insertion-ordered so the map reads in posting order, and last-write-wins when one
     * posting touches an account twice — which is the balance that actually ended up on the
     * account.
     */
    public Map<String, Long> resultingBalancesMinor() {
        Map<String, Long> balances = new LinkedHashMap<>();
        for (JournalLine line : lines) {
            balances.put(line.getAccountId(), line.getBalanceAfterMinor());
        }
        return Map.copyOf(balances);
    }
}
