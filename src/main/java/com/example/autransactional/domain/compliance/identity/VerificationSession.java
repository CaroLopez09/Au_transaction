package com.example.autransactional.domain.compliance.identity;

import com.example.autransactional.domain.shared.TenantId;
import lombok.Getter;

import java.time.Instant;

/**
 * Raiz del agregado de verificacion de identidad.
 *
 * Existe sobre todo para atar las tres pruebas entre si. La recomendacion S-2 del documento
 * de integracion lo dice sin rodeos: hoy nada impide que un cliente manipulado combine un
 * liveness valido de una sesion con documentos de otra. Aqui el reto de voz, la prueba de
 * vida y los documentos solo valen si pertenecen a esta misma sesion.
 */
@Getter
public class VerificationSession {

    private final String id;
    private final TenantId tenantId;
    private final String correlationId;
    private final Instant createdAt;
    private final Instant expiresAt;

    private String countryCode;
    private String documentType;
    private String kiraUserId;

    private NumberChallenge challenge;
    private boolean challengePassed;
    private double challengeConfidence;

    private String livenessSessionId;
    private boolean livenessPassed;
    private double livenessScore;

    private double matchScore;
    private DocumentData documentData;

    private VerificationVerdict verdict;
    private IdentityErrorCode failureCode;
    private Instant completedAt;

    public VerificationSession(String id, TenantId tenantId, String correlationId, Instant expiresAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.correlationId = correlationId;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
        this.verdict = VerificationVerdict.PENDING;
    }

    public static VerificationSession rehydrate(String id, TenantId tenantId, String correlationId,
                                                Instant createdAt, Instant expiresAt, String countryCode,
                                                String documentType, String kiraUserId,
                                                NumberChallenge challenge, boolean challengePassed,
                                                double challengeConfidence, String livenessSessionId,
                                                boolean livenessPassed, double livenessScore,
                                                double matchScore, DocumentData documentData,
                                                VerificationVerdict verdict, IdentityErrorCode failureCode,
                                                Instant completedAt) {
        VerificationSession s = new VerificationSession(id, tenantId, correlationId, expiresAt);
        s.countryCode = countryCode;
        s.documentType = documentType;
        s.kiraUserId = kiraUserId;
        s.challenge = challenge;
        s.challengePassed = challengePassed;
        s.challengeConfidence = challengeConfidence;
        s.livenessSessionId = livenessSessionId;
        s.livenessPassed = livenessPassed;
        s.livenessScore = livenessScore;
        s.matchScore = matchScore;
        s.documentData = documentData;
        s.verdict = verdict;
        s.failureCode = failureCode;
        s.completedAt = completedAt;
        return s;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    private void assertUsable(Instant now) {
        if (isExpired(now)) {
            throw new IdentityException(IdentityErrorCode.SESSION_NOT_FOUND);
        }
        if (verdict.isFinal()) {
            throw new IdentityException(IdentityErrorCode.SESSION_NOT_FOUND,
                    "Esta sesion de verificacion ya fue resuelta.");
        }
    }

    public void describeSubject(String countryCode, String documentType, String kiraUserId) {
        this.countryCode = countryCode;
        this.documentType = documentType;
        this.kiraUserId = kiraUserId;
    }

    public void issueChallenge(NumberChallenge challenge, Instant now) {
        assertUsable(now);
        this.challenge = challenge;
        this.challengePassed = false;
    }

    public NumberChallenge requireChallenge(String challengeId) {
        if (challenge == null || !challenge.getId().equals(challengeId)) {
            throw new IdentityException(IdentityErrorCode.SESSION_MISMATCH);
        }
        return challenge;
    }

    public void markChallengePassed(double confidence, Instant now) {
        assertUsable(now);
        this.challengePassed = true;
        this.challengeConfidence = confidence;
    }

    public void attachLivenessSession(String livenessSessionId, Instant now) {
        assertUsable(now);
        // El orden importa: la prueba de vida solo se ofrece a quien ya demostro voz y labios.
        if (!challengePassed) {
            throw new IdentityException(IdentityErrorCode.VERIFICATION_INCOMPLETE,
                    "Completa el reto de voz antes de la prueba de vida.");
        }
        this.livenessSessionId = livenessSessionId;
        this.livenessPassed = false;
    }

    public void requireLivenessSession(String livenessSessionId) {
        if (this.livenessSessionId == null || !this.livenessSessionId.equals(livenessSessionId)) {
            throw new IdentityException(IdentityErrorCode.SESSION_MISMATCH);
        }
    }

    public void markLivenessPassed(double confidence, Instant now) {
        assertUsable(now);
        this.livenessPassed = true;
        this.livenessScore = confidence;
    }

    /** Las tres pruebas deben estar completas y ser de esta sesion antes de emitir veredicto. */
    public void assertReadyForValidation(String challengeId, String livenessSessionId, Instant now) {
        assertUsable(now);
        requireChallenge(challengeId);
        requireLivenessSession(livenessSessionId);
        if (!challengePassed || !livenessPassed) {
            throw new IdentityException(IdentityErrorCode.VERIFICATION_INCOMPLETE);
        }
    }

    /** Emite el veredicto. Es la unica via para pasar de PENDING a un estado final. */
    public VerificationVerdict decide(FaceMatch faceMatch, DocumentData documentData,
                                      VerificationThresholds thresholds, Instant now) {
        assertUsable(now);
        this.documentData = documentData;
        this.matchScore = faceMatch.similarity();
        this.completedAt = now;

        if (!faceMatch.hasSingleFace()) {
            this.failureCode = faceMatch.facesFoundInDocument() == 0
                    ? IdentityErrorCode.DOCUMENT_UNREADABLE
                    : IdentityErrorCode.MULTIPLE_FACES;
            this.verdict = VerificationVerdict.REJECTED;
            return this.verdict;
        }

        this.verdict = thresholds.decide(livenessScore, matchScore);
        if (this.verdict == VerificationVerdict.REJECTED) {
            this.failureCode = livenessScore < thresholds.minLivenessScore()
                    ? IdentityErrorCode.LIVENESS_FAILED
                    : IdentityErrorCode.SPOOFING_DETECTED;
        }
        return this.verdict;
    }

    public void failWith(IdentityErrorCode code, Instant now) {
        this.failureCode = code;
        this.verdict = VerificationVerdict.REJECTED;
        this.completedAt = now;
    }
}
