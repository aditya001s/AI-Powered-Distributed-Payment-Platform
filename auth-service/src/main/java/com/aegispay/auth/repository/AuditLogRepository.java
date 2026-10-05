package com.aegispay.auth.repository;

import com.aegispay.auth.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    Page<AuditLog> findByActorIdOrderByOccurredAtDesc(String actorId, Pageable pageable);

    Page<AuditLog> findByEventTypeOrderByOccurredAtDesc(AuditLog.EventType eventType, Pageable pageable);

    Page<AuditLog> findByOutcomeOrderByOccurredAtDesc(AuditLog.Outcome outcome, Pageable pageable);

    /**
     * Counts recent login failures from one IP.
     *
     * <p>Complements per-account lockout rather than duplicating it. An attacker
     * spreading a few attempts across thousands of accounts never trips any single
     * account's counter, but does show up strongly here — that is the credential
     * stuffing signature.
     */
    @Query("""
            SELECT COUNT(a) FROM AuditLog a
            WHERE a.eventType = 'LOGIN_FAILED'
              AND a.ipAddress = :ipAddress
              AND a.occurredAt > :since
            """)
    long countRecentFailuresFromIp(@Param("ipAddress") String ipAddress,
                                   @Param("since") Instant since);
}
