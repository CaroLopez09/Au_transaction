package com.example.autransactional.domain.compliance.identity;

/** Datos extraidos del documento por OCR. Todos opcionales: el OCR falla parcialmente a menudo. */
public record DocumentData(
        String documentNumber,
        String firstName,
        String lastName,
        String birthDate,
        String expirationDate,
        String issuingCountry,
        String nationality,
        String gender) {
}
