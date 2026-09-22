package com.example.autransactional.domain.tenant;

import java.time.Instant;

/**
 * Datos de verificacion de un operador. Los archivos, el enlace de liveness y el numero completo
 * del documento nunca se persisten: Kira los conserva y el BFF solo necesita correlacionar estado.
 */
public record OperatorIdentity(
        IdentityVerificationStatus status,
        String kiraPersonReferenceId,
        String documentType,
        String documentNumberLastFour,
        String issuingCountry,
        Instant biometricConsentAt,
        Instant verificationRequestedAt,
        Instant verifiedAt,
        String rejectionReason) {

    public OperatorIdentity {
        status = status == null ? IdentityVerificationStatus.PENDING_DOCUMENTS : status;
    }

    public static OperatorIdentity pendingDocuments() {
        return new OperatorIdentity(IdentityVerificationStatus.PENDING_DOCUMENTS, null, null, null,
                null, null, null, null, null);
    }
}