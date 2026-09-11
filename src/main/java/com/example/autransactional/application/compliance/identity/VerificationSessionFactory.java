package com.example.autransactional.application.compliance.identity;

import com.example.autransactional.domain.compliance.identity.IdentityErrorCode;
import com.example.autransactional.domain.compliance.identity.IdentityException;
import com.example.autransactional.domain.compliance.identity.VerificationSession;
import com.example.autransactional.domain.compliance.identity.VerificationSessionRepository;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.infrastructure.identity.IdentityProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Obtiene la sesion de verificacion de una peticion publica.
 *
 * Estos endpoints los llama la persona que se esta vinculando, que todavia no tiene sesion
 * en el BFF. El aislamiento por organizacion se conserva atando cada verificacion al
 * clientId que envia el portal: sin un clientId de una organizacion activa no hay sesion.
 */
@Component
public class VerificationSessionFactory {

    private final VerificationSessionRepository sessions;
    private final TenantRepository tenants;
    private final IdentityProperties properties;

    public VerificationSessionFactory(VerificationSessionRepository sessions, TenantRepository tenants,
                                      IdentityProperties properties) {
        this.sessions = sessions;
        this.tenants = tenants;
        this.properties = properties;
    }

    /** Reutiliza la sesion indicada, o crea una nueva para el clientId recibido. */
    public VerificationSession resolve(String clientId, String verificationId, String correlationId) {
        if (verificationId != null && !verificationId.isBlank()) {
            VerificationSession existing = sessions.findById(verificationId)
                    .orElseThrow(() -> new IdentityException(IdentityErrorCode.SESSION_NOT_FOUND));
            if (existing.isExpired(Instant.now())) {
                throw new IdentityException(IdentityErrorCode.SESSION_NOT_FOUND);
            }
            return existing;
        }
        return create(clientId, correlationId);
    }

    public VerificationSession require(String verificationId) {
        VerificationSession session = sessions.findById(verificationId)
                .orElseThrow(() -> new IdentityException(IdentityErrorCode.SESSION_NOT_FOUND));
        if (session.isExpired(Instant.now())) {
            throw new IdentityException(IdentityErrorCode.SESSION_NOT_FOUND);
        }
        return session;
    }

    private VerificationSession create(String clientId, String correlationId) {
        if (clientId == null || clientId.isBlank()) {
            throw new IdentityException(IdentityErrorCode.UNAUTHORIZED,
                    "Falta el identificador de la organizacion (clientId).");
        }
        Tenant tenant = tenants.findById(TenantId.of(clientId))
                .orElseThrow(() -> new IdentityException(IdentityErrorCode.UNAUTHORIZED,
                        "La organizacion no existe."));
        tenant.assertActive();

        return new VerificationSession(
                UUID.randomUUID().toString(),
                tenant.getId(),
                correlationId != null && !correlationId.isBlank() ? correlationId : UUID.randomUUID().toString(),
                Instant.now().plusSeconds(properties.sessionTtlSeconds()));
    }
}
