package com.example.autransactional.application.compliance;

import com.example.autransactional.domain.account.Deposit;
import com.example.autransactional.domain.account.DepositRepository;
import com.example.autransactional.domain.compliance.Rfi;
import com.example.autransactional.domain.compliance.RfiRepository;
import com.example.autransactional.domain.compliance.RfiStatus;
import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.Rail;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.FeatureFlag;
import com.example.autransactional.domain.tenant.Role;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.domain.tenant.TenantSettings;
import com.example.autransactional.domain.tenant.TenantStatus;
import com.example.autransactional.domain.treasury.Payout;
import com.example.autransactional.domain.treasury.PayoutRepository;
import com.example.autransactional.domain.treasury.WalletToken;
import com.example.autransactional.infrastructure.audit.AuditTrail;
import com.example.autransactional.infrastructure.kira.KiraApiClient;
import com.example.autransactional.infrastructure.kira.KiraApiException;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class AnswerRfiServiceTest {

    private static final TenantId TENANT = TenantId.of("juriscop");
    private static final TenantId OTRA = TenantId.of("bankvision");

    private final ObjectMapper mapper = new ObjectMapper();
    private final KiraApiClient kira = mock(KiraApiClient.class);
    private final AuditTrail audit = mock(AuditTrail.class);
    private final RfiRepository rfis = mock(RfiRepository.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final PayoutRepository payouts = mock(PayoutRepository.class);
    private final DepositRepository deposits = mock(DepositRepository.class);

    private AnswerRfiService service;
    private List<Rfi> registro;

    private final AuthenticatedOperator cumplimiento =
            new AuthenticatedOperator("u-1", "compliance@juriscop.test", TENANT, Role.COMPLIANCE_INTERNAL);
    private final AuthenticatedOperator admin =
            new AuthenticatedOperator("u-9", "admin@juriscop.test", TENANT, Role.ADMIN);

    private static final String DETALLE = detalle("pending", "pending");

    /** Estado del RFI y del item de texto; el de documento sigue pendiente. */
    private static String detalle(String estadoRfi, String estadoEin) {
        return """
            { "rfi_id": "rfi_1", "user_id": "usr_1", "status": "%s",
              "due_at": "2026-09-25T00:00:00.000Z",
              "blocking": { "type": "transfer", "transfer_uuid": "kpo_9" },
              "items": [
                { "item_id": "i-ein", "answer_type": "identifier", "answer_spec": {"format": "ein"}, "status": "%s" },
                { "item_id": "i-doc", "answer_type": "document", "status": "pending" }
              ] }
            """.formatted(estadoRfi, estadoEin);
    }

    @BeforeEach
    void setUp() {
        Tenant empresa = new Tenant(TENANT, "Juriscop", "900123456-1", "Colombia");
        empresa.linkKiraUser("usr_1");
        empresa.applyRemoteState(TenantStatus.VERIFIED, null, null, true);
        Tenant otra = new Tenant(OTRA, "Bankvision", "900999999-1", "Colombia");
        otra.linkKiraUser("usr_2");
        registro = new ArrayList<>();

        when(tenants.findById(TENANT)).thenReturn(Optional.of(empresa));
        when(tenants.findByKiraUserId("usr_1")).thenReturn(Optional.of(empresa));
        when(tenants.findByKiraUserId("usr_2")).thenReturn(Optional.of(otra));
        when(rfis.save(any())).thenAnswer(i -> {
            Rfi r = i.getArgument(0);
            registro.removeIf(x -> x.getId().equals(r.getId()));
            registro.add(r);
            return r;
        });
        when(rfis.findByKiraRfiId(anyString())).thenAnswer(i -> registro.stream()
                .filter(r -> r.getKiraRfiId().equals(i.getArgument(0))).findFirst());
        when(rfis.findByIdAndTenant(anyString(), any())).thenAnswer(i -> registro.stream()
                .filter(r -> r.getId().equals(i.getArgument(0)) && r.getTenantId().equals(i.getArgument(1)))
                .findFirst());
        when(rfis.findByTenant(any())).thenAnswer(i -> registro.stream()
                .filter(r -> r.getTenantId().equals(i.getArgument(0))).toList());
        when(rfis.findOpenByTenant(any())).thenAnswer(i -> registro.stream()
                .filter(r -> r.getTenantId().equals(i.getArgument(0)) && r.getStatus().isOpen()).toList());

        service = new AnswerRfiService(rfis, tenants, payouts, deposits, kira, audit, mapper);
    }

    private JsonNode json(String raw) {
        return mapper.readTree(raw);
    }

    private Rfi sincronizarUno() {
        when(kira.listRfis(any())).thenReturn(json("{\"data\": [" + DETALLE + "]}"));
        service.sync(cumplimiento);
        return registro.getFirst();
    }

    @Test
    void laSincronizacionAsientaEstadoPlazoYBloqueo() {
        Rfi rfi = sincronizarUno();

        assertEquals(RfiStatus.PENDING, rfi.getStatus());
        assertEquals("kpo_9", rfi.getBlockingResourceId());
        assertNotNull(rfi.getDueDate());
    }

    @Test
    void sinElFeatureFlagRfisNoSeSincroniza() {
        tenants.findById(TENANT).orElseThrow().applySettings(new TenantSettings(
                EnumSet.allOf(Rail.class), EnumSet.allOf(WalletToken.class), Set.of(FeatureFlag.LIVENESS)));

        assertThrows(DomainException.class, () -> service.sync(cumplimiento));
        verify(kira, never()).listRfis(any());
    }

    // --- RFI retirado, motivo de cierre y enlace de beneficiario (docs 15-sep) ---

    private KiraApiException noEncontrado() {
        return new KiraApiException(404, "not_found", "Not found", "{}");
    }

    @Test
    void unRfiQueDesapareceDelListadoYDa404QuedaRetirado() {
        Rfi rfi = sincronizarUno();
        when(kira.listRfis(any())).thenReturn(json("{\"data\": []}"));
        when(kira.getRfi("rfi_1")).thenThrow(noEncontrado());

        service.sync(cumplimiento);

        assertEquals(RfiStatus.WITHDRAWN, rfi.getStatus());
        assertEquals("withdrawn", rfi.getResolutionReason());
        assertFalse(rfi.getStatus().isOpen());
    }

    @Test
    void refrescarUnRfiRetiradoLoCierraSinError() {
        Rfi rfi = sincronizarUno();
        when(kira.getRfi("rfi_1")).thenThrow(noEncontrado());

        var vista = service.refresh(cumplimiento, rfi.getId());

        assertEquals("WITHDRAWN", vista.status());
        assertFalse(vista.open());
    }

    @Test
    void seGuardaElMotivoDeUnRfiCerradoSinResolver() {
        Rfi rfi = sincronizarUno();
        when(kira.getRfi("rfi_1")).thenReturn(json("""
                { "rfi_id": "rfi_1", "user_id": "usr_1", "status": "not_resolved", "resolution_reason": "expired",
                  "items": [ { "item_id": "i-ein", "answer_type": "identifier", "status": "pending" } ] }
                """));

        var vista = service.refresh(cumplimiento, rfi.getId());

        assertEquals("NOT_RESOLVED", vista.status());
        assertEquals("expired", vista.resolutionReason());
    }

    private Rfi rfiConBeneficiario(String answerSpec) {
        when(kira.listRfis(any())).thenReturn(json("""
                { "data": [ { "rfi_id": "rfi_1", "user_id": "usr_1", "status": "pending",
                  "items": [ { "item_id": "i-ubo", "answer_type": "ubo_link", "answer_spec": %s, "status": "pending" } ] } ] }
                """.formatted(answerSpec)));
        service.sync(cumplimiento);
        return registro.getFirst();
    }

    @Test
    void seAcunaElEnlaceDelBeneficiarioCuandoElItemNoTraeUrl() {
        Rfi rfi = rfiConBeneficiario("{ \"applicant_id\": \"ap_1\", \"person_id\": \"pe_1\" }");
        when(kira.mintRfiUboLink("rfi_1", "i-ubo")).thenReturn(json(
                "{ \"url\": \"https://verify.example/abc\", \"expires_at\": \"2026-09-15T20:00:00Z\" }"));

        var enlace = service.mintUboLink(cumplimiento, rfi.getId(), "i-ubo");

        assertEquals("https://verify.example/abc", enlace.url());
        assertNotNull(enlace.expiresAt());
    }

    @Test
    void siElItemYaTraeUrlSeDevuelveSinLlamarAKira() {
        Rfi rfi = rfiConBeneficiario("{ \"url\": \"https://verify.example/listo\" }");

        var enlace = service.mintUboLink(cumplimiento, rfi.getId(), "i-ubo");

        assertEquals("https://verify.example/listo", enlace.url());
        verify(kira, never()).mintRfiUboLink(anyString(), anyString());
    }

    @Test
    void noSeAcunaEnlaceParaUnItemQueNoEsDeBeneficiario() {
        Rfi rfi = sincronizarUno();

        assertThrows(RfiAnswerRejectedException.class, () -> service.mintUboLink(cumplimiento, rfi.getId(), "i-ein"));
        verify(kira, never()).mintRfiUboLink(anyString(), anyString());
    }

    @Test
    void laSincronizacionNoImportaRfisDeOtraEmpresaAunqueKiraLosDevuelva() {
        when(kira.listRfis(any())).thenReturn(json("""
                [ { "rfi_id": "rfi_ajeno", "user_id": "usr_2", "status": "pending", "items": [] },
                  { "rfi_id": "rfi_huerfano", "status": "pending", "items": [] } ]
                """));

        service.sync(cumplimiento);

        assertTrue(registro.isEmpty());
    }

    @Test
    void laSincronizacionFiltraPorEmpresaYPaginaConOffset() {
        when(kira.listRfis(any())).thenReturn(json("[]"));

        service.sync(cumplimiento);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> query = ArgumentCaptor.forClass(Map.class);
        verify(kira).listRfis(query.capture());
        assertEquals("usr_1", query.getValue().get("user_id"));
        assertEquals(0, query.getValue().get("offset"));
    }

    @Test
    void unaEntradaResumidaSeCompletaConElDetalle() {
        when(kira.listRfis(any())).thenReturn(json("[{\"rfi_id\": \"rfi_1\", \"user_id\": \"usr_1\"}]"));
        when(kira.getRfi("rfi_1")).thenReturn(json(DETALLE));

        service.sync(cumplimiento);

        assertEquals(2, service.list(cumplimiento, false).getFirst().totalItems());
    }

    @Test
    void unItemDeDocumentoNuncaEnviaAnswerValue() {
        Rfi rfi = sincronizarUno();

        RfiAnswerRejectedException e = assertThrows(RfiAnswerRejectedException.class, () ->
                service.answer(cumplimiento, rfi.getId(), new RfiCommands.AnswerItems(List.of(
                        new RfiCommands.ItemAnswer("i-ein", "12-3456789"),
                        new RfiCommands.ItemAnswer("i-doc", "ver adjunto")))));

        assertTrue(e.itemErrors().containsKey("i-doc"));
        verify(kira, never()).answerRfiItems(anyString(), any());
    }

    @Test
    void unItemQueNoEsDelRfiSeRechazaSinLlamarAKira() {
        Rfi rfi = sincronizarUno();

        RfiAnswerRejectedException e = assertThrows(RfiAnswerRejectedException.class, () ->
                service.answer(cumplimiento, rfi.getId(), new RfiCommands.AnswerItems(List.of(
                        new RfiCommands.ItemAnswer("i-inventado", "x")))));

        assertTrue(e.itemErrors().containsKey("i-inventado"));
        verify(kira, never()).answerRfiItems(anyString(), any());
    }

    @Test
    void un422DeKiraSeDevuelvePorItemYNoSeMarcaNadaComoRespondido() {
        Rfi rfi = sincronizarUno();
        when(kira.answerRfiItems(eq("rfi_1"), any())).thenThrow(new KiraApiException(422, null, "invalid",
                "{\"errors\": [{\"item_id\": \"i-ein\", \"message\": \"EIN format is invalid\"}]}"));

        RfiAnswerRejectedException e = assertThrows(RfiAnswerRejectedException.class, () ->
                service.answer(cumplimiento, rfi.getId(), new RfiCommands.AnswerItems(List.of(
                        new RfiCommands.ItemAnswer("i-ein", "123")))));

        assertEquals("EIN format is invalid", e.itemErrors().get("i-ein"));
        assertEquals(RfiStatus.PENDING, registro.getFirst().getStatus());
    }

    @Test
    void un409AsientaElCierreAntesDeAvisar() {
        Rfi rfi = sincronizarUno();
        when(kira.answerRfiItems(eq("rfi_1"), any()))
                .thenThrow(new KiraApiException(409, null, "closed", null));
        when(kira.getRfi("rfi_1")).thenReturn(json(detalle("not_resolved", "pending")));

        assertThrows(DomainException.class, () ->
                service.answer(cumplimiento, rfi.getId(), new RfiCommands.AnswerItems(List.of(
                        new RfiCommands.ItemAnswer("i-ein", "12-3456789")))));

        assertEquals(RfiStatus.NOT_RESOLVED, registro.getFirst().getStatus());
    }

    @Test
    void trasUnaRespuestaParcialElEstadoLoDecideKiraYNoElPortal() {
        Rfi rfi = sincronizarUno();
        when(kira.answerRfiItems(eq("rfi_1"), any())).thenReturn(json("{\"items\": []}"));
        // Queda un documento sin subir: el RFI sigue pendiente.
        when(kira.getRfi("rfi_1")).thenReturn(json(detalle("pending", "answered")));

        RfiView vista = service.answer(cumplimiento, rfi.getId(), new RfiCommands.AnswerItems(List.of(
                new RfiCommands.ItemAnswer("i-ein", "12-3456789"))));

        assertEquals("PENDING", vista.status());
        assertEquals(1, vista.pendingItems());
    }

    @Test
    void elCuerpoDelPatchLlevaItemIdYAnswerValue() {
        Rfi rfi = sincronizarUno();
        when(kira.answerRfiItems(eq("rfi_1"), any())).thenReturn(json("{}"));
        when(kira.getRfi("rfi_1")).thenReturn(json(DETALLE));

        service.answer(cumplimiento, rfi.getId(), new RfiCommands.AnswerItems(List.of(
                new RfiCommands.ItemAnswer("i-ein", "12-3456789"))));

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(kira).answerRfiItems(eq("rfi_1"), body.capture());
        assertEquals(Map.of("items", List.of(Map.of("item_id", "i-ein", "answer_value", "12-3456789"))),
                body.getValue());
    }

    @Test
    void tesoreriaNoPuedeResponderRfis() {
        Rfi rfi = sincronizarUno();
        AuthenticatedOperator maker =
                new AuthenticatedOperator("u-2", "treasury.maker@juriscop.test", TENANT, Role.TREASURY_MAKER);

        assertThrows(DomainException.class, () -> service.answer(maker, rfi.getId(),
                new RfiCommands.AnswerItems(List.of(new RfiCommands.ItemAnswer("i-ein", "1")))));
    }

    @Test
    void elRfiEnlazaElPagoQueTieneDetenido() {
        Payout pago = mock(Payout.class);
        when(pago.getId()).thenReturn("p-1");
        when(pago.getTenantId()).thenReturn(TENANT);
        when(pago.getStatus()).thenReturn(com.example.autransactional.domain.treasury.PayoutStatus.IN_REVIEW);
        when(payouts.findByKiraPayoutId("kpo_9")).thenReturn(Optional.of(pago));
        Rfi rfi = sincronizarUno();

        RfiView vista = service.get(cumplimiento, rfi.getId());

        assertEquals("p-1", vista.blocking().payoutId());
        assertEquals("IN_REVIEW", vista.blocking().payoutStatus());
    }

    @Test
    void unRfiNuevoPorWebhookSeLeeDeKiraYSeAtribuyePorUserId() {
        when(kira.getRfi("rfi_1")).thenReturn(json(DETALLE));

        service.applyWebhook("rfi_1", "pending");

        assertEquals(1, registro.size());
        assertEquals(TENANT, registro.getFirst().getTenantId());
    }

    @Test
    void unWebhookDeResolucionCierraElRfiLocal() {
        // Aunque el GET llegue con retraso respecto al evento, el cierre no se deshace.
        sincronizarUno();
        when(kira.getRfi("rfi_1")).thenReturn(json(detalle("resolved", "answered")));

        service.applyWebhook("rfi_1", "resolved");

        assertEquals(RfiStatus.RESOLVED, registro.getFirst().getStatus());
    }

    // ---------- Valores tipados y bloqueo por deposito ----------

    @Test
    void unaRespuestaNumericaOBooleanaViajaConSuTipo() {
        when(kira.listRfis(any())).thenReturn(json("""
                { "data": [ { "rfi_id": "rfi_2", "user_id": "usr_1", "status": "pending", "items": [
                  { "item_id": "i-num", "answer_type": "number", "status": "pending" },
                  { "item_id": "i-bool", "answer_type": "boolean", "status": "pending" } ] } ] }
                """));
        service.sync(cumplimiento);
        Rfi rfi = registro.getFirst();
        when(kira.answerRfiItems(eq("rfi_2"), any())).thenReturn(json("{}"));
        when(kira.getRfi("rfi_2")).thenReturn(json("{\"rfi_id\":\"rfi_2\",\"status\":\"answered\",\"items\":[]}"));

        service.answer(cumplimiento, rfi.getId(), new RfiCommands.AnswerItems(List.of(
                new RfiCommands.ItemAnswer("i-num", 25000), new RfiCommands.ItemAnswer("i-bool", true))));

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(kira).answerRfiItems(eq("rfi_2"), body.capture());
        assertEquals(Map.of("items", List.of(Map.of("item_id", "i-num", "answer_value", 25000),
                Map.of("item_id", "i-bool", "answer_value", true))), body.getValue());
    }

    @Test
    void unRfiQueDetieneUnDepositoEnlazaElDepositoDelPortal() {
        Deposit deposito = mock(Deposit.class);
        when(deposito.getId()).thenReturn("d-1");
        when(deposito.getTenantId()).thenReturn(TENANT);
        when(deposito.getStatus()).thenReturn(com.example.autransactional.domain.account.DepositStatus.PENDING);
        when(deposits.findByKiraDepositId("kdep_7")).thenReturn(Optional.of(deposito));
        when(kira.listRfis(any())).thenReturn(json("""
                [ { "rfi_id": "rfi_3", "user_id": "usr_1", "status": "pending",
                    "blocking": { "type": "virtual_account_deposit", "virtual_account_deposit_uuid": "kdep_7" },
                    "items": [] } ]
                """));
        service.sync(cumplimiento);

        RfiView vista = service.get(cumplimiento, registro.getFirst().getId());

        assertEquals("virtual_account_deposit", vista.blocking().type());
        assertEquals("d-1", vista.blocking().depositId());
        assertNull(vista.blocking().payoutId());
    }

    // ---------- Documentos ----------

    private RfiCommands.UploadedFile pdf(String nombre) {
        return new RfiCommands.UploadedFile(nombre, "application/pdf", "%PDF-1.4".getBytes());
    }

    @Test
    void subirArchivosAUnItemDeTextoSeRechazaSinLlamarAKira() {
        Rfi rfi = sincronizarUno();

        assertThrows(RfiAnswerRejectedException.class, () ->
                service.uploadDocuments(cumplimiento, rfi.getId(), "i-ein", List.of(pdf("a.pdf"))));
        verify(kira, never()).uploadRfiDocuments(anyString(), anyString(), any());
    }

    @Test
    void unTipoDeArchivoNoAdmitidoSeRechazaPorArchivo() {
        Rfi rfi = sincronizarUno();

        RfiAnswerRejectedException e = assertThrows(RfiAnswerRejectedException.class, () ->
                service.uploadDocuments(cumplimiento, rfi.getId(), "i-doc", List.of(
                        new RfiCommands.UploadedFile("hoja.xlsx", "application/vnd.ms-excel", new byte[]{1}))));

        assertTrue(e.itemErrors().get("hoja.xlsx").contains("Tipo no admitido"));
        verify(kira, never()).uploadRfiDocuments(anyString(), anyString(), any());
    }

    @Test
    void elAnswerSpecDelItemPuedeLimitarElNumeroDeArchivos() {
        when(kira.listRfis(any())).thenReturn(json("""
                [ { "rfi_id": "rfi_4", "user_id": "usr_1", "status": "pending", "items": [
                  { "item_id": "i-doc", "answer_type": "document", "status": "pending",
                    "answer_spec": { "max_files": 1, "mime_types": ["application/pdf"] } } ] } ]
                """));
        service.sync(cumplimiento);

        RfiAnswerRejectedException e = assertThrows(RfiAnswerRejectedException.class, () ->
                service.uploadDocuments(cumplimiento, registro.getFirst().getId(), "i-doc",
                        List.of(pdf("a.pdf"), pdf("b.pdf"))));

        assertTrue(e.itemErrors().get("i-doc").contains("maximo 1"));
    }

    @Test
    void subirUnPdfLoEnviaAKiraYReleeElRfi() {
        Rfi rfi = sincronizarUno();
        when(kira.uploadRfiDocuments(eq("rfi_1"), eq("i-doc"), any())).thenReturn(json("{\"item\":{}}"));
        when(kira.getRfi("rfi_1")).thenReturn(json(detalle("answered", "answered")));

        RfiView vista = service.uploadDocuments(cumplimiento, rfi.getId(), "i-doc", List.of(pdf("acta.pdf")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.example.autransactional.infrastructure.kira.KiraFile>> files =
                ArgumentCaptor.forClass(List.class);
        verify(kira).uploadRfiDocuments(eq("rfi_1"), eq("i-doc"), files.capture());
        assertEquals("acta.pdf", files.getValue().getFirst().fileName());
        assertEquals("ANSWERED", vista.status());
    }

    @Test
    void noSePuedeBorrarElUltimoArchivoYElErrorVaPorItem() {
        Rfi rfi = sincronizarUno();
        when(kira.removeRfiDocument("rfi_1", "i-doc", "doc-1")).thenThrow(new KiraApiException(422, null,
                "The last file cannot be removed", "{\"message\":\"The last file cannot be removed\"}"));

        RfiAnswerRejectedException e = assertThrows(RfiAnswerRejectedException.class, () ->
                service.removeDocument(admin, rfi.getId(), "i-doc", "doc-1"));

        assertEquals("The last file cannot be removed", e.itemErrors().get("i-doc"));
    }

    @Test
    void cumplimientoNoPuedeBorrarDocumentosSoloAdmin() {
        // Borrar es destructivo: se separa de la gestion general de RFIs (guia de arquitectura §2.5).
        Rfi rfi = sincronizarUno();

        assertThrows(DomainException.class, () ->
                service.removeDocument(cumplimiento, rfi.getId(), "i-doc", "doc-1"));
        verify(kira, never()).removeRfiDocument(any(), any(), any());
    }

    @Test
    void elEnlaceDeDescargaSeDevuelveConSuCaducidad() {
        Rfi rfi = sincronizarUno();
        when(kira.getRfiDocumentLink("rfi_1", "i-doc", "doc-1")).thenReturn(json(
                "{\"download_url\":\"https://files.kira.test/x?sig=abc\",\"expires_at\":\"2026-09-11T15:05:00Z\"}"));

        RfiDocumentLink link = service.documentLink(cumplimiento, rfi.getId(), "i-doc", "doc-1");

        assertEquals("https://files.kira.test/x?sig=abc", link.downloadUrl());
        assertEquals(java.time.Instant.parse("2026-09-11T15:05:00Z"), link.expiresAt());
        // La descarga queda auditada, pero sin la URL firmada.
        verify(audit).record(eq(cumplimiento), eq("compliance.rfi_document_link_issued"), eq("rfi"),
                eq(rfi.getId()), isNull(), eq("OK"), eq("item=i-doc documento=doc-1"));
    }
}
