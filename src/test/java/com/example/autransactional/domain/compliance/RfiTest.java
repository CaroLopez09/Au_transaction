package com.example.autransactional.domain.compliance;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.TenantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class RfiTest {

    private static final TenantId TENANT = TenantId.of("juriscop");

    private Rfi nuevo() {
        return new Rfi("r-1", TENANT, "rfi_1", "[{\"item_id\":\"i1\"}]", null);
    }

    @Test
    void notResolvedEsUnCierreYNoUnRfiPendiente() {
        assertEquals(RfiStatus.NOT_RESOLVED, RfiStatus.fromWire("not_resolved"));
        assertTrue(RfiStatus.NOT_RESOLVED.isTerminal());
        assertFalse(RfiStatus.NOT_RESOLVED.isOpen());
    }

    @Test
    void losEstadosSeLeenSinDistinguirMayusculas() {
        assertEquals(RfiStatus.ANSWERED, RfiStatus.fromWire("answered"));
        assertEquals(RfiStatus.RESOLVED, RfiStatus.fromWire("RESOLVED"));
    }

    @Test
    void unEstadoDesconocidoQuedaVisibleEnLaBandeja() {
        assertEquals(RfiStatus.PENDING, RfiStatus.fromWire("algo_nuevo"));
    }

    @Test
    void unRfiRespondidoSigueAdmitiendoRespuestasPorqueKiraPuedeDevolverUnItem() {
        Rfi rfi = nuevo();
        rfi.applyRemoteStatus(RfiStatus.ANSWERED, null);

        assertDoesNotThrow(rfi::assertAcceptsAnswers);
    }

    @Test
    void unRfiCerradoNoAdmiteRespuestas() {
        Rfi rfi = nuevo();
        rfi.applyRemoteStatus(RfiStatus.NOT_RESOLVED, null);

        assertThrows(DomainException.class, rfi::assertAcceptsAnswers);
    }

    @Test
    void unEventoTardioNoReabreUnRfiResuelto() {
        Rfi rfi = nuevo();
        rfi.applyRemoteStatus(RfiStatus.RESOLVED, null);

        rfi.applyRemoteStatus(RfiStatus.ANSWERED, null);

        assertEquals(RfiStatus.RESOLVED, rfi.getStatus());
    }

    @Test
    void unRfiVencidoYAbiertoEstaAtrasado() {
        Rfi rfi = new Rfi("r-1", TENANT, "rfi_1", "[]", Instant.parse("2026-09-01T00:00:00Z"));

        assertTrue(rfi.isOverdue(Instant.parse("2026-09-02T00:00:00Z")));
    }

    @Test
    void alRehidratarSeConservaLaFechaDeCreacion() {
        Instant creado = Instant.parse("2026-08-01T10:00:00Z");

        Rfi rfi = Rfi.rehydrate("r-1", TENANT, "rfi_1", RfiStatus.PENDING, "[]", null,
                "transfer", "po_1", creado, creado);

        assertEquals(creado, rfi.getCreatedAt());
        assertEquals("po_1", rfi.getBlockingResourceId());
    }
}
