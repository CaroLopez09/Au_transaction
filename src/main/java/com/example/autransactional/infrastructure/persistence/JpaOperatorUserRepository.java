package com.example.autransactional.infrastructure.persistence;

import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.OperatorUser;
import com.example.autransactional.domain.tenant.OperatorUserRepository;
import com.example.autransactional.domain.tenant.Role;
import com.example.autransactional.domain.tenant.UserStatus;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JpaOperatorUserRepository implements OperatorUserRepository {

    private final OperatorUserJpaRepository jpa;
    private final RoleJpaRepository roles;

    public JpaOperatorUserRepository(OperatorUserJpaRepository jpa, RoleJpaRepository roles) {
        this.jpa = jpa;
        this.roles = roles;
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
    public boolean existsByEmail(String email) {
        return jpa.findByEmailIgnoreCase(email).isPresent();
    }

    /**
     * El rol es una FK a `roles`: se resuelve por su nombre tecnico, no por la constante del enum.
     * Si la fila no existe el alta falla aqui y no a mitad del flush, con un mensaje util.
     */
    @Override
    @org.springframework.transaction.annotation.Transactional
    public OperatorUser create(OperatorUser user) {
        RoleEntity role = roles.findByName(user.role().dbName())
                .orElseThrow(() -> new IllegalStateException(
                        "El rol " + user.role().dbName() + " no esta en la tabla `roles`."));

        OperatorUserEntity entity = new OperatorUserEntity();
        entity.setId(user.id());
        entity.setTenantId(user.tenantId() == null || user.tenantId().isPlatform()
                ? null : user.tenantId().value());
        entity.setEmail(user.email());
        entity.setPasswordHash(user.passwordHash());
        entity.setFirstName(user.firstName());
        entity.setLastName(user.lastName());
        entity.setRole(role);
        entity.setStatus(user.status());
        entity.setMfaEnabled(false);
        return toDomain(jpa.save(entity));
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void updateStatus(String userId, UserStatus status) {
        OperatorUserEntity entity = jpa.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Usuario inexistente: " + userId));
        entity.setStatus(status);
        entity.setUpdatedAt(java.time.Instant.now());
        jpa.save(entity);
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
