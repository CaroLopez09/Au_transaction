package com.example.autransactional.infrastructure.persistence;

import com.example.autransactional.domain.compliance.AuditLog;
import com.example.autransactional.domain.compliance.AuditLogRepository;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.Role;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JpaAuditLogRepository implements AuditLogRepository {

    private final AuditLogJpaRepository jpa;

    public JpaAuditLogRepository(AuditLogJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public AuditLog append(AuditLog entry) {
        AuditLogEntity e = new AuditLogEntity();
        e.setId(entry.id());
        e.setTenantId(entry.tenantId() == null ? null : entry.tenantId().value());
        e.setUserId(entry.userId());
        e.setUserRole(entry.userRole() == null ? null : entry.userRole().dbName());
        e.setAction(entry.action());
        e.setResourceType(entry.resourceType());
        e.setResourceId(entry.resourceId());
        e.setChanges(entry.changes());
        e.setIpAddress(entry.ipAddress());
        e.setCreatedAt(entry.createdAt());
        jpa.save(e);
        return entry;
    }

    @Override
    public List<AuditLog> findByTenant(TenantId tenantId, int limit) {
        return jpa.findByTenantIdOrderByCreatedAtDesc(tenantId.value()).stream()
                .limit(limit)
                .map(JpaAuditLogRepository::toDomain)
                .toList();
    }

    private static AuditLog toDomain(AuditLogEntity e) {
        return new AuditLog(e.getId(),
                e.getTenantId() == null ? null : TenantId.of(e.getTenantId()),
                e.getUserId(),
                e.getUserRole() == null ? null : Role.fromDbName(e.getUserRole()),
                e.getAction(), e.getResourceType(), e.getResourceId(), e.getChanges(),
                e.getIpAddress(), e.getCreatedAt());
    }
}
