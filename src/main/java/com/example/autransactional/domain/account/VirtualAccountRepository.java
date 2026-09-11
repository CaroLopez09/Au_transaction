package com.example.autransactional.domain.account;

import com.example.autransactional.domain.shared.TenantId;

import java.util.List;
import java.util.Optional;

public interface VirtualAccountRepository {

    VirtualAccount save(VirtualAccount account);

    Optional<VirtualAccount> findByIdAndTenant(String id, TenantId tenantId);

    Optional<VirtualAccount> findByKiraAccountId(String kiraAccountId);

    List<VirtualAccount> findByTenant(TenantId tenantId);
}
