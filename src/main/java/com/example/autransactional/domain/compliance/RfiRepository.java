package com.example.autransactional.domain.compliance;

import com.example.autransactional.domain.shared.TenantId;

import java.util.List;
import java.util.Optional;

public interface RfiRepository {

    Rfi save(Rfi rfi);

    Optional<Rfi> findByIdAndTenant(String id, TenantId tenantId);

    Optional<Rfi> findByKiraRfiId(String kiraRfiId);

    List<Rfi> findByTenant(TenantId tenantId);

    List<Rfi> findOpenByTenant(TenantId tenantId);
}
