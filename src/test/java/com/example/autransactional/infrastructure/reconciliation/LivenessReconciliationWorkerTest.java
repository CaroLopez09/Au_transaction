package com.example.autransactional.infrastructure.reconciliation;

import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.LivenessStatus;
import com.example.autransactional.domain.tenant.Ubo;
import com.example.autransactional.domain.tenant.UboRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * El enlace de prueba de vida vive 7 dias y Kira no lo prorroga. Uno vencido que sigue en
 * PENDING deja al portal esperando un resultado que ya no va a llegar.
 */
class LivenessReconciliationWorkerTest {

    private static final TenantId TENANT = TenantId.of("juriscop");

    private final UboRepository ubos = mock(UboRepository.class);
    private final LivenessReconciliationWorker worker = new LivenessReconciliationWorker(ubos);

    private Ubo conEnlaceVencido(String id) {
        Ubo ubo = new Ubo(id, TENANT, "Maria", "Perez", new BigDecimal("60.00"), "Socia");
        ubo.describeRole(true, new BigDecimal("60.00"), true, true, false, "COL");
        ubo.assignLivenessLink("https://liveness.kira.test/" + id, Instant.now().minusSeconds(1));
        return ubo;
    }

    @Test
    void unEnlaceVencidoQuedaMarcadoComoExpirado() {
        Ubo ubo = conEnlaceVencido("ubo-1");
        when(ubos.findPendingLivenessExpiredBefore(any())).thenReturn(List.of(ubo));

        worker.expireStaleLivenessLinks();

        assertEquals(LivenessStatus.EXPIRED, ubo.getLivenessStatus());
        verify(ubos).save(ubo);
    }

    @Test
    void sinEnlacesVencidosNoSeGuardaNada() {
        when(ubos.findPendingLivenessExpiredBefore(any())).thenReturn(List.of());

        worker.expireStaleLivenessLinks();

        verify(ubos, never()).save(any());
    }

    @Test
    void unResultadoFinalYaRecibidoNoSePisa() {
        Ubo ubo = conEnlaceVencido("ubo-1");
        ubo.applyLivenessStatus(LivenessStatus.COMPLETED);
        when(ubos.findPendingLivenessExpiredBefore(any())).thenReturn(List.of(ubo));

        worker.expireStaleLivenessLinks();

        assertEquals(LivenessStatus.COMPLETED, ubo.getLivenessStatus());
    }
}
