package com.example.autransactional.domain.tenant;

import java.time.Instant;

/**
 * Datos de verificacion de un operador. Los archivos, el enlace de liveness y el numero completo
 * del documento nunca se persisten: Kira los conserva y el BFF solo necesita correlacionar estado.
 *
 * `rejectedAttempts` cuenta los intentos consecutivos que el proveedor biometrico rechazo (rostro
 * o documento no coinciden). Un IN_REVIEW no cuenta como rechazo: es una decision pendiente, no un
 * fallo. Al llegar a IdentityVerificationService.MAX_REJECTED_ATTEMPTS, begin() deja de emitir
 * nuevos retos hasta que un ADMIN relance la verificacion (ManageOperatorsService.relaunchIdentity,
 * que vuelve a poner el contador en cero via pendingDocuments()).
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
        String rejectionReason,
        int rejectedAttempts) {

    public OperatorIdentity {
        status = status == null ? IdentityVerificationStatus.PENDING_DOCUMENTS : status;
        rejectedAttempts = Math.max(0, rejectedAttempts);
    }

    public static OperatorIdentity pendingDocuments() {
        return new OperatorIdentity(IdentityVerificationStatus.PENDING_DOCUMENTS, null, null, null,
                null, null, null, null, null, 0);
    }
}