package com.example.autransactional.domain.treasury;

import com.example.autransactional.domain.shared.TenantId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface QuotationRepository {

    Quotation save(Quotation quotation);

    Optional<Quotation> findByIdAndTenant(String id, TenantId tenantId);

    List<Quotation> findByTenant(TenantId tenantId, int limit);

    /** Cotizaciones ACTIVE cuyo TTL ya paso: el reconciliador las cierra. */
    List<Quotation> findActiveExpiredBefore(Instant cutoff);
}
