package com.example.autransactional.infrastructure.identity;

import com.example.autransactional.domain.compliance.identity.LivenessOutcome;
import com.example.autransactional.domain.compliance.identity.LivenessProvider;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adaptador de desarrollo de la prueba de vida.
 *
 * Deja el flujo completo ejecutable y testeable sin cuenta de AWS. El resultado es
 * determinista y se controla con bff.identity.dev-liveness-passes, para poder recorrer el
 * camino de rechazo a voluntad. Nada de aleatoriedad: haria intermitentes las pruebas.
 *
 * Se activa con bff.identity.provider=dev, que es el valor por defecto. El adaptador de AWS
 * se registrara con provider=aws sin tocar dominio ni controladores.
 */
public class DevLivenessProvider implements LivenessProvider {

    private static final String PIXEL_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    private final Map<String, Instant> sessions = new ConcurrentHashMap<>();
    private final IdentityProperties properties;

    public DevLivenessProvider(IdentityProperties properties) {
        this.properties = properties;
    }

    @Override
    public Config config() {
        var aws = properties.aws();
        return new Config(properties.livenessEnabled(),
                aws != null ? aws.identityPoolId() : "dev-identity-pool",
                aws != null ? aws.region() : "us-east-1");
    }

    @Override
    public Session createSession() {
        String sessionId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(properties.sessionTtlSeconds());
        sessions.put(sessionId, expiresAt);
        return new Session(sessionId, now, expiresAt);
    }

    @Override
    public LivenessOutcome result(String sessionId) {
        Instant expiresAt = sessions.get(sessionId);
        if (expiresAt == null) {
            return new LivenessOutcome(sessionId, "NOT_FOUND", 0, false, null);
        }
        if (Instant.now().isAfter(expiresAt)) {
            return new LivenessOutcome(sessionId, "EXPIRED", 0, false, null);
        }
        boolean passed = properties.devLivenessPasses();
        return new LivenessOutcome(sessionId, passed ? "SUCCEEDED" : "FAILED",
                passed ? 96.5 : 12.0, passed, passed ? PIXEL_PNG_BASE64 : null);
    }
}
