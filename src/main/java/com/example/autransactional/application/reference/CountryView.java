package com.example.autransactional.application.reference;

import java.util.List;

/**
 * Pais soportado por Kira. alpha3 es el codigo ISO-3 que piden la empresa y sus UBOs;
 * subdivisions alimenta el selector de estado o departamento, y postalCodeFormat es la
 * expresion regular (sintaxis Ruby, \A...\Z) con la que Kira valida el codigo postal.
 */
public record CountryView(String name, String alpha3, String postalCodeFormat, List<Subdivision> subdivisions) {

    public record Subdivision(String name, String code) {
    }
}
