package com.example.autransactional.infrastructure.persistence;

import com.example.autransactional.domain.treasury.QuotationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface QuotationJpaRepository extends JpaRepository<QuotationEntity, String> {

    Optional<QuotationEntity> findByIdAndTenantId(String id, String tenantId);

    List<QuotationEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<QuotationEntity> findByStatusAndQuoteExpiresAtBefore(QuotationStatus status, Instant cutoff);
}
