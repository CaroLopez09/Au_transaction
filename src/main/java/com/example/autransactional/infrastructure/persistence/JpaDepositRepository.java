package com.example.autransactional.infrastructure.persistence;

import com.example.autransactional.domain.account.Deposit;
import com.example.autransactional.domain.account.DepositRepository;
import com.example.autransactional.domain.shared.TenantId;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JpaDepositRepository implements DepositRepository {

    private final DepositJpaRepository jpa;

    public JpaDepositRepository(DepositJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Deposit save(Deposit deposit) {
        DepositEntity e = jpa.findById(deposit.getId()).orElseGet(DepositEntity::new);
        e.setId(deposit.getId());
        e.setTenantId(deposit.getTenantId().value());
        e.setVirtualAccountId(deposit.getVirtualAccountId());
        e.setKiraDepositId(deposit.getKiraDepositId());
        e.setGrossAmount(deposit.getGrossAmount());
        e.setFeeAmount(deposit.getFeeAmount());
        e.setNetAmount(deposit.getNetAmount());
        e.setCurrency(deposit.getCurrency());
        e.setSenderName(deposit.getSenderName());
        e.setSenderAccount(deposit.getSenderAccount());
        e.setRail(deposit.getRail());
        e.setStatus(deposit.getStatus());
        e.setMicrodeposit(deposit.isMicrodeposit());
        e.setCreatedAt(deposit.getCreatedAt());
        e.setUpdatedAt(deposit.getUpdatedAt());
        jpa.save(e);
        return deposit;
    }

    @Override
    public Optional<Deposit> findByKiraDepositId(String kiraDepositId) {
        return jpa.findByKiraDepositId(kiraDepositId).map(JpaDepositRepository::toDomain);
    }

    @Override
    public List<Deposit> findByTenant(TenantId tenantId, int limit) {
        return jpa.findByTenantIdOrderByCreatedAtDesc(tenantId.value()).stream()
                .limit(limit)
                .map(JpaDepositRepository::toDomain)
                .toList();
    }

    @Override
    public List<Deposit> findByVirtualAccount(String virtualAccountId, int limit) {
        return jpa.findByVirtualAccountIdOrderByCreatedAtDesc(virtualAccountId).stream()
                .limit(limit)
                .map(JpaDepositRepository::toDomain)
                .toList();
    }

    private static Deposit toDomain(DepositEntity e) {
        return Deposit.rehydrate(e.getId(), TenantId.of(e.getTenantId()), e.getVirtualAccountId(),
                e.getKiraDepositId(), e.getGrossAmount(), e.getFeeAmount(), e.getNetAmount(),
                e.getCurrency(), e.getSenderName(), e.getSenderAccount(), e.getRail(),
                e.getStatus(), e.isMicrodeposit(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
