package com.example.autransactional.application.tenant;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.IdempotencyKey;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.Role;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.infrastructure.audit.AuditTrail;
import com.example.autransactional.infrastructure.kira.KiraApiClient;
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
        service = new SubmitOnboardingService(tenants, kira, audit, mapper, idempotencyKeys);
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
        assertEquals("business", body.getValue().get("type"));
        assertEquals("sales_of_goods_and_services", body.getValue().get("source_of_funds"));
        assertTrue(empresa.isVerificationTriggered());
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
}
