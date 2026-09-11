package com.example.autransactional.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VirtualAccountJpaRepository extends JpaRepository<VirtualAccountEntity, String> {

    Optional<VirtualAccountEntity> findByIdAndTenantId(String id, String tenantId);

    Optional<VirtualAccountEntity> findByKiraAccountId(String kiraAccountId);

    List<VirtualAccountEntity> findByTenantId(String tenantId);
}
