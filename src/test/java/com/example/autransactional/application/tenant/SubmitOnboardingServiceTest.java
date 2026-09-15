package com.example.autransactional.application.tenant;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.IdempotencyKey;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.Role;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.infrastructure.audit.AuditTrail;
import com.example.autransactional.infrastructure.kira.KiraApiClient;
import com.example.autransactional.infrastructure.kira.KiraProperties;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
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

class SubmitOnboardingServiceTest {

    private static final TenantId TENANT = TenantId.of("juriscop");

    private final ObjectMapper mapper = new ObjectMapper();
    private final KiraApiClient kira = mock(KiraApiClient.class);
    private final AuditTrail audit = mock(AuditTrail.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final com.example.autransactional.application.shared.IdempotencyKeyStore idempotencyKeys =
            mock(com.example.autransactional.application.shared.IdempotencyKeyStore.class);

    private SubmitOnboardingService service;
    private Tenant empresa;

    private final AuthenticatedOperator compliance =
            new AuthenticatedOperator("u-1", "compliance.internal@juriscop.test", TENANT,
                    Role.COMPLIANCE_INTERNAL);

    @BeforeEach
    void setUp() {
        empresa = new Tenant(TENANT, "Juriscop", "900123456-1", "Colombia");
        when(tenants.findById(TENANT)).thenAnswer(i -> Optional.of(empresa));
        when(tenants.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new SubmitOnboardingService(tenants, kira, audit, mapper, idempotencyKeys,
                new KiraProperties("https://kira.test", "k", "c", "p", "2026-06-01", "w", null,
                        3600, 300, 5000, 30000, "jp_morgan", true), "2026-09", "https://au.test/terminos");
    }

    private JsonNode json(String raw) {
        return mapper.readTree(raw);
    }

    private OnboardingCommands.RegisterBusiness alta() {
        return new OnboardingCommands.RegisterBusiness(
                "Juriscop S.A.S.", "finanzas@juriscop.co", "sales_of_goods_and_services");
    }

    @Test
    void elAltaEnviaSoloEmpresasYAmarraElIdInterno() {
        when(kira.createUser(any(), any())).thenReturn(json("""
                { "id": "usr_9c1f", "status": "CREATED", "verification_triggered": false }
                """));

        service.register(compliance, alta());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(kira).createUser(body.capture(), any(IdempotencyKey.class));

        // 'individual' se rechaza con 400 business_only; source_of_funds es lo que
        // permite que el KYB llegue a dispararse.
        assertEquals("business", body.getValue().get("type"));
        assertEquals("sales_of_goods_and_services", body.getValue().get("source_of_funds"));
        assertEquals("juriscop", body.getValue().get("external_id"));
        // La elegibilidad del producto depende del banco que la empresa declara.
        assertEquals(Map.of("requested_banks", List.of("jp_morgan")), body.getValue().get("capabilities"));
        assertEquals("usr_9c1f", empresa.getKiraUserId());
    }

    @Test
    void laClaveDeIdempotenciaSePersisteAntesDeLlamarAKira() {
        when(kira.createUser(any(), any())).thenReturn(json("{ \"id\": \"usr_1\", \"status\": \"CREATED\" }"));

        service.register(compliance, alta());

        // La reserva se consolida en su propia transaccion (para que un rollback no la borre)
        // y el resultado se guarda despues con el caso de uso.
        verify(idempotencyKeys).persistNow(empresa);
        verify(tenants).save(any());
        assertNotNull(empresa.getOnboardingIdempotencyKey());
    }

    @Test
    void siLaLlamadaFallaLaClaveQuedaGuardadaParaElReintento() {
        when(kira.createUser(any(), any())).thenThrow(new IllegalStateException("timeout"));

        assertThrows(IllegalStateException.class, () -> service.register(compliance, alta()));

        String key = empresa.getOnboardingIdempotencyKey();
        assertNotNull(key);
        // El reintento reutiliza exactamente la misma clave: no crea una segunda empresa.
        assertEquals(key, empresa.reserveOnboardingKey().value());
        verify(audit).record(eq(compliance), eq("tenant.onboarding_registered"), anyString(),
                anyString(), eq(key), eq("ERROR"), anyString());
    }

    @Test
    void reenviarElAltaNoVuelveALlamarAKira() {
        when(kira.createUser(any(), any())).thenReturn(json("{ \"id\": \"usr_1\", \"status\": \"CREATED\" }"));
        service.register(compliance, alta());
        clearInvocations(kira);

        var view = service.register(compliance, alta());

        verify(kira, never()).createUser(any(), any());
        assertEquals("usr_1", view.kiraUserId());
    }

    @Test
    void elPutReenviaElObjetoCompletoNoSoloLoNuevo() {
        // G8: un PUT parcial borra en silencio lo que no viaje en el.
        when(kira.createUser(any(), any())).thenReturn(json("{ \"id\": \"usr_1\", \"status\": \"CREATED\" }"));
        service.register(compliance, alta());

        when(kira.updateUser(anyString(), any())).thenReturn(json("""
                { "id": "usr_1", "status": "VERIFYING", "verification_triggered": true }
                """));
        when(kira.getUser("usr_1")).thenReturn(json("""
                { "id": "usr_1", "status": "VERIFYING", "missing_fields": {} }
                """));

        service.completeProfile(compliance,
                new OnboardingCommands.CompleteProfile(Map.of("business_type", "sa_de_cv")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(kira).updateUser(eq("usr_1"), body.capture());

        assertEquals("sa_de_cv", body.getValue().get("business_type"));
        // Lo enviado en el alta sigue viajando.
        assertEquals("sales_of_goods_and_services", body.getValue().get("source_of_funds"));
        assertTrue(empresa.isVerificationTriggered());
    }

    @Test
    void elPutNoLlevaLasClavesQueSoloExistenEnElAlta() {
        // Sandbox 15-sep: type y external_id en el PUT dan 400 "Unrecognized key(s)".
        when(kira.createUser(any(), any())).thenReturn(json("{ \"id\": \"usr_1\", \"status\": \"CREATED\" }"));
        service.register(compliance, alta());
        when(kira.updateUser(anyString(), any())).thenReturn(json("{ \"id\": \"usr_1\", \"status\": \"CREATED\" }"));
        when(kira.getUser("usr_1")).thenReturn(json("{ \"id\": \"usr_1\", \"status\": \"CREATED\" }"));

        service.completeProfile(compliance,
                new OnboardingCommands.CompleteProfile(Map.of("business_description", "Consultoria")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(kira).updateUser(eq("usr_1"), body.capture());
        assertFalse(body.getValue().containsKey("type"));
        assertFalse(body.getValue().containsKey("external_id"));
    }

    @Test
    void losNombresDelAltaSeTraducenALosDelPut() {
        // Cada par verificado contra el sandbox el 15-sep: el nombre del alta da 400, el del PUT da 200.
        Map<String, Object> perfil = new java.util.LinkedHashMap<>();
        perfil.put("representative_date_of_birth", "1985-04-12");
        perfil.put("business_trade_name", "Juriscop");
        perfil.put("has_material_intermediary_ownership", false);
        perfil.put("registered_address", Map.of(
                "street_line_1", "Calle 1 # 2-3", "street_line_2", "Oficina 4", "city", "Bogota",
                "subdivision", "DC", "postal_code", "110111", "country", "COL"));

        Map<String, Object> body = SubmitOnboardingService.forUpdate(perfil);

        assertEquals("1985-04-12", body.get("representative_birth_date"));
        assertEquals("Juriscop", body.get("doing_business_as"));
        assertEquals("Calle 1 # 2-3, Oficina 4", body.get("address_street"));
        assertEquals("Bogota", body.get("address_city"));
        assertEquals("DC", body.get("address_state"));
        assertEquals("110111", body.get("address_zip_code"));
        assertEquals("COL", body.get("address_country"));
        assertFalse(body.containsKey("representative_date_of_birth"));
        assertFalse(body.containsKey("business_trade_name"));
        assertFalse(body.containsKey("registered_address"));
        assertFalse(body.containsKey("has_material_intermediary_ownership"));
    }

    @Test
    void unaDireccionNuevaReemplazaALaGuardada() {
        when(kira.createUser(any(), any())).thenReturn(json("{ \"id\": \"usr_1\", \"status\": \"CREATED\" }"));
        service.register(compliance, alta());
        when(kira.updateUser(anyString(), any())).thenReturn(json("{ \"id\": \"usr_1\", \"status\": \"CREATED\" }"));
        when(kira.getUser("usr_1")).thenReturn(json("{ \"id\": \"usr_1\", \"status\": \"CREATED\" }"));

        service.completeProfile(compliance, new OnboardingCommands.CompleteProfile(Map.of(
                "registered_address", Map.of("street_line_1", "Calle 1", "city", "Bogota", "country", "COL"),
                "representative_date_of_birth", "1985-04-12")));
        service.completeProfile(compliance, new OnboardingCommands.CompleteProfile(Map.of(
                "registered_address", Map.of("street_line_1", "Carrera 7", "city", "Medellin", "country", "COL"),
                "representative_date_of_birth", "1990-01-01")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(kira, times(2)).updateUser(anyString(), body.capture());
        assertEquals("Carrera 7", body.getValue().get("address_street"));
        assertEquals("Medellin", body.getValue().get("address_city"));
        assertEquals("1990-01-01", body.getValue().get("representative_birth_date"));
    }

    @Test
    void unArrayNuevoReemplazaEnteroAlGuardado() {
        // associated_persons debe viajar completo: mezclar elemento a elemento perderia campos.
        when(kira.createUser(any(), any())).thenReturn(json("{ \"id\": \"usr_1\", \"status\": \"CREATED\" }"));
        service.register(compliance, alta());
        when(kira.updateUser(anyString(), any())).thenReturn(json("{ \"id\": \"usr_1\", \"status\": \"CREATED\" }"));
        when(kira.getUser("usr_1")).thenReturn(json("{ \"id\": \"usr_1\", \"status\": \"CREATED\" }"));

        service.completeProfile(compliance, new OnboardingCommands.CompleteProfile(
                Map.of("associated_persons", List.of(Map.of("first_name", "Maria")))));
        service.completeProfile(compliance, new OnboardingCommands.CompleteProfile(
                Map.of("associated_persons", List.of(Map.of("first_name", "Ana"), Map.of("first_name", "Luis")))));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(kira, times(2)).updateUser(anyString(), body.capture());

        assertEquals(2, ((List<?>) body.getValue().get("associated_persons")).size());
    }

    @Test
    void completarElPerfilExigeAltaPrevia() {
        var e = assertThrows(DomainException.class, () -> service.completeProfile(compliance,
                new OnboardingCommands.CompleteProfile(Map.of("business_type", "ltda"))));

        assertTrue(e.getMessage().contains("no esta dada de alta"), e.getMessage());
        verify(kira, never()).updateUser(anyString(), any());
    }

    @Test
    void unRolDeTesoreriaNoGestionaElOnboarding() {
        var maker = new AuthenticatedOperator("u-2", "treasury.maker@juriscop.test", TENANT,
                Role.TREASURY_MAKER);

        assertThrows(DomainException.class, () -> service.register(maker, alta()));
        verify(kira, never()).createUser(any(), any());
    }

    // --- Documentos KYB (identifying_information[].documents[]) ---

    private KybDocumentCommands.AttachDocuments acta() {
        var file = new KybDocumentCommands.UploadedFile("acta.pdf", "application/pdf",
                "PDF".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new KybDocumentCommands.AttachDocuments("business_formation", "COL", "900123456", null,
                List.of(new KybDocumentCommands.DocumentFile("file_business_formation", file)));
    }

    private void empresaDadaDeAlta() {
        when(kira.createUser(any(), any())).thenReturn(json("{ \"id\": \"usr_1\", \"status\": \"CREATED\" }"));
        service.register(compliance, alta());
        when(kira.getUser(anyString())).thenReturn(json("{ \"id\": \"usr_1\", \"status\": \"CREATED\" }"));
        when(kira.updateUser(anyString(), any())).thenReturn(json("{ \"id\": \"usr_1\", \"status\": \"CREATED\" }"));
    }

    @Test
    void elDocumentoViajaDentroDelPutDelExpediente() {
        empresaDadaDeAlta();

        service.attachDocuments(compliance, acta());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(kira).updateUser(anyString(), body.capture());

        // Kira no tiene endpoint de subida: el archivo va anidado en el registro.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries =
                (List<Map<String, Object>>) body.getValue().get("identifying_information");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> documents = (List<Map<String, Object>>) entries.get(0).get("documents");
        assertEquals("data:application/pdf;base64,UERG", documents.get(0).get("file"));
    }

    @Test
    void elBase64NoSeGuardaEnElPayloadDeOnboarding() {
        empresaDadaDeAlta();

        service.attachDocuments(compliance, acta());

        // Si se guardara, el siguiente PUT lo reenviaria y el cuerpo crece sin techo
        // hasta pasarse de los 10 MB que admite Kira.
        String guardado = empresa.getOnboardingPayload();
        assertFalse(guardado.contains("base64"), guardado);
        assertTrue(guardado.contains("business_formation"), guardado);
    }

    @Test
    void elRegistroSobreviveAlSiguientePutDelPerfil() {
        empresaDadaDeAlta();
        service.attachDocuments(compliance, acta());

        service.completeProfile(compliance, new OnboardingCommands.CompleteProfile(
                Map.of("business_type", "ltda")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(kira, times(2)).updateUser(anyString(), body.capture());

        // El registro se reenvia sin archivos, y eso no los borra en Kira:
        // "a missing file works differently: sending other fields will not clear it".
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries =
                (List<Map<String, Object>>) body.getValue().get("identifying_information");
        assertEquals("business_formation", entries.get(0).get("type"));
        assertFalse(entries.get(0).containsKey("documents"));
    }

    @Test
    void subirDocumentosExigeAltaPrevia() {
        var e = assertThrows(DomainException.class, () -> service.attachDocuments(compliance, acta()));

        assertTrue(e.getMessage().contains("no esta dada de alta"), e.getMessage());
        verify(kira, never()).updateUser(anyString(), any());
    }

    @Test
    void unRolDeTesoreriaNoSubeDocumentosKyb() {
        var maker = new AuthenticatedOperator("u-2", "treasury.maker@juriscop.test", TENANT,
                Role.TREASURY_MAKER);

        assertThrows(DomainException.class, () -> service.attachDocuments(maker, acta()));
        verify(kira, never()).updateUser(anyString(), any());
    }

    // --- Terminos (arquitectura §2.1 y §7; Kira: tos_accepted_version) ---

    private void registrada() {
        empresa.linkKiraUser("usr_1");
    }

    @Test
    void aceptarLosTerminosVigentesLosMandaAKiraYQuedaAuditado() {
        registrada();

        var vista = service.acceptTerms(compliance, new OnboardingCommands.AcceptTerms("2026-09"));

        verify(kira).updateUser("usr_1", Map.of("tos_accepted_version", "2026-09"));
        verify(audit).record(eq(compliance), eq("tenant.terms_accepted"), eq("tenant"), eq("juriscop"),
                isNull(), eq("OK"), eq("version=2026-09"));
        assertEquals("2026-09", vista.acceptedVersion());
        assertEquals("https://au.test/terminos", vista.url());
    }

    @Test
    void unaVersionQueNoEsLaVigenteSeRechaza() {
        registrada();

        assertThrows(DomainException.class,
                () -> service.acceptTerms(compliance, new OnboardingCommands.AcceptTerms("2026-01")));
        verify(kira, never()).updateUser(anyString(), any());
    }

    @Test
    void elPerfilQueMandaElPortalNoPuedeFijarLaAceptacion() {
        registrada();
        when(kira.updateUser(anyString(), any())).thenReturn(json("{ \"id\": \"usr_1\", \"status\": \"CREATED\" }"));
        when(kira.getUser("usr_1")).thenReturn(json("{ \"id\": \"usr_1\", \"status\": \"CREATED\" }"));

        service.completeProfile(compliance, new OnboardingCommands.CompleteProfile(
                Map.of("business_description", "Abogados", "tos_accepted_version", "2026-09")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(kira).updateUser(eq("usr_1"), body.capture());
        assertFalse(body.getValue().containsKey("tos_accepted_version"));
        assertNull(service.terms(compliance).acceptedVersion());
    }
}
