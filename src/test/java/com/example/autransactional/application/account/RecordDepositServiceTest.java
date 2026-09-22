package com.example.autransactional.application.account;

import com.example.autransactional.domain.account.Deposit;
import com.example.autransactional.domain.account.DepositRepository;
import com.example.autransactional.domain.account.DepositStatus;
import com.example.autransactional.domain.account.VirtualAccount;
import com.example.autransactional.domain.account.VirtualAccountMode;
import com.example.autransactional.domain.account.VirtualAccountRepository;
import com.example.autransactional.domain.shared.Rail;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.Role;
import com.example.autransactional.infrastructure.kira.KiraApiClient;
import com.example.autransactional.infrastructure.security.AuthenticatedOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RecordDepositServiceTest {

    private static final TenantId TENANT = TenantId.of("juriscop");

    private final ObjectMapper mapper = new ObjectMapper();
    private final DepositRepository deposits = mock(DepositRepository.class);
    private final VirtualAccountRepository accounts = mock(VirtualAccountRepository.class);
    private final KiraApiClient kira = mock(KiraApiClient.class);

    private RecordDepositService service;
    private VirtualAccount cuenta;
    private List<Deposit> registro;

    @BeforeEach
    void setUp() {
        cuenta = new VirtualAccount("va-1", TENANT, "USD", VirtualAccountMode.FIAT,
                "jp_morgan", null);
        cuenta.linkKiraAccount("kva_1");
        cuenta.describeBank("Example Bank", "1234567890", "021000021");
        cuenta.refreshBalance(new BigDecimal("5000.00"), Instant.now());
        registro = new ArrayList<>();

        when(accounts.findByKiraAccountId("kva_1")).thenReturn(Optional.of(cuenta));
        when(accounts.save(any())).thenAnswer(i -> i.getArgument(0));
        when(deposits.save(any())).thenAnswer(i -> {
            Deposit d = i.getArgument(0);
            if (!registro.contains(d)) {
                registro.add(d);
            }
            return d;
        });
        when(deposits.findByKiraDepositId(anyString())).thenAnswer(i -> registro.stream()
                .filter(d -> i.getArgument(0).equals(d.getKiraDepositId()))
                .findFirst());

        service = new RecordDepositService(deposits, accounts, kira);
    }

    private JsonNode json(String raw) {
        return mapper.readTree(raw);
    }

    private void evento(String nombre, String payload) {
        service.apply(KiraDepositEvent.from(nombre, json(payload)));
    }

    private void recibido() {
        evento("virtual_account.deposit_funds_received", """
                { "deposit_id": "dep_1", "virtual_account_id": "kva_1", "status": "completed",
                  "amount": 5000.00, "fee": 25.00, "currency": "USD",
                  "sender_name": "Acme Corp", "sender_account": "999", "payment_type": "wire" }
                """);
    }

    @Test
    void unDepositoRecibidoSeProyectaConSusTresImportes() {
        recibido();

        Deposit d = registro.getFirst();
        assertEquals(0, new BigDecimal("5000.00").compareTo(d.getGrossAmount()));
        assertEquals(0, new BigDecimal("25.00").compareTo(d.getFeeAmount()));
        // El neto se deriva si el evento no lo trae: bruto menos comision.
        assertEquals(0, new BigDecimal("4975.00").compareTo(d.getNetAmount()));
        assertEquals(DepositStatus.COMPLETED, d.getStatus());
        assertEquals(Rail.WIRE, d.getRail());
        assertEquals("Acme Corp", d.getSenderName());
    }

    @Test
    void seLeeElCasingMezcladoDelPayload() {
        // Las respuestas de deposito mezclan snake_case y camelCase en el mismo objeto.
        evento("virtual_account.deposit_funds_received", """
                { "internalPaymentId": "dep_2", "virtualAccountId": "kva_1",
                  "grossAmount": 100.00, "feeAmount": 1.00, "netAmount": 99.00,
                  "senderName": "Ana", "paymentType": "ach" }
                """);

        Deposit d = registro.getFirst();
        assertEquals("dep_2", d.getKiraDepositId());
        assertEquals("Ana", d.getSenderName());
        assertEquals(Rail.ACH, d.getRail());
        assertEquals(0, new BigDecimal("99.00").compareTo(d.getNetAmount()));
    }

    @Test
    void losSeisEventosConvergenEnUnaSolaFila() {
        evento("virtual_account.deposit_funds_in_transit", """
                { "deposit_id": "dep_1", "virtual_account_id": "kva_1", "amount": 5000.00 }
                """);
        assertEquals(DepositStatus.PENDING, registro.getFirst().getStatus());

        recibido();

        assertEquals(1, registro.size());
        assertEquals(DepositStatus.COMPLETED, registro.getFirst().getStatus());
    }

    @Test
    void unDepositoDevueltoNoVuelveAAcreditar() {
        recibido();
        evento("virtual_account.deposit_returned", """
                { "deposit_id": "dep_1", "virtual_account_id": "kva_1", "amount": 5000.00 }
                """);

        Deposit d = registro.getFirst();
        assertEquals(DepositStatus.REFUNDED, d.getStatus());
        assertFalse(d.creditsBalance());
    }

    // --- Payloads copiados de docs.kirafin.ai/webhooks/notification-examples (15-sep) ---

    @Test
    void docs_depositFundsReceivedLeeOrdenanteYRielDeSource() {
        evento("virtual_account.deposit_funds_received", """
                { "event_id": "7c8d9e00-1f2a-4b3c-8d4e-5f6a7b8c9d0e", "user_id": "e687484f",
                  "virtual_account_id": "kva_1", "deposit_id": "dep_doc", "amount": "100.00",
                  "currency": "USD", "created_at": "2026-09-01T12:00:00.000Z",
                  "source": { "payment_rail": "wire", "description": "Invoice 4471",
                              "sender_name": "Northwind Trading LLC", "trace_number": "20260901MMQFMP3K000123",
                              "sender_bank_routing_number": "000000001" } }
                """);

        Deposit d = registro.getFirst();
        assertEquals(DepositStatus.COMPLETED, d.getStatus());
        assertEquals("Northwind Trading LLC", d.getSenderName());
        assertEquals(Rail.WIRE, d.getRail());
        assertEquals(0, new BigDecimal("100.00").compareTo(d.getGrossAmount()));
    }

    @Test
    void docs_depositFundsRefundedNoQuedaComoAcreditado() {
        recibido();
        evento("virtual_account.deposit_funds_refunded", """
                { "event_id": "6d7e8f90-0a1b-4c2d-8e3f-4a5b6c7d8e9f", "virtual_account_id": "kva_1",
                  "deposit_id": "dep_1", "amount": "100.00", "currency": "USD",
                  "created_at": "2026-09-01T12:00:00.000Z",
                  "return_details": { "code": "R01", "reason": "Insufficient funds at the sending bank",
                                      "refunded_at": "2026-09-02T09:00:00.000Z" } }
                """);

        Deposit d = registro.getFirst();
        assertEquals(DepositStatus.REFUNDED, d.getStatus());
        assertFalse(d.creditsBalance());
    }

    @Test
    void docs_depositoProgramadoOEnRevisionNoAcredita() {
        evento("virtual_account.deposit_scheduled", """
                { "deposit_id": "dep_s", "virtual_account_id": "kva_1", "amount": "10.00" }
                """);
        evento("virtual_account.deposit_in_review", """
                { "deposit_id": "dep_r", "virtual_account_id": "kva_1", "amount": "10.00" }
                """);

        assertTrue(registro.stream().noneMatch(Deposit::creditsBalance));
    }

    @Test
    void unDepositoRetenidoNoAcreditaYPuedeLiberarse() {
        recibido();
        evento("virtual_account.deposit_in_review", """
                { "deposit_id": "dep_1", "virtual_account_id": "kva_1", "status": "KYT_PENDING" }
                """);
        assertEquals(DepositStatus.KYT_PENDING, registro.getFirst().getStatus());
        assertFalse(registro.getFirst().creditsBalance());

        evento("virtual_account.deposit_in_review", """
                { "deposit_id": "dep_1", "virtual_account_id": "kva_1", "status": "KYT_REJECTED" }
                """);
        assertEquals(DepositStatus.KYT_REJECTED, registro.getFirst().getStatus());
        assertFalse(DepositStatus.KYT_REJECTED.isTerminal());

        // Cumplimiento retira el rechazo: el deposito se libera y acredita.
        recibido();
        assertEquals(DepositStatus.COMPLETED, registro.getFirst().getStatus());
    }

    @Test
    void laCuentaDelOrdenanteSaleEnmascarada() {
        recibido();
        assertEquals("****", com.example.autransactional.application.account.DepositView
                .from(registro.getFirst()).senderAccount());
        assertEquals("****7890", com.example.autransactional.application.account.DepositView.maskAccount("1234567890"));
    }

    @Test
    void unEstadoDesconocidoNuncaAcreditaSaldo() {
        assertEquals(DepositStatus.PENDING, DepositStatus.fromWire("something_new"));
        assertEquals(DepositStatus.PENDING,
                DepositStatus.fromEventName("virtual_account.deposit_something_new", null));
    }

    @Test
    void unEventoTardioNoResucitaUnDepositoDevuelto() {
        recibido();
        evento("virtual_account.deposit_returned", """
                { "deposit_id": "dep_1", "virtual_account_id": "kva_1", "amount": 5000.00 }
                """);

        evento("virtual_account.deposit_funds_in_transit", """
                { "deposit_id": "dep_1", "virtual_account_id": "kva_1", "amount": 5000.00 }
                """);

        assertEquals(DepositStatus.REFUNDED, registro.getFirst().getStatus());
    }

    @Test
    void elFalloSeProyectaAunqueElPayloadNoTraigaEstado() {
        // El nombre del evento es mas fiable que un 'status' ausente.
        evento("virtual_account.deposit_funds_failed", """
                { "deposit_id": "dep_9", "virtual_account_id": "kva_1", "amount": 100.00 }
                """);

        assertEquals(DepositStatus.FAILED, registro.getFirst().getStatus());
    }

    @Test
    void unMicrodepositoNoCuentaComoIngreso() {
        evento("virtual_account.microdeposit_funds_received", """
                { "deposit_id": "dep_micro", "virtual_account_id": "kva_1", "amount": 0.11 }
                """);

        Deposit d = registro.getFirst();
        assertTrue(d.isMicrodeposit());
        assertEquals(DepositStatus.COMPLETED, d.getStatus());
        assertFalse(d.creditsBalance());
    }

    @Test
    void unDepositoAcreditadoInvalidaElSaldoCacheadoPeroNoLoInventa() {
        BigDecimal antes = cuenta.getBalanceAvailable();

        recibido();

        // El saldo local NO se suma: la autoridad es Kira.
        assertEquals(0, antes.compareTo(cuenta.getBalanceAvailable()));
        // Pero queda marcado como desactualizado para que el portal lo vuelva a pedir.
        assertTrue(cuenta.isBalanceStale());
        verify(accounts).save(cuenta);
    }

    @Test
    void unMicrodepositoNoInvalidaElSaldo() {
        evento("virtual_account.microdeposit_funds_received", """
                { "deposit_id": "dep_micro", "virtual_account_id": "kva_1", "amount": 0.11 }
                """);

        assertFalse(cuenta.isBalanceStale());
        verify(accounts, never()).save(any());
    }

    @Test
    void unDepositoDeUnaCuentaDesconocidaNoRompeNada() {
        assertDoesNotThrow(() -> evento("virtual_account.deposit_funds_received", """
                { "deposit_id": "dep_x", "virtual_account_id": "kva_ajena", "amount": 10.00 }
                """));

        assertTrue(registro.isEmpty());
    }

    @Test
    void unEventoSinIdentificadorNoSeProyecta() {
        assertDoesNotThrow(() -> evento("virtual_account.deposit_funds_received",
                "{ \"amount\": 10.00 }"));

        assertTrue(registro.isEmpty());
        verify(deposits, never()).save(any());
    }

    @Test
    void laSincronizacionDesdeKiraConvergeEnLaMismaFilaQueElWebhook() {
        when(accounts.findByIdAndTenant("va-1", TENANT)).thenReturn(Optional.of(cuenta));
        when(deposits.findByVirtualAccount(eq("va-1"), anyInt())).thenAnswer(i -> registro);
        recibido();
        String idDelWebhook = registro.getFirst().getKiraDepositId();
        when(kira.listAccountDeposits(eq("kva_1"), any())).thenReturn(json("""
                [ { "id": "%s", "virtual_account_id": "kva_1", "amount": "5000.00", "currency": "USD",
                    "status": "REFUNDED", "sender": { "name": "Acme Corp", "account_number": "999" },
                    "payment_rail": "wire", "fees": { "total_fees": "25.00" }, "net_amount": "4975.00" },
                  { "id": "dep_otra_cuenta", "virtual_account_id": "kva_ajena", "amount": "10.00",
                    "currency": "USD", "status": "COMPLETED" } ]
                """.formatted(idDelWebhook)));

        service.syncFromKira(new AuthenticatedOperator("u-1", "treasury.approver@juriscop.test", TENANT, Role.TREASURY_APPROVER),
                "va-1");

        assertEquals(1, registro.size());
        assertEquals(DepositStatus.REFUNDED, registro.getFirst().getStatus());
    }

    @Test
    void losEstadosDeRetencionSeConservanYNoSonFallos() {
        // KYT_REJECTED es una decision de cumplimiento, no un fallo tecnico, y puede revertirse.
        assertEquals(DepositStatus.KYT_REJECTED, DepositStatus.fromWire("KYT_REJECTED"));
        assertEquals(DepositStatus.KYT_PENDING, DepositStatus.fromWire("kyt_pending"));
        assertTrue(DepositStatus.KYT_PENDING.isHeld());
    }
}
