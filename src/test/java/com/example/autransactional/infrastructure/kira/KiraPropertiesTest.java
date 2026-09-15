package com.example.autransactional.infrastructure.kira;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Un banco o una version que el BFF no entiende deben parar el arranque, no fallar al abrir cuentas. */
class KiraPropertiesTest {

    private static KiraProperties with(String apiVersion, String bank) {
        return new KiraProperties("https://kira.test", "k", "c", "p", apiVersion, "w", null,
                3600, 300, 5000, 30000, bank, true);
    }

    @Test
    void aceptaLaVersionYElBancoSoportados() {
        assertDoesNotThrow(() -> with("2026-06-01", "jp_morgan"));
    }

    @Test
    void rechazaOtraVersion() {
        var e = assertThrows(IllegalStateException.class, () -> with("2026-04-14", "jp_morgan"));
        assertTrue(e.getMessage().contains("2026-06-01"));
    }

    @Test
    void rechazaUnBancoNoDocumentadoONoSoportado() {
        // slovak_savings_bank era el valor por defecto antiguo y Kira no lo documenta.
        assertThrows(IllegalStateException.class, () -> with("2026-06-01", "slovak_savings_bank"));
        assertThrows(IllegalStateException.class, () -> with("2026-06-01", "austin_capital_trust"));
    }
}
