package com.example.autransactional.infrastructure.persistence;

import com.example.autransactional.domain.treasury.PayoutStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PayoutJpaRepository extends JpaRepository<PayoutEntity, String> {

    Optional<PayoutEntity> findByIdAndTenantId(String id, String tenantId);

    Optional<PayoutEntity> findByIdempotencyKey(String idempotencyKey);

    Optional<PayoutEntity> findByKiraPayoutId(String kiraPayoutId);

    List<PayoutEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<PayoutEntity> findByKiraPayoutIdIsNotNullAndStatusInOrderByUpdatedAtAsc(
            Collection<PayoutStatus> statuses);
}
