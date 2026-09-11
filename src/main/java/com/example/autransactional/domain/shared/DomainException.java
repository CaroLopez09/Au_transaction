package com.example.autransactional.domain.shared;

/** Violacion de una invariante de negocio. Se traduce a HTTP 409/422 en la capa REST. */
public class DomainException extends RuntimeException {
    public DomainException(String message) {
        super(message);
    }
}
