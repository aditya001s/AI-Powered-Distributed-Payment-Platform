package com.aegispay.auth.service;

import com.aegispay.auth.domain.AuditLog;
import com.aegispay.auth.repository.AuditLogRepository;
import com.aegispay.common.correlation.CorrelationContext;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Writes the security audit trail.
 *
 * <p><strong>{@link Propagation#REQUIRES_NEW} is the important detail.</strong>
 * A failed login must be recorded even though the surrounding request ends in an
 * exception. If the audit write joined the caller's transaction it would roll back
 * with it, and the system would keep no record of exactly the events that matter
 * most — failed logins, reuse detection, denied service tokens. A separate
 * transaction commits independently of the outcome being audited.
 *
 * <p>The trade-off is that an audit entry can exist for an operation that later
 * rolled back. For a security log that is the correct bias: "we attempted X and it
 * failed" is useful, "no evidence X was ever attempted" is not.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditLog.EventType eventType,
                       AuditLog.Outcome outcome,
                       String actorId,
                       String targetId,
                       String description,
                       RequestContext context,
                       Map<String, Object> metadata) {
        try {
            AuditLog entry = AuditLog.record(
                    eventType,
                    outcome,
                    actorId,
                    targetId,
                    description,
                    context == null ? null : context.ipAddress(),
                    context == null ? null : context.userAgent(),
                    CorrelationContext.get(),
                    serialise(metadata));
            repository.save(entry);
        } catch (RuntimeException ex) {
            // An audit failure must not break the user's request, but it is a
            // serious operational problem, so it is logged at ERROR for alerting.
            log.error("Failed to write audit entry {} for actor {}", eventType, actorId, ex);
        }
    }

    public void record(AuditLog.EventType eventType, AuditLog.Outcome outcome,
                       String actorId, String description, RequestContext context) {
        record(eventType, outcome, actorId, null, description, context, Map.of());
    }

    /** Counts recent failed logins from one IP, for the credential-stuffing signal. */
    @Transactional(readOnly = true)
    public long recentFailuresFromIp(String ipAddress, Duration window) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return 0L;
        }
        return repository.countRecentFailuresFromIp(ipAddress, Instant.now().minus(window));
    }

    private String serialise(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception ex) {
            log.warn("Could not serialise audit metadata; storing without it", ex);
            return null;
        }
    }
}
