package com.example.autransactional.application.tenant;

import com.example.autransactional.domain.tenant.EligibleProduct;
import com.example.autransactional.domain.tenant.MissingFields;
import com.example.autransactional.domain.tenant.TenantStatus;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lo que POST, PUT y GET de /v1/users devuelven sobre el KYB, normalizado.
 *
 * Las tres superficies comparten forma pero no la rellenan igual: el POST no trae
 * verification_triggered y el GET nunca devuelve el cuestionario. Aqui se leen todas
 * igual y se deja que el agregado decida que conserva.
 */
public record KiraUserState(String kiraUserId, TenantStatus status, MissingFields missingFields,
                            List<EligibleProduct> eligibleProducts, Boolean verificationTriggered) {

    public static KiraUserState from(JsonNode response) {
        JsonNode body = response.has("data") ? response.get("data") : response;

        return new KiraUserState(
                text(body, "id"),
                TenantStatus.fromWire(text(body, "status")),
                readMissingFields(body.path("missing_fields")),
                readProducts(body.path("eligible_products")),
                body.has("verification_triggered") ? body.path("verification_triggered").asBoolean() : null);
    }

    private static MissingFields readMissingFields(JsonNode node) {
        if (node == null || !node.isObject()) {
            return MissingFields.empty();
        }
        Map<String, List<String>> byProduct = new LinkedHashMap<>();
        for (String product : node.propertyNames()) {
            List<String> fields = new ArrayList<>();
            node.get(product).forEach(field -> fields.add(field.asText()));
            byProduct.put(product, fields);
        }
        return new MissingFields(byProduct);
    }

    private static List<EligibleProduct> readProducts(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<EligibleProduct> products = new ArrayList<>();
        for (JsonNode item : node) {
            List<String> missing = new ArrayList<>();
            item.path("missing_fields").forEach(field -> missing.add(field.asText()));
            products.add(new EligibleProduct(
                    text(item, "product_code"),
                    item.path("eligible").asBoolean(false),
                    missing,
                    text(item, "unsupported_reason")));
        }
        return products;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
