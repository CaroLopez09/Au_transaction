package com.example.autransactional.infrastructure.persistence;

import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.OperatorUser;
import com.example.autransactional.domain.tenant.OperatorUserRepository;
import com.example.autransactional.domain.tenant.Role;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JpaOperatorUserRepository implements OperatorUserRepository {

    private final OperatorUserJpaRepository jpa;

    public JpaOperatorUserRepository(OperatorUserJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<OperatorUser> findByEmail(String email) {
        return jpa.findByEmailIgnoreCase(email).map(JpaOperatorUserRepository::toDomain);
    }

    @Override
    public Optional<OperatorUser> findById(String id) {
        return jpa.findById(id).map(JpaOperatorUserRepository::toDomain);
    }

    @Override
    public List<OperatorUser> findByTenant(TenantId tenantId) {
        return jpa.findByTenantId(tenantId.value()).stream()
                .map(JpaOperatorUserRepository::toDomain)
                .toList();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void updateMfa(String userId, String encryptedSecret, boolean enabled) {
        OperatorUserEntity entity = jpa.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Usuario inexistente: " + userId));
        entity.setMfaSecret(encryptedSecret);
        entity.setMfaEnabled(enabled && encryptedSecret != null);
        entity.setUpdatedAt(java.time.Instant.now());
        jpa.save(entity);
    }

    private static OperatorUser toDomain(OperatorUserEntity e) {
        // Los operadores de la plataforma no tienen empresa: tenant_id nulo en la tabla.
        TenantId tenant = e.getTenantId() == null ? TenantId.PLATFORM : TenantId.of(e.getTenantId());
        return new OperatorUser(e.getId(), tenant, e.getEmail(), e.getPasswordHash(),
                e.getFirstName(), e.getLastName(), Role.fromDbName(e.getRole().getName()),
                e.getStatus(), e.getMfaSecret(), e.isMfaEnabled());
    }
}
