package com.example.autransactional.application.tenant;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.LivenessStatus;
import com.example.autransactional.domain.tenant.Role;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.domain.tenant.TenantStatus;
import com.example.autransactional.domain.tenant.Ubo;
import com.example.autransactional.domain.tenant.UboRepository;
import com.example.autransactional.domain.tenant.UboRoster;
import com.example.autransactional.infrastructure.audit.AuditTrail;
import com.example.autransactional.infrastructure.kira.KiraApiClient;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SyncUbosServiceTest {

    private static final TenantId TENANT = TenantId.of("juriscop");

    private final ObjectMapper mapper = new ObjectMapper();
    private final KiraApiClient kira = mock(KiraApiClient.class);
    private final AuditTrail audit = mock(AuditTrail.class);
    private final UboRepository ubos = mock(UboRepository.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final SubmitOnboardingService onboarding = mock(SubmitOnboardingService.class);

    private SyncUbosService service;
    private Tenant empresa;
    private List<Ubo> registro;

    private final AuthenticatedOperator compliance =
            new AuthenticatedOperator("u-1", "compliance.internal@juriscop.test", TENANT,
                    Role.COMPLIANCE_INTERNAL);

    @BeforeEach
    void setUp() {
        empresa = new Tenant(TENANT, "Juriscop", "900123456-1", "Colombia");
        registro = new ArrayList<>();

        when(tenants.findById(TENANT)).thenAnswer(i -> Optional.of(empresa));
        when(ubos.rosterOf(TENANT)).thenAnswer(i -> new UboRoster(registro));
        when(ubos.findByTenant(TENANT)).thenAnswer(i -> registro);
        when(ubos.save(any())).thenAnswer(i -> i.getArgument(0));
        when(ubos.findByIdAndTenant(anyString(), eq(TENANT))).thenAnswer(i -> registro.stream()
                .filter(u -> i.getArgument(0).equals(u.getId()))
                .findFirst());
        when(ubos.findByPersonReferenceId(anyString())).thenAnswer(i -> registro.stream()
                .filter(u -> i.getArgument(0).equals(u.getPersonReferenceId()))
                .findFirst());

        service = new SyncUbosService(ubos, tenants, onboarding, kira, audit);
    }

    private JsonNode json(String raw) {
        return mapper.readTree(raw);
    }

    private Ubo registrar(String nombre, boolean propiedad, String porcentaje) {
        Ubo u = new Ubo(nombre, TENANT, nombre, "Perez", new BigDecimal(porcentaje), "Socio");
        u.describeRole(propiedad, new BigDecimal(porcentaje), false, true, false, "COL");
        registro.add(u);
        return u;
    }

    private void kybEnCurso() {
        empresa.linkKiraUser("usr_1");
        empresa.applyRemoteState(TenantStatus.VERIFYING, null, null, true);
    }

    @Test
    void elArrayEnviadoLlevaLosBooleanosQueKiraExige() {
        registrar("Ana", true, "60");
        when(onboarding.completeProfile(any(), any())).thenReturn(null);

        service.syncToKira(compliance);

        ArgumentCaptor<OnboardingCommands.CompleteProfile> captor =
                ArgumentCaptor.forClass(OnboardingCommands.CompleteProfile.class);
        verify(onboarding).completeProfile(eq(compliance), captor.capture());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> personas =
                (List<Map<String, Object>>) captor.getValue().profile().get("associated_persons");

        assertEquals(1, personas.size());
        Map<String, Object> ana = personas.getFirst();
        // Sin estos campos explicitos el KYB se atasca sin decir por que.
        assertEquals(true, ana.get("has_ownership"));
        assertEquals(false, ana.get("pep_status"));
        assertEquals("COL", ana.get("country_of_birth"));
        assertEquals(true, ana.get("is_signer"));
    }

    // --- Datos de identidad por persona (missing_fields reales del sandbox, 15-sep) ---

    private UboCommands.SaveUbo edicion(String id, String nombre) {
        return new UboCommands.SaveUbo(id, nombre, "Gomez", "ana@juriscop.co", "passport", "AB123",
                true, new BigDecimal("60"), false, true, false, "COL", "Gerente",
                java.time.LocalDate.of(1985, 4, 12), "col", "Abogada", "female", "+573001234567", "COL",
                new UboCommands.ResidentialAddress("Calle 1 # 2-3", "Bogota", "DC", "110111", "COL"));
    }

    @Test
    void laPersonaViajaConLosDatosQueKiraPideYSinPersonReferenceId() {
        Ubo ana = registrar("Ana", true, "60");
        ana.linkKiraPerson("per_ana");
        service.save(compliance, edicion("Ana", "Ana"));
        when(onboarding.completeProfile(any(), any())).thenReturn(null);

        service.syncToKira(compliance);

        ArgumentCaptor<OnboardingCommands.CompleteProfile> captor =
                ArgumentCaptor.forClass(OnboardingCommands.CompleteProfile.class);
        verify(onboarding).completeProfile(eq(compliance), captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> persona = ((List<Map<String, Object>>) captor.getValue().profile()
                .get("associated_persons")).getFirst();

        assertEquals("1985-04-12", persona.get("birth_date"));
        assertEquals("COL", persona.get("nationality"));
        assertEquals("Abogada", persona.get("occupation"));
        assertEquals("female", persona.get("gender"));
        assertEquals("+573001234567", persona.get("phone_number"));
        assertEquals("COL", persona.get("document_country"));
        assertEquals("Calle 1 # 2-3", persona.get("address_street"));
        assertEquals("COL", persona.get("address_country"));
        assertFalse(persona.containsKey("person_reference_id"));
    }

    @Test
    void editarCorrigeNombreApellidoYCargo() {
        registrar("Ana", true, "60");

        UboView vista = service.save(compliance, edicion("Ana", "Anna"));

        assertEquals("Anna", vista.firstName());
        assertEquals("Gomez", vista.lastName());
        assertEquals("Gerente", vista.roleInCompany());
        assertEquals("Anna Gomez", vista.fullName());
    }

    @Test
    void seBorraUnBeneficiarioQueKiraAunNoConoce() {
        registrar("Ana", true, "60");
        doAnswer(i -> registro.remove((Ubo) i.getArgument(0))).when(ubos).delete(any());

        UboView.Roster grupo = service.delete(compliance, "Ana");

        assertTrue(grupo.members().isEmpty());
        verify(audit).record(eq(compliance), eq("tenant.ubo_deleted"), anyString(), eq("Ana"), any(), eq("OK"), anyString());
    }

    @Test
    void noSeRegistraUnSegundoBeneficiarioConElMismoCorreo() {
        Ubo ana = registrar("Ana", true, "60");
        ana.describeEmail("ana@juriscop.co");

        var otra = new UboCommands.SaveUbo(null, "Ana", "Duplicada", "ANA@juriscop.co", null, null,
                true, new BigDecimal("40"), false, false, false, "COL", null,
                null, null, null, null, null, null, null);

        assertThrows(DomainException.class, () -> service.save(compliance, otra));
        verify(ubos, never()).save(any());
    }

    @Test
    void trasSincronizarElBeneficiarioYaNoSePuedeBorrar() {
        Ubo ana = registrar("Ana", true, "60");
        when(onboarding.completeProfile(any(), any())).thenReturn(null);

        service.syncToKira(compliance);

        assertTrue(ana.isKnownToKira());
        assertThrows(DomainException.class, () -> service.delete(compliance, "Ana"));
    }

    @Test
    void noSeBorraUnBeneficiarioQueKiraYaConoce() {
        registrar("Ana", true, "60").linkKiraPerson("per_ana");

        assertThrows(DomainException.class, () -> service.delete(compliance, "Ana"));
        verify(ubos, never()).delete(any());
    }

    @Test
    void seEnviaSiempreElArrayCompletoNoSoloElUltimoAlta() {
        // Un PUT parcial borraria en silencio a los demas beneficiarios.
        registrar("Ana", true, "60");
        registrar("Luis", true, "40");
        when(onboarding.completeProfile(any(), any())).thenReturn(null);

        service.syncToKira(compliance);

        ArgumentCaptor<OnboardingCommands.CompleteProfile> captor =
                ArgumentCaptor.forClass(OnboardingCommands.CompleteProfile.class);
        verify(onboarding).completeProfile(any(), captor.capture());
        assertEquals(2, ((List<?>) captor.getValue().profile().get("associated_persons")).size());
    }

    @Test
    void sinBeneficiarioNoSeGastaLaLlamadaAKira() {
        registrar("Ana", false, "0");

        assertThrows(DomainException.class, () -> service.syncToKira(compliance));

        verify(onboarding, never()).completeProfile(any(), any());
    }

    @Test
    void sinVerificacionEnCursoNoSePidenEnlaces() {
        // Kira responde 422 "No verification is in progress".
        empresa.linkKiraUser("usr_1");
        registrar("Ana", true, "60");

        assertThrows(DomainException.class,
                () -> service.requestLivenessLinks(compliance, null));

        verify(kira, never()).requestLivenessLink(anyString(), any());
    }

    @Test
    void losEnlacesSeRepartenPorReferenciaDePersona() {
        kybEnCurso();
        Ubo ana = registrar("Ana", true, "60");
        ana.linkKiraPerson("per_ana");

        when(kira.requestLivenessLink(eq("usr_1"), any())).thenReturn(json("""
                { "links": [ { "subject": "ubo", "person_reference_id": "per_ana",
                               "name": "Ana Perez", "liveness_link": "https://kira/l/ana",
                               "expires_at": "2026-09-17T20:30:00.000Z" } ] }
                """));

        service.requestLivenessLinks(compliance,
                new UboCommands.RequestLivenessLinks("https://portal/ok", "https://portal/ko"));

        assertEquals("https://kira/l/ana", ana.getLivenessLink());
        assertEquals(Instant.parse("2026-09-17T20:30:00.000Z"), ana.getLivenessExpiresAt());
        assertEquals(LivenessStatus.PENDING, ana.getLivenessStatus());
    }

    @Test
    void sinReferenciaPreviaElEnlaceSeEmparejaPorNombreYLaGuarda() {
        kybEnCurso();
        Ubo ana = registrar("Ana", true, "60");

        when(kira.requestLivenessLink(anyString(), any())).thenReturn(json("""
                { "data": { "links": [ { "person_reference_id": "per_nueva", "name": "Ana Perez",
                                          "liveness_link": "https://kira/l/ana" } ] } }
                """));

        service.requestLivenessLinks(compliance, null);

        assertEquals("per_nueva", ana.getPersonReferenceId());
        assertNotNull(ana.getLivenessLink());
        // Sin expires_at explicito se aplica la vigencia documentada de 7 dias.
        assertTrue(ana.getLivenessExpiresAt().isAfter(Instant.now().plus(java.time.Duration.ofDays(6))));
    }

    @Test
    void lasUrlsDeRedireccionViajanSoloSiEstanCompletas() {
        kybEnCurso();
        registrar("Ana", true, "60");
        when(kira.requestLivenessLink(anyString(), any())).thenReturn(json("{ \"links\": [] }"));

        service.requestLivenessLinks(compliance, new UboCommands.RequestLivenessLinks(null, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(kira).requestLivenessLink(eq("usr_1"), body.capture());
        // Un 'redirect' a medias con URLs no preautorizadas es un 400 seguro.
        assertFalse(body.getValue().containsKey("redirect"));
    }

    @Test
    void elWebhookDeLivenessAsientaElResultadoEnSuPersona() {
        Ubo ana = registrar("Ana", true, "60");
        ana.linkKiraPerson("per_ana");

        service.applyLivenessResult("per_ana", LivenessStatus.COMPLETED);

        assertEquals(LivenessStatus.COMPLETED, ana.getLivenessStatus());
    }

    @Test
    void unWebhookSinPersonaNoTocaANadie() {
        Ubo ana = registrar("Ana", true, "60");
        ana.linkKiraPerson("per_ana");

        service.applyLivenessResult(null, LivenessStatus.COMPLETED);
        service.applyLivenessResult("per_desconocida", LivenessStatus.FAILED);

        assertEquals(LivenessStatus.PENDING, ana.getLivenessStatus());
    }

    @Test
    void unRolDeTesoreriaNoGestionaBeneficiarios() {
        var maker = new AuthenticatedOperator("u-2", "treasury.maker@juriscop.test", TENANT,
                Role.TREASURY_MAKER);

        assertThrows(DomainException.class, () -> service.syncToKira(maker));
    }

    // --- Documentos de identidad de una persona ---

    private KybDocumentCommands.AttachDocuments pasaporte() {
        var file = new KybDocumentCommands.UploadedFile("pasaporte.png", "image/png",
                "PNG".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new KybDocumentCommands.AttachDocuments("passport", "COL", "AB1234567", "2030-01-01",
                List.of(new KybDocumentCommands.DocumentFile("front", file),
                        new KybDocumentCommands.DocumentFile("selfie", file)));
    }

    @Test
    void elDocumentoDeUnaPersonaViajaAnidadoEnSuEntradaYSeEmparejaPorEmail() {
        kybEnCurso();
        Ubo ana = registrar("ana", true, "60");
        ana.describeEmail("ana@juriscop.co");
        when(kira.updateUser(anyString(), any())).thenReturn(json("{ \"id\": \"usr_1\" }"));

        service.attachDocuments(compliance, "ana", pasaporte());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(kira).updateUser(eq("usr_1"), body.capture());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> personas =
                (List<Map<String, Object>>) body.getValue().get("associated_persons");
        assertEquals(1, personas.size());
        // Sin email, Kira no actualiza a Ana: le crea otra persona.
        assertEquals("ana@juriscop.co", personas.get(0).get("email"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> registros =
                (List<Map<String, Object>>) personas.get(0).get("identifying_information");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> documentos = (List<Map<String, Object>>) registros.get(0).get("documents");
        assertEquals("passport", registros.get(0).get("type"));
        // La selfie junto al documento le basta a Kira para el face match.
        assertEquals("selfie", documentos.get(1).get("type"));
        assertTrue(((String) documentos.get(0).get("file")).startsWith("data:image/png;base64,"));
    }

    @Test
    void sinEmailNoSePuedenSubirSusDocumentos() {
        kybEnCurso();
        registrar("ana", true, "60");

        var e = assertThrows(DomainException.class,
                () -> service.attachDocuments(compliance, "ana", pasaporte()));

        assertTrue(e.getMessage().contains("email"), e.getMessage());
        verify(kira, never()).updateUser(anyString(), any());
    }

    @Test
    void noSePuedenSubirDocumentosDeUnBeneficiarioDeOtraEmpresa() {
        kybEnCurso();

        assertThrows(DomainException.class,
                () -> service.attachDocuments(compliance, "de-otra-empresa", pasaporte()));
        verify(kira, never()).updateUser(anyString(), any());
    }

    @Test
    void elEmailViajaEnLaSincronizacionDelGrupo() {
        kybEnCurso();
        Ubo ana = registrar("ana", true, "60");
        ana.describeEmail("ana@juriscop.co");

        service.syncToKira(compliance);

        ArgumentCaptor<OnboardingCommands.CompleteProfile> perfil =
                ArgumentCaptor.forClass(OnboardingCommands.CompleteProfile.class);
        verify(onboarding).completeProfile(any(), perfil.capture());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> personas =
                (List<Map<String, Object>>) perfil.getValue().profile().get("associated_persons");
        assertEquals("ana@juriscop.co", personas.get(0).get("email"));
    }
}
