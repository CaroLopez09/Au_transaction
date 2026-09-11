package com.example.autransactional.domain.account;

import com.example.autransactional.domain.shared.TenantId;

import java.util.List;
import java.util.Optional;

public interface DepositRepository {

    Deposit save(Deposit deposit);

    Optional<Deposit> findByKiraDepositId(String kiraDepositId);

    List<Deposit> findByTenant(TenantId tenantId, int limit);

    List<Deposit> findByVirtualAccount(String virtualAccountId, int limit);
}
