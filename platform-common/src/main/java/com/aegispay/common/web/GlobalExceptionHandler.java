package com.aegispay.common.web;

import com.aegispay.common.api.ApiError;
import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.correlation.CorrelationContext;
import com.aegispay.common.exception.AegisException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * Translates exceptions into the single {@link ApiError} contract.
 *
 * <p>Two rules drive the design:
 *
 * <ol>
 *   <li><strong>Never leak internals.</strong> Anything unrecognised becomes a
 *       generic 500 whose body contains only a correlation ID. Stack traces,
 *       exception class names, SQL fragments and constraint names stay in the
 *       logs, where they are useful and not attacker-visible.</li>
 *   <li><strong>Log at the right level.</strong> Expected business outcomes
 *       (insufficient funds, validation errors) log at DEBUG/WARN without a
 *       stack trace, so genuine 5xx incidents are not buried in noise. Only
 *       unexpected failures log at ERROR with the full trace.</li>
 * </ol>
 */
@RestControllerAdvice
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Business failures deliberately raised by the domain. */
    @ExceptionHandler(AegisException.class)
    public ResponseEntity<ApiError> handleAegis(AegisException ex, HttpServletRequest request) {
        ErrorCode code = ex.errorCode();
        if (code.status().is5xxServerError()) {
            log.error("{} at {}: {}", code, request.getRequestURI(), ex.getMessage(), ex);
        } else {
            // Expected outcome: record it, but do not pollute error dashboards.
            log.warn("{} at {}: {}", code, request.getRequestURI(), ex.getMessage());
        }
        return respond(code, ex.getMessage(), request, List.of(), ex.details());
    }

    /** {@code @Valid} failures on request bodies. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBodyValidation(MethodArgumentNotValidException ex,
                                                        HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toViolation)
                .toList();
        log.debug("Validation failed at {}: {}", request.getRequestURI(), violations);
        return respond(ErrorCode.VALIDATION_FAILED, null, request, violations, null);
    }

    /** Spring MVC native method validation for controller parameters and return values. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> handleMethodValidation(HandlerMethodValidationException ex,
                                                           HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = ex.getParameterValidationResults().stream()
                .flatMap(result -> {
                    String parameterName = result.getMethodParameter().getParameterName();
                    String field = parameterName == null ? "argument" : parameterName;
                    return result.getResolvableErrors().stream()
                            .map(error -> new ApiError.FieldViolation(
                                    field,
                                    error.getDefaultMessage(),
                                    maskIfSensitive(field, result.getArgument())));
                })
                .toList();
        log.debug("Method validation failed at {}: {}", request.getRequestURI(), violations);
        return respond(ErrorCode.VALIDATION_FAILED, null, request, violations, null);
    }

    /** {@code @Validated} failures on path variables, params and method arguments. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex,
                                                             HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(this::toViolation)
                .toList();
        log.debug("Constraint violation at {}: {}", request.getRequestURI(), violations);
        return respond(ErrorCode.VALIDATION_FAILED, null, request, violations, null);
    }

    /** Unparseable JSON, wrong types, missing required params. */
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingRequestValueException.class
    })
    public ResponseEntity<ApiError> handleMalformed(Exception ex, HttpServletRequest request) {
        // The raw parser message can echo request content back to the caller,
        // so it is logged but not returned.
        log.debug("Malformed request at {}: {}", request.getRequestURI(), ex.getMessage());
        return respond(ErrorCode.MALFORMED_REQUEST, null, request, List.of(), null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return respond(ErrorCode.RESOURCE_NOT_FOUND, null, request, List.of(), null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                            HttpServletRequest request) {
        ApiError body = ApiError.builder(ErrorCode.MALFORMED_REQUEST)
                .message("HTTP method " + ex.getMethod() + " is not supported on this endpoint.")
                .path(request.getRequestURI())
                .correlationId(CorrelationContext.get())
                .build();
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(body);
    }

    /**
     * A unique constraint or FK check fired. Treated as a 409 because in a
     * concurrent system it usually means "someone else got there first", and the
     * safe client behaviour is to re-read and retry rather than to retry blindly.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException ex,
                                                    HttpServletRequest request) {
        // Constraint names describe the schema, so they are logged, never returned.
        log.warn("Data integrity violation at {}: {}", request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return respond(ErrorCode.RESOURCE_ALREADY_EXISTS, null, request, List.of(), null);
    }

    /** Lost an optimistic-lock race, or could not get a pessimistic lock in time. */
    @ExceptionHandler({OptimisticLockingFailureException.class, PessimisticLockingFailureException.class})
    public ResponseEntity<ApiError> handleLocking(Exception ex, HttpServletRequest request) {
        log.warn("Lock contention at {}: {}", request.getRequestURI(), ex.getMessage());
        return respond(ErrorCode.CONCURRENT_MODIFICATION, null, request, List.of(), null);
    }

    /** Catch-all. Nothing about the cause reaches the client. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        String correlationId = CorrelationContext.getOrCreate();
        log.error("Unhandled exception at {} [correlationId={}]", request.getRequestURI(), correlationId, ex);
        ApiError body = ApiError.builder(ErrorCode.INTERNAL_ERROR)
                .path(request.getRequestURI())
                .correlationId(correlationId)
                .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private ResponseEntity<ApiError> respond(ErrorCode code,
                                             String message,
                                             HttpServletRequest request,
                                             List<ApiError.FieldViolation> violations,
                                             java.util.Map<String, Object> details) {
        ApiError body = ApiError.builder(code)
                .message(code.status().is5xxServerError() ? code.defaultMessage() : message)
                .path(request.getRequestURI())
                .correlationId(CorrelationContext.get())
                .fieldErrors(violations)
                .details(details)
                .build();
        return ResponseEntity.status(code.status()).body(body);
    }

    private ApiError.FieldViolation toViolation(FieldError error) {
        return new ApiError.FieldViolation(error.getField(), error.getDefaultMessage(),
                maskIfSensitive(error.getField(), error.getRejectedValue()));
    }

    private ApiError.FieldViolation toViolation(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath() == null ? "" : violation.getPropertyPath().toString();
        // Trim the "method.arg0." prefix Hibernate Validator adds for method params.
        String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
        return new ApiError.FieldViolation(field, violation.getMessage(),
                maskIfSensitive(field, violation.getInvalidValue()));
    }

    /**
     * Echoing a rejected value back is helpful for debugging, except when the
     * field is a credential: a validation error must never reflect a password or
     * token, because error responses are frequently logged by intermediaries.
     */
    private Object maskIfSensitive(String field, Object rejectedValue) {
        if (field == null || rejectedValue == null) {
            return null;
        }
        String lower = field.toLowerCase();
        boolean sensitive = lower.contains("password")
                || lower.contains("secret")
                || lower.contains("token")
                || lower.contains("credential")
                || lower.contains("pin")
                || lower.contains("otp");
        return sensitive ? "***" : rejectedValue;
    }
}
