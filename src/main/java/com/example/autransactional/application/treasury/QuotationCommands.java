package com.example.autransactional.application.treasury;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public final class QuotationCommands {

    private QuotationCommands() {
    }

    /**
     * Peticion de cotizacion.
     *
     * 'amount' es lo que RECIBE el destinatario: las comisiones se suman por encima y el
     * debito de la cuenta virtual es mayor. El desglose vuelve en la respuesta.
     *
     * 'rail' es opcional: por defecto se deriva del destinatario, que es la unica fuente
     * valida. Enviarlo sirve para elegir entre ACH_STANDARD y ACH_SAME_DAY.
     */
    public record CreateQuote(
            @NotBlank String virtualAccountId,
            @NotBlank String recipientId,
            @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
            String rail,
            String targetCurrency) {
    }
}
