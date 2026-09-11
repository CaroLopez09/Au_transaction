package com.example.autransactional.application.tenant;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.Map;

public final class OnboardingCommands {

    private OnboardingCommands() {
    }

    /**
     * Alta minima viable en Kira. Crea el registro pero NO dispara la verificacion.
     *
     * source_of_funds es obligatorio aqui aunque la API lo acepte vacio: sin el, el KYB
     * no arranca nunca por mucho que el resto del formulario este completo, y ese fallo
     * es silencioso.
     */
    public record RegisterBusiness(
            @NotBlank String businessLegalName,
            @NotBlank @Email String email,
            @NotBlank String sourceOfFunds) {
    }

    /**
     * Campos del perfil KYB. Se envian tal cual los nombra Kira (business_type,
     * formation_date, associated_persons...), porque el formulario se renderiza desde
     * missing_fields y traducir nombres aqui obligaria a mantener un diccionario que
     * cambia cada vez que Kira pide un campo nuevo.
     */
    public record CompleteProfile(@NotEmpty Map<String, Object> profile) {
    }
}
