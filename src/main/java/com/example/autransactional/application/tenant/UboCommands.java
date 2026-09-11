package com.example.autransactional.application.tenant;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public final class UboCommands {

    private UboCommands() {
    }

    /**
     * Alta o edicion de un beneficiario final.
     *
     * hasOwnership es obligatorio y explicito: el cargo no identifica al beneficiario, y
     * si se omite, Kira bloquea el KYB pidiendo "associated_persons:has_ownership" sin mas
     * pistas. pepStatus y countryOfBirth son igual de obligatorios para Kira.
     */
    public record SaveUbo(
            String id,
            @NotBlank String firstName,
            @NotBlank String lastName,
            String documentType,
            String documentNumber,
            @NotNull Boolean hasOwnership,
            @NotNull @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal ownershipPercentage,
            @NotNull Boolean hasControl,
            @NotNull Boolean isSigner,
            @NotNull Boolean politicallyExposed,
            @NotBlank @Size(min = 3, max = 3) String countryOfBirth,
            String roleInCompany) {
    }

    /**
     * URLs a las que Kira devuelve a la persona tras la prueba de vida. Deben estar
     * preautorizadas por Kira; la landing NO es fuente de verdad del resultado.
     */
    public record RequestLivenessLinks(String successUrl, String rejectUrl) {
    }
}
