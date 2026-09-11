package com.example.autransactional.application.treasury;

import java.util.Map;

/**
 * Vista previa de un pago: lo que sale de la cuenta, lo que llega y las comisiones, sin
 * reservar precio. Para cerrar el precio se cotiza (POST /api/quotations).
 *
 * fees va tal cual lo desglosa Kira: su forma depende del riel.
 */
public record PayoutPreviewView(
        String amount,
        String currency,
        String recipientAmount,
        String recipientCurrency,
        Map<String, Object> fees) {
}
