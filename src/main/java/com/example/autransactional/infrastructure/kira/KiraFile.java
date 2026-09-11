package com.example.autransactional.infrastructure.kira;

/** Archivo que se reenvia a Kira en una peticion multipart. */
public record KiraFile(String fileName, String contentType, byte[] content) {
}
