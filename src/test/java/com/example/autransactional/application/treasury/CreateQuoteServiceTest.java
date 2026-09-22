package com.example.autransactional.application.treasury;

import com.example.autransactional.domain.account.VirtualAccount;
import com.example.autransactional.domain.account.VirtualAccountMode;
import com.example.autransactional.domain.account.VirtualAccountRepository;
import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.Rail;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.Role;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.domain.tenant.TenantSettings;
import com.example.autransactional.domain.tenant.TenantStatus;
import com.example.autransactional.domain.treasury.Quotation;
import com.example.autransactional.domain.treasury.QuotationRepository;
import com.example.autransactional.domain.treasury.BankAccountKind;
import com.example.autransactional.domain.shared.PostalAddress;
import com.example.autransactional.domain.treasury.Recipient;
import com.example.autransactional.domain.treasury.RecipientAccount;
import com.example.autransactional.domain.treasury.RecipientHolder;
import com.example.autransactional.domain.treasury.RecipientRepository;
import com.example.autransactional.domain.treasury.WalletToken;
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
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CreateQuoteServiceTest {

    private static final TenantId TENANT = TenantId.of("juriscop");

    private final ObjectMapper mapper = new ObjectMapper();
    private final KiraApiClient kira = mock(KiraApiClient.class);
    private final AuditTrail audit = mock(AuditTrail.class);
    private final QuotationRepository quotations = mock(QuotationRepository.class);
    private final RecipientRepository recipients = mock(RecipientRepository.class);
    private final VirtualAccountRepository accounts = mock(VirtualAccountRepository.class);
    private final TenantRepository tenants = mock(TenantRepository.class);

    private CreateQuoteService service;
    private VirtualAccount cuenta;
    private Recipient destinatario;

    private final AuthenticatedOperator admin =
            new AuthenticatedOperator("u-1", "admin@juriscop.test", TENANT, Role.ADMIN);

    /** 1.000 al destinatario, 15 de Kira + 15 de la plataforma, 1.030 debitados. */
    private static final String RESPUESTA_KIRA = """
            {
              "quote_id": "qt_1",
              "quote_expires_at": "2026-09-10T18:15:00.000Z",
              "source":    { "amount": 103000, "currency": "USD", "precision": 2 },
              "recipient": { "amount": 100000, "currency": "USD", "precision": 2 },
              "conversion": { "rate": "1.000000", "rate_source": "kraken" },
              "fees": [ { "code": "wire_domestic_outbound", "kind": "fixed",
                          "charged_by": "kira", "amount": 1500 } ],
              "totals": { "kira_revenue_total": 1500, "client_markup_total": 1500,
                          "fee_total": 3000, "currency": "USD", "precision": 2 }
            }
            """;

    @BeforeEach
    void setUp() {
        Tenant empresa = new Tenant(TENANT, "Juriscop", "900123456-1", "Colombia");
        empresa.linkKiraUser("usr_1");
        empresa.applyRemoteState(TenantStatus.VERIFIED, null, null, true);

        cuenta = new VirtualAccount("va-1", TENANT, "USD", VirtualAccountMode.FIAT, "jp_morgan", null);
        cuenta.linkKiraAccount("kva-1");
        cuenta.describeBank("Bank", "1234567890", "021000021");
        // Saldo local suficiente por defecto: el calculo de balanceSufficient ahora compara
        // contra este valor, no contra un campo inexistente en la respuesta de Kira.
        cuenta.refreshBalance(new BigDecimal("5000.00"), Instant.now());

        destinatario = new Recipient("rec-1", TENANT,
                RecipientHolder.company("Acme Corp", null, null),
                new RecipientAccount.Wire("021000021", null, "1234567890", BankAccountKind.CHECKING,
                        "Chase", new PostalAddress("1 Bank Plaza", "New York", "NY", "10001", "US"),
                        "ein", "12-3456789"),
                new PostalAddress("1 Main St", "New York", "NY", "10001", "US"));
        destinatario.linkKiraRecipient("krec-1");

        when(tenants.findById(TENANT)).thenReturn(Optional.of(empresa));
        when(accounts.findByIdAndTenant("va-1", TENANT)).thenAnswer(i -> Optional.of(cuenta));
        when(recipients.findByIdAndTenant("rec-1", TENANT)).thenAnswer(i -> Optional.of(destinatario));
        when(quotations.save(any())).thenAnswer(i -> i.getArgument(0));
        when(kira.createQuotation(any())).thenReturn(json(RESPUESTA_KIRA));

        service = new CreateQuoteService(quotations, recipients, accounts, tenants, kira, audit);
    }

    private JsonNode json(String raw) {
        return mapper.readTree(raw);
    }

    private QuotationCommands.CreateQuote peticion(String rail) {
        return new QuotationCommands.CreateQuote("va-1", "rec-1", new BigDecimal("1000.00"), rail, null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cuerpoEnviado() {
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(kira).createQuotation(body.capture());
        return body.getValue();
    }

    @Test
    void seCotizaEnModoRedimibleYCompensandoHaciaArriba() {
        service.create(admin, peticion(null));

        Map<String, Object> body = cuerpoEnviado();
        // virtual_account_id y quote_for son excluyentes: con quote_for el quote_id es nulo.
        // El id de Kira, no el del portal: el del portal no significa nada para su API.
        assertEquals("kva-1", body.get("virtual_account_id"));
        assertFalse(body.containsKey("quote_for"));
        // inverse: el operador teclea lo que RECIBE el destinatario.
        assertEquals(true, body.get("inverse"));
        // Sin esto, el pago no sale del saldo de la cuenta virtual.
        assertEquals(true, body.get("from_held_balance"));
        assertEquals("1000.00", body.get("amount"));
    }

    @Test
    void elMarkupDeLaPlataformaViajaEnUnidadesMenores() {
        service.create(admin, peticion(null));

        @SuppressWarnings("unchecked")
        Map<String, Object> markup = (Map<String, Object>) cuerpoEnviado().get("client_markup");

        assertEquals(1500L, markup.get("fixed_minor"));
    }

    @Test
    void lasComisionesRealesSonLasQueLiquidaKiraNoLaEstimacion() {
        var view = service.create(admin, peticion(null));

        assertEquals(0, new BigDecimal("15.0000").compareTo(view.kiraFee()));
        assertEquals(0, new BigDecimal("15.0000").compareTo(view.platformFee()));
        assertEquals(0, new BigDecimal("30.0000").compareTo(view.totalFee()));
    }

    @Test
    void elDestinatarioRecibeElImporteExactoYLaCuentaPagaMas() {
        var view = service.create(admin, peticion(null));

        assertEquals(0, new BigDecimal("1000.00").compareTo(view.originAmount()));
        assertEquals(0, new BigDecimal("1000.00").compareTo(view.destinationAmount()));
        // source.amount es el bruto que sale de la cuenta virtual.
        assertEquals(0, new BigDecimal("1030.00").compareTo(view.totalDebitAmount()));
    }

    @Test
    void elRielSeDerivaDelDestinatarioNoDelFormulario() {
        service.create(admin, peticion(null));

        assertEquals("WIRE_DOMESTIC", cuerpoEnviado().get("rail"));
    }

    @Test
    void unRielQueNoCorrespondeSeCortaAntesDeCotizar() {
        // Kira solo lo detectaria al ejecutar el pago, con 422 y el viaje ya perdido.
        var e = assertThrows(DomainException.class, () -> service.create(admin, peticion("ACH_SAME_DAY")));

        assertTrue(e.getMessage().contains("WIRE_DOMESTIC"), e.getMessage());
        verify(kira, never()).createQuotation(any());
    }

    @Test
    void unaCuentaSinNumeroRealNoPuedeCotizar() {
        // 'approved' no significa fondos disponibles.
        cuenta = new VirtualAccount("va-1", TENANT, "USD", VirtualAccountMode.FIAT, "jp_morgan", null);

        assertThrows(DomainException.class, () -> service.create(admin, peticion(null)));
        verify(kira, never()).createQuotation(any());
    }

    @Test
    void elVencimientoEsElQueDiceKiraNoElCalculado() {
        var view = service.create(admin, peticion(null));

        assertEquals(Instant.parse("2026-09-10T18:15:00.000Z"), view.expiresAt());
    }

    @Test
    void unPreviewSinQuoteIdNoSeAcepta() {
        when(kira.createQuotation(any())).thenReturn(json("""
                { "quote_id": null, "source": { "amount": 103000, "currency": "USD", "precision": 2 } }
                """));

        var e = assertThrows(DomainException.class, () -> service.create(admin, peticion(null)));
        assertTrue(e.getMessage().contains("redimible"), e.getMessage());
    }

    @Test
    void elSaldoInsuficienteQuedaRegistradoEnLaCotizacion() {
        // El saldo local no alcanza para cubrir los 1.030 que Kira va a debitar (1.000 + comisiones).
        cuenta.refreshBalance(new BigDecimal("10.00"), Instant.now());

        var view = service.create(admin, peticion(null));

        assertFalse(view.balanceSufficient());
        ArgumentCaptor<Quotation> guardada = ArgumentCaptor.forClass(Quotation.class);
        verify(quotations).save(guardada.capture());
        // No se puede redimir sin saldo: Kira no encola ni cancela sola.
        assertThrows(DomainException.class, () -> guardada.getValue().assertRedeemable(Instant.now()));
    }

    @Test
    void unaTasaDeContingenciaQuedaSenalizada() {
        when(kira.createQuotation(any())).thenReturn(json(
                RESPUESTA_KIRA.replace("\"rate_source\": \"kraken\"", "\"rate_source\": \"stale_at_peg\"")));

        assertTrue(service.create(admin, peticion(null)).fallbackRate());
    }

    @Test
    void seGuardaLaCopiaDelPrecioMostrado() {
        service.create(admin, peticion(null));

        ArgumentCaptor<Quotation> guardada = ArgumentCaptor.forClass(Quotation.class);
        verify(quotations).save(guardada.capture());
        assertNotNull(guardada.getValue().getFeesSnapshot());
        assertTrue(guardada.getValue().getFeesSnapshot().contains("kira_revenue_total"));
    }

    @Test
    void unRolAprobadorNoCotiza() {
        var approver = new AuthenticatedOperator("u-2", "treasury.approver@juriscop.test", TENANT,
                Role.TREASURY_APPROVER);

        assertThrows(DomainException.class, () -> service.create(approver, peticion(null)));
    }

    @Test
    void unTenantSinElRielHabilitadoNoPuedeCotizar() {
        Tenant empresa = tenants.findById(TENANT).orElseThrow();
        empresa.applySettings(new TenantSettings(EnumSet.of(Rail.ACH), EnumSet.allOf(WalletToken.class)));

        DomainException ex = assertThrows(DomainException.class, () -> service.create(admin, peticion(null)));
        assertTrue(ex.getMessage().contains("WIRE"));
    }
}
