package com.example.autransactional.domain.compliance.identity;

import com.example.autransactional.domain.shared.DomainException;

/**
 * Umbrales de decision. Viven en el servidor porque son una politica de riesgo:
 * un umbral que viaja al navegador es un umbral que el atacante edita.
 *
 * La franja de revision manual evita el corte binario: por debajo del minimo se rechaza,
 * por encima se aprueba, y entre 'reviewFloor' y el minimo va a un humano.
 */
public record VerificationThresholds(int minLivenessScore, int minMatchScore, int reviewFloor) {

    public VerificationThresholds {
        if (minLivenessScore < 0 || minLivenessScore > 100
                || minMatchScore < 0 || minMatchScore > 100
                || reviewFloor < 0 || reviewFloor > 100) {
            throw new DomainException("Los umbrales biometricos deben estar entre 0 y 100.");
        }
        if (reviewFloor > minMatchScore) {
            throw new DomainException("El piso de revision manual no puede superar el umbral de aprobacion.");
        }
    }

    public VerificationVerdict decide(double livenessScore, double matchScore) {
        if (livenessScore < minLivenessScore) {
            return VerificationVerdict.REJECTED;
        }
        if (matchScore >= minMatchScore) {
            return VerificationVerdict.APPROVED;
        }
        if (matchScore >= reviewFloor) {
            return VerificationVerdict.MANUAL_REVIEW;
        }
        return VerificationVerdict.REJECTED;
    }
}
