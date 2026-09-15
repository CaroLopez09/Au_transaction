package com.example.autransactional.application.webhook;

import com.example.autransactional.application.compliance.AnswerRfiService;
import com.example.autransactional.application.tenant.SyncUbosService;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.LivenessStatus;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.domain.tenant.TenantStatus;
import com.example.autransactional.domain.account.VirtualAccount;
import com.example.autransactional.domain.account.VirtualAccountMode;
import com.example.autransactional.application.account.RecordDepositService;
import com.example.autransactional.domain.account.VirtualAccountRepository;
import com.example.autransactional.domain.treasury.PayoutRepository;
import com.example.autransactional.infrastructure.persistence.WebhookEventEntity;
import com.example.autransactional.infrastructure.persistence.WebhookEventJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * La familia user.* trae dos datos que no existen en ningun otro sitio: el motivo del
 * rechazo del KYB y el resultado real de la prueba de vida. Si no se proyectan aqui,
 * se pierden: la entrega es unica y el GET no los expone.
 */
class UserEventProjectionTest {

    private final WebhookEventJpaRepository events = mock(WebhookEventJpaRepository.class);
    private final PayoutRepository payouts = mock(PayoutRepository.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final SyncUbosService ubos = mock(SyncUbosService.class);
    private final VirtualAccountRepository accounts = mock(VirtualAccountRepository.class);
    private final RecordDepositService deposits = mock(RecordDepositService.class);
    private final AnswerRfiService rfis = mock(AnswerRfiService.class);
    private final com.example.autransactional.application.notification.NotificationService notifications =
            mock(com.example.autransactional.application.notification.NotificationService.class);

    private ProcessWebhookUseCase useCase;
    private Tenant empresa;

    @BeforeEach
    void setUp() {
        empresa = new Tenant(TenantId.of("juriscop"), "Juriscop", "900123456-1", "Colombia");
        empresa.linkKiraUser("usr_1");

        when(events.existsByEventId(anyString())).thenReturn(false);
        when(events.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(events.save(any())).thenAnswer(i -> i.getArgument(0));
        when(tenants.findByKiraUserId("usr_1")).thenAnswer(i -> Optional.of(empresa));
        when(tenants.save(any())).thenAnswer(i -> i.getArgument(0));

        useCase = new ProcessWebhookUseCase(events, payouts, tenants, accounts, deposits, ubos,
                rfis, new ObjectMapper(), notifications);
    }

    private void procesar(String json) throws Exception {
        useCase.process(json);
    }

    @Test
    void elMotivoDelRechazoSeCapturaPorqueElGetNoLoExpone() throws Exception {
        procesar("""
                { "event": "user.verification.failed",
                  "data": { "event_id": "e1", "user_id": "usr_1", "status": "REJECTED",
                            "reason": "Documento de constitucion ilegible" } }
                """);

        assertEquals(TenantStatus.REJECTED, empresa.getStatus());
        assertEquals("Documento de constitucion ilegible", empresa.getRejectionReason());
    }

    // --- Payloads copiados de docs.kirafin.ai/webhooks/notification-examples (15-sep) ---

    @Test
    void docs_verificationFailedGuardaLosReasons() throws Exception {
        procesar("""
                { "event": "user.verification.failed",
                  "data": { "event_id": "9a8b7c60-1d2e-4f30-8a4b-5c6d7e8f9a0b", "user_id": "usr_1",
                            "verification_status": "rejected",
                            "reasons": ["Verification session expired", "Document unreadable"] } }
                """);

        assertEquals(TenantStatus.REJECTED, empresa.getStatus());
        assertEquals("Verification session expired; Document unreadable", empresa.getRejectionReason());
    }

    @Test
    void docs_statusChangedMueveElEstadoConNewStatus() throws Exception {
        procesar("""
                { "event": "user.status_changed",
                  "data": { "event_id": "8c2b1d40-5e6f-4a7b-9c8d-2e3f4a5b6c7d", "user_id": "usr_1",
                            "previous_status": "CREATED", "new_status": "VERIFYING" } }
                """);

        assertEquals(TenantStatus.VERIFYING, empresa.getStatus());
        verify(tenants).save(empresa);
    }

    @Test
    void unaEmpresaVerificadaGeneraUnAvisoYElEventoQuedaAtribuido() throws Exception {
        procesar("""
                { "event": "user.status_changed",
                  "data": { "event_id": "e-aviso", "user_id": "usr_1", "previous_status": "REVIEW", "new_status": "VERIFIED" } }
                """);

        verify(notifications).notify(eq(empresa.getId()), eq("onboarding.verified"), eq("success"), anyString(),
                anyString(), eq("tenant"), eq("juriscop"));
        ArgumentCaptor<WebhookEventEntity> stored = ArgumentCaptor.forClass(WebhookEventEntity.class);
        verify(events).save(stored.capture());
        assertEquals("juriscop", stored.getValue().getTenantId());
    }

    @Test
    void unEventoQueNoCambiaElEstadoNoAvisa() throws Exception {
        empresa.applyRemoteState(TenantStatus.VERIFIED, null, null, true);
        procesar("""
                { "event": "user.status_changed",
                  "data": { "event_id": "e-igual", "user_id": "usr_1", "new_status": "VERIFIED" } }
                """);

        verify(notifications, never()).notify(any(), anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void docs_livenessCompletedLeeResult() throws Exception {
        procesar("""
                { "event": "user.liveness_completed",
                  "data": { "event_id": "5b6c7d80-9e0f-4a1b-8c2d-3e4f5a6b7c8d", "user_id": "usr_1",
                            "person_reference_id": "0123456789abcdef0123456789abcdef", "result": "approved" } }
                """);

        verify(ubos).applyLivenessResult("0123456789abcdef0123456789abcdef", LivenessStatus.COMPLETED);
    }

    @Test
    void docs_livenessDeLaPropiaEmpresaNoTocaBeneficiarios() throws Exception {
        procesar("""
                { "event": "user.liveness_completed",
                  "data": { "event_id": "e-liv-empresa", "user_id": "usr_1",
                            "person_reference_id": null, "result": "approved" } }
                """);

        verify(ubos, never()).applyLivenessResult(any(), any());
    }

    @Test
    void unRechazoSinMotivoDejaConstanciaIgual() throws Exception {
        procesar("""
                { "event": "user.verification.failed",
                  "data": { "event_id": "e2", "user_id": "usr_1", "status": "REJECTED" } }
                """);

        assertEquals(TenantStatus.REJECTED, empresa.getStatus());
        assertNotNull(empresa.getRejectionReason());
    }

    @Test
    void laAceptacionMarcaVerificadoYVerificacionDisparada() throws Exception {
        procesar("""
                { "event": "user.verification.accepted",
                  "data": { "event_id": "e3", "user_id": "usr_1", "status": "VERIFIED" } }
                """);

        assertTrue(empresa.isVerified());
        assertTrue(empresa.isVerificationTriggered());
    }

    @Test
    void unEventoSinStatusNoDegradaAUnaEmpresaVerificada() throws Exception {
        empresa.applyRemoteState(TenantStatus.VERIFIED, null, null, true);

        procesar("""
                { "event": "user.updated", "data": { "event_id": "e4", "user_id": "usr_1" } }
                """);

        // fromWire cae a CREATED ante un status ausente: aplicarlo seria una regresion.
        assertEquals(TenantStatus.VERIFIED, empresa.getStatus());
        verify(tenants, never()).save(any());
    }

    @Test
    void elResultadoDeLivenessSeDelegaEnSuPersona() throws Exception {
        procesar("""
                { "event": "user.liveness_completed",
                  "data": { "event_id": "e5", "user_id": "usr_1",
                            "person_reference_id": "per_ana", "status": "approved" } }
                """);

        verify(ubos).applyLivenessResult("per_ana", LivenessStatus.COMPLETED);
        // El estado del evento es de la persona, no de la empresa.
        assertEquals(TenantStatus.CREATED, empresa.getStatus());
    }

    @Test
    void unLivenessRechazadoSeTraduceAFallido() throws Exception {
        procesar("""
                { "event": "user.liveness_completed",
                  "data": { "event_id": "e6", "person_reference_id": "per_ana", "status": "rejected" } }
                """);

        verify(ubos).applyLivenessResult("per_ana", LivenessStatus.FAILED);
    }

    @Test
    void unUsuarioDesconocidoSeRegistraSinRomperNada() throws Exception {
        procesar("""
                { "event": "user.status_changed",
                  "data": { "event_id": "e7", "user_id": "usr_ajeno", "status": "VERIFIED" } }
                """);

        ArgumentCaptor<WebhookEventEntity> stored = ArgumentCaptor.forClass(WebhookEventEntity.class);
        verify(events).saveAndFlush(stored.capture());
        assertEquals("usr_ajeno", stored.getValue().getResourceId());
        verify(tenants, never()).save(any());
    }

    @Test
    void elMismoEventoDosVecesSoloSeProyectaUnaVez() throws Exception {
        when(events.existsByEventId("e8")).thenReturn(false, true);
        String evento = """
                { "event": "user.verification.accepted",
                  "data": { "event_id": "e8", "user_id": "usr_1", "status": "VERIFIED" } }
                """;

        procesar(evento);
        procesar(evento);

        verify(tenants, times(1)).save(any());
    }

    @Test
    void elEventoQuedaMarcadoComoProcesado() throws Exception {
        procesar("""
                { "event": "user.verification.accepted",
                  "data": { "event_id": "e9", "user_id": "usr_1", "status": "VERIFIED" } }
                """);

        ArgumentCaptor<WebhookEventEntity> stored = ArgumentCaptor.forClass(WebhookEventEntity.class);
        verify(events).save(stored.capture());
        assertTrue(stored.getValue().isProcessed());
        assertNotNull(stored.getValue().getProcessedAt());
        assertEquals("user.verification.accepted", stored.getValue().getEventType());
    }

    @Test
    void elEventoActivatedEsLaSenalDeFondosListos() throws Exception {
        // 'approved' colapsa activating y active: solo este evento (o un numero de cuenta
        // real) dice que la cuenta puede mover dinero.
        VirtualAccount cuenta = new VirtualAccount("va-1", TenantId.of("juriscop"), "USD",
                VirtualAccountMode.FIAT, "jp_morgan", null);
        cuenta.linkKiraAccount("kva_1");
        when(accounts.findByKiraAccountId("kva_1")).thenReturn(Optional.of(cuenta));

        procesar("""
                { "event": "virtual_account.activated",
                  "data": { "event_id": "e11", "virtual_account_id": "kva_1",
                            "status": "approved", "account_number": "1234567890" } }
                """);

        assertTrue(cuenta.isActivatedEventSeen());
        assertTrue(cuenta.isFundsReady());
        verify(accounts).save(cuenta);
    }

    @Test
    void unEventoDeCuentaSinNumeroNoBorraElQueYaTeniamos() throws Exception {
        VirtualAccount cuenta = new VirtualAccount("va-1", TenantId.of("juriscop"), "USD",
                VirtualAccountMode.FIAT, "jp_morgan", null);
        cuenta.linkKiraAccount("kva_1");
        cuenta.describeBank("Example Bank", "1234567890", "021000021");
        when(accounts.findByKiraAccountId("kva_1")).thenReturn(Optional.of(cuenta));

        procesar("""
                { "event": "virtual_account.created",
                  "data": { "event_id": "e12", "virtual_account_id": "kva_1", "status": "pending" } }
                """);

        // Ese numero es la senal de fondos-listos: perderlo seria perder la senal.
        assertEquals("1234567890", cuenta.getAccountNumber());
    }

    @Test
    void unEventoDeOtraFamiliaSigueFuncionando() throws Exception {
        when(payouts.findByKiraPayoutId(anyString())).thenReturn(Optional.empty());

        procesar("""
                { "event": "payout.completed",
                  "data": { "event_id": "e10", "payout_id": "pay_1", "status": "completed" } }
                """);

        verify(payouts).findByKiraPayoutId("pay_1");
        verify(ubos, never()).applyLivenessResult(anyString(), any());
    }

    @Test
    void unEventoDeRfiSeProyectaEnLaBandejaYNoSoloSeAlmacena() throws Exception {
        procesar("""
                { "event": "rfi.created",
                  "data": { "event_id": "e-rfi", "rfi_id": "rfi_1", "status": "pending" } }
                """);

        verify(rfis).applyWebhook("rfi_1", "pending");
    }
}
