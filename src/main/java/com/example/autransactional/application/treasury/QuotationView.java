package com.example.autransactional.application.treasury;

import com.example.autransactional.domain.treasury.Quotation;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * La cotizacion tal como debe verla el tesorero.
 *
 * secondsToExpiry alimenta el contador: al llegar a cero el boton de pago se deshabilita,
 * porque una cotizacion vencida ejecuta a otra tasa.
 */
public record QuotationView(
        String id,
        String kiraQuoteId,
        String virtualAccountId,
        String recipientId,
        String rail,
        BigDecimal originAmount,
        BigDecimal destinationAmount,
        String destinationCurrency,
        BigDecimal exchangeRate,
        BigDecimal kiraFee,
        BigDecimal platformFee,
        BigDecimal totalFee,
        BigDecimal totalDebitAmount,
        boolean balanceSufficient,
        boolean fallbackRate,
        String status,
        Instant expiresAt,
        long secondsToExpiry) {

    public static QuotationView from(Quotation q) {
        return new QuotationView(
                q.getId(),
                q.getKiraQuoteId(),
                q.getVirtualAccountId(),
                q.getRecipientId(),
                q.getRail().name(),
                q.getOriginAmount(),
                q.getDestinationAmount(),
                q.getDestinationCurrency(),
                q.getExchangeRate(),
                q.getFees().kiraFee(),
                q.getFees().platformFee(),
                q.getFees().totalFee(),
                q.getTotalDebitAmount(),
                q.isBalanceSufficient(),
                q.usesFallbackRate(),
                q.getStatus().name(),
                q.getExpiresAt(),
                q.secondsToExpiry(Instant.now()));
    }
}
