package com.example.autransactional.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogJpaRepository extends JpaRepository<AuditLogEntity, String> {

    List<AuditLogEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}
