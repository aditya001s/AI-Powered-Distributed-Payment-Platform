package com.aegispay.common.exception;

import com.aegispay.common.api.ErrorCode;

import java.util.Map;

/**
 * Base type for all deliberately-thrown business failures.
 *
 * <p>Carrying an {@link ErrorCode} means the HTTP status and the client-facing
 * message are decided at the point where the domain rule lives, not guessed at
 * by a controller. {@link #details()} attaches structured, non-sensitive context
 * (e.g. the available balance on a rejected transfer).
 */
public abstract class AegisException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Map<String, Object> details;

    protected AegisException(ErrorCode errorCode, String message) {
        this(errorCode, message, Map.of(), null);
    }

    protected AegisException(ErrorCode errorCode, String message, Map<String, Object> details) {
        this(errorCode, message, details, null);
    }

    protected AegisException(ErrorCode errorCode, String message, Map<String, Object> details, Throwable cause) {
        super(message == null || message.isBlank() ? errorCode.defaultMessage() : message, cause);
        this.errorCode = errorCode;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, Object> details() {
        return details;
    }
}
