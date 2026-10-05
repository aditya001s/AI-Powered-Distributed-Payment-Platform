package com.aegispay.orchestrator.repository;

import com.aegispay.orchestrator.domain.ProviderState;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProviderStateRepository extends JpaRepository<ProviderState, String> {

    List<ProviderState> findAllByOrderByProviderIdAsc();

    /**
     * Providers an operator has not switched off.
     *
     * <p>Only the {@code enabled} flag is filtered here, not health. Health is read from the live
     * circuit breaker at routing time, because a stored health value is stale the moment it is
     * written and routing on it would send traffic to a provider that failed a second ago.
     */
    @Query("SELECT p FROM ProviderState p WHERE p.enabled = true ORDER BY p.providerId")
    List<ProviderState> findEnabled();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("SELECT p FROM ProviderState p WHERE p.providerId = :providerId")
    Optional<ProviderState> findByIdForUpdate(@Param("providerId") String providerId);

    long countByEnabled(boolean enabled);
}
