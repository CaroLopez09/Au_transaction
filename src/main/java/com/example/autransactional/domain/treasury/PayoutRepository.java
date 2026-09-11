package com.example.autransactional.domain.treasury;

import com.example.autransactional.domain.shared.IdempotencyKey;
import com.example.autransactional.domain.shared.TenantId;

import java.util.List;
import java.util.Optional;

public interface PayoutRepository {

    Payout save(Payout payout);

    /** Toda lectura se filtra por tenant, aunque Kira trate el recurso como global del integrador. */
    Optional<Payout> findByIdAndTenant(String id, TenantId tenantId);

    Optional<Payout> findByIdempotencyKey(IdempotencyKey key);

    Optional<Payout> findByKiraPayoutId(String kiraPayoutId);

    List<Payout> findByTenant(TenantId tenantId, int limit);

    /** Pagos que Kira ya conoce y siguen sin estado terminal: material del reconciliador. */
    List<Payout> findInFlight(int limit);
}
