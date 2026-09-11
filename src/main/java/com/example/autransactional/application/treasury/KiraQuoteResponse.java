package com.example.autransactional.application.treasury;

import com.example.autransactional.domain.treasury.FeeBreakdown;
import com.example.autransactional.infrastructure.kira.KiraAmounts;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Respuesta de POST /v1/quotations, con los importes ya convertidos a BigDecimal.
 *
 * Kira envia unidades menores mas una precision: 5000000 con precision 2 son 50.000,00 USD.
 * La conversion ocurre aqui y una sola vez; del agregado hacia adentro solo circulan
 * importes decimales.
 */
public record KiraQuoteResponse(
        String quoteId,
        Instant expiresAt,
        BigDecimal sourceAmount,
        String sourceCurrency,
        BigDecimal recipientAmount,
        String recipientCurrency,
        BigDecimal exchangeRate,
        FeeBreakdown fees,
        boolean balanceSufficient,
        String rateSource,
        String feesSnapshot) {

    public static KiraQuoteResponse from(JsonNode response) {
        JsonNode body = response.has("data") ? response.get("data") : response;
        JsonNode source = body.path("source");
        JsonNode recipient = body.path("recipient");
        JsonNode conversion = body.path("conversion");
        JsonNode totals = body.path("totals");

        return new KiraQuoteResponse(
                text(body, "quote_id"),
                instant(text(body, "quote_expires_at")),
                amount(source),
                text(source, "currency"),
                amount(recipient),
                text(recipient, "currency"),
                decimal(text(conversion, "rate")),
                new FeeBreakdown(
                        amount(totals, "kira_revenue_total", totals),
                        amount(totals, "client_markup_total", totals),
                        null),
                body.path("balance_sufficient").asBoolean(false),
                text(conversion, "rate_source"),
                // Se guarda el bloque entero: es la prueba del precio que se mostro.
                body.has("fees") || !totals.isMissingNode()
                        ? "{\"fees\":" + body.path("fees") + ",\"totals\":" + totals + "}"
                        : null);
    }

    /** Un nodo { amount, currency, precision } convertido a decimal. */
    private static BigDecimal amount(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        int precision = node.has("precision")
                ? node.path("precision").asInt()
                : KiraAmounts.precisionOf(text(node, "currency"));
        return KiraAmounts.fromMinor(node.path("amount").asLong(0), precision);
    }

    /** Un total suelto dentro de 'totals', que comparte la precision del bloque. */
    private static BigDecimal amount(JsonNode totals, String field, JsonNode precisionSource) {
        if (totals == null || !totals.has(field)) {
            return BigDecimal.ZERO;
        }
        int precision = precisionSource.has("precision")
                ? precisionSource.path("precision").asInt()
                : KiraAmounts.FIAT_PRECISION;
        return KiraAmounts.fromMinor(totals.path(field).asLong(0), precision);
    }

    private static BigDecimal decimal(String raw) {
        try {
            return raw == null || raw.isBlank() ? null : new BigDecimal(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant instant(String raw) {
        try {
            return raw == null || raw.isBlank() ? null : Instant.parse(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
