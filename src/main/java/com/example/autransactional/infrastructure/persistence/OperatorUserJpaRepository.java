package com.example.autransactional.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OperatorUserJpaRepository extends JpaRepository<OperatorUserEntity, String> {

    Optional<OperatorUserEntity> findByEmailIgnoreCase(String email);

    List<OperatorUserEntity> findByTenantId(String tenantId);
}
