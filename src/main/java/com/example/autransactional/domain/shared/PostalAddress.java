package com.example.autransactional.domain.shared;

import java.util.Locale;

/**
 * Direccion postal.
 *
 * Ojo con el pais: los destinatarios usan ISO-2 ("US") y las empresas del KYB usan ISO-3
 * ("USA"). Mezclarlos es un error de validacion en la API, asi que el codigo se guarda
 * tal como lo exige cada superficie y esta clase solo comprueba la longitud.
 */
public record PostalAddress(String streetName, String city, String state,
                            String postalCode, String country) {

    public PostalAddress {
        if (country != null && !country.isBlank()) {
            country = country.trim().toUpperCase(Locale.ROOT);
        }
    }

    /** Direccion de un destinatario: pais en ISO-2. */
    public void assertIso2Country() {
        if (country == null || country.length() != 2) {
            throw new DomainException("El pais del destinatario debe ir en ISO-2 (por ejemplo US).");
        }
    }

    public boolean isBlank() {
        return (streetName == null || streetName.isBlank())
                && (city == null || city.isBlank())
                && (postalCode == null || postalCode.isBlank());
    }
}
