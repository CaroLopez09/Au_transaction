package com.example.autransactional.application.compliance.identity;

import com.example.autransactional.domain.compliance.identity.*;
import com.example.autransactional.infrastructure.identity.IdentityProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Prueba de vida: configuracion para el navegador, creacion de sesion y lectura del resultado. */
@Service
public class LivenessUseCase {

    private final LivenessProvider provider;
    private final VerificationSessionRepository sessions;
    private final VerificationSessionFactory factory;
    private final IdentityProperties properties;

    public LivenessUseCase(LivenessProvider provider, VerificationSessionRepository sessions,
                           VerificationSessionFactory factory, IdentityProperties properties) {
        this.provider = provider;
        this.sessions = sessions;
        this.factory = factory;
        this.properties = properties;
    }

    /** Solo datos publicos: pool de identidad y region. Ninguna credencial sale de aqui. */
    public IdentityResponses.LivenessConfig config() {
        var config = provider.config();
        return new IdentityResponses.LivenessConfig(
                config.enabled() && properties.livenessEnabled(), config.identityPoolId(), config.region());
    }

    public IdentityResponses.LivenessStatus status() {
        return new IdentityResponses.LivenessStatus(provider.config().enabled() && properties.livenessEnabled());
    }

    @Transactional
    public IdentityResponses.LivenessSession createSession(String clientId, String verificationId,
                                                           String correlationId) {
        if (!properties.livenessEnabled()) {
            throw new IdentityException(IdentityErrorCode.LIVENESS_NOT_ENABLED);
        }
        VerificationSession session = factory.resolve(clientId, verificationId, correlationId);

        var created = provider.createSession();
        session.attachLivenessSession(created.sessionId(), Instant.now());
        sessions.save(session);

        return new IdentityResponses.LivenessSession(created.sessionId(), created.createdAt(),
                created.expiresAt(), session.getId());
    }

    /**
     * Consulta el resultado y lo asienta en la sesion. El frontend no decide nada con esto:
     * solo sabra si puede avanzar al paso del documento.
     */
    @Transactional
    public IdentityResponses.LivenessResult result(String livenessSessionId) {
        VerificationSession session = sessions.findByLivenessSessionId(livenessSessionId)
                .orElseThrow(() -> new IdentityException(IdentityErrorCode.SESSION_NOT_FOUND));

        LivenessOutcome outcome = provider.result(livenessSessionId);
        Instant now = Instant.now();

        if (outcome.passed()) {
            session.markLivenessPassed(outcome.confidence(), now);
            sessions.save(session);
        }

        return new IdentityResponses.LivenessResult(outcome.sessionId(), outcome.status(),
                outcome.confidence(), outcome.passed(), outcome.referenceImageBase64());
    }
}
