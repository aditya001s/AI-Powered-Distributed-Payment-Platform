package com.aegispay.analytics.repository;

import com.aegispay.analytics.domain.StreamState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StreamStateRepository extends JpaRepository<StreamState, String> {

    List<StreamState> findAllByOrderByTopicAsc();
}
