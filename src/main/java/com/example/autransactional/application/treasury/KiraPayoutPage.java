package com.example.autransactional.application.treasury;

import java.util.List;

/**
 * Historial de pagos tal como lo ve Kira para la empresa, paginado por pagina (no offset).
 *
 * Incluye movimientos que no nacieron en este portal (origin "deposit" o "api"). Cuando un
 * pago si nacio aqui, localPayoutId enlaza con /api/payouts/{id}.
 */
public record KiraPayoutPage(List<Item> items, int page, int limit, int total, int totalPages) {

    public record Item(
            String kiraPayoutId,
            String shortId,
            String localPayoutId,
            String virtualAccountId,
            String status,
            String origin,
            String fromAmount,
            String fromCurrency,
            String toAmount,
            String toCurrency,
            String paymentMethod,
            String senderName,
            String recipientName,
            String reference,
            String memo,
            String createdAt) {
    }
}
