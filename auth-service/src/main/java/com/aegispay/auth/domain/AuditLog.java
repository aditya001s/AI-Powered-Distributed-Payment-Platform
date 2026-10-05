package com.aegispay.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * An append-only record of a security-relevant event.
 *
 * <p>Every authentication decision lands here: successful and failed logins,
 * lockouts, token issuance, refresh-reuse detection, password changes, role
 * grants. That is what makes it possible to answer "who accessed this account and
 * when" after an incident, and it is a baseline requirement for any system holding
 * money.
 *
 * <p>Append-only by convention <em>and</em> by grant: the application's database
 * role has INSERT and SELECT on this table but no UPDATE or DELETE (see
 * {@code V2__audit_grants.sql}). An audit trail an attacker can edit after
 * compromising the app is not an audit trail.
 *
 * <p>No credential material is ever stored. {@code metadata} is free-form JSON for
 * non-sensitive context only.
 */
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_actor", columnList = "actor_id, occurred_at"),
        @Index(name = "idx_audit_event", columnList = "event_type, occurred_at"),
        @Index(name = "idx_audit_occurred", columnList = "occurred_at"),
        @Index(name = "idx_audit_outcome", columnList = "outcome, occurred_at")
})
public class AuditLog {

    public enum EventType {
        USER_REGISTERED,
        LOGIN_SUCCEEDED,
        LOGIN_FAILED,
        ACCOUNT_LOCKED,
        ACCOUNT_UNLOCKED,
        ACCOUNT_DISABLED,
        ACCESS_TOKEN_ISSUED,
        REFRESH_TOKEN_ROTATED,
        /** A consumed refresh token was presented again: suspected theft. */
        REFRESH_TOKEN_REUSE_DETECTED,
        REFRESH_TOKEN_REVOKED,
        LOGOUT,
        PASSWORD_CHANGED,
        ROLE_GRANTED,
        ROLE_REVOKED,
        SERVICE_TOKEN_ISSUED,
        SERVICE_TOKEN_DENIED,
        PROFILE_UPDATED
    }

    public enum Outcome {
        SUCCESS,
        FAILURE,
        /** Recorded for review but not blocked. */
        SUSPICIOUS
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 48)
    private EventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 16)
    private Outcome outcome;

    /**
     * Who performed the action: a user ID, a client ID, or {@code anonymous} for a
     * failed login where the account could not be identified.
     */
    @Column(name = "actor_id", length = 64)
    private String actorId;

    /** What was acted on, when different from the actor (e.g. an admin editing a user). */
    @Column(name = "target_id", length = 64)
    private String targetId;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 256)
    private String userAgent;

    /** Correlation ID, so an audit entry links to the full request trace. */
    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    /** Human-readable summary. Must not contain credentials. */
    @Column(name = "description", length = 512)
    private String description;

    /** Additional non-sensitive structured context, JSON-encoded. */
    @Column(name = "metadata", columnDefinition = "text")
    private String metadata;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt = Instant.now();

    protected AuditLog() {
        // JPA
    }

    public static AuditLog record(EventType eventType, Outcome outcome, String actorId,
                                  String targetId, String description, String ipAddress,
                                  String userAgent, String correlationId, String metadata) {
        AuditLog entry = new AuditLog();
        entry.id = UUID.randomUUID();
        entry.eventType = eventType;
        entry.outcome = outcome;
        entry.actorId = actorId;
        entry.targetId = targetId;
        entry.description = truncate(description, 512);
        entry.ipAddress = truncate(ipAddress, 64);
        entry.userAgent = truncate(userAgent, 256);
        entry.correlationId = correlationId;
        entry.metadata = metadata;
        entry.occurredAt = Instant.now();
        return entry;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
    }

    public UUID getId() {
        return id;
    }

    public EventType getEventType() {
        return eventType;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public String getActorId() {
        return actorId;
    }

    public String getTargetId() {
        return targetId;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getDescription() {
        return description;
    }

    public String getMetadata() {
        return metadata;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
