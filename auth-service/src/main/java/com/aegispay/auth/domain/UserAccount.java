package com.aegispay.auth.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A platform identity.
 *
 * <p>Named {@code UserAccount} rather than {@code User} because {@code User} is
 * also a Spring Security type, and having both in scope is a reliable source of
 * wrong-import bugs.
 *
 * <p>Lockout state lives on the row rather than in a cache so it survives a
 * restart and is consistent across replicas: an attacker must not be able to reset
 * the failure counter by getting their requests routed to a different pod.
 */
@Entity
@Table(name = "users", indexes = {
        @Index(name = "idx_users_email", columnList = "email", unique = true),
        @Index(name = "idx_users_user_id", columnList = "user_id", unique = true),
        @Index(name = "idx_users_status", columnList = "status")
})
public class UserAccount {

    public enum Status {
        /** Normal. May authenticate and transact. */
        ACTIVE,
        /** Temporarily locked by failed-login throttling. Unlocks automatically. */
        LOCKED,
        /** Administratively disabled. Requires manual reactivation. */
        DISABLED,
        /** Registered but email not yet verified. Limited capabilities. */
        PENDING_VERIFICATION
    }

    /**
     * Know-your-customer tier. Drives transaction limits, and is also a fraud
     * feature: an unverified account moving a large amount is a stronger signal
     * than a fully-verified one doing the same.
     */
    public enum KycLevel {
        NONE,
        BASIC,
        FULL
    }

    /** Surrogate database key. Never exposed over the API. */
    @Id
    @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /**
     * Public, immutable identifier ({@code USR-...}) used in tokens, events and
     * every cross-service reference.
     *
     * <p>Opaque and non-sequential on purpose: a numeric ID in a URL invites
     * enumeration, and leaks how many customers the platform has.
     */
    @Column(name = "user_id", nullable = false, unique = true, updatable = false, length = 40)
    private String userId;

    /** Stored lowercase so lookups are case-insensitive without a functional index. */
    @Column(name = "email", nullable = false, unique = true, length = 254)
    private String email;

    /**
     * Algorithm-prefixed hash, e.g. {@code {bcrypt}$2a$12$...}. Never the password.
     * Excluded from {@link #toString()} so it cannot be logged by accident.
     */
    @Column(name = "password_hash", nullable = false, length = 120)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Column(name = "phone", length = 24)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private Status status = Status.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_level", nullable = false, length = 16)
    private KycLevel kycLevel = KycLevel.BASIC;

    /**
     * Roles as a simple string collection rather than a {@code @ManyToMany} to a
     * roles table. Roles are a small, static vocabulary
     * ({@link com.aegispay.common.security.Roles}); a join table would add a query
     * and a migration to every role change for no gain. {@code EAGER} because roles
     * are needed on literally every authentication.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id", referencedColumnName = "id"),
            indexes = @Index(name = "idx_user_roles_user", columnList = "user_id"))
    @Column(name = "role", nullable = false, length = 32)
    private Set<String> roles = new LinkedHashSet<>();

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts = 0;

    @Column(name = "last_failed_login_at")
    private Instant lastFailedLoginAt;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /** Coarse device/IP context of the last successful login, for the fraud model. */
    @Column(name = "last_login_ip", length = 64)
    private String lastLoginIp;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /** Optimistic locking so two concurrent profile updates cannot clobber each other. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected UserAccount() {
        // JPA
    }

    public static UserAccount register(String userId, String email, String passwordHash,
                                       String fullName, String phone, Set<String> roles) {
        UserAccount account = new UserAccount();
        account.userId = userId;
        account.email = normaliseEmail(email);
        account.passwordHash = passwordHash;
        account.fullName = fullName;
        account.phone = phone;
        account.roles = new LinkedHashSet<>(roles);
        account.status = Status.ACTIVE;
        account.kycLevel = KycLevel.BASIC;
        account.createdAt = Instant.now();
        account.updatedAt = account.createdAt;
        return account;
    }

    public static String normaliseEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    /**
     * True when the account may currently authenticate.
     *
     * <p>Evaluates {@code lockedUntil} against the clock rather than relying on a
     * scheduled unlock job, so a lock always expires on time even if that job is
     * not running.
     */
    public boolean isAuthenticationAllowed() {
        if (status == Status.DISABLED) {
            return false;
        }
        return !isCurrentlyLocked();
    }

    public boolean isCurrentlyLocked() {
        return lockedUntil != null && Instant.now().isBefore(lockedUntil);
    }

    /**
     * Records a failed login and locks the account once the threshold is reached.
     *
     * <p>The counter decays: failures older than {@code attemptWindow} do not count
     * towards a lock, so a user who mistypes once a month is never locked out while
     * a burst of attempts still is.
     */
    public void recordFailedLogin(int maxAttempts, Duration lockDuration, Duration attemptWindow) {
        Instant now = Instant.now();
        boolean windowExpired = lastFailedLoginAt == null
                || lastFailedLoginAt.isBefore(now.minus(attemptWindow));

        this.failedLoginAttempts = windowExpired ? 1 : this.failedLoginAttempts + 1;
        this.lastFailedLoginAt = now;

        if (this.failedLoginAttempts >= maxAttempts) {
            this.lockedUntil = now.plus(lockDuration);
            this.status = Status.LOCKED;
        }
        this.updatedAt = now;
    }

    /** Clears throttling state after a successful login. */
    public void recordSuccessfulLogin(String ipAddress) {
        this.failedLoginAttempts = 0;
        this.lastFailedLoginAt = null;
        this.lockedUntil = null;
        if (this.status == Status.LOCKED) {
            this.status = Status.ACTIVE;
        }
        this.lastLoginAt = Instant.now();
        this.lastLoginIp = ipAddress;
        this.updatedAt = this.lastLoginAt;
    }

    public void changePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
        this.updatedAt = Instant.now();
    }

    public void updateProfile(String fullName, String phone) {
        if (fullName != null && !fullName.isBlank()) {
            this.fullName = fullName;
        }
        this.phone = phone;
        this.updatedAt = Instant.now();
    }

    public void grantRole(String role) {
        this.roles.add(role);
        this.updatedAt = Instant.now();
    }

    public void revokeRole(String role) {
        this.roles.remove(role);
        this.updatedAt = Instant.now();
    }

    public void disable() {
        this.status = Status.DISABLED;
        this.updatedAt = Instant.now();
    }

    public void enable() {
        this.status = Status.ACTIVE;
        this.lockedUntil = null;
        this.failedLoginAttempts = 0;
        this.updatedAt = Instant.now();
    }

    public void setKycLevel(KycLevel kycLevel) {
        this.kycLevel = kycLevel;
        this.updatedAt = Instant.now();
    }

    /** Account age in days. Used directly as a fraud feature. */
    public long accountAgeDays() {
        return Duration.between(createdAt, Instant.now()).toDays();
    }

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public String getPhone() {
        return phone;
    }

    public Status getStatus() {
        return status;
    }

    public KycLevel getKycLevel() {
        return kycLevel;
    }

    public Set<String> getRoles() {
        return Set.copyOf(roles);
    }

    public int getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public String getLastLoginIp() {
        return lastLoginIp;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    /** Excludes the password hash and email deliberately: this ends up in logs. */
    @Override
    public String toString() {
        return "UserAccount{userId='" + userId + "', status=" + status + ", roles=" + roles + '}';
    }
}
