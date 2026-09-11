package com.example.autransactional.infrastructure.persistence;

import com.example.autransactional.domain.compliance.RfiStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RfiJpaRepository extends JpaRepository<RfiEntity, String> {

    Optional<RfiEntity> findByIdAndTenantId(String id, String tenantId);

    Optional<RfiEntity> findByKiraRfiId(String kiraRfiId);

    List<RfiEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<RfiEntity> findByTenantIdAndStatusInOrderByCreatedAtDesc(String tenantId,
                                                                 Collection<RfiStatus> statuses);
}
