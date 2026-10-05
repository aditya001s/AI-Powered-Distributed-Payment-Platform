package com.aegispay.auth.api;

import com.aegispay.auth.domain.UserAccount;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Set;

/**
 * Request and response bodies for auth-service.
 *
 * <p>Grouped in one file so the full HTTP contract can be reviewed together.
 * Records because these are immutable data carriers: no setters means no
 * accidental mutation between validation and use.
 *
 * <p>Validation lives on the DTO rather than in the service. Rejecting malformed
 * input at the edge keeps invalid state from ever reaching the domain, and the
 * constraints double as machine-readable API documentation via springdoc.
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    // ------------------------------------------------------------------ input

    @Schema(description = "New user registration")
    public record RegisterRequest(

            @NotBlank
            @Email(message = "must be a valid email address")
            @Size(max = 254)
            @Schema(example = "asha.verma@example.com")
            String email,

            /**
             * Length is capped as well as floored. BCrypt silently truncates input
             * beyond 72 bytes, so accepting a 200-character password would give the
             * user a false sense of strength. An explicit maximum is honest about
             * what is actually hashed.
             */
            @NotBlank
            @Size(min = 12, max = 72, message = "must be between 12 and 72 characters")
            @Schema(example = "correct-horse-Battery-9!", format = "password")
            String password,

            @NotBlank
            @Size(max = 120)
            @Schema(example = "Asha Verma")
            String fullName,

            /** E.164, optional. */
            @Pattern(regexp = "^$|^\\+?[1-9]\\d{7,14}$", message = "must be a valid phone number")
            @Schema(example = "+919876543210")
            String phone
    ) {
    }

    @Schema(description = "Password login")
    public record LoginRequest(

            @NotBlank
            @Email
            String email,

            @NotBlank
            @Size(max = 72)
            @Schema(format = "password")
            String password
    ) {
    }

    @Schema(description = "Exchange a refresh token for a new token pair")
    public record RefreshRequest(

            @NotBlank
            @Size(max = 512)
            String refreshToken
    ) {
    }

    @Schema(description = "Revoke the current session")
    public record LogoutRequest(

            @Size(max = 512)
            String refreshToken,

            @Schema(description = "Revoke every session for this user, not just this one")
            boolean allSessions
    ) {
    }

    @Schema(description = "Change the authenticated user's password")
    public record ChangePasswordRequest(

            @NotBlank
            @Size(max = 72)
            @Schema(format = "password")
            String currentPassword,

            @NotBlank
            @Size(min = 12, max = 72)
            @Schema(format = "password")
            String newPassword
    ) {
    }

    @Schema(description = "Update the authenticated user's profile")
    public record UpdateProfileRequest(

            @Size(max = 120)
            String fullName,

            @Pattern(regexp = "^$|^\\+?[1-9]\\d{7,14}$")
            String phone
    ) {
    }

    // ----------------------------------------------------------------- output

    /**
     * OAuth2-style token response.
     *
     * <p>Field names follow RFC 6749 ({@code access_token}, {@code expires_in}) so
     * standard OAuth2 client libraries work without custom mapping.
     */
    @Schema(description = "Issued token pair")
    public record TokenResponse(

            @Schema(description = "Short-lived JWT for API calls")
            String accessToken,

            @Schema(description = "Long-lived, single-use token. Rotated on every refresh.")
            String refreshToken,

            @Schema(example = "Bearer")
            String tokenType,

            @Schema(description = "Access token lifetime in seconds", example = "900")
            long expiresIn,

            @Schema(description = "Refresh token lifetime in seconds", example = "2592000")
            long refreshExpiresIn,

            UserSummary user
    ) {
        public static TokenResponse of(String accessToken, String refreshToken,
                                       long expiresIn, long refreshExpiresIn, UserSummary user) {
            return new TokenResponse(accessToken, refreshToken, "Bearer", expiresIn, refreshExpiresIn, user);
        }
    }

    /** Machine token response for the client-credentials grant (no refresh token). */
    @Schema(description = "Service-to-service token")
    public record ServiceTokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") long expiresIn,
            @JsonProperty("scope") String scope
    ) {
        public static ServiceTokenResponse of(String accessToken, long expiresIn, String scope) {
            return new ServiceTokenResponse(accessToken, "Bearer", expiresIn, scope);
        }
    }

    /** Compact identity, embedded in the login response. */
    @Schema(description = "Identity summary")
    public record UserSummary(
            String userId,
            String email,
            String fullName,
            Set<String> roles,
            UserAccount.Status status,
            UserAccount.KycLevel kycLevel
    ) {
        public static UserSummary from(UserAccount account) {
            return new UserSummary(
                    account.getUserId(),
                    account.getEmail(),
                    account.getFullName(),
                    account.getRoles(),
                    account.getStatus(),
                    account.getKycLevel());
        }
    }

    /**
     * Full profile for {@code GET /api/users/me}.
     *
     * <p>Note what is absent: the password hash, the failure counter, the internal
     * numeric primary key. A response DTO is a deliberate allow-list, which is why
     * entities are never serialised straight to the client.
     */
    @Schema(description = "Authenticated user profile")
    public record UserProfileResponse(
            String userId,
            String email,
            String fullName,
            String phone,
            Set<String> roles,
            UserAccount.Status status,
            UserAccount.KycLevel kycLevel,
            Instant lastLoginAt,
            Instant createdAt,
            @Schema(description = "Account age in days; also used as a fraud feature")
            long accountAgeDays
    ) {
        public static UserProfileResponse from(UserAccount account) {
            return new UserProfileResponse(
                    account.getUserId(),
                    account.getEmail(),
                    account.getFullName(),
                    account.getPhone(),
                    account.getRoles(),
                    account.getStatus(),
                    account.getKycLevel(),
                    account.getLastLoginAt(),
                    account.getCreatedAt(),
                    account.accountAgeDays());
        }
    }

    /**
     * Identity view served to other services over {@code /internal/users/{userId}}.
     *
     * <p>Carries the behavioural attributes the fraud model needs (account age, KYC
     * level, last-login context) and nothing else. The fraud service has no reason
     * to know a user's phone number, so it is not sent.
     */
    @Schema(description = "Internal identity view for service-to-service calls")
    public record InternalUserView(
            String userId,
            String email,
            String fullName,
            Set<String> roles,
            UserAccount.Status status,
            UserAccount.KycLevel kycLevel,
            long accountAgeDays,
            Instant lastLoginAt,
            String lastLoginIp,
            boolean active
    ) {
        public static InternalUserView from(UserAccount account) {
            return new InternalUserView(
                    account.getUserId(),
                    account.getEmail(),
                    account.getFullName(),
                    account.getRoles(),
                    account.getStatus(),
                    account.getKycLevel(),
                    account.accountAgeDays(),
                    account.getLastLoginAt(),
                    account.getLastLoginIp(),
                    account.isAuthenticationAllowed());
        }
    }

    /** One active session, for the "where am I logged in" view. */
    @Schema(description = "An active session")
    public record SessionResponse(
            String sessionId,
            String userAgent,
            String ipAddress,
            Instant issuedAt,
            Instant expiresAt
    ) {
    }
}
