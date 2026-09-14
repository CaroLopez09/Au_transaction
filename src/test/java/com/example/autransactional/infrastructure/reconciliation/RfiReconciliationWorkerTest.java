package com.example.autransactional.infrastructure.reconciliation;

import com.example.autransactional.application.compliance.AnswerRfiService;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.infrastructure.kira.KiraNotConfiguredException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Los eventos rfi.* exigen suscripcion explicita en Kira y se entregan una sola vez. Un RFI que
 * no llega es un pago detenido que nadie ve hasta que vence, y el plazo no se prorroga.
 */
class RfiReconciliationWorkerTest {

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final AnswerRfiService rfis = mock(AnswerRfiService.class);
    private final RfiReconciliationWorker worker = new RfiReconciliationWorker(tenants, rfis);

    private Tenant empresa(String id, String kiraUserId) {
        Tenant t = new Tenant(TenantId.of(id), "Empresa " + id, "900-" + id, "Colombia");
        if (kiraUserId != null) {
            t.linkKiraUser(kiraUserId);
        }
        return t;
    }

    @Test
    void soloSeSincronizanLasEmpresasDadasDeAltaEnKira() {
        when(tenants.findAll()).thenReturn(List.of(empresa("juriscop", "usr_1"), empresa("bankvision", null)));

        worker.syncOpenRfis();

        verify(rfis).syncForTenant(TenantId.of("juriscop"));
        verify(rfis, never()).syncForTenant(TenantId.of("bankvision"));
    }

    @Test
    void unFalloEnUnaEmpresaNoDetieneALasDemas() {
        when(tenants.findAll()).thenReturn(List.of(empresa("juriscop", "usr_1"), empresa("bankvision", "usr_2")));
        when(rfis.syncForTenant(TenantId.of("juriscop"))).thenThrow(new IllegalStateException("timeout"));

        worker.syncOpenRfis();

        verify(rfis).syncForTenant(TenantId.of("bankvision"));
    }

    @Test
    void sinCredencialesDeKiraSeCortaSinRecorrerElResto() {
        when(tenants.findAll()).thenReturn(List.of(empresa("juriscop", "usr_1"), empresa("bankvision", "usr_2")));
        when(rfis.syncForTenant(any())).thenThrow(new KiraNotConfiguredException("Falta KIRA_API_KEY."));

        worker.syncOpenRfis();

        verify(rfis, times(1)).syncForTenant(any());
    }

    @Test
    void sinEmpresasRegistradasNoSeLlamaAlServicio() {
        when(tenants.findAll()).thenReturn(List.of(empresa("juriscop", null)));

        worker.syncOpenRfis();

        verifyNoInteractions(rfis);
    }
}
