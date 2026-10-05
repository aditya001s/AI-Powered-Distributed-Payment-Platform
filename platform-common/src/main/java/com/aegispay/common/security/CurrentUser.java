package com.aegispay.common.security;

import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.exception.DomainExceptions.ForbiddenException;
import com.aegispay.common.exception.DomainExceptions.UnauthorizedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads the authenticated caller out of the security context.
 *
 * <p>Exists to keep one rule enforceable: <strong>the user ID used for an
 * authorisation decision comes from the validated token, never from the request
 * body or a path variable.</strong> Trusting a client-supplied {@code userId} is
 * the classic broken-object-level-authorisation vulnerability — it would let any
 * authenticated user transfer from someone else's wallet by editing one field.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** The authenticated caller, or empty when the endpoint is public. */
    public static Optional<AuthenticatedCaller> find() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            return Optional.empty();
        }

        Jwt jwt = jwtAuth.getToken();
        Set<String> authorities = jwtAuth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toUnmodifiableSet());

        return Optional.of(new AuthenticatedCaller(
                jwt.getSubject(),
                jwt.getClaimAsString(JwtAuthenticationConverter.CLAIM_EMAIL),
                authorities,
                jwt.getId()));
    }

    /** The authenticated caller, or 401 if there is none. */
    public static AuthenticatedCaller require() {
        return find().orElseThrow(() -> new UnauthorizedException(
                ErrorCode.UNAUTHENTICATED, "No authenticated caller in the security context"));
    }

    /** The authenticated user's immutable ID. */
    public static String requireUserId() {
        return require().userId();
    }

    /**
     * Asserts that the caller may act on {@code ownerUserId}: either they are that
     * user, or they hold an elevated role.
     *
     * <p>Every endpoint that reads or mutates user-scoped data calls this. Without
     * it, {@code GET /api/payments/{id}} would happily return another customer's
     * payment to anyone who guessed its ID.
     */
    public static void requireSelfOrElevated(String ownerUserId) {
        AuthenticatedCaller caller = require();
        if (caller.isSameUser(ownerUserId) || caller.hasAnyRole(Roles.ADMIN, Roles.FRAUD_ANALYST, Roles.SERVICE)) {
            return;
        }
        throw new ForbiddenException("Caller is not permitted to access resources owned by another user");
    }

    /** An authenticated principal: a human user or a machine client. */
    public record AuthenticatedCaller(String userId,
                                      String email,
                                      Set<String> authorities,
                                      String tokenId) {

        public boolean hasRole(String role) {
            return authorities.contains(Roles.withPrefix(role));
        }

        public boolean hasAnyRole(String... roles) {
            for (String role : roles) {
                if (hasRole(role)) {
                    return true;
                }
            }
            return false;
        }

        public boolean hasScope(String scope) {
            return authorities.contains("SCOPE_" + scope);
        }

        public boolean isAdmin() {
            return hasRole(Roles.ADMIN);
        }

        /** True for a service-to-service token rather than a human session. */
        public boolean isService() {
            return hasRole(Roles.SERVICE);
        }

        public boolean isSameUser(String otherUserId) {
            return userId != null && userId.equals(otherUserId);
        }
    }
}
