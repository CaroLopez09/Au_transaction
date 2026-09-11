package com.example.autransactional.domain.compliance;

import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.Role;

import java.time.Instant;

/**
 * Entrada inmutable de la bitacora de auditoria B2B.
 *
 * Kira no ofrece historial de cambios al integrador, asi que el registro de quien hizo que
 * es propio. Regla que no se negocia: aqui van actor, recurso y resultado; nunca secretos,
 * biometria ni datos personales.
 *
 * tenantId y userId son opcionales a proposito: hay acciones del sistema (reconciliacion,
 * proyeccion de webhooks) que no tienen persona detras.
 */
public record AuditLog(
        String id,
        TenantId tenantId,
        String userId,
        Role userRole,
        String action,
        String resourceType,
        String resourceId,
        String changes,
        String ipAddress,
        Instant createdAt) {

    /** Longitud maxima del JSON de cambios; el resto se recorta antes de persistir. */
    public static final int MAX_CHANGES_LENGTH = 4000;

    public AuditLog {
        if (action == null || action.isBlank()) {
            throw new com.example.autransactional.domain.shared.DomainException(
                    "Una entrada de auditoria sin accion no dice nada.");
        }
        if (changes != null && changes.length() > MAX_CHANGES_LENGTH) {
            changes = changes.substring(0, MAX_CHANGES_LENGTH);
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
