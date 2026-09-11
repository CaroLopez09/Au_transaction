package com.example.autransactional.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DepositJpaRepository extends JpaRepository<DepositEntity, String> {

    Optional<DepositEntity> findByKiraDepositId(String kiraDepositId);

    List<DepositEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<DepositEntity> findByVirtualAccountIdOrderByCreatedAtDesc(String virtualAccountId);
}
