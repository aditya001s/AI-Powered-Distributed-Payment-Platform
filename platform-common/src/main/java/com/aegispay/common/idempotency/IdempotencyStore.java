package com.aegispay.common.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;

/**
 * Transactional operations on the authoritative idempotency table.
 *
 * <p><strong>Every method is {@link Propagation#REQUIRES_NEW}, and that is the whole
 * point.</strong> The claim must be <em>committed and visible to other transactions</em>
 * before the guarded operation starts running. If the claim shared the caller's
 * transaction it would stay invisible until that transaction committed, so two
 * concurrent identical requests would both see "no existing claim", both proceed, and
 * both execute — which is exactly the double-charge that idempotency exists to prevent.
 *
 * <p>The same reasoning applies in reverse to {@link #fail}: it must commit even though
 * the caller is about to rethrow, or the key would be left permanently claimed by a
 * failed attempt and the client could never retry.
 *
 * <p>This mirrors the transaction discipline established for the auth flows: writes that
 * must outlive the caller's outcome live on their own bean with their own transaction.
 * A separate bean is required rather than merely convenient — Spring applies transaction
 * advice through a proxy, so a self-invocation would silently ignore the propagation.
 */
public class IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyStore.class);

    private final IdempotencyRecordRepository repository;

    public IdempotencyStore(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    /** What the caller should do about a claim attempt. */
    public enum Outcome {
        /** Key is ours; run the operation. */
        PROCEED,
        /** Already completed; replay {@code responseBody} instead of re-running. */
        REPLAY,
        /** A concurrent request holds the key right now; tell the client to retry. */
        IN_FLIGHT,
        /** Key reused with a different payload; reject as a client bug. */
        CONFLICT
    }

    public record BeginResult(Outcome outcome, String responseBody, Integer responseStatus,
                              String resourceId) {

        static BeginResult proceed() {
            return new BeginResult(Outcome.PROCEED, null, null, null);
        }

        static BeginResult replay(IdempotencyRecord record) {
            return new BeginResult(Outcome.REPLAY, record.getResponseBody(),
                    record.getResponseStatus(), record.getResourceId());
        }

        static BeginResult inFlight() {
            return new BeginResult(Outcome.IN_FLIGHT, null, null, null);
        }

        static BeginResult conflict() {
            return new BeginResult(Outcome.CONFLICT, null, null, null);
        }

        public boolean shouldProceed() {
            return outcome == Outcome.PROCEED;
        }
    }

    /**
     * Attempts to claim {@code (scope, key)} for a request hashing to {@code fingerprint}.
     *
     * @param staleAfter how long an {@code IN_PROGRESS} claim may sit before it is
     *                   treated as abandoned and taken over. Without a takeover path, a
     *                   process that died mid-operation would lock its key out forever.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BeginResult begin(String scope, String key, String fingerprint,
                            Duration retention, Duration staleAfter) {

        Optional<IdempotencyRecord> existing = repository.findByScopeAndIdempotencyKey(scope, key);

        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();

            // Checked FIRST, before status. A key replayed with a different payload is
            // a client bug whatever state the original attempt reached, and returning
            // the stored response for a different request would silently swallow it.
            if (!record.matchesRequest(fingerprint)) {
                log.warn("Idempotency key reused with a different payload [scope={}]", scope);
                return BeginResult.conflict();
            }

            if (record.isCompleted()) {
                log.debug("Replaying completed idempotent operation [scope={}]", scope);
                return BeginResult.replay(record);
            }

            if (record.isInProgress() && !record.isStale(staleAfter)) {
                return BeginResult.inFlight();
            }

            // Stale IN_PROGRESS, or a previous FAILED attempt: the payload matches, so
            // take the claim over and let the client retry.
            log.info("Reclaiming {} idempotency record [scope={}]", record.getStatus(), scope);
            repository.delete(record);
            repository.flush();
        }

        IdempotencyRecord claim = IdempotencyRecord.begin(
                scope, key, fingerprint, retention);
        int inserted = repository.insertIfAbsent(
                claim.getScope(), claim.getIdempotencyKey(), claim.getRequestHash(),
                claim.getCreatedAt(), claim.getExpiresAt());
        if (inserted == 1) {
            return BeginResult.proceed();
        }

        // Lost the insert race against a concurrent identical request. ON CONFLICT
        // prevents PostgreSQL from aborting this transaction, unlike catching a unique
        // constraint failure after saveAndFlush.
        log.debug("Lost the idempotency claim race [scope={}]", scope);
        return BeginResult.inFlight();
    }

    /**
     * Marks the operation completed and stores its response for future replays.
     *
     * <p>Called after the business transaction has committed, so a stored response
     * always describes work that actually happened.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String scope, String key, String responseBody,
                         int responseStatus, String resourceId) {
        repository.findByScopeAndIdempotencyKey(scope, key).ifPresentOrElse(
                record -> {
                    record.complete(responseBody, responseStatus, resourceId);
                    repository.save(record);
                },
                () -> log.warn("Idempotency record vanished before completion [scope={}]", scope));
    }

    /**
     * Releases the claim after a failure so the client can retry with the same key.
     *
     * <p>Marked {@code FAILED} rather than deleted, so the attempt remains visible for
     * diagnostics; {@link #begin} treats a {@code FAILED} record with a matching
     * fingerprint as reclaimable.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(String scope, String key) {
        repository.findByScopeAndIdempotencyKey(scope, key).ifPresent(record -> {
            record.fail();
            repository.save(record);
        });
    }

    /** Retention sweep. Expired terminal records only; see the repository for why. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purgeExpired() {
        int deleted = repository.deleteExpired(java.time.Instant.now());
        if (deleted > 0) {
            log.debug("Purged {} expired idempotency records", deleted);
        }
        return deleted;
    }
}
