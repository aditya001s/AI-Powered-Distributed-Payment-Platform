package com.aegispay.notification.repository;

import com.aegispay.notification.domain.NotificationPreferences;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NotificationPreferencesRepository
        extends JpaRepository<NotificationPreferences, String> {

    Optional<NotificationPreferences> findByUserId(String userId);

    long countBySmsEnabledTrue();
}
