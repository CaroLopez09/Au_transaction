package com.example.autransactional.domain.tenant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Campos que Kira todavia exige para verificar a la empresa, agrupados por producto.
 *
 * Es la fuente de verdad del formulario de onboarding: la pantalla NO debe tener campos
 * estaticos, sino renderizar lo que llegue aqui. La clave "general" aplica a todos los
 * productos; el resto son codigos de producto (p. ej. usa-virtual-accounts).
 */
public record MissingFields(Map<String, List<String>> byProduct) {

    public static final String GENERAL = "general";

    public MissingFields {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        if (byProduct != null) {
            byProduct.forEach((k, v) -> copy.put(k, v == null ? List.of() : List.copyOf(v)));
        }
        byProduct = Map.copyOf(copy);
    }

    public static MissingFields empty() {
        return new MissingFields(Map.of());
    }

    /** Lo que falta para un producto concreto: los generales mas los suyos. */
    public List<String> forProduct(String productCode) {
        List<String> all = new ArrayList<>(byProduct.getOrDefault(GENERAL, List.of()));
        for (String field : byProduct.getOrDefault(productCode, List.of())) {
            if (!all.contains(field)) {
                all.add(field);
            }
        }
        return List.copyOf(all);
    }

    public boolean isCompleteFor(String productCode) {
        return forProduct(productCode).isEmpty();
    }

    public boolean isEmpty() {
        return byProduct.values().stream().allMatch(List::isEmpty);
    }

    public Set<String> products() {
        return byProduct.keySet();
    }
}
