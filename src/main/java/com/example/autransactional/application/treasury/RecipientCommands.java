package com.example.autransactional.application.treasury;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class RecipientCommands {

    private RecipientCommands() {
    }

    /** Direccion postal. El pais del destinatario va en ISO-2 ("US"), no en ISO-3. */
    public record Address(
            String streetName,
            String city,
            String state,
            String postalCode,
            @Size(min = 2, max = 2) String country) {
    }

    /**
     * Alta de un destinatario. El bloque que se rellena depende del riel:
     * ACH y WIRE llevan datos bancarios, WALLET lleva token, red y direccion.
     *
     * Un destinatario = un riel. Enviar campos de dos rieles a la vez se rechaza.
     */
    public record RegisterRecipient(
            @NotBlank String rail,

            // Titular
            boolean business,
            String firstName,
            String lastName,
            String companyName,
            @Email String email,
            @Size(max = 16) String phone,
            Address address,

            // ACH / WIRE
            @Pattern(regexp = "\\d{9}|", message = "El routing number debe tener 9 digitos")
            String routingNumber,
            String swiftCode,
            String accountNumber,
            String accountKind,
            String bankName,
            String bankAddressText,
            Address bankAddress,

            // WALLET
            String token,
            String network,
            String walletAddress,

            String docType,
            String docNumber) {
    }

    /** Motivo del archivado. Kira no borra: se archiva local y se crea un reemplazo. */
    public record ArchiveRecipient(String replacedByRecipientId) {
    }
}
