package com.example.autransactional.infrastructure.persistence;

import com.example.autransactional.domain.tenant.LivenessStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UboJpaRepository extends JpaRepository<UboEntity, String> {

    Optional<UboEntity> findByIdAndTenantId(String id, String tenantId);

    Optional<UboEntity> findByPersonReferenceId(String personReferenceId);

    List<UboEntity> findByTenantId(String tenantId);

    List<UboEntity> findByLivenessStatusAndLivenessExpiresAtBefore(LivenessStatus status, Instant cutoff);
}
