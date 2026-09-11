package com.example.autransactional.infrastructure.persistence;

import com.example.autransactional.domain.account.VirtualAccount;
import com.example.autransactional.domain.account.VirtualAccountRepository;
import com.example.autransactional.domain.shared.TenantId;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JpaVirtualAccountRepository implements VirtualAccountRepository {

    private final VirtualAccountJpaRepository jpa;

    public JpaVirtualAccountRepository(VirtualAccountJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public VirtualAccount save(VirtualAccount account) {
        VirtualAccountEntity e = jpa.findById(account.getId()).orElseGet(VirtualAccountEntity::new);
        e.setId(account.getId());
        e.setTenantId(account.getTenantId().value());
        e.setKiraAccountId(account.getKiraAccountId());
        e.setBankName(account.getBankName());
        e.setAccountNumber(account.getAccountNumber());
        e.setRoutingNumber(account.getRoutingNumber());
        e.setCurrency(account.getCurrency());
        e.setMode(account.getMode());
        e.setBank(account.getBank());
        e.setDescription(account.getDescription());
        e.setStatus(account.getStatus());
        e.setBalanceAvailable(account.getBalanceAvailable());
        e.setActivatedEventSeen(account.isActivatedEventSeen());
        e.setBalanceRefreshedAt(account.getBalanceRefreshedAt());
        e.setOpeningIdempotencyKey(account.getOpeningIdempotencyKey());
        e.setCreatedAt(account.getCreatedAt());
        e.setUpdatedAt(account.getUpdatedAt());
        jpa.save(e);
        return account;
    }

    @Override
    public Optional<VirtualAccount> findByIdAndTenant(String id, TenantId tenantId) {
        return jpa.findByIdAndTenantId(id, tenantId.value()).map(JpaVirtualAccountRepository::toDomain);
    }

    @Override
    public Optional<VirtualAccount> findByKiraAccountId(String kiraAccountId) {
        return jpa.findByKiraAccountId(kiraAccountId).map(JpaVirtualAccountRepository::toDomain);
    }

    @Override
    public List<VirtualAccount> findByTenant(TenantId tenantId) {
        return jpa.findByTenantId(tenantId.value()).stream()
                .map(JpaVirtualAccountRepository::toDomain)
                .toList();
    }

    private static VirtualAccount toDomain(VirtualAccountEntity e) {
        return VirtualAccount.rehydrate(e.getId(), TenantId.of(e.getTenantId()), e.getKiraAccountId(),
                e.getBankName(), e.getAccountNumber(), e.getRoutingNumber(), e.getCurrency(),
                e.getMode(), e.getBank(), e.getDescription(), e.getStatus(), e.getBalanceAvailable(),
                e.isActivatedEventSeen(), e.getBalanceRefreshedAt(), e.getOpeningIdempotencyKey(),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
