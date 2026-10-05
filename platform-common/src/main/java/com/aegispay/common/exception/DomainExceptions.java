package com.aegispay.common.exception;

import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.money.Money;

import java.util.Map;

/**
 * The concrete business exceptions, grouped in one file so the whole error
 * surface of the platform can be read at a glance.
 *
 * <p>Each nested type is a normal top-level-usable static class; grouping is
 * purely for readability, not for behaviour.
 */
public final class DomainExceptions {

    private DomainExceptions() {
    }

    /** 400 - request violated a syntactic or simple semantic rule. */
    public static class BadRequestException extends AegisException {
        public BadRequestException(String message) {
            super(ErrorCode.VALIDATION_FAILED, message);
        }

        public BadRequestException(ErrorCode code, String message) {
            super(code, message);
        }

        public BadRequestException(ErrorCode code, String message, Map<String, Object> details) {
            super(code, message, details);
        }
    }

    /** 404 - the addressed aggregate does not exist (or is not visible to the caller). */
    public static class NotFoundException extends AegisException {
        public NotFoundException(String message) {
            super(ErrorCode.RESOURCE_NOT_FOUND, message);
        }

        public NotFoundException(ErrorCode code, String message) {
            super(code, message);
        }

        /** Convenience for the very common "type + id" lookup miss. */
        public static NotFoundException of(String resourceType, Object id) {
            return new NotFoundException(resourceType + " " + id + " was not found");
        }
    }

    /** 409 - the request conflicts with the current state of the resource. */
    public static class ConflictException extends AegisException {
        public ConflictException(ErrorCode code, String message) {
            super(code, message);
        }

        public ConflictException(ErrorCode code, String message, Map<String, Object> details) {
            super(code, message, details);
        }
    }

    /** 403 - authenticated, but not permitted. */
    public static class ForbiddenException extends AegisException {
        public ForbiddenException(String message) {
            super(ErrorCode.ACCESS_DENIED, message);
        }

        public ForbiddenException(ErrorCode code, String message) {
            super(code, message);
        }
    }

    /** 401 - not authenticated, or credentials/token rejected. */
    public static class UnauthorizedException extends AegisException {
        public UnauthorizedException(ErrorCode code, String message) {
            super(code, message);
        }
    }

    /**
     * 422 - the wallet does not have enough available balance.
     *
     * <p>Reports {@code available} and {@code requested} so a client can render a
     * useful message, and so the reason is visible in structured logs. Both are
     * the caller's own balances, so this is not an information leak.
     */
    public static class InsufficientFundsException extends AegisException {
        public InsufficientFundsException(String walletId, Money available, Money requested) {
            super(ErrorCode.INSUFFICIENT_FUNDS,
                    "Wallet " + walletId + " has " + available + " available but " + requested + " was requested",
                    Map.of("walletId", walletId,
                            "available", available.toDecimal().toPlainString(),
                            "requested", requested.toDecimal().toPlainString(),
                            "currency", requested.currency()));
        }
    }

    /**
     * 409 - an idempotency key was replayed with a materially different body.
     *
     * <p>Replaying a key with the <em>same</em> body is a success (the stored
     * response is returned). Replaying it with a different body is
     * unambiguously a client bug and must never be treated as a new payment.
     */
    public static class IdempotencyConflictException extends AegisException {
        public IdempotencyConflictException(String key) {
            super(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency key '" + key + "' was already used with a different request payload",
                    Map.of("idempotencyKey", key));
        }
    }

    /** 409 - a concurrent request holds the idempotency reservation right now. */
    public static class IdempotentRequestInFlightException extends AegisException {
        public IdempotentRequestInFlightException(String key) {
            super(ErrorCode.IDEMPOTENT_REQUEST_IN_FLIGHT,
                    "Idempotency key '" + key + "' is currently being processed",
                    Map.of("idempotencyKey", key));
        }
    }

    /** 503 - a downstream service or provider could not be reached. */
    public static class UpstreamUnavailableException extends AegisException {
        public UpstreamUnavailableException(String dependency, String message, Throwable cause) {
            super(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "Dependency '" + dependency + "' is unavailable: " + message,
                    Map.of("dependency", dependency),
                    cause);
        }

        public UpstreamUnavailableException(String dependency, String message) {
            this(dependency, message, null);
        }
    }

    /**
     * A downstream call failed without a trustworthy HTTP response, so the caller cannot know
     * whether the remote side effect committed. This is narrower than an explicit 5xx response,
     * which confirms that the downstream rejected the operation.
     */
    public static class UpstreamTransportException extends UpstreamUnavailableException {
        public UpstreamTransportException(String dependency, String message, Throwable cause) {
            super(dependency, message, cause);
        }
    }

    /**
     * 422 - a double-entry posting failed the debit==credit invariant.
     *
     * <p>This is a programming error, not a user error, and it is thrown before
     * anything is committed. It exists so the invariant fails loudly rather than
     * corrupting the books.
     */
    public static class LedgerImbalanceException extends AegisException {
        public LedgerImbalanceException(long totalDebitMinor, long totalCreditMinor) {
            super(ErrorCode.LEDGER_IMBALANCE,
                    "Unbalanced posting rejected: debits=" + totalDebitMinor + " credits=" + totalCreditMinor,
                    Map.of("totalDebitMinor", totalDebitMinor,
                            "totalCreditMinor", totalCreditMinor,
                            "differenceMinor", totalDebitMinor - totalCreditMinor));
        }
    }

    /** 409 - an operation was attempted from a state that does not allow it. */
    public static class IllegalStateTransitionException extends AegisException {
        public IllegalStateTransitionException(ErrorCode code, String entity, String from, String to) {
            super(code,
                    entity + " cannot move from " + from + " to " + to,
                    Map.of("entity", entity, "fromState", from, "toState", to));
        }
    }
}
