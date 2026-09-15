package com.example.autransactional.domain.account;

import com.example.autransactional.domain.shared.TenantId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * La fecha de alta es la base del aviso de activacion demorada. Si al leer la cuenta de la
 * base se tomara la hora actual, el aviso no saltaria nunca: la cuenta siempre parece nueva.
 */
class VirtualAccountActivationTest {

    private VirtualAccount leidaDeLaBase(Instant creada) {
        return VirtualAccount.rehydrate("va-1", TenantId.of("juriscop"), "kva_1", null, null, null,
                "USD", VirtualAccountMode.FIAT, "jp_morgan", "Operativa",
                VirtualAccountStatus.PENDING, BigDecimal.ZERO, false, null, null, creada, creada);
    }

    @Test
    void alLeerLaCuentaSeConservaSuFechaDeAlta() {
        Instant creada = Instant.parse("2026-09-11T10:00:00Z");

        assertEquals(creada, leidaDeLaBase(creada).getCreatedAt());
    }

    @Test
    void unaCuentaSinActivarTrasCincoMinutosEstaDemorada() {
        Instant creada = Instant.parse("2026-09-11T10:00:00Z");

        assertTrue(leidaDeLaBase(creada).isActivationDelayed(creada.plusSeconds(301)));
        assertFalse(leidaDeLaBase(creada).isActivationDelayed(creada.plusSeconds(120)));
    }
}
