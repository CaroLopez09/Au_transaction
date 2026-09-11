package com.example.autransactional.domain.compliance;

import com.example.autransactional.domain.shared.TenantId;

import java.util.List;

/** Puerto de salida de la bitacora. Solo escritura y lectura: nunca actualizacion ni borrado. */
public interface AuditLogRepository {

    AuditLog append(AuditLog entry);

    List<AuditLog> findByTenant(TenantId tenantId, int limit);
}
