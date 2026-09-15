package com.example.autransactional.infrastructure.reconciliation;

import com.example.autransactional.application.account.OpenVirtualAccountService;
import com.example.autransactional.application.tenant.SubmitOnboardingService;
import com.example.autransactional.domain.account.VirtualAccount;
import com.example.autransactional.domain.account.VirtualAccountMode;
import com.example.autransactional.domain.account.VirtualAccountRepository;
import com.example.autransactional.domain.account.VirtualAccountStatus;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.EligibleProduct;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.domain.tenant.TenantStatus;
import com.example.autransactional.infrastructure.kira.KiraNotConfiguredException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Empresas y cuentas: los cambios sin webhook (o con webhook perdido) solo se ven consultando. */
class TenantAndAccountReconciliationWorkerTest {

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final VirtualAccountRepository accounts = mock(VirtualAccountRepository.class);
    private final SubmitOnboardingService onboarding = mock(SubmitOnboardingService.class);
    private final OpenVirtualAccountService accountService = mock(OpenVirtualAccountService.class);

    private Tenant empresa(String id, boolean registrada, TenantStatus status, boolean elegible) {
        Tenant t = new Tenant(TenantId.of(id), id, "900", "Colombia");
        if (registrada) {
            t.linkKiraUser("usr_" + id);
            t.applyRemoteState(status, null,
                    List.of(new EligibleProduct(EligibleProduct.USA_VIRTUAL_ACCOUNTS, elegible, List.of(), null)), true);
        }
        return t;
    }

    @Test
    void soloSeConsultanLasEmpresasRegistradasQueAunNoPuedenOperar() {
        Tenant enRevision = empresa("revision", true, TenantStatus.REVIEW, false);
        Tenant lista = empresa("lista", true, TenantStatus.VERIFIED, true);
        Tenant sinAlta = empresa("sinalta", false, TenantStatus.CREATED, false);
        when(tenants.findAll()).thenReturn(List.of(enRevision, lista, sinAlta));

        new TenantReconciliationWorker(tenants, onboarding).reconcile();

        verify(onboarding).reconcile(enRevision.getId());
        verify(onboarding, never()).reconcile(lista.getId());
        verify(onboarding, never()).reconcile(sinAlta.getId());
    }

    @Test
    void sinCredencialesElLoteDeEmpresasSeCorta() {
        Tenant a = empresa("a", true, TenantStatus.VERIFYING, false);
        Tenant b = empresa("b", true, TenantStatus.VERIFYING, false);
        when(tenants.findAll()).thenReturn(List.of(a, b));
        when(onboarding.reconcile(any())).thenThrow(new KiraNotConfiguredException("KIRA_API_KEY"));

        new TenantReconciliationWorker(tenants, onboarding).reconcile();

        verify(onboarding, times(1)).reconcile(any());
    }

    @Test
    void seConsultanLasCuentasAbiertasSalvoLasDesactivadas() {
        Tenant t = empresa("juriscop", true, TenantStatus.VERIFIED, true);
        VirtualAccount activa = cuenta("va-1", t, "kva_1", VirtualAccountStatus.ACTIVE);
        VirtualAccount desactivada = cuenta("va-2", t, "kva_2", VirtualAccountStatus.INACTIVE);
        VirtualAccount sinAbrir = cuenta("va-3", t, null, VirtualAccountStatus.PENDING);
        when(tenants.findAll()).thenReturn(List.of(t));
        when(accounts.findByTenant(t.getId())).thenReturn(List.of(activa, desactivada, sinAbrir));

        new VirtualAccountReconciliationWorker(tenants, accounts, accountService).reconcile();

        verify(accountService).reconcile(activa);
        verify(accountService, never()).reconcile(desactivada);
        verify(accountService, never()).reconcile(sinAbrir);
    }

    private VirtualAccount cuenta(String id, Tenant t, String kiraId, VirtualAccountStatus status) {
        VirtualAccount a = new VirtualAccount(id, t.getId(), "USD", VirtualAccountMode.FIAT, "jp_morgan", null);
        if (kiraId != null) {
            a.linkKiraAccount(kiraId);
        }
        a.applyRemoteStatus(status);
        return a;
    }
}
