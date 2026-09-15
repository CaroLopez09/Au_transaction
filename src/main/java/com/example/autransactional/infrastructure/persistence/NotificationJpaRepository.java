package com.example.autransactional.infrastructure.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface NotificationJpaRepository extends JpaRepository<NotificationEntity, String> {

    List<NotificationEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable page);

    long countByTenantIdAndCreatedAtAfter(String tenantId, Instant since);
}
