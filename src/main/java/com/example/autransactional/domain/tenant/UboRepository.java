package com.example.autransactional.domain.tenant;

import com.example.autransactional.domain.shared.TenantId;

import java.util.List;
import java.util.Optional;

public interface UboRepository {

    Ubo save(Ubo ubo);

    void delete(Ubo ubo);

    Optional<Ubo> findByIdAndTenant(String id, TenantId tenantId);

    /** Los webhooks de liveness identifican a la persona por esta referencia de Kira. */
    Optional<Ubo> findByPersonReferenceId(String personReferenceId);

    List<Ubo> findByTenant(TenantId tenantId);

    UboRoster rosterOf(TenantId tenantId);

    /** Enlaces de liveness que ya vencieron y siguen en PENDING: material del reconciliador. */
    List<Ubo> findPendingLivenessExpiredBefore(java.time.Instant cutoff);
}
