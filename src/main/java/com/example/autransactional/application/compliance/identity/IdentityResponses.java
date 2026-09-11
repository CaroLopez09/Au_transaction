package com.example.autransactional.application.compliance.identity;

import com.example.autransactional.domain.compliance.identity.DocumentData;
import com.example.autransactional.domain.compliance.identity.VerificationVerdict;

import java.time.Instant;

/** Formas de respuesta exactas que espera la libreria de frontend. */
public final class IdentityResponses {

    private IdentityResponses() {
    }

    public record LivenessConfig(boolean enabled, String identityPoolId, String region) {
    }

    public record LivenessStatus(boolean enabled) {
    }

    public record LivenessSession(String sessionId, Instant createdAt, Instant expiresAt,
                                  String verificationId) {
    }

    public record LivenessResult(String sessionId, String status, double confidence,
                                 boolean passed, String referenceImageBase64) {
    }

    public record ChallengeSession(String challengeId, String challengeNumber, Instant expiresAt,
                                   String verificationId) {
    }

    public record ChallengeVerifyResult(boolean passed, String transcribedText, String normalizedNumber,
                                        double confidence, String failureReason) {
    }

    public record ChallengeStatus(boolean valid, boolean used, boolean expired) {
    }

    /**
     * Respuesta de /identity/validate. 'status' es el veredicto del servidor y es lo unico
     * que debe decidir si la identidad es valida.
     */
    public record ValidationResult(String verificationId, VerificationVerdict status,
                                   double livenessScore, double matchScore,
                                   DocumentData documentData, String failureCode) {
    }
}
