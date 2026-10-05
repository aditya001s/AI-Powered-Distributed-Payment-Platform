package com.aegispay.auth.repository;

import com.aegispay.auth.domain.ServiceClient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ServiceClientRepository extends JpaRepository<ServiceClient, Long> {

    Optional<ServiceClient> findByClientId(String clientId);
}
