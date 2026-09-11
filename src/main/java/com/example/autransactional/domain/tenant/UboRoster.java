package com.example.autransactional.domain.tenant;

import com.example.autransactional.domain.shared.DomainException;

import java.math.BigDecimal;
import java.util.List;

/**
 * El conjunto de UBOs de una empresa, con las reglas que Kira verifica sobre el grupo
 * y no sobre cada persona.
 *
 * La regla del beneficiario final causa la mayoria de los bloqueos silenciosos del KYB:
 * sin al menos una persona con propiedad >= 5 %, Kira devuelve
 * missing_fields "associated_persons:beneficial_owner" y la verificacion no avanza,
 * aunque el formulario parezca completo.
 */
public record UboRoster(List<Ubo> members) {

    public UboRoster {
        members = members == null ? List.of() : List.copyOf(members);
    }

    public List<Ubo> beneficialOwners() {
        return members.stream().filter(Ubo::isBeneficialOwner).toList();
    }

    public BigDecimal totalOwnership() {
        return members.stream()
                .map(Ubo::getOwnershipPercentage)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public boolean hasBeneficialOwner() {
        return !beneficialOwners().isEmpty();
    }

    /** Se comprueba antes de enviar el array a Kira: es mas barato que un KYB atascado. */
    public void assertReadyForVerification() {
        if (members.isEmpty()) {
            throw new DomainException("La empresa necesita al menos un beneficiario final registrado.");
        }
        if (!hasBeneficialOwner()) {
            throw new DomainException("Kira exige al menos una persona con propiedad declarada del 5 % "
                    + "o mas. Marca la casilla de propiedad y su porcentaje.");
        }
        if (totalOwnership().compareTo(new BigDecimal("100")) > 0) {
            throw new DomainException("La suma de participaciones no puede superar el 100 % (actual: "
                    + totalOwnership() + " %).");
        }
    }

    /** UBOs cuyo enlace de prueba de vida sigue pendiente de resolverse. */
    public List<Ubo> pendingLiveness() {
        return members.stream().filter(u -> !u.getLivenessStatus().isFinal()).toList();
    }

    public boolean livenessComplete() {
        return !members.isEmpty()
                && beneficialOwners().stream()
                .allMatch(u -> u.getLivenessStatus() == LivenessStatus.COMPLETED);
    }
}
