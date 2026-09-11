package com.example.autransactional.infrastructure.audit;

import com.example.autransactional.domain.compliance.AuditLog;
import com.example.autransactional.domain.compliance.AuditLogRepository;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bitacora de acciones sensibles. Registra actor, organizacion, recurso, clave de idempotencia
 * y resultado; nunca secretos ni datos personales.
 *
 * El detalle va como JSON en `changes` porque el esquema v2 tiene una sola columna para el
 * contexto de la accion: meter ahi campos sueltos obligaria a migrar la tabla cada vez que
 * una accion nueva quiera anotar algo distinto.
 */
@Component
public class AuditTrail {

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;

    public AuditTrail(AuditLogRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void record(AuthenticatedOperator operator, String action, String resourceType,
                       String resourceId, String idempotencyKey, String result, String detail) {
        Map<String, Object> changes = new LinkedHashMap<>();
        if (idempotencyKey != null) {
            changes.put("idempotencyKey", idempotencyKey);
        }
        if (result != null) {
            changes.put("result", result);
        }
        if (detail != null) {
            changes.put("detail", detail);
        }

        repository.append(new AuditLog(
                UUID.randomUUID().toString(),
                operator == null ? null : operator.tenantId(),
                operator == null ? null : operator.userId(),
                operator == null ? null : operator.role(),
                action,
                resourceType,
                resourceId,
                changes.isEmpty() ? null : objectMapper.writeValueAsString(changes),
                clientIp(),
                Instant.now()));
    }

    /**
     * IP del cliente. Fuera de una peticion HTTP (reconciliacion, proyeccion de webhooks)
     * no hay ninguna, y eso es un dato tan valido como cualquier otro.
     */
    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                // El primer salto es el cliente real; el resto son los proxies intermedios.
                return forwarded.split(",")[0].trim();
            }
            return request.getRemoteAddr();
        }
        return null;
    }
}
