package com.example.autransactional.domain.treasury;

import com.example.autransactional.domain.shared.DomainException;

import java.util.Locale;

/**
 * Titular del destinatario.
 *
 * No existe un campo 'holder_name' en la API: el titular se infiere de first_name +
 * last_name para personas y de company_name para empresas. Enviar el que no toca deja al
 * destinatario sin nombre.
 */
public record RecipientHolder(boolean business, String firstName, String lastName,
                              String companyName, String email, String phone) {

    public static final int MAX_PHONE_LENGTH = 16;

    public RecipientHolder {
        if (business) {
            if (companyName == null || companyName.isBlank()) {
                throw new DomainException("Un destinatario empresa necesita razon social.");
            }
        } else if (firstName == null || firstName.isBlank() || lastName == null || lastName.isBlank()) {
            throw new DomainException("Un destinatario persona necesita nombre y apellido.");
        }
        if (phone != null && phone.length() > MAX_PHONE_LENGTH) {
            throw new DomainException("El telefono no puede superar los " + MAX_PHONE_LENGTH + " caracteres.");
        }
    }

    public static RecipientHolder company(String companyName, String email, String phone) {
        return new RecipientHolder(true, null, null, companyName, email, phone);
    }

    public static RecipientHolder person(String firstName, String lastName, String email, String phone) {
        return new RecipientHolder(false, firstName, lastName, null, email, phone);
    }

    public String wireType() {
        return business ? "business" : "individual";
    }

    /** Nombre legible para el directorio local. */
    public String displayName() {
        return business ? companyName : (firstName + " " + lastName).trim();
    }

    public String type() {
        return wireType().toUpperCase(Locale.ROOT);
    }
}
