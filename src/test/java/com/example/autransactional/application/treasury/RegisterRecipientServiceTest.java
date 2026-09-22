package com.example.autransactional.application.treasury;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.IdempotencyKey;
import com.example.autransactional.domain.shared.Rail;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.Role;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.domain.tenant.TenantSettings;
import com.example.autransactional.domain.tenant.TenantStatus;
import com.example.autransactional.domain.treasury.Recipient;
import com.example.autransactional.domain.treasury.RecipientRepository;
import com.example.autransactional.domain.treasury.WalletToken;
import com.example.autransactional.infrastructure.audit.AuditTrail;
import com.example.autransactional.infrastructure.kira.KiraApiClient;
import com.example.autransactional.infrastructure.kira.KiraResponse;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RegisterRecipientServiceTest {

    private static final TenantId TENANT = TenantId.of("juriscop");

    private final ObjectMapper mapper = new ObjectMapper();
    private final KiraApiClient kira = mock(KiraApiClient.class);
    private final AuditTrail audit = mock(AuditTrail.class);
    private final RecipientRepository recipients = mock(RecipientRepository.class);
    private final TenantRepository tenants = mock(TenantRepository.class);

    private RegisterRecipientService service;
    private List<Recipient> registro;

    private final AuthenticatedOperator admin =
            new AuthenticatedOperator("u-1", "admin@juriscop.test", TENANT, Role.ADMIN);

    @BeforeEach
    void setUp() {
        Tenant empresa = new Tenant(TENANT, "Juriscop", "900123456-1", "Colombia");
        empresa.linkKiraUser("usr_1");
        empresa.applyRemoteState(TenantStatus.VERIFIED, null, null, true);
        registro = new ArrayList<>();

        when(tenants.findById(TENANT)).thenReturn(Optional.of(empresa));
        when(recipients.save(any())).thenAnswer(i -> {
            registro.add(i.getArgument(0));
            return i.getArgument(0);
        });
        when(recipients.findByIdAndTenant(any(), any())).thenAnswer(i -> registro.stream()
                .filter(r -> r.getId().equals(i.getArgument(0)))
                .findFirst());
        when(kira.createRecipient(any(), any())).thenReturn(
                new KiraResponse(201, mapper.readTree("{\"recipient_id\":\"krec_1\"}")));
        when(recipients.findByKiraRecipientId(any())).thenAnswer(i -> registro.stream()
                .filter(r -> i.getArgument(0).equals(r.getKiraRecipientId()))
                .findFirst());

        service = new RegisterRecipientService(recipients, tenants, kira, audit);
    }

    private RecipientCommands.RegisterRecipient wire() {
        return new RecipientCommands.RegisterRecipient("WIRE", true, null, null, "Acme Corp",
                "pagos@acme.com", "+13055551234",
                new RecipientCommands.Address("1 Main St", "New York", "NY", "10001", "US"),
                "021000021", "EXAMUS33XXX", "1234567890", "checking", "Example Bank",
                null, new RecipientCommands.Address("1 Bank Plaza", "New York", "NY", "10001", "US"),
                null, null, null, "ein", "12-3456789");
    }

    private RecipientCommands.RegisterRecipient wallet(String token, String network) {
        return new RecipientCommands.RegisterRecipient("WALLET", false, "Ana", "Perez", null,
                null, null, null,
                null, null, null, null, null, null, null,
                token, network, "0xabc123", "passport", "AB1234567");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cuerpoEnviado() {
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(kira).createRecipient(body.capture(), any(IdempotencyKey.class));
        return body.getValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cuentaEnviada() {
        return (Map<String, Object>) cuerpoEnviado().get("account");
    }

    @Test
    void elTitularSeInfiereDeLosNombresPorqueNoExisteHolderName() {
        service.register(admin, wire());

        Map<String, Object> body = cuerpoEnviado();
        assertEquals("business", body.get("type"));
        assertEquals("Acme Corp", body.get("company_name"));
        assertFalse(body.containsKey("holder_name"));
        assertEquals("usr_1", body.get("user_id"));
    }

    @Test
    void enWireLaDireccionDelBancoEsUnObjeto() {
        service.register(admin, wire());

        Map<String, Object> cuenta = cuentaEnviada();
        assertEquals("WIRE", cuenta.get("account_type"));
        assertEquals("EXAMUS33XXX", cuenta.get("swift_code"));
        assertInstanceOf(Map.class, cuenta.get("bank_address"));
    }

    @Test
    void enAchLaDireccionDelBancoEsTextoPlano() {
        var ach = new RecipientCommands.RegisterRecipient("ACH", true, null, null, "Acme Corp",
                null, null, new RecipientCommands.Address("1 Main St", "NY", "NY", "10001", "US"),
                "021000021", null, "1234567890", "savings", "Example Bank",
                "1 Bank Plaza, NY", null, null, null, null, "ein", "12-3456789");

        service.register(admin, ach);

        Map<String, Object> cuenta = cuentaEnviada();
        assertEquals("ACH", cuenta.get("account_type"));
        assertEquals("1 Bank Plaza, NY", cuenta.get("bank_address"));
        assertEquals("savings", cuenta.get("type"));
        assertFalse(cuenta.containsKey("swift_code"));
    }

    @Test
    void unaWalletViajaConTokenYRed() {
        service.register(admin, wallet("USDT", "tron"));

        Map<String, Object> cuenta = cuentaEnviada();
        assertEquals("WALLET", cuenta.get("account_type"));
        assertEquals("USDT", cuenta.get("token"));
        assertEquals("tron", cuenta.get("network"));
        // Una wallet no lleva datos bancarios.
        assertFalse(cuenta.containsKey("routing_number"));
    }

    @Test
    void unParTokenRedInvalidoSeCortaAntesDeLlamar() {
        assertThrows(DomainException.class, () -> service.register(admin, wallet("USDC", "tron")));

        verify(kira, never()).createRecipient(any(), any());
    }

    @Test
    void seLeeRecipientIdNoId() {
        var view = service.register(admin, wire());

        assertEquals("krec_1", view.kiraRecipientId());
        assertTrue(view.registeredInKira());
    }

    @Test
    void unDoscientosDosEsExitoNoError() {
        // "Ya existia, te devuelvo el registro existente".
        when(kira.createRecipient(any(), any())).thenReturn(
                new KiraResponse(202, mapper.readTree("{\"recipient_id\":\"krec_ya_existia\"}")));

        var view = service.register(admin, wire());

        assertTrue(view.alreadyExisted());
        assertEquals("krec_ya_existia", view.kiraRecipientId());
    }

    @Test
    void unReintentoConLaMismaClaveNoGuardaUnSegundoDestinatario() {
        String clave = "8c2b1d40-5e6f-4a7b-9c8d-2e3f4a5b6c7d";

        var primero = service.register(admin, wire(), clave);
        var segundo = service.register(admin, wire(), clave);

        assertEquals(1, registro.size());
        assertEquals(primero.id(), segundo.id());
        ArgumentCaptor<IdempotencyKey> enviada = ArgumentCaptor.forClass(IdempotencyKey.class);
        verify(kira, times(2)).createRecipient(any(), enviada.capture());
        assertEquals(clave, enviada.getValue().value());
    }

    @Test
    void elEstadoYElCodigoPostalSalenDelEspejoLocal() {
        // Kira los devuelve vacios aunque se hayan enviado.
        var view = service.register(admin, wire());

        assertEquals("NY", view.bankAddress().state());
        assertEquals("10001", view.bankAddress().postalCode());
    }

    @Test
    void laCuentaSeMuestraEnmascarada() {
        var view = service.register(admin, wire());

        assertEquals("****7890", view.maskedDestination());
    }

    @Test
    void sinKybAprobadoNoHayDestinatarios() {
        Tenant sinVerificar = new Tenant(TENANT, "Juriscop", "900123456-1", "Colombia");
        sinVerificar.linkKiraUser("usr_1");
        when(tenants.findById(TENANT)).thenReturn(Optional.of(sinVerificar));

        assertThrows(DomainException.class, () -> service.register(admin, wire()));
        verify(kira, never()).createRecipient(any(), any());
    }

    @Test
    void archivarEnlazaConElReemplazo() {
        var original = service.register(admin, wire());
        var reemplazo = service.register(admin, wire());

        var view = service.archive(admin, original.id(),
                new RecipientCommands.ArchiveRecipient(reemplazo.id()));

        assertEquals("ARCHIVED", view.status());
        assertEquals(reemplazo.id(), view.replacedByRecipientId());
    }

    @Test
    void unRolAprobadorNoRegistraDestinatarios() {
        var approver = new AuthenticatedOperator("u-2", "treasury.approver@juriscop.test", TENANT,
                Role.TREASURY_APPROVER);

        assertThrows(DomainException.class, () -> service.register(approver, wire()));
    }

    @Test
    void unTenantSinElRielHabilitadoNoPuedeRegistrarEseDestinatario() {
        Tenant empresa = tenants.findById(TENANT).orElseThrow();
        empresa.applySettings(new TenantSettings(EnumSet.of(Rail.ACH), EnumSet.allOf(WalletToken.class)));

        DomainException ex = assertThrows(DomainException.class, () -> service.register(admin, wire()));
        assertTrue(ex.getMessage().contains("WIRE"));
    }

    @Test
    void unTenantSinElTokenHabilitadoNoPuedeRegistrarEseWallet() {
        Tenant empresa = tenants.findById(TENANT).orElseThrow();
        empresa.applySettings(new TenantSettings(EnumSet.allOf(Rail.class), EnumSet.of(WalletToken.USDT)));

        DomainException ex = assertThrows(DomainException.class,
                () -> service.register(admin, wallet("USDC", "polygon")));
        assertTrue(ex.getMessage().contains("USDC"));
    }

    @Test
    void losDestinatariosDeKiraSeEnmascaranYSeEnlazanConElDirectorio() {
        service.register(admin, wire());
        when(recipients.findByKiraRecipientId("krec_1")).thenAnswer(i -> registro.stream().findFirst());
        when(kira.listRecipients(eq("usr_1"), any())).thenReturn(mapper.readTree("""
                { "recipients": [
                    { "recipient_id": "krec_1", "type": "business", "company_name": "Acme Corp",
                      "account_type": "WIRE", "account_details": { "account_number": "1234567890" } },
                    { "recipient_id": "krec_fuera", "type": "individual", "first_name": "Ana", "last_name": "Perez",
                      "account_type": "WALLET", "account_details": { "address": "0xabcdef123456" } } ],
                  "total": 2 }
                """));

        List<KiraRecipientView> enKira = service.listInKira(admin);

        assertEquals(2, enKira.size());
        assertEquals("****7890", enKira.getFirst().maskedDestination());
        assertEquals(registro.getFirst().getId(), enKira.getFirst().localRecipientId());
        // Dado de alta fuera del portal: se ve, pero no enlaza con el directorio.
        assertNull(enKira.get(1).localRecipientId());
        assertEquals("Ana Perez", enKira.get(1).name());
    }
}
