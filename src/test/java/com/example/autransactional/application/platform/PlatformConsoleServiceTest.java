package com.example.autransactional.application.platform;

import com.example.autransactional.application.account.OpenVirtualAccountService;
import com.example.autransactional.application.tenant.SubmitOnboardingService;
import com.example.autransactional.domain.account.DepositRepository;
import com.example.autransactional.domain.account.VirtualAccountRepository;
import com.example.autransactional.domain.compliance.Rfi;
import com.example.autransactional.domain.compliance.RfiRepository;
import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.Role;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.domain.tenant.TenantStatus;
import com.example.autransactional.domain.tenant.UboRepository;
import com.example.autransactional.domain.tenant.UboRoster;
import com.example.autransactional.domain.treasury.PayoutRepository;
import com.example.autransactional.infrastructure.audit.AuditTrail;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlatformConsoleServiceTest {

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final UboRepository ubos = mock(UboRepository.class);
    private final VirtualAccountRepository accounts = mock(VirtualAccountRepository.class);
    private final PayoutRepository payouts = mock(PayoutRepository.class);
    private final DepositRepository deposits = mock(DepositRepository.class);
    private final RfiRepository rfis = mock(RfiRepository.class);
    private final AuditTrail audit = mock(AuditTrail.class);

    private final AuthenticatedOperator plataforma =
            new AuthenticatedOperator("platform:operator", "operaciones@au.test", TenantId.PLATFORM, Role.PLATFORM_OPERATOR);
    private final AuthenticatedOperator adminEmpresa =
            new AuthenticatedOperator("juriscop:admin", "admin@juriscop.test", TenantId.of("juriscop"), Role.ADMIN);

    private PlatformConsoleService service;
    private Tenant juriscop;
    private Tenant bankvision;

    @BeforeEach
    void setUp() {
        juriscop = new Tenant(TenantId.of("juriscop"), "Juriscop", "900", "Colombia");
        juriscop.linkKiraUser("usr_1");
        juriscop.rejectVerification("Documento ilegible");
        bankvision = new Tenant(TenantId.of("bankvision"), "Bankvision", "901", "Colombia");
        when(tenants.findAll()).thenReturn(List.of(juriscop, bankvision));
        when(tenants.findById(TenantId.of("juriscop"))).thenReturn(Optional.of(juriscop));
        when(ubos.findByTenant(any())).thenReturn(List.of());
        when(ubos.rosterOf(any())).thenReturn(new UboRoster(List.of()));
        when(accounts.findByTenant(any())).thenReturn(List.of());
        when(payouts.findByTenant(any(), anyInt())).thenReturn(List.of());
        when(deposits.findByTenant(any(), anyInt())).thenReturn(List.of());
        when(rfis.findByTenant(any())).thenReturn(List.of());
        when(rfis.findOpenByTenant(any())).thenReturn(List.of());
        service = new PlatformConsoleService(tenants, ubos, accounts, payouts, deposits, rfis,
                mock(SubmitOnboardingService.class), mock(OpenVirtualAccountService.class), audit);
    }

    @Test
    void unOperadorDeEmpresaNoAccedeALaConsola() {
        assertThrows(DomainException.class, () -> service.tenants(adminEmpresa));
        assertThrows(DomainException.class, () -> service.tenant(adminEmpresa, "juriscop"));
    }

    @Test
    void elListadoIncluyeTodasLasOrganizacionesOrdenadas() {
        var listado = service.tenants(plataforma);

        assertEquals(List.of("Bankvision", "Juriscop"), listado.stream().map(PlatformConsoleService.TenantSummary::name).toList());
        assertEquals("REJECTED", listado.get(1).status());
        assertEquals("Documento ilegible", listado.get(1).rejectionReason());
    }

    @Test
    void consultarUnaFichaQuedaAuditado() {
        var ficha = service.tenant(plataforma, "juriscop");

        assertEquals("juriscop", ficha.onboarding().tenantId());
        verify(audit).record(eq(plataforma), eq("platform.tenant_viewed"), eq("tenant"), eq("juriscop"), isNull(),
                eq("OK"), isNull());
    }

    @Test
    void laBandejaPoneLoCriticoPrimero() {
        Tenant enRevision = new Tenant(TenantId.of("otra"), "Otra", "902", "Colombia");
        enRevision.linkKiraUser("usr_3");
        enRevision.applyRemoteState(TenantStatus.REVIEW, null, null, true);
        when(tenants.findAll()).thenReturn(List.of(enRevision, juriscop));
        Rfi vencido = Rfi.rehydrate("r-1", TenantId.of("otra"), "rfi_1",
                com.example.autransactional.domain.compliance.RfiStatus.PENDING, "[{}]",
                Instant.now().minusSeconds(3600), null, null, null, Instant.now(), Instant.now());
        when(rfis.findOpenByTenant(TenantId.of("otra"))).thenReturn(List.of(vencido));

        var bandeja = service.reviewQueue(plataforma);

        assertEquals(3, bandeja.size());
        assertEquals("critical", bandeja.get(0).severity());
        assertEquals("critical", bandeja.get(1).severity());
        assertEquals("onboarding.review", bandeja.get(2).kind());
    }
}
