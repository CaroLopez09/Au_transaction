package com.example.autransactional.infrastructure.persistence;

import com.example.autransactional.domain.treasury.RecipientStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RecipientJpaRepository extends JpaRepository<RecipientEntity, String> {

    Optional<RecipientEntity> findByIdAndTenantId(String id, String tenantId);

    Optional<RecipientEntity> findByKiraRecipientId(String kiraRecipientId);

    List<RecipientEntity> findByTenantIdAndStatusOrderByNameAsc(String tenantId, RecipientStatus status);
}
