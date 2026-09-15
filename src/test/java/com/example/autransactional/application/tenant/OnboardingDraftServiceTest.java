package com.example.autransactional.application.tenant;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.Role;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.domain.tenant.TenantStatus;
import com.example.autransactional.domain.tenant.MissingFields;
import com.example.autransactional.infrastructure.audit.AuditTrail;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class OnboardingDraftServiceTest {

    private static final TenantId TENANT = TenantId.of("juriscop");

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final AuditTrail audit = mock(AuditTrail.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private OnboardingDraftService service;
    private Tenant empresa;

    private final AuthenticatedOperator compliance =
            new AuthenticatedOperator("u-1", "compliance.internal@juriscop.test", TENANT, Role.COMPLIANCE_INTERNAL);
    private final AuthenticatedOperator consulta =
            new AuthenticatedOperator("u-2", "read.only@juriscop.test", TENANT, Role.READ_ONLY);

    @BeforeEach
    void setUp() {
        empresa = new Tenant(TENANT, "Juriscop", "900123456-1", "Colombia");
        when(tenants.findById(TENANT)).thenAnswer(i -> Optional.of(empresa));
        when(tenants.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new OnboardingDraftService(tenants, audit, mapper);
    }

    @Test
    void sinBorradorDevuelveVacioYSinFecha() {
        OnboardingDraftView view = service.get(consulta);

        assertTrue(view.draft().isEmpty());
        assertNull(view.updatedAt());
    }

    @Test
    void guardaYRecuperaElBorradorSinLlamarAKira() {
        Map<String, Object> draft = Map.of(
                "company", Map.of("business_legal_name", "Juriscop S.A.S.", "business_type", "corporation"),
                "activity", Map.of("expected_monthly_volume", "less_than_50000"));

        OnboardingDraftView saved = service.save(compliance, new OnboardingCommands.SaveDraft(draft));

        assertNotNull(saved.updatedAt());
        assertNotNull(empresa.getOnboardingDraft());
        assertEquals(draft, service.get(consulta).draft());
        // Kira no aparece: el borrador no sale del BFF.
        verify(tenants).save(empresa);
        verify(audit).record(eq(compliance), eq("tenant.onboarding_draft_saved"), eq("tenant"), eq("juriscop"),
                isNull(), eq("OK"), contains("company"));
    }

    @Test
    void laBitacoraNoGuardaDatosDeLaEmpresa() {
        service.save(compliance, new OnboardingCommands.SaveDraft(
                Map.of("company", Map.of("ein", "12-3456789"))));

        verify(audit).record(any(), anyString(), anyString(), anyString(), isNull(), anyString(),
                argThat(detail -> !detail.contains("12-3456789")));
    }

    @Test
    void unObjetoVacioBorraElBorrador() {
        service.save(compliance, new OnboardingCommands.SaveDraft(Map.of("company", Map.of("email", "a@b.co"))));

        service.save(compliance, new OnboardingCommands.SaveDraft(Map.of()));

        assertNull(empresa.getOnboardingDraft());
        assertTrue(service.get(consulta).draft().isEmpty());
    }

    @Test
    void rechazaArchivosDentroDelBorrador() {
        Map<String, Object> draft = Map.of("documents",
                List.of(Map.of("type", "file_bylaws", "file", "data:application/pdf;base64,JVBERi0=")));

        DomainException error = assertThrows(DomainException.class,
                () -> service.save(compliance, new OnboardingCommands.SaveDraft(draft)));

        assertTrue(error.getMessage().contains("no admite archivos"));
        verify(tenants, never()).save(any());
    }

    @Test
    void rechazaUnBorradorDemasiadoGrande() {
        Map<String, Object> draft = Map.of("company", Map.of("business_description", "x".repeat(Tenant.MAX_DRAFT_CHARS)));

        assertThrows(DomainException.class, () -> service.save(compliance, new OnboardingCommands.SaveDraft(draft)));
        verify(tenants, never()).save(any());
    }

    @Test
    void unRolSinPermisoDeCumplimientoNoGuarda() {
        assertThrows(DomainException.class,
                () -> service.save(consulta, new OnboardingCommands.SaveDraft(Map.of("company", Map.of()))));
        verify(tenants, never()).save(any());
    }

    @Test
    void unaEmpresaRechazadaNoPuedeGuardarBorrador() {
        empresa = Tenant.rehydrate(TENANT, "Juriscop", "900123456-1", "Colombia", "usr_1", TenantStatus.REJECTED,
                List.of(), MissingFields.empty(), true, null, null, null, null, null);

        assertThrows(DomainException.class,
                () -> service.save(compliance, new OnboardingCommands.SaveDraft(Map.of("company", Map.of()))));
    }

    @Test
    void unBorradorIlegibleEnBaseSeDegradaAVacio() {
        empresa.restoreOnboardingDraft("{no es json", null);

        assertTrue(service.get(consulta).draft().isEmpty());
    }
}
