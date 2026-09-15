package com.example.autransactional.domain.tenant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Campos que Kira todavia exige para verificar a la empresa, agrupados por producto.
 *
 * Es la fuente de verdad del formulario de onboarding: la pantalla NO debe tener campos
 * estaticos, sino renderizar lo que llegue aqui. Las claves son codigos de producto
 * (p. ej. usa-virtual-accounts) mas "general", que es la union de todos ellos.
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

    /**
     * Lo que falta para un producto concreto.
     *
     * "general" NO es una base comun: es la union de los faltantes de todos los productos
     * (docs.kirafin.ai: "a general key holding every token once"; confirmado en sandbox el
     * 15-sep, donde traia requisitos de otros bancos). Sumarlo pedia datos de productos que no
     * se usan y dejaba el producto sin completar para siempre. Solo se usa si Kira no lista el
     * producto por separado.
     */
    public List<String> forProduct(String productCode) {
        List<String> own = byProduct.get(productCode);
        return own != null ? own : byProduct.getOrDefault(GENERAL, List.of());
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
