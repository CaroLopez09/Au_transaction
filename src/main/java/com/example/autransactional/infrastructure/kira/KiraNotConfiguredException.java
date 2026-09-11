package com.example.autransactional.infrastructure.kira;

/**
 * Faltan las credenciales de Kira en este entorno. No es un fallo de la peticion ni de Kira:
 * la integracion no esta configurada, y el portal debe decirlo asi en vez de "error inesperado".
 */
public class KiraNotConfiguredException extends RuntimeException {

    public KiraNotConfiguredException(String message) {
        super(message);
    }
}
