package com.example.autransactional.domain.compliance.identity;

/**
 * Puerto de la prueba de vida. El adaptador por defecto es de desarrollo; el de AWS
 * (Rekognition Face Liveness sobre un Identity Pool de Cognito) se enchufa aqui sin tocar
 * ni el dominio ni los controladores.
 */
public interface LivenessProvider {

    /** Lo que el navegador necesita para configurar el SDK del proveedor. */
    record Config(boolean enabled, String identityPoolId, String region) {
    }

    record Session(String sessionId, java.time.Instant createdAt, java.time.Instant expiresAt) {
    }

    Config config();

    Session createSession();

    LivenessOutcome result(String sessionId);
}
