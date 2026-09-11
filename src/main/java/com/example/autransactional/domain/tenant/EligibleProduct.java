package com.example.autransactional.domain.tenant;

import java.util.List;

/**
 * Producto bancario de Kira y su elegibilidad para esta empresa.
 *
 * Abrir una cuenta virtual exige DOS condiciones a la vez: que el KYB este VERIFIED y que
 * el producto concreto este 'eligible'. Guardar solo el codigo del producto perderia la
 * segunda mitad de esa pregunta.
 */
public record EligibleProduct(String productCode, boolean eligible,
                              List<String> missingFields, String unsupportedReason) {

    /** Producto objetivo de la integracion: cuentas virtuales en bancos de EE. UU. */
    public static final String USA_VIRTUAL_ACCOUNTS = "usa-virtual-accounts";

    /** Motivo que Kira devuelve cuando exige diligencia reforzada (file_proof_of_address). */
    public static final String EDD_REQUIRED = "enhanced_due_diligence_required";

    public EligibleProduct {
        missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
    }

    public boolean requiresEnhancedDueDiligence() {
        return EDD_REQUIRED.equalsIgnoreCase(unsupportedReason);
    }
}
