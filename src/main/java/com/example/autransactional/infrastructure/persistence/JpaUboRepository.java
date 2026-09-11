package com.example.autransactional.infrastructure.persistence;

import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.LivenessStatus;
import com.example.autransactional.domain.tenant.Ubo;
import com.example.autransactional.domain.tenant.UboRepository;
import com.example.autransactional.domain.tenant.UboRoster;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JpaUboRepository implements UboRepository {

    private final UboJpaRepository jpa;

    public JpaUboRepository(UboJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Ubo save(Ubo ubo) {
        UboEntity e = jpa.findById(ubo.getId()).orElseGet(UboEntity::new);
        e.setId(ubo.getId());
        e.setTenantId(ubo.getTenantId().value());
        e.setPersonReferenceId(ubo.getPersonReferenceId());
        e.setFirstName(ubo.getFirstName());
        e.setLastName(ubo.getLastName());
        e.setDocumentType(ubo.getDocumentType());
        e.setDocumentNumber(ubo.getDocumentNumber());
        e.setOwnershipPercentage(ubo.getOwnershipPercentage());
        e.setRoleInCompany(ubo.getRoleInCompany());
        e.setHasOwnership(ubo.isHasOwnership());
        e.setHasControl(ubo.isHasControl());
        e.setSigner(ubo.isSigner());
        e.setPoliticallyExposed(ubo.isPoliticallyExposed());
        e.setCountryOfBirth(ubo.getCountryOfBirth());
        e.setLivenessStatus(ubo.getLivenessStatus());
        e.setLivenessLink(ubo.getLivenessLink());
        e.setLivenessExpiresAt(ubo.getLivenessExpiresAt());
        e.setCreatedAt(ubo.getCreatedAt());
        e.setUpdatedAt(ubo.getUpdatedAt());
        jpa.save(e);
        return ubo;
    }

    @Override
    public Optional<Ubo> findByIdAndTenant(String id, TenantId tenantId) {
        return jpa.findByIdAndTenantId(id, tenantId.value()).map(JpaUboRepository::toDomain);
    }

    @Override
    public Optional<Ubo> findByPersonReferenceId(String personReferenceId) {
        return jpa.findByPersonReferenceId(personReferenceId).map(JpaUboRepository::toDomain);
    }

    @Override
    public List<Ubo> findByTenant(TenantId tenantId) {
        return jpa.findByTenantId(tenantId.value()).stream().map(JpaUboRepository::toDomain).toList();
    }

    @Override
    public UboRoster rosterOf(TenantId tenantId) {
        return new UboRoster(findByTenant(tenantId));
    }

    @Override
    public List<Ubo> findPendingLivenessExpiredBefore(Instant cutoff) {
        return jpa.findByLivenessStatusAndLivenessExpiresAtBefore(LivenessStatus.PENDING, cutoff).stream()
                .map(JpaUboRepository::toDomain)
                .toList();
    }

    private static Ubo toDomain(UboEntity e) {
        return Ubo.rehydrate(e.getId(), TenantId.of(e.getTenantId()), e.getPersonReferenceId(),
                e.getFirstName(), e.getLastName(), e.getDocumentType(), e.getDocumentNumber(),
                e.getOwnershipPercentage(), e.getRoleInCompany(), e.isHasOwnership(), e.isHasControl(),
                e.isSigner(), e.isPoliticallyExposed(), e.getCountryOfBirth(), e.getLivenessStatus(),
                e.getLivenessLink(), e.getLivenessExpiresAt(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
