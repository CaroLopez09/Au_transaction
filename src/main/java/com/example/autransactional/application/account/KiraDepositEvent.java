package com.example.autransactional.application.account;

import com.example.autransactional.domain.account.DepositStatus;
import com.example.autransactional.domain.shared.Rail;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;

/**
 * Evento de deposito, normalizado.
 *
 * Las respuestas de deposito mezclan snake_case (deposit_id) y camelCase
 * (internalPaymentId) en el mismo payload, asi que cada campo se busca en las dos formas.
 * Los importes llegan en decimal, no en unidades menores: esa convencion es exclusiva de
 * las cotizaciones.
 */
public record KiraDepositEvent(
        String kiraDepositId,
        String kiraAccountId,
        DepositStatus status,
        boolean microdeposit,
        BigDecimal grossAmount,
        BigDecimal feeAmount,
        BigDecimal netAmount,
        String currency,
        String senderName,
        String senderAccount,
        Rail rail) {

    public static KiraDepositEvent from(String eventName, JsonNode payload) {
        BigDecimal gross = decimal(payload, "amount", "gross_amount", "grossAmount");
        BigDecimal fee = decimal(payload, "fee", "fee_amount", "feeAmount");
        BigDecimal net = decimal(payload, "net_amount", "netAmount");
        // Forma documentada: el ordenante y el riel van anidados en 'source'.
        JsonNode source = payload.path("source");
        String senderName = text(payload, "sender_name", "senderName");
        String rail = text(payload, "rail", "payment_type", "paymentType");

        return new KiraDepositEvent(
                text(payload, "deposit_id", "depositId", "internalPaymentId", "id"),
                text(payload, "virtual_account_id", "virtualAccountId"),
                DepositStatus.fromEventName(eventName, text(payload, "status")),
                eventName != null && eventName.contains("microdeposit"),
                gross,
                fee,
                net,
                text(payload, "currency"),
                senderName != null ? senderName : text(source, "sender_name"),
                text(payload, "sender_account", "senderAccount"),
                Rail.fromWireOrNull(rail != null ? rail : text(source, "payment_rail")));
    }

    /**
     * Deposito tal como lo devuelve GET /v1/virtual-accounts/{id}/deposits.
     *
     * No es la forma del webhook: el ordenante va anidado en 'sender', la comision en
     * 'fees.total_fees' y el riel en 'payment_rail'.
     */
    public static KiraDepositEvent fromResource(JsonNode resource, String fallbackKiraAccountId) {
        JsonNode sender = resource.path("sender");
        String accountId = text(resource, "virtual_account_id");
        return new KiraDepositEvent(
                text(resource, "id"),
                accountId == null ? fallbackKiraAccountId : accountId,
                DepositStatus.fromWire(text(resource, "status")),
                false,
                decimal(resource, "amount"),
                decimal(resource.path("fees"), "total_fees"),
                decimal(resource, "net_amount"),
                text(resource, "currency"),
                text(sender, "name"),
                text(sender, "account_number"),
                Rail.fromWireOrNull(text(resource, "payment_rail")));
    }

    public boolean isIdentifiable() {
        return kiraDepositId != null && kiraAccountId != null;
    }

    private static String text(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private static BigDecimal decimal(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull()) {
                if (value.isNumber()) {
                    return value.decimalValue();
                }
                try {
                    return new BigDecimal(value.asText());
                } catch (NumberFormatException ignored) {
                    // Un importe ilegible no debe tumbar la proyeccion del evento.
                }
            }
        }
        return null;
    }
}
