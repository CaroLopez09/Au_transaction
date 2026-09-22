package com.example.autransactional.application.tenant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reconstruye el borrador del asistente a partir del expediente ya enviado a Kira
 * (`tenants.onboarding_payload`).
 *
 * Por que existe: el asistente solo se rehidrataba desde `onboarding_draft`, asi que quien
 * completaba el formulario y lo enviaba veia la pantalla en blanco al volver otro dia, aunque
 * el dato estuviera guardado y aceptado por el proveedor. Kira no tiene borradores, pero el BFF
 * si guarda lo ultimo que le mando, y eso alcanza para devolver el formulario tal como quedo.
 *
 * Es la inversa de {@link SubmitOnboardingService#forUpdate(Map)} mas el agrupado en secciones
 * que usa el asistente. Todos los valores salen como texto: el front descarta lo que no sea
 * cadena al normalizar el borrador.
 *
 * Una traduccion no es reversible: `forUpdate` une `street_line_1` y `street_line_2` en un unico
 * `address_street`, asi que al volver todo cae en `street_line_1`. Los documentos tampoco se
 * reconstruyen: el borrador solo anotaba nombre y fecha de lo subido, y el payload no los lleva.
 */
final class OnboardingDraftSeed {

    /** Claves del payload que van a la seccion «Empresa», con el nombre que usa el asistente. */
    private static final Map<String, String> COMPANY = Map.ofEntries(
            Map.entry("business_legal_name", "business_legal_name"),
            Map.entry("email", "email"),
            Map.entry("business_type", "business_type"),
            Map.entry("doing_business_as", "business_trade_name"),
            Map.entry("business_description", "business_description"),
            Map.entry("business_website", "business_website"),
            Map.entry("phone", "phone"),
            Map.entry("formation_date", "formation_date"),
            Map.entry("formation_country", "formation_country"),
            Map.entry("formation_state", "formation_state"),
            Map.entry("document_number", "document_number"),
            Map.entry("document_country", "document_country"),
            Map.entry("international_entity_type", "international_entity_type"));

    private static final Map<String, String> ADDRESS = Map.of(
            "address_street", "street_line_1",
            "address_city", "city",
            "address_state", "subdivision",
            "address_zip_code", "postal_code",
            "address_country", "country");

    private static final List<String> ACTIVITY = List.of(
            "account_purpose", "source_of_funds", "expected_monthly_volume",
            "expected_transaction_count", "expected_monthly_payments", "high_risk_industries",
            "is_nbfi_vasp", "business_legal_history");

    private static final Map<String, String> REPRESENTATIVE = Map.of(
            "representative_first_name", "representative_first_name",
            "representative_last_name", "representative_last_name",
            "representative_title", "representative_title",
            "representative_birth_date", "representative_date_of_birth");

    private OnboardingDraftSeed() {
    }

    /** Borrador equivalente a lo ya enviado. Vacio si no hay expediente. */
    static Map<String, Object> from(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> draft = new LinkedHashMap<>();
        putSection(draft, "company", company(payload));
        putSection(draft, "activity", activity(payload));
        putSection(draft, "representative", section(payload, REPRESENTATIVE));
        return draft;
    }

    private static Map<String, Object> company(Map<String, Object> payload) {
        Map<String, Object> company = section(payload, COMPANY);
        // `business_industry` viaja a Kira como arreglo de un elemento; el asistente guarda el slug.
        if (payload.get("business_industry") instanceof List<?> industries && !industries.isEmpty()) {
            putIfText(company, "business_industry", industries.get(0));
        } else {
            putIfText(company, "business_industry", payload.get("business_industry"));
        }
        Map<String, Object> address = section(payload, ADDRESS);
        if (!address.isEmpty()) {
            company.put("registered_address", address);
        }
        return company;
    }

    private static Map<String, Object> activity(Map<String, Object> payload) {
        Map<String, Object> activity = new LinkedHashMap<>();
        ACTIVITY.forEach(key -> putIfText(activity, key, payload.get(key)));
        if (payload.get("transaction_countries") instanceof List<?> countries) {
            String joinedCountries = countries.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .map(String::trim)
                    .filter(country -> !country.isEmpty())
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("");
            putIfText(activity, "transaction_countries", joinedCountries);
        } else {
            putIfText(activity, "transaction_countries", payload.get("transaction_countries"));
        }
        // El asistente maneja el PEP como "true"/"false"; a Kira viaja como booleano.
        putIfText(activity, "pep_status", payload.get("pep_status"));
        if (payload.get("additional_info") instanceof Map<?, ?> additional) {
            putIfText(activity, "has_us_bank_account", additional.get("has_us_bank_account"));
            putIfText(activity, "has_denied_bank_account", additional.get("has_denied_bank_account"));
        }
        return activity;
    }

    private static Map<String, Object> section(Map<String, Object> payload, Map<String, String> keys) {
        Map<String, Object> section = new LinkedHashMap<>();
        keys.forEach((payloadKey, draftKey) -> putIfText(section, draftKey, payload.get(payloadKey)));
        return section;
    }

    private static void putSection(Map<String, Object> draft, String name, Map<String, Object> section) {
        if (!section.isEmpty()) {
            draft.put(name, section);
        }
    }

    private static void putIfText(Map<String, Object> target, String key, Object value) {
        if (value == null || value instanceof Map<?, ?> || value instanceof List<?>) {
            return;
        }
        String text = String.valueOf(value).trim();
        if (!text.isEmpty()) {
            target.put(key, text);
        }
    }
}
