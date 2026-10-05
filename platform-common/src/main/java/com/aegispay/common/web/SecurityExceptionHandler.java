package com.aegispay.common.web;

import com.aegispay.common.api.ApiError;
import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.correlation.CorrelationContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders Spring Security failures in the platform's {@link ApiError} shape.
 *
 * <p>Kept separate from {@link GlobalExceptionHandler} and guarded by
 * {@link ConditionalOnClass} so that a service without the security starter on
 * its classpath still starts: an {@code @ExceptionHandler} referencing an absent
 * class would fail advice registration at boot.
 *
 * <p>Ordered ahead of the general handler so {@code AccessDeniedException} is not
 * swallowed by the {@code Exception} catch-all and reported as a 500.
 *
 * <p>Note this only covers failures thrown <em>inside</em> the MVC dispatch.
 * Rejections in the filter chain itself are handled by the entry point and
 * access-denied handler configured in each service's security chain.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnClass(AccessDeniedException.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SecurityExceptionHandler.class);

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        // Authorisation failures are security-relevant: always audited.
        log.warn("Access denied for {} {} [user={}]", request.getMethod(), request.getRequestURI(),
                CorrelationContext.get());
        return build(ErrorCode.ACCESS_DENIED, request);
    }

    @ExceptionHandler(InvalidBearerTokenException.class)
    public ResponseEntity<ApiError> handleInvalidToken(InvalidBearerTokenException ex, HttpServletRequest request) {
        log.debug("Invalid bearer token at {}: {}", request.getRequestURI(), ex.getMessage());
        return build(ErrorCode.TOKEN_INVALID, request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        log.debug("Authentication failed at {}: {}", request.getRequestURI(), ex.getMessage());
        return build(ErrorCode.UNAUTHENTICATED, request);
    }

    private ResponseEntity<ApiError> build(ErrorCode code, HttpServletRequest request) {
        ApiError body = ApiError.builder(code)
                .path(request.getRequestURI())
                .correlationId(CorrelationContext.get())
                .build();
        return ResponseEntity.status(code.status()).body(body);
    }
}
