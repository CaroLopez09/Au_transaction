package com.example.autransactional.application.treasury;

/** Un paso de la linea de tiempo de un pago (events[] de GET /v1/payouts/{id}). */
public record PayoutEventView(String eventId, String status, String message, String createdAt) {
}
