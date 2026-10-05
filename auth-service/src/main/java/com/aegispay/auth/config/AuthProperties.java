package com.aegispay.auth.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Everything that governs credential handling and token issuance.
 *
 * <p>All secrets arrive from the environment. Nothing here has a usable default
 * for a secret value: a blank client secret fails startup rather than silently
 * running with an empty password.
 */
@Validated
@ConfigurationProperties(prefix = "aegis.auth")
public class AuthProperties {

    private final Jwt jwt = new Jwt();
    private final Password password = new Password();
    private final Lockout lockout = new Lockout();
    private final Demo demo = new Demo();
    private List<ServiceClient> serviceClients = List.of();

    public static class Jwt {

        /**
         * PEM-encoded PKCS#8 RSA private key location (a Spring {@code Resource}
         * such as {@code file:/run/secrets/jwt-private.pem}).
         *
         * <p>Blank means "generate an ephemeral key pair at startup", which is
         * convenient locally but invalidates every issued token on restart. Startup
         * refuses to do this when {@code aegis.platform-env=kubernetes}.
         */
        private String privateKeyLocation = "";

        /** Matching X.509 public key. Derived from the private key when blank. */
        private String publicKeyLocation = "";

        /** The {@code iss} claim, and the expected issuer at every validator. */
        @NotBlank
        private String issuer = "https://auth.aegispay.local";

        /** The {@code aud} claim. */
        @NotBlank
        private String audience = "aegispay-platform";

        /**
         * Access token lifetime. Short on purpose: a leaked access token is only
         * useful for this long, and the refresh token covers session continuity.
         */
        @NotNull
        private Duration accessTokenTtl = Duration.ofMinutes(15);

        /** Refresh token lifetime (rotated on every use). */
        @NotNull
        private Duration refreshTokenTtl = Duration.ofDays(30);

        /** Lifetime of a machine (client-credentials) token. */
        @NotNull
        private Duration serviceTokenTtl = Duration.ofMinutes(10);

        /** Tolerance for clock skew between issuer and validators. */
        @NotNull
        private Duration clockSkew = Duration.ofSeconds(30);

        public String getPrivateKeyLocation() {
            return privateKeyLocation;
        }

        public void setPrivateKeyLocation(String privateKeyLocation) {
            this.privateKeyLocation = privateKeyLocation;
        }

        public String getPublicKeyLocation() {
            return publicKeyLocation;
        }

        public void setPublicKeyLocation(String publicKeyLocation) {
            this.publicKeyLocation = publicKeyLocation;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience;
        }

        public Duration getAccessTokenTtl() {
            return accessTokenTtl;
        }

        public void setAccessTokenTtl(Duration accessTokenTtl) {
            this.accessTokenTtl = accessTokenTtl;
        }

        public Duration getRefreshTokenTtl() {
            return refreshTokenTtl;
        }

        public void setRefreshTokenTtl(Duration refreshTokenTtl) {
            this.refreshTokenTtl = refreshTokenTtl;
        }

        public Duration getServiceTokenTtl() {
            return serviceTokenTtl;
        }

        public void setServiceTokenTtl(Duration serviceTokenTtl) {
            this.serviceTokenTtl = serviceTokenTtl;
        }

        public Duration getClockSkew() {
            return clockSkew;
        }

        public void setClockSkew(Duration clockSkew) {
            this.clockSkew = clockSkew;
        }
    }

    public static class Password {

        /**
         * BCrypt cost factor. Each increment doubles hashing time. 12 is roughly
         * 250ms on current server hardware: slow enough to make offline brute force
         * expensive, fast enough not to become a login-path bottleneck.
         */
        @Min(10)
        @Max(15)
        private int bcryptStrength = 12;

        /** Minimum length. Length dominates character-class rules for real strength. */
        @Min(10)
        private int minLength = 12;

        @Max(256)
        private int maxLength = 128;

        /** Require a mix of character classes in addition to the length floor. */
        private boolean requireMixedCase = true;
        private boolean requireDigit = true;
        private boolean requireSpecial = true;

        public int getBcryptStrength() {
            return bcryptStrength;
        }

        public void setBcryptStrength(int bcryptStrength) {
            this.bcryptStrength = bcryptStrength;
        }

        public int getMinLength() {
            return minLength;
        }

        public void setMinLength(int minLength) {
            this.minLength = minLength;
        }

        public int getMaxLength() {
            return maxLength;
        }

        public void setMaxLength(int maxLength) {
            this.maxLength = maxLength;
        }

        public boolean isRequireMixedCase() {
            return requireMixedCase;
        }

        public void setRequireMixedCase(boolean requireMixedCase) {
            this.requireMixedCase = requireMixedCase;
        }

        public boolean isRequireDigit() {
            return requireDigit;
        }

        public void setRequireDigit(boolean requireDigit) {
            this.requireDigit = requireDigit;
        }

        public boolean isRequireSpecial() {
            return requireSpecial;
        }

        public void setRequireSpecial(boolean requireSpecial) {
            this.requireSpecial = requireSpecial;
        }
    }

    public static class Lockout {

        /** Consecutive failures before the account is temporarily locked. */
        @Min(3)
        private int maxFailedAttempts = 6;

        /**
         * Lock duration. Temporary rather than permanent: a permanent lock turns a
         * credential-stuffing attempt against a victim's email into a denial of
         * service against that victim.
         */
        @NotNull
        private Duration lockDuration = Duration.ofMinutes(15);

        /** Window after which the failure counter decays. */
        @NotNull
        private Duration attemptWindow = Duration.ofMinutes(30);

        public int getMaxFailedAttempts() {
            return maxFailedAttempts;
        }

        public void setMaxFailedAttempts(int maxFailedAttempts) {
            this.maxFailedAttempts = maxFailedAttempts;
        }

        public Duration getLockDuration() {
            return lockDuration;
        }

        public void setLockDuration(Duration lockDuration) {
            this.lockDuration = lockDuration;
        }

        public Duration getAttemptWindow() {
            return attemptWindow;
        }

        public void setAttemptWindow(Duration attemptWindow) {
            this.attemptWindow = attemptWindow;
        }
    }

    /**
     * Demo accounts created at startup when enabled.
     *
     * <p>Seeded from configuration rather than from a Flyway migration, because a
     * migration file containing a password hash would be committed to git. These
     * come from the environment and the seeder refuses to run unless explicitly
     * enabled.
     */
    public static class Demo {

        private boolean seedEnabled = false;

        private String userEmail = "demo.user@aegispay.local";
        private String userPassword = "";
        private String merchantEmail = "demo.merchant@aegispay.local";
        private String merchantPassword = "";
        private String adminEmail = "demo.admin@aegispay.local";
        private String adminPassword = "";

        public boolean isSeedEnabled() {
            return seedEnabled;
        }

        public void setSeedEnabled(boolean seedEnabled) {
            this.seedEnabled = seedEnabled;
        }

        public String getUserEmail() {
            return userEmail;
        }

        public void setUserEmail(String userEmail) {
            this.userEmail = userEmail;
        }

        public String getUserPassword() {
            return userPassword;
        }

        public void setUserPassword(String userPassword) {
            this.userPassword = userPassword;
        }

        public String getMerchantEmail() {
            return merchantEmail;
        }

        public void setMerchantEmail(String merchantEmail) {
            this.merchantEmail = merchantEmail;
        }

        public String getMerchantPassword() {
            return merchantPassword;
        }

        public void setMerchantPassword(String merchantPassword) {
            this.merchantPassword = merchantPassword;
        }

        public String getAdminEmail() {
            return adminEmail;
        }

        public void setAdminEmail(String adminEmail) {
            this.adminEmail = adminEmail;
        }

        public String getAdminPassword() {
            return adminPassword;
        }

        public void setAdminPassword(String adminPassword) {
            this.adminPassword = adminPassword;
        }
    }

    /** A machine client permitted to use the client-credentials grant. */
    public static class ServiceClient {

        @NotBlank
        private String clientId;

        /** Plaintext from the environment; stored only as a BCrypt hash. */
        @NotBlank
        private String clientSecret;

        /** Scopes this client may request. */
        private List<String> scopes = List.of("internal");

        /** Roles embedded in the issued token. */
        private List<String> roles = List.of("SERVICE");

        private String description = "";

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public List<String> getScopes() {
            return scopes;
        }

        public void setScopes(List<String> scopes) {
            this.scopes = scopes;
        }

        public List<String> getRoles() {
            return roles;
        }

        public void setRoles(List<String> roles) {
            this.roles = roles;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }

    public Jwt getJwt() {
        return jwt;
    }

    public Password getPassword() {
        return password;
    }

    public Lockout getLockout() {
        return lockout;
    }

    public Demo getDemo() {
        return demo;
    }

    public List<ServiceClient> getServiceClients() {
        return serviceClients;
    }

    public void setServiceClients(List<ServiceClient> serviceClients) {
        this.serviceClients = serviceClients;
    }
}
