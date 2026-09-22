package com.example.autransactional.domain.tenant;

import com.example.autransactional.domain.shared.TenantId;

import java.util.List;
import java.util.Optional;

/** Puerto de salida. La implementacion vive en infrastructure/persistence. */
public interface TenantRepository {

    Tenant save(Tenant tenant);

    Optional<Tenant> findById(TenantId id);

    Optional<Tenant> findByKiraUserId(String kiraUserId);

    boolean existsByNameIgnoreCase(String name);

    List<Tenant> findAll();
}
