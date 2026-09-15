package com.example.autransactional.application.tenant;

import com.example.autransactional.domain.shared.PostalAddress;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

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
            /** Opcional en el alta, obligatorio para subirle documentos: Kira empareja por email. */
            @Email String email,
            String documentType,
            String documentNumber,
            @NotNull Boolean hasOwnership,
            @NotNull @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal ownershipPercentage,
            @NotNull Boolean hasControl,
            @NotNull Boolean isSigner,
            @NotNull Boolean politicallyExposed,
            @NotBlank @Size(min = 3, max = 3) String countryOfBirth,
            String roleInCompany,
            // --- Opcionales: Kira los pide por persona segun el banco (missing_fields) ---
            @Past LocalDate birthDate,
            @Size(min = 3, max = 3) String nationality,
            @Size(max = 100) String occupation,
            @Pattern(regexp = "male|female|other") String gender,
            @Pattern(regexp = "\\+[1-9]\\d{6,14}", message = "debe ir en formato E.164, p. ej. +573001234567")
            String phoneNumber,
            @Size(min = 3, max = 3) String documentCountry,
            @Valid ResidentialAddress address) {
    }

    /** Direccion de residencia. Pais en ISO-3, como el resto de datos de persona en Kira. */
    public record ResidentialAddress(
            @Size(max = 255) String streetName,
            @Size(max = 100) String city,
            @Size(max = 100) String state,
            @Size(max = 20) String postalCode,
            @Size(min = 3, max = 3) String country) {

        public PostalAddress toDomain() {
            return new PostalAddress(streetName, city, state, postalCode, country);
        }
    }

    /**
     * URLs a las que Kira devuelve a la persona tras la prueba de vida. Deben estar
     * preautorizadas por Kira; la landing NO es fuente de verdad del resultado.
     */
    /**
     * biometricConsent: el operador declara que cada persona consintio el tratamiento biometrico
     * antes de recibir su enlace (arquitectura §7). Sin esa declaracion no se piden enlaces.
     */
    public record RequestLivenessLinks(String successUrl, String rejectUrl, Boolean biometricConsent) {
    }
}
