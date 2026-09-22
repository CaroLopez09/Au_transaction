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

    private final AuthenticatedOperator admin =
            new AuthenticatedOperator("u-1", "admin@juriscop.test", TENANT, Role.ADMIN);
    private final AuthenticatedOperator approver =
            new AuthenticatedOperator("u-2", "treasury.approver@juriscop.test", TENANT, Role.TREASURY_APPROVER);

    @BeforeEach
    void setUp() {
        empresa = new Tenant(TENANT, "Juriscop", "900123456-1", "Colombia");
        when(tenants.findById(TENANT)).thenAnswer(i -> Optional.of(empresa));
        when(tenants.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new OnboardingDraftService(tenants, audit, mapper);
    }

    @Test
    void sinBorradorDevuelveVacioYSinFecha() {
        OnboardingDraftView view = service.get(approver);

        assertTrue(view.draft().isEmpty());
        assertNull(view.updatedAt());
    }

    @Test
    void guardaYRecuperaElBorradorSinLlamarAKira() {
        Map<String, Object> draft = Map.of(
                "company", Map.of("business_legal_name", "Juriscop S.A.S.", "business_type", "corporation"),
                "activity", Map.of("expected_monthly_volume", "less_than_50000"));

        OnboardingDraftView saved = service.save(admin, new OnboardingCommands.SaveDraft(draft));

        assertNotNull(saved.updatedAt());
        assertNotNull(empresa.getOnboardingDraft());
        assertEquals(draft, service.get(approver).draft());
        // Kira no aparece: el borrador no sale del BFF.
        verify(tenants).save(empresa);
        verify(audit).record(eq(admin), eq("tenant.onboarding_draft_saved"), eq("tenant"), eq("juriscop"),
                isNull(), eq("OK"), contains("company"));
    }

    @Test
    void laBitacoraNoGuardaDatosDeLaEmpresa() {
        service.save(admin, new OnboardingCommands.SaveDraft(
                Map.of("company", Map.of("ein", "12-3456789"))));

        verify(audit).record(any(), anyString(), anyString(), anyString(), isNull(), anyString(),
                argThat(detail -> !detail.contains("12-3456789")));
    }

    @Test
    void unObjetoVacioBorraElBorrador() {
        service.save(admin, new OnboardingCommands.SaveDraft(Map.of("company", Map.of("email", "a@b.co"))));

        service.save(admin, new OnboardingCommands.SaveDraft(Map.of()));

        assertNull(empresa.getOnboardingDraft());
        assertTrue(service.get(approver).draft().isEmpty());
    }

    @Test
    void rechazaArchivosDentroDelBorrador() {
        Map<String, Object> draft = Map.of("documents",
                List.of(Map.of("type", "file_bylaws", "file", "data:application/pdf;base64,JVBERi0=")));

        DomainException error = assertThrows(DomainException.class,
                () -> service.save(admin, new OnboardingCommands.SaveDraft(draft)));

        assertTrue(error.getMessage().contains("no admite archivos"));
        verify(tenants, never()).save(any());
    }

    @Test
    void rechazaUnBorradorDemasiadoGrande() {
        Map<String, Object> draft = Map.of("company", Map.of("business_description", "x".repeat(Tenant.MAX_DRAFT_CHARS)));

        assertThrows(DomainException.class, () -> service.save(admin, new OnboardingCommands.SaveDraft(draft)));
        verify(tenants, never()).save(any());
    }

    @Test
    void unRolSinPermisoDeCumplimientoNoGuarda() {
        assertThrows(DomainException.class,
                () -> service.save(approver, new OnboardingCommands.SaveDraft(Map.of("company", Map.of()))));
        verify(tenants, never()).save(any());
    }

    @Test
    void unaEmpresaRechazadaNoPuedeGuardarBorrador() {
        empresa = Tenant.rehydrate(TENANT, "Juriscop", "900123456-1", "Colombia", "usr_1", TenantStatus.REJECTED,
                List.of(), MissingFields.empty(), true, null, null, null, null, null);

        assertThrows(DomainException.class,
                () -> service.save(admin, new OnboardingCommands.SaveDraft(Map.of("company", Map.of()))));
    }

    @Test
    void unBorradorIlegibleEnBaseSeDegradaAVacio() {
        empresa.restoreOnboardingDraft("{no es json", null);

        assertTrue(service.get(approver).draft().isEmpty());
    }

    // --- Rehidratacion desde el expediente ya enviado ---

    /** Expediente como lo deja `forUpdate`: nombres del PUT de Kira, no los del asistente. */
    private void conExpedienteEnviado() {
        empresa.recordOnboardingPayload(mapper.writeValueAsString(Map.ofEntries(
                Map.entry("business_legal_name", "Juriscop S.A.S."),
                Map.entry("email", "tesoreria@juriscop.test"),
                Map.entry("doing_business_as", "Juriscop"),
                Map.entry("business_industry", List.of("legal-services")),
                Map.entry("address_street", "Calle 1 # 2-3"),
                Map.entry("address_city", "Bogota"),
                Map.entry("address_country", "COL"),
                Map.entry("pep_status", false),
                Map.entry("source_of_funds", "sales_of_goods_and_services"),
                Map.entry("expected_monthly_payments", "10"),
                Map.entry("transaction_countries", List.of("COL", "USA")),
                Map.entry("additional_info", Map.of("has_us_bank_account", "No")),
                Map.entry("representative_birth_date", "1980-05-04"),
                Map.entry("representative_first_name", "Ana"),
                Map.entry("capabilities", Map.of("requested_banks", List.of("zenus"))))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sinBorradorElFormularioSeRellenaConLoYaEnviado() {
        conExpedienteEnviado();

        Map<String, Object> draft = service.get(approver).draft();

        Map<String, Object> company = (Map<String, Object>) draft.get("company");
        assertEquals("Juriscop S.A.S.", company.get("business_legal_name"));
        // Los nombres vuelven a los del asistente.
        assertEquals("Juriscop", company.get("business_trade_name"));
        assertEquals("legal-services", company.get("business_industry"));
        assertEquals("Calle 1 # 2-3", ((Map<String, Object>) company.get("registered_address")).get("street_line_1"));
        assertEquals("COL", ((Map<String, Object>) company.get("registered_address")).get("country"));

        Map<String, Object> activity = (Map<String, Object>) draft.get("activity");
        assertEquals("false", activity.get("pep_status"));
        assertEquals("No", activity.get("has_us_bank_account"));
        assertEquals("sales_of_goods_and_services", activity.get("source_of_funds"));
        assertEquals("10", activity.get("expected_monthly_payments"));
        assertEquals("COL, USA", activity.get("transaction_countries"));

        Map<String, Object> representative = (Map<String, Object>) draft.get("representative");
        assertEquals("1980-05-04", representative.get("representative_date_of_birth"));
        assertEquals("Ana", representative.get("representative_first_name"));

        // Lo que no es un campo del asistente no se cuela en el formulario.
        assertFalse(company.containsKey("capabilities"));
    }

    /** El defecto reportado: el asistente guarda la forma completa y sus vacios tapaban lo enviado. */
    @Test
    @SuppressWarnings("unchecked")
    void unBorradorRecienEmpezadoNoBorraLoYaEnviado() {
        conExpedienteEnviado();
        service.save(admin, new OnboardingCommands.SaveDraft(Map.of(
                "company", new java.util.LinkedHashMap<>(Map.of("business_legal_name", "Otra Razon S.A.S.",
                        "email", "", "business_type", "")),
                "activity", Map.of("source_of_funds", ""))));

        Map<String, Object> draft = service.get(approver).draft();

        Map<String, Object> company = (Map<String, Object>) draft.get("company");
        // Lo tecleado manda...
        assertEquals("Otra Razon S.A.S.", company.get("business_legal_name"));
        // ...y lo que quedo en blanco se recupera del expediente.
        assertEquals("tesoreria@juriscop.test", company.get("email"));
        assertEquals("sales_of_goods_and_services",
                ((Map<String, Object>) draft.get("activity")).get("source_of_funds"));
    }

    @Test
    void sinExpedienteNiBorradorSigueDevolviendoVacio() {
        assertTrue(service.get(approver).draft().isEmpty());
        assertNull(service.get(approver).updatedAt());
    }

    @Test
    void unExpedienteIlegibleNoRompeElFormulario() {
        empresa.recordOnboardingPayload("{no es json");
        service.save(admin, new OnboardingCommands.SaveDraft(Map.of("company", Map.of("email", "a@b.co"))));

        assertEquals(Map.of("company", Map.of("email", "a@b.co")), service.get(approver).draft());
    }
}
