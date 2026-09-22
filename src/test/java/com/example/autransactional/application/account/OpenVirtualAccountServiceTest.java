package com.example.autransactional.application.account;

import com.example.autransactional.domain.account.VirtualAccount;
import com.example.autransactional.domain.account.VirtualAccountRepository;
import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.IdempotencyKey;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.EligibleProduct;
import com.example.autransactional.domain.tenant.MissingFields;
import com.example.autransactional.domain.tenant.Role;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.domain.tenant.TenantStatus;
import com.example.autransactional.infrastructure.audit.AuditTrail;
import com.example.autransactional.infrastructure.kira.KiraApiClient;
import com.example.autransactional.infrastructure.kira.KiraApiException;
import com.example.autransactional.infrastructure.kira.KiraProperties;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OpenVirtualAccountServiceTest {

    private static final TenantId TENANT = TenantId.of("juriscop");

    private final ObjectMapper mapper = new ObjectMapper();
    private final KiraApiClient kira = mock(KiraApiClient.class);
    private final AuditTrail audit = mock(AuditTrail.class);
    private final VirtualAccountRepository accounts = mock(VirtualAccountRepository.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final com.example.autransactional.application.shared.IdempotencyKeyStore idempotencyKeys =
            mock(com.example.autransactional.application.shared.IdempotencyKeyStore.class);

    private OpenVirtualAccountService service;
    private Tenant empresa;
    private List<VirtualAccount> registro;

    private final AuthenticatedOperator admin =
            new AuthenticatedOperator("u-1", "admin@juriscop.test", TENANT, Role.ADMIN);

    private KiraProperties properties(String bank, boolean sandbox) {
        return new KiraProperties("https://api.balampay.com/sandbox", "k", "c", "p", "2026-06-01",
                "w", null, 3600, 300, 5000, 30000, bank, sandbox);
    }

    @BeforeEach
    void setUp() {
        empresa = verificada();
        registro = new ArrayList<>();

        when(tenants.findById(TENANT)).thenAnswer(i -> Optional.of(empresa));
        when(accounts.save(any())).thenAnswer(i -> {
            VirtualAccount a = i.getArgument(0);
            if (!registro.contains(a)) {
                registro.add(a);
            }
            return a;
        });
        when(accounts.findByIdAndTenant(any(), any())).thenAnswer(i -> registro.stream()
                .filter(a -> a.getId().equals(i.getArgument(0)))
                .findFirst());
        when(accounts.findByTenant(any())).thenAnswer(i -> registro.stream()
                .filter(a -> a.getTenantId().equals(i.getArgument(0)))
                .toList());

        service = new OpenVirtualAccountService(accounts, tenants, kira,
                properties("jp_morgan", true), audit, idempotencyKeys);
    }

    private Tenant verificada() {
        Tenant t = new Tenant(TENANT, "Juriscop", "900123456-1", "Colombia");
        t.linkKiraUser("usr_1");
        t.applyRemoteState(TenantStatus.VERIFIED, MissingFields.empty(),
                List.of(new EligibleProduct(EligibleProduct.USA_VIRTUAL_ACCOUNTS, true, List.of(), null)),
                true);
        return t;
    }

    private JsonNode json(String raw) {
        return mapper.readTree(raw);
    }

    private void kiraAbre(String status, String accountNumber) {
        when(kira.createVirtualAccount(any(), any())).thenReturn(json(
                "{\"id\":\"kva_1\",\"status\":\"" + status + "\",\"account_number\":"
                        + (accountNumber == null ? "null" : "\"" + accountNumber + "\"") + "}"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cuerpoEnviado() {
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(kira).createVirtualAccount(body.capture(), any(IdempotencyKey.class));
        return body.getValue();
    }

    @Test
    void elBancoLoFijaElEntornoNoElFormulario() {
        kiraAbre("pending", null);

        service.open(admin, new VirtualAccountCommands.OpenAccount("Operativa", "fiat", "USD"));

        Map<String, Object> body = cuerpoEnviado();
        assertEquals("jp_morgan", body.get("bank"));
        assertEquals("US_BANK", body.get("type"));
        assertEquals("fiat", body.get("mode"));
        assertEquals("usr_1", body.get("user_id"));
    }

    @Test
    void verifiedNoBastaSiElProductoNoEsElegible() {
        // Son dos condiciones a la vez, no una.
        Tenant sinProducto = new Tenant(TENANT, "Juriscop", "900123456-1", "Colombia");
        sinProducto.linkKiraUser("usr_1");
        sinProducto.applyRemoteState(TenantStatus.VERIFIED, MissingFields.empty(),
                List.of(new EligibleProduct(EligibleProduct.USA_VIRTUAL_ACCOUNTS, false, List.of(),
                        EligibleProduct.EDD_REQUIRED)), true);
        empresa = sinProducto;

        assertThrows(DomainException.class,
                () -> service.open(admin, new VirtualAccountCommands.OpenAccount(null, null, null)));
        verify(kira, never()).createVirtualAccount(any(), any());
    }

    @Test
    void laClaveDeIdempotenciaSePersisteAntesDeLlamar() {
        kiraAbre("pending", null);

        var view = service.open(admin, new VirtualAccountCommands.OpenAccount(null, null, null));

        VirtualAccount abierta = registro.stream()
                .filter(a -> a.getId().equals(view.id())).findFirst().orElseThrow();
        assertNotNull(abierta.getOpeningIdempotencyKey());
        // La reserva se consolida en su propia transaccion; el resultado lo guarda el caso de uso.
        verify(idempotencyKeys).persistNow(any(VirtualAccount.class));
        verify(accounts, atLeastOnce()).save(any());
    }

    @Test
    void unaCuentaPendienteNoEstaListaParaFondos() {
        kiraAbre("pending", null);

        var view = service.open(admin, new VirtualAccountCommands.OpenAccount(null, null, null));

        assertFalse(view.fundsReady());
        assertEquals("kva_1", view.kiraAccountId());
    }

    @Test
    void activatingSinNumeroRealSigueSinEstarLista() {
        // 2026-06-01: una cuenta nueva vuelve 'activating'; el banco aun la esta abriendo.
        kiraAbre("activating", "PENDING-ACT-ACCOUNT");

        var view = service.open(admin, new VirtualAccountCommands.OpenAccount(null, null, null));

        assertFalse(view.fundsReady());
        assertEquals("PENDING", view.status());
    }

    @Test
    void activeLaHabilitaAunqueNoHayaLlegadoElEvento() {
        kiraAbre("active", null);

        var view = service.open(admin, new VirtualAccountCommands.OpenAccount(null, null, null));

        assertTrue(view.fundsReady());
    }

    @Test
    void unNumeroDeCuentaRealSiLaHabilita() {
        kiraAbre("active", "1234567890");

        var view = service.open(admin, new VirtualAccountCommands.OpenAccount(null, null, null));

        assertTrue(view.fundsReady());
        assertEquals("1234567890", view.accountNumber());
    }

    @Test
    void unConflictoReutilizaLaCuentaExistente() {
        // 409: ya existe una cuenta para ese user. Es una solucion, no un error.
        when(kira.createVirtualAccount(any(), any()))
                .thenThrow(new KiraApiException(409, "conflict", "Ya existe", null));
        when(kira.listVirtualAccounts(any())).thenReturn(json("{\"data\":[{\"id\":\"kva_previa\"}]}"));
        // El listado devuelve provider y currency nulos: hay que releer la cuenta individual.
        when(kira.getVirtualAccount("kva_previa")).thenReturn(json(
                "{\"id\":\"kva_previa\",\"status\":\"active\",\"account_number\":\"999888777\"}"));

        var view = service.open(admin, new VirtualAccountCommands.OpenAccount(null, null, null));

        assertEquals("kva_previa", view.kiraAccountId());
        assertTrue(view.fundsReady());
    }

    @Test
    void unCuatrocientosEnElSaldoEsCalculandoNoUnError() {
        kiraAbre("pending", null);
        var view = service.open(admin, new VirtualAccountCommands.OpenAccount(null, null, null));
        when(kira.getVirtualAccountBalance(anyString()))
                .thenThrow(new KiraApiException(400, "bad_request", "Aun activando", null));

        var refrescada = assertDoesNotThrow(() -> service.refreshBalance(admin, view.id()));

        assertEquals(0, BigDecimal.ZERO.compareTo(refrescada.availableBalance()));
    }

    @Test
    void otrosErroresDeSaldoSiSePropagan() {
        kiraAbre("pending", null);
        var view = service.open(admin, new VirtualAccountCommands.OpenAccount(null, null, null));
        when(kira.getVirtualAccountBalance(anyString()))
                .thenThrow(new KiraApiException(500, "server_error", "Kira caido", null));

        assertThrows(KiraApiException.class, () -> service.refreshBalance(admin, view.id()));
    }

    @Test
    void elSaldoLlegaEnDecimalNoEnUnidadesMenores() {
        kiraAbre("active", "1234567890");
        var view = service.open(admin, new VirtualAccountCommands.OpenAccount(null, null, null));
        when(kira.getVirtualAccountBalance("kva_1"))
                .thenReturn(json("{\"available_balance\":5000.00,\"currency\":\"USD\"}"));

        var refrescada = service.refreshBalance(admin, view.id());

        assertEquals(0, new BigDecimal("5000.00").compareTo(refrescada.availableBalance()));
        assertNotNull(refrescada.balanceRefreshedAt());
    }

    @Test
    void simularDepositoNoExisteFueraDelSandbox() {
        service = new OpenVirtualAccountService(accounts, tenants, kira,
                properties("jp_morgan", false), audit, idempotencyKeys);
        kiraAbre("active", "1234567890");
        var view = service.open(admin, new VirtualAccountCommands.OpenAccount(null, null, null));

        assertThrows(DomainException.class, () -> service.simulateDeposit(admin, view.id(),
                new VirtualAccountCommands.SimulateDeposit(new BigDecimal("100.00"), "wire")));
        verify(kira, never()).simulateDeposit(anyString(), any());
    }

    @Test
    void enSandboxSimularDepositoRefrescaElSaldo() {
        kiraAbre("active", "1234567890");
        var view = service.open(admin, new VirtualAccountCommands.OpenAccount(null, null, null));
        when(kira.getVirtualAccountBalance("kva_1"))
                .thenReturn(json("{\"available_balance\":5000.00,\"currency\":\"USD\"}"));

        var refrescada = service.simulateDeposit(admin, view.id(),
                new VirtualAccountCommands.SimulateDeposit(new BigDecimal("5000"), "wire"));

        verify(kira).simulateDeposit(eq("kva_1"), any());
        assertEquals(0, new BigDecimal("5000.00").compareTo(refrescada.availableBalance()));
    }

    @Test
    void unaCuentaSinAbrirEnKiraNoSeRefresca() {
        VirtualAccount local = new VirtualAccount("va-x", TENANT, "USD",
                com.example.autransactional.domain.account.VirtualAccountMode.FIAT, "banco", null);
        registro.add(local);

        assertThrows(DomainException.class, () -> service.refresh(admin, "va-x"));
    }

    // --- G-23: un fallo de Kira no puede dejar cuentas huerfanas ---

    /** Simula el intento anterior: la fila local quedo guardada y Kira nunca confirmo nada. */
    private VirtualAccount aperturaSinConfirmar() {
        VirtualAccount previa = new VirtualAccount("va-previa", TENANT, "USD",
                com.example.autransactional.domain.account.VirtualAccountMode.FIAT, "jp_morgan", "Operativa");
        previa.reserveOpeningKey();
        registro.add(previa);
        return previa;
    }

    @Test
    void elReintentoRetomaLaAperturaSinConfirmarYNoCreaOtraCuenta() {
        VirtualAccount previa = aperturaSinConfirmar();
        String claveOriginal = previa.getOpeningIdempotencyKey();
        kiraAbre("pending", null);

        var view = service.open(admin, new VirtualAccountCommands.OpenAccount("Operativa", "fiat", "USD"));

        assertEquals("va-previa", view.id(), "debe reutilizar la fila, no crear otra");
        assertEquals(1, registro.size(), "la empresa no acumula cuentas huerfanas");

        // La misma clave: si Kira si habia creado la cuenta, el reintento no abre una segunda.
        ArgumentCaptor<IdempotencyKey> clave = ArgumentCaptor.forClass(IdempotencyKey.class);
        verify(kira).createVirtualAccount(any(), clave.capture());
        assertEquals(claveOriginal, clave.getValue().value());
    }

    @Test
    void unaAperturaSinConfirmarDeOtraMonedaNoSeReutiliza() {
        VirtualAccount previa = new VirtualAccount("va-eur", TENANT, "EUR",
                com.example.autransactional.domain.account.VirtualAccountMode.FIAT, "jp_morgan", null);
        previa.reserveOpeningKey();
        registro.add(previa);
        kiraAbre("pending", null);

        var view = service.open(admin, new VirtualAccountCommands.OpenAccount(null, "fiat", "USD"));

        assertNotEquals("va-eur", view.id());
        assertEquals(2, registro.size());
    }

    @Test
    void unaCuentaYaConfirmadaPorKiraNoSeReutiliza() {
        // Tiene kira_account_id: es una cuenta real, no un intento a medias.
        kiraAbre("pending", null);
        var primera = service.open(admin, new VirtualAccountCommands.OpenAccount(null, "fiat", "USD"));
        reset(kira);
        kiraAbre("pending", null);

        var segunda = service.open(admin, new VirtualAccountCommands.OpenAccount(null, "fiat", "USD"));

        assertNotEquals(primera.id(), segunda.id(), "abrir una segunda cuenta sigue siendo posible");
    }
}
