package com.aegispay.common.api;

import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error taxonomy.
 *
 * <p>Clients branch on these codes, not on HTTP status alone (several distinct
 * business failures legitimately map to 409 or 422). The string values are part
 * of the public API contract and must not be renamed once released.
 *
 * <p>Every message here is deliberately generic. Detailed diagnostics go to the
 * logs keyed by correlation ID; leaking internals (SQL text, stack frames, which
 * of email/password was wrong) to callers is an information-disclosure bug.
 */
public enum ErrorCode {

    // ---- 400 / 422: caller-side problems ----
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "One or more fields are invalid."),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "The request body could not be parsed."),
    UNSUPPORTED_CURRENCY(HttpStatus.BAD_REQUEST, "The requested currency is not supported."),
    INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "The transfer amount is invalid."),
    SELF_TRANSFER_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "Source and destination wallets must differ."),

    // ---- 401 / 403: authentication and authorisation ----
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Authentication failed."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "The access token has expired."),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "The access token is not valid."),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "The refresh token is not valid or has been used."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication is required."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "You do not have permission to perform this action."),
    ACCOUNT_LOCKED(HttpStatus.FORBIDDEN, "The account is locked. Contact support."),
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "The account is disabled."),

    // ---- 404 ----
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "The requested resource was not found."),
    WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "Wallet not found."),
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Payment not found."),

    // ---- 409: state conflicts ----
    RESOURCE_ALREADY_EXISTS(HttpStatus.CONFLICT, "The resource already exists."),
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "That email address is already registered."),
    WALLET_ALREADY_EXISTS(HttpStatus.CONFLICT, "A wallet already exists for this currency."),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT,
            "This idempotency key was already used with a different request payload."),
    IDEMPOTENT_REQUEST_IN_FLIGHT(HttpStatus.CONFLICT,
            "An identical request is currently being processed. Retry shortly."),
    INVALID_PAYMENT_STATE(HttpStatus.CONFLICT, "The payment is not in a state that allows this operation."),
    REFUND_NOT_ALLOWED(HttpStatus.CONFLICT, "This payment cannot be refunded."),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "The resource was modified concurrently. Retry the request."),

    // ---- 422: business rules ----
    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_CONTENT, "Insufficient available balance."),
    LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT, "The transaction exceeds an account limit."),
    LEDGER_IMBALANCE(HttpStatus.UNPROCESSABLE_CONTENT, "The posting is not balanced and was rejected."),
    PAYMENT_BLOCKED_BY_FRAUD(HttpStatus.UNPROCESSABLE_CONTENT,
            "The payment was blocked by fraud controls."),

    // ---- 429 ----
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Slow down."),

    // ---- 5xx ----
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred."),
    UPSTREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "A dependency is temporarily unavailable."),
    ALL_PROVIDERS_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE,
            "No payment provider could process this transaction. It was not charged."),
    REQUEST_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "The request timed out.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public int statusValue() {
        return status.value();
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
