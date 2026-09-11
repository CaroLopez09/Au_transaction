package com.example.autransactional.domain.compliance.identity;

/** Transcripcion del audio del reto de voz, mas la senal de movimiento labial del video. */
public record Transcription(String rawText, String normalizedNumber, double confidence,
                            boolean lipMovementDetected) {
}
