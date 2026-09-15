package com.example.autransactional.infrastructure.persistence;

import com.example.autransactional.domain.compliance.Rfi;
import com.example.autransactional.domain.compliance.RfiRepository;
import com.example.autransactional.domain.compliance.RfiStatus;
import com.example.autransactional.domain.shared.TenantId;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public class JpaRfiRepository implements RfiRepository {

    private static final Set<RfiStatus> OPEN = Set.of(RfiStatus.PENDING, RfiStatus.ANSWERED);

    private final RfiJpaRepository jpa;

    public JpaRfiRepository(RfiJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Rfi save(Rfi rfi) {
        RfiEntity e = jpa.findById(rfi.getId()).orElseGet(RfiEntity::new);
        e.setId(rfi.getId());
        e.setTenantId(rfi.getTenantId().value());
        e.setKiraRfiId(rfi.getKiraRfiId());
        e.setStatus(rfi.getStatus());
        e.setItemsPayload(rfi.getItemsPayload());
        e.setDueDate(rfi.getDueDate());
        e.setBlockingType(rfi.getBlockingType());
        e.setBlockingResourceId(rfi.getBlockingResourceId());
        e.setResolutionReason(rfi.getResolutionReason());
        e.setCreatedAt(rfi.getCreatedAt());
        e.setUpdatedAt(rfi.getUpdatedAt());
        jpa.save(e);
        return rfi;
    }

    @Override
    public Optional<Rfi> findByIdAndTenant(String id, TenantId tenantId) {
        return jpa.findByIdAndTenantId(id, tenantId.value()).map(JpaRfiRepository::toDomain);
    }

    @Override
    public Optional<Rfi> findByKiraRfiId(String kiraRfiId) {
        return jpa.findByKiraRfiId(kiraRfiId).map(JpaRfiRepository::toDomain);
    }

    @Override
    public List<Rfi> findByTenant(TenantId tenantId) {
        return jpa.findByTenantIdOrderByCreatedAtDesc(tenantId.value()).stream()
                .map(JpaRfiRepository::toDomain)
                .toList();
    }

    @Override
    public List<Rfi> findOpenByTenant(TenantId tenantId) {
        return jpa.findByTenantIdAndStatusInOrderByCreatedAtDesc(tenantId.value(), OPEN).stream()
                .map(JpaRfiRepository::toDomain)
                .toList();
    }

    @Override
    public Optional<Rfi> findOpenBlocking(String kiraResourceId) {
        if (kiraResourceId == null) {
            return Optional.empty();
        }
        return jpa.findFirstByBlockingResourceIdAndStatusInOrderByCreatedAtDesc(kiraResourceId, OPEN)
                .map(JpaRfiRepository::toDomain);
    }

    private static Rfi toDomain(RfiEntity e) {
        return Rfi.rehydrate(e.getId(), TenantId.of(e.getTenantId()), e.getKiraRfiId(), e.getStatus(),
                e.getItemsPayload(), e.getDueDate(), e.getBlockingType(), e.getBlockingResourceId(),
                e.getResolutionReason(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
