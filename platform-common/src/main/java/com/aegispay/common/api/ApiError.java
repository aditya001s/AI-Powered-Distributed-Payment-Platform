package com.aegispay.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The single error body shape returned by every service and by the gateway.
 *
 * <p>Deliberately excludes stack traces, exception class names and SQL. The
 * {@code correlationId} is the bridge between what the caller sees and what an
 * engineer can find in the logs, which gives support-grade debuggability
 * without leaking internals.
 *
 * <pre>{@code
 * {
 *   "timestamp": "2026-09-03T10:15:30.123Z",
 *   "status": 422,
 *   "code": "INSUFFICIENT_FUNDS",
 *   "message": "Insufficient available balance.",
 *   "path": "/api/payments",
 *   "correlationId": "c7f1e2a9-...",
 *   "fieldErrors": [ { "field": "amount", "message": "must be greater than 0" } ]
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        String correlationId,
        List<FieldViolation> fieldErrors,
        Map<String, Object> details
) {

    /** A single failed field-level constraint. */
    public record FieldViolation(String field, String message, Object rejectedValue) {
    }

    public static Builder builder(ErrorCode code) {
        return new Builder(code);
    }

    public static final class Builder {
        private final ErrorCode code;
        private String message;
        private String path;
        private String correlationId;
        private List<FieldViolation> fieldErrors = List.of();
        private Map<String, Object> details = Map.of();

        private Builder(ErrorCode code) {
            this.code = code;
            this.message = code.defaultMessage();
        }

        public Builder message(String message) {
            if (message != null && !message.isBlank()) {
                this.message = message;
            }
            return this;
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder fieldErrors(List<FieldViolation> fieldErrors) {
            this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
            return this;
        }

        public Builder details(Map<String, Object> details) {
            this.details = details == null ? Map.of() : Map.copyOf(details);
            return this;
        }

        public ApiError build() {
            return new ApiError(Instant.now(), code.statusValue(), code.name(), message,
                    path, correlationId, fieldErrors, details);
        }
    }
}
