package com.example.autransactional.infrastructure.identity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DevLivenessProviderTest {

    private DevLivenessProvider provider(boolean pasa) {
        return new DevLivenessProvider(new IdentityProperties(
                "dev", true, 80, 85, 70, 120, 1800, 10485760, pasa,
                new IdentityProperties.Aws("us-east-1", "pool-123")));
    }

    @Test
    void unaSesionDesconocidaNoPasa() {
        var resultado = provider(true).result("no-existe");

        assertFalse(resultado.passed());
        assertEquals("NOT_FOUND", resultado.status());
    }

    @Test
    void elCaminoFelizDevuelveElFotogramaDeReferencia() {
        var provider = provider(true);
        var sesion = provider.createSession();

        var resultado = provider.result(sesion.sessionId());

        assertTrue(resultado.passed());
        // Ese fotograma es el que se compara contra el documento: sin el no hay validacion.
        assertNotNull(resultado.referenceImageBase64());
    }

    @Test
    void elCaminoDeRechazoNoEntregaFotograma() {
        var provider = provider(false);
        var sesion = provider.createSession();

        var resultado = provider.result(sesion.sessionId());

        assertFalse(resultado.passed());
        assertNull(resultado.referenceImageBase64());
    }

    @Test
    void laConfiguracionSoloLlevaRegionYPoolDeIdentidad() {
        var config = provider(true).config();

        assertTrue(config.enabled());
        assertEquals("us-east-1", config.region());
        assertEquals("pool-123", config.identityPoolId());
    }
}
