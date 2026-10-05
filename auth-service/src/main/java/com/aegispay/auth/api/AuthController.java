package com.aegispay.auth.api;

import com.aegispay.auth.api.AuthDtos.LoginRequest;
import com.aegispay.auth.api.AuthDtos.LogoutRequest;
import com.aegispay.auth.api.AuthDtos.RefreshRequest;
import com.aegispay.auth.api.AuthDtos.RegisterRequest;
import com.aegispay.auth.api.AuthDtos.ServiceTokenResponse;
import com.aegispay.auth.api.AuthDtos.TokenResponse;
import com.aegispay.auth.service.AuthService;
import com.aegispay.auth.service.RequestContext;
import com.aegispay.auth.service.ServiceClientAuthService;
import com.aegispay.common.api.ApiError;
import com.aegispay.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Authentication endpoints.
 *
 * <p>Thin by design: bind, delegate, map to a status code. Every rule lives in the
 * service layer so it is testable without HTTP and reusable from other entry points.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Registration, login, token refresh and logout")
public class AuthController {

    private final AuthService authService;
    private final ServiceClientAuthService serviceClientAuthService;

    public AuthController(AuthService authService, ServiceClientAuthService serviceClientAuthService) {
        this.authService = authService;
        this.serviceClientAuthService = serviceClientAuthService;
    }

    @PostMapping("/register")
    @Operation(summary = "Register a new user",
            description = """
                    Creates an account and returns a token pair immediately, so the client
                    does not have to transmit the password a second time to log in.

                    Password policy: at least 12 characters with mixed case, a digit and a
                    special character. Common and sequential passwords are rejected.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created"),
            @ApiResponse(responseCode = "400", description = "Validation or password-policy failure",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Email already registered",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<TokenResponse> register(@Valid @RequestBody RegisterRequest request,
                                                 HttpServletRequest httpRequest) {
        TokenResponse response = authService.register(request, RequestContext.from(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate with email and password",
            description = """
                    Returns a short-lived access token and a single-use refresh token.

                    Every failure returns the same 401 regardless of cause (unknown email,
                    wrong password, locked account) so the endpoint cannot be used to
                    discover which addresses are registered. Repeated failures lock the
                    account temporarily.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Authenticated"),
            @ApiResponse(responseCode = "401", description = "Invalid credentials",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Account disabled",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "429", description = "Rate limited by the gateway",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest httpRequest) {
        return ResponseEntity.ok(authService.login(request, RequestContext.from(httpRequest)));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token for a new token pair",
            description = """
                    Refresh tokens are single-use and rotated: the presented token is
                    consumed and a new one issued.

                    Presenting an already-consumed token is treated as theft and revokes
                    every token in that session's family, forcing a fresh sign-in.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "New token pair issued"),
            @ApiResponse(responseCode = "401", description = "Refresh token invalid, expired, revoked or replayed",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request,
                                                 HttpServletRequest httpRequest) {
        return ResponseEntity.ok(authService.refresh(request.refreshToken(), RequestContext.from(httpRequest)));
    }

    @PostMapping("/logout")
    @Operation(summary = "Sign out",
            description = """
                    Revokes the refresh token, or every session for the user when
                    allSessions is true.

                    The current access token stays valid until it expires (15 minutes).
                    That is inherent to stateless JWT validation; the short TTL is the
                    deliberate trade-off against a per-request denylist lookup.""")
    @ApiResponse(responseCode = "204", description = "Signed out")
    public ResponseEntity<Void> logout(@Valid @RequestBody(required = false) LogoutRequest request,
                                       HttpServletRequest httpRequest) {
        String userId = CurrentUser.requireUserId();
        String refreshToken = request == null ? null : request.refreshToken();
        boolean allSessions = request != null && request.allSessions();

        authService.logout(userId, refreshToken, allSessions, RequestContext.from(httpRequest));
        return ResponseEntity.noContent().build();
    }

    /**
     * The OAuth2 client-credentials token endpoint.
     *
     * <p>Form-encoded and using {@code grant_type} / {@code client_id} /
     * {@code client_secret} so off-the-shelf OAuth2 client libraries work against it
     * unmodified, rather than requiring a bespoke JSON shape.
     */
    @PostMapping(path = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @Operation(summary = "Issue a service-to-service token (client credentials)",
            description = """
                    Machine-to-machine grant. The issued token carries ROLE_SERVICE and the
                    granted scopes, and is required by every /internal/** endpoint across
                    the platform.

                    Not for user authentication: use /api/auth/login for that.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token issued"),
            @ApiResponse(responseCode = "401", description = "Client authentication failed",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<ServiceTokenResponse> token(
            @RequestParam("grant_type") String grantType,
            @RequestParam("client_id") String clientId,
            @RequestParam("client_secret") String clientSecret,
            @RequestParam(value = "scope", required = false) String scope,
            HttpServletRequest httpRequest) {

        if (!"client_credentials".equals(grantType)) {
            // Only this grant is supported here; the others have their own endpoints.
            throw new com.aegispay.common.exception.DomainExceptions.BadRequestException(
                    com.aegispay.common.api.ErrorCode.VALIDATION_FAILED,
                    "Unsupported grant_type. This endpoint only supports 'client_credentials'.");
        }

        ServiceTokenResponse response = serviceClientAuthService.issueToken(
                clientId, clientSecret, parseScopes(scope), RequestContext.from(httpRequest));
        return ResponseEntity.ok(response);
    }

    /** Splits the space-delimited OAuth2 scope parameter. */
    private Set<String> parseScopes(String scope) {
        if (scope == null || scope.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(scope.trim().split("\\s+"))
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
}
