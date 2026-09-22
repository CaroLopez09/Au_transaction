package com.example.autransactional.application.treasury;

import com.example.autransactional.domain.account.VirtualAccount;
import com.example.autransactional.domain.account.VirtualAccountMode;
import com.example.autransactional.domain.account.VirtualAccountRepository;
import com.example.autransactional.domain.compliance.Rfi;
import com.example.autransactional.domain.compliance.RfiRepository;
import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.PostalAddress;
import com.example.autransactional.domain.tenant.Tenant;
import com.example.autransactional.domain.tenant.TenantRepository;
import com.example.autransactional.domain.tenant.TenantStatus;
import com.example.autransactional.domain.tenant.UserStatus;
import com.example.autransactional.domain.tenant.IdentityVerificationStatus;
import com.example.autransactional.domain.tenant.OperatorIdentity;
import com.example.autransactional.domain.treasury.BankAccountKind;
import com.example.autransactional.domain.treasury.Recipient;
import com.example.autransactional.domain.treasury.RecipientAccount;
import com.example.autransactional.domain.treasury.RecipientHolder;
import com.example.autransactional.domain.treasury.RecipientRepository;
import com.example.autransactional.domain.shared.IdempotencyKey;
import com.example.autransactional.domain.shared.Money;
import com.example.autransactional.domain.shared.TenantId;
import com.example.autransactional.domain.tenant.OperatorUser;
import com.example.autransactional.domain.tenant.OperatorUserRepository;
import com.example.autransactional.domain.tenant.Role;
import com.example.autransactional.domain.treasury.FeeBreakdown;
import com.example.autransactional.domain.treasury.Payout;
import com.example.autransactional.domain.treasury.PayoutRepository;
import com.example.autransactional.domain.treasury.PayoutStatus;
import com.example.autransactional.domain.treasury.Quotation;
import com.example.autransactional.domain.treasury.QuotationRail;
import com.example.autransactional.domain.treasury.QuotationRepository;
import com.example.autransactional.domain.treasury.QuotationStatus;
import com.example.autransactional.domain.treasury.SupportingDocument;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ExecutePayoutServiceTest {

    private static final TenantId TENANT = TenantId.of("juriscop");

    private final ObjectMapper mapper = new ObjectMapper();
    private final KiraApiClient kira = mock(KiraApiClient.class);
    private final AuditTrail audit = mock(AuditTrail.class);
    private final PayoutRepository payouts = mock(PayoutRepository.class);
    private final QuotationRepository quotations = mock(QuotationRepository.class);
    private final VirtualAccountRepository accounts = mock(VirtualAccountRepository.class);
    private final RecipientRepository recipients = mock(RecipientRepository.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final RfiRepository rfis = mock(RfiRepository.class);
    private final OperatorUserRepository usuarios = mock(OperatorUserRepository.class);

    private ExecutePayoutService service;
    private Payout pago;
    private Quotation cotizacion;
    private Recipient destinatario;
    private final CreateQuoteService cotizador = mock(CreateQuoteService.class);
    /** Umbral de doble firma: 5.000 USD (el pago de prueba es de 1.000). */
    private final PayoutApprovalPolicy politica = new PayoutApprovalPolicy(new BigDecimal("5000"), null);

    private final AuthenticatedOperator maker =
            new AuthenticatedOperator("maker-1", "treasury.maker@juriscop.test", TENANT, Role.TREASURY_MAKER);

    private final AuthenticatedOperator approver =
            new AuthenticatedOperator("approver-2", "treasury.approver@juriscop.test", TENANT,
                    Role.TREASURY_APPROVER);

    private static final String RESPUESTA_201 = """
            { "id": "pay_1", "status": "created", "reference_number": "IMAD-20260910-001",
              "payment_method": "wire" }
            """;

    @BeforeEach
    void setUp() {
        cotizacion = new Quotation("q-1", TENANT, "va-1", "rec-1", QuotationRail.WIRE_DOMESTIC,
                new BigDecimal("1000.00"), FeeBreakdown.standard(), Instant.now().plusSeconds(900));
        cotizacion.applyKiraQuote("qt_1", Instant.now().plusSeconds(900), new BigDecimal("1030.00"),
                new BigDecimal("1000.00"), "USD", BigDecimal.ONE,
                FeeBreakdown.fromTotals(new BigDecimal("15.00"), new BigDecimal("15.00")),
                true, "kraken", "{}");

        Tenant empresa = new Tenant(TENANT, "Juriscop", "900123456-1", "Colombia");
        empresa.linkKiraUser("usr_1");
        empresa.applyRemoteState(TenantStatus.VERIFIED, null, null, true);
        VirtualAccount cuenta = new VirtualAccount("va-1", TENANT, "USD", VirtualAccountMode.FIAT,
                "jp_morgan", null);
        cuenta.linkKiraAccount("kva-1");
        cuenta.describeBank("Bank", "1234567890", "021000021");
        destinatario = new Recipient("rec-1", TENANT,
                RecipientHolder.company("Acme Corp", null, null),
                new RecipientAccount.Wire("021000021", null, "1234567890", BankAccountKind.CHECKING,
                        "Chase", new PostalAddress("1 Bank Plaza", "New York", "NY", "10001", "US"),
                        "ein", "12-3456789"),
                new PostalAddress("1 Main St", "New York", "NY", "10001", "US"));
        destinatario.linkKiraRecipient("krec-1");
        when(tenants.findById(TENANT)).thenReturn(Optional.of(empresa));
        when(accounts.findByIdAndTenant("va-1", TENANT)).thenReturn(Optional.of(cuenta));
        when(recipients.findByIdAndTenant("rec-1", TENANT)).thenReturn(Optional.of(destinatario));

        pago = new Payout("p-1", TENANT, "usr_1", "va-1", "rec-1",
                Money.of(new BigDecimal("1000.00"), "USD"), FeeBreakdown.standard(),
                IdempotencyKey.newKey(), "maker-1");

        when(payouts.findByIdAndTenant("p-1", TENANT)).thenAnswer(i -> Optional.of(pago));
        when(payouts.save(any())).thenAnswer(i -> i.getArgument(0));
        when(quotations.findByIdAndTenant("q-1", TENANT)).thenAnswer(i -> Optional.of(cotizacion));
        when(quotations.save(any())).thenAnswer(i -> i.getArgument(0));
        when(kira.executePayout(anyString(), any(), any())).thenReturn(json(RESPUESTA_201));

        when(rfis.findOpenBlocking(any())).thenReturn(Optional.empty());
        when(usuarios.findById("maker-1")).thenReturn(Optional.of(verifiedOperator("maker-1", Role.TREASURY_MAKER)));
        when(usuarios.findById("approver-2")).thenReturn(Optional.of(verifiedOperator("approver-2", Role.TREASURY_APPROVER)));
        service = new ExecutePayoutService(payouts, quotations, accounts, recipients, tenants, rfis, kira,
                audit, mapper, politica, cotizador, usuarios);
    }

    private OperatorUser verifiedOperator(String id, Role role) {
        return new OperatorUser(id, TENANT, id + "@juriscop.test", "hash", "Operador", "Prueba", role,
                UserStatus.ACTIVE, null, false,
                new OperatorIdentity(IdentityVerificationStatus.VERIFIED, null, null, null, null,
                        null, null, Instant.now(), null));
    }

    // --- D9: recotizar un pago pendiente cuya cotizacion vencio ---

    @Test
    void recotizarAtaUnaCotizacionNuevaYAnulaLaPrimeraFirma() {
        pagoGrande();
        pago.attachQuotation("q-1", Instant.now().plusSeconds(900));
        service.approveAndSubmit(approver, "p-1", new PayoutCommands.ApprovePayout(null, null, null, null));
        assertEquals("approver-2", pago.getFirstApproverUserId());

        Quotation nueva = new Quotation("q-2", TENANT, "va-1", "rec-1", QuotationRail.WIRE_DOMESTIC,
                new BigDecimal("5000.00"), FeeBreakdown.fromTotals(new BigDecimal("20.00"), new BigDecimal("15.00")),
                Instant.now().plusSeconds(900));
        when(cotizador.requote(eq(approver), eq(cotizacion), any())).thenReturn(nueva);

        var vista = service.requote(approver, "p-1");

        assertEquals("q-2", vista.quotationId());
        assertNull(vista.firstApproverUserId());
        assertEquals(0, new BigDecimal("35.00").compareTo(vista.totalFee()));
        verify(audit).record(eq(approver), eq("payout.requoted"), eq("payout"), eq("p-1"), any(), eq("OK"), anyString());
    }

    // --- G-03 / G-26: el portal no tiene directorio de operadores ni de destinatarios archivados ---

    @Test
    void laVistaTraeElNombreDelMakerDelAprobadorYDelDestinatario() {
        pagoGrande();
        pago.attachQuotation("q-1", Instant.now().plusSeconds(900));
        when(usuarios.findById("maker-1")).thenReturn(Optional.of(operador("maker-1", "Ana", "Restrepo")));
        when(usuarios.findById("approver-2")).thenReturn(Optional.of(operador("approver-2", "Luis", "Gomez")));
        service.approveAndSubmit(approver, "p-1", new PayoutCommands.ApprovePayout(null, null, null, null));

        var vista = service.get(approver, "p-1");

        assertEquals("Ana Restrepo", vista.makerName());
        assertEquals("Luis Gomez", vista.firstApproverName());
        assertEquals("Acme Corp", vista.recipientName());
    }

    @Test
    void unOperadorQueYaNoEstaDejaElNombreVacioPeroConservaSuId() {
        when(usuarios.findById("maker-1")).thenReturn(Optional.empty());

        var vista = service.get(maker, "p-1");

        assertEquals("maker-1", vista.makerUserId());
        assertNull(vista.makerName());
    }

    @Test
    void elListadoResuelveCadaOperadorUnaSolaVez() {
        when(payouts.findByTenant(eq(TENANT), anyInt())).thenReturn(List.of(pago, pago, pago));
        when(usuarios.findById("maker-1")).thenReturn(Optional.of(operador("maker-1", "Ana", "Restrepo")));

        var vistas = service.list(maker, 50);

        assertEquals(3, vistas.size());
        assertEquals("Ana Restrepo", vistas.get(0).makerName());
        verify(usuarios, times(1)).findById("maker-1");
        verify(recipients, times(1)).findByIdAndTenant("rec-1", TENANT);
    }

    private static OperatorUser operador(String id, String nombre, String apellido) {
        return new OperatorUser(id, TENANT, id + "@juriscop.test", "hash", nombre, apellido,
            Role.TREASURY_MAKER, UserStatus.ACTIVE, null, false,
            new OperatorIdentity(IdentityVerificationStatus.VERIFIED, null, null, null, null,
                null, null, Instant.now(), null));
    }

    @Test
    void unPagoSinPrecioFijadoNoSeRecotiza() {
        assertThrows(DomainException.class, () -> service.requote(approver, "p-1"));
        verifyNoInteractions(cotizador);
    }

    // --- Limites y segregacion de funciones (arquitectura §5 y §7) ---

    private final AuthenticatedOperator otroAprobador =
            new AuthenticatedOperator("approver-3", "admin@juriscop.test", TENANT, Role.ADMIN);

    private void pagoGrande() {
        pago = new Payout("p-1", TENANT, "usr_1", "va-1", "rec-1",
                Money.of(new BigDecimal("5000.00"), "USD"), FeeBreakdown.standard(),
                IdempotencyKey.newKey(), "maker-1");
    }

    @Test
    void desdeElUmbralLaPrimeraFirmaNoEnviaElPago() {
        pagoGrande();

        var vista = service.approveAndSubmit(approver, "p-1", new PayoutCommands.ApprovePayout(null, null, null, null));

        verify(kira, never()).executePayout(anyString(), any(), any());
        assertEquals("PENDING_APPROVAL", vista.approvalState());
        assertEquals("approver-2", vista.firstApproverUserId());
        assertEquals(2, vista.requiredApprovals());
    }

    @Test
    void laSegundaFirmaTieneQueSerDeOtraPersonaYEntoncesSeEnvia() {
        pagoGrande();
        when(usuarios.findById("approver-3")).thenReturn(Optional.of(verifiedOperator("approver-3", Role.ADMIN)));
        service.approveAndSubmit(approver, "p-1", new PayoutCommands.ApprovePayout(null, null, null, null));

        assertThrows(DomainException.class, () -> service.approveAndSubmit(approver, "p-1",
                new PayoutCommands.ApprovePayout(null, null, null, null)));
        var vista = service.approveAndSubmit(otroAprobador, "p-1", new PayoutCommands.ApprovePayout(null, null, null, null));

        verify(kira).executePayout(anyString(), any(), any());
        assertEquals("SUBMITTED", vista.approvalState());
        assertEquals("approver-3", vista.approverUserId());
    }

    @Test
    void elUmbralDeUnaEmpresaMandaSobreElGeneral() {
        var propia = new PayoutApprovalPolicy(new BigDecimal("5000"), Map.of("juriscop", new BigDecimal("500")));
        assertEquals(2, propia.requiredApprovals(TENANT, Money.of(new BigDecimal("1000"), "USD")));
        assertEquals(1, propia.requiredApprovals(TenantId.of("bankvision"), Money.of(new BigDecimal("1000"), "USD")));
        assertEquals(1, new PayoutApprovalPolicy(null, null).requiredApprovals(TENANT, Money.of(new BigDecimal("1e9"), "USD")));
    }

    @Test
    void quienRegistroElDestinatarioNoApruebaPagosHaciaEl() {
        destinatario.recordAuthor("approver-2");

        var e = assertThrows(DomainException.class, () -> service.approveAndSubmit(approver, "p-1",
                new PayoutCommands.ApprovePayout(null, null, null, null)));

        assertTrue(e.getMessage().contains("registro el destinatario"));
        verify(kira, never()).executePayout(anyString(), any(), any());
    }

    private JsonNode json(String raw) {
        return mapper.readTree(raw);
    }

    private void conCotizacion() {
        pago.attachQuotation(cotizacion, Instant.now());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cuerpoEnviado() {
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(kira).executePayout(eq("kva-1"), body.capture(), any(IdempotencyKey.class));
        return body.getValue();
    }

    @Test
    void seEnviaElBrutoParaQueElDestinatarioRecibaLoPrometido() {
        // Kira DESCUENTA las comisiones del monto: enviar 1000 dejaria al destinatario con 970.
        conCotizacion();

        service.approveAndSubmit(approver, "p-1", null);

        assertEquals("1030.00", cuerpoEnviado().get("amount"));
    }

    @Test
    void conCotizacionViajaElQuoteIdYNoElMarkup() {
        conCotizacion();

        service.approveAndSubmit(approver, "p-1", null);

        Map<String, Object> body = cuerpoEnviado();
        assertEquals("qt_1", body.get("quote_id"));
        // El precio ya esta cerrado: reenviar el markup lo cobraria dos veces.
        assertFalse(body.containsKey("client_markup"));
    }

    @Test
    void sinCotizacionElMargenViajaComoCadenaDecimal() {
        service.approveAndSubmit(approver, "p-1", null);

        Map<String, Object> body = cuerpoEnviado();
        assertFalse(body.containsKey("quote_id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> markup = (Map<String, Object>) body.get("client_markup");
        // En esta ruta la API espera decimales, no unidades menores.
        assertEquals("15.00", markup.get("fixed_fee"));
        assertEquals("1030.00", body.get("amount"));
    }

    @Test
    void laCotizacionSeConsumeAlEnviarNoAlPreparar() {
        conCotizacion();
        assertEquals(QuotationStatus.ACTIVE, cotizacion.getStatus());

        service.approveAndSubmit(approver, "p-1", null);

        assertEquals(QuotationStatus.EXECUTED, cotizacion.getStatus());
    }

    @Test
    void siElEnvioFallaLaCotizacionSigueSiendoRedimible() {
        // Las validaciones del quote fallan con 400 ANTES de consumirlo.
        conCotizacion();
        when(kira.executePayout(anyString(), any(), any())).thenThrow(new IllegalStateException("400"));

        assertThrows(IllegalStateException.class, () -> service.approveAndSubmit(approver, "p-1", null));

        assertEquals(QuotationStatus.ACTIVE, cotizacion.getStatus());
        assertEquals(PayoutStatus.FAILED, pago.getStatus());
    }

    @Test
    void unaCotizacionVencidaNoSeEnvia() {
        Quotation vencida = new Quotation("q-1", TENANT, "va-1", "rec-1", QuotationRail.WIRE_DOMESTIC,
                new BigDecimal("1000.00"), FeeBreakdown.standard(), Instant.now().plusSeconds(900));
        vencida.applyKiraQuote("qt_1", Instant.now().minusSeconds(1), new BigDecimal("1030.00"),
                new BigDecimal("1000.00"), "USD", BigDecimal.ONE, FeeBreakdown.standard(), true, "kraken", null);
        when(quotations.findByIdAndTenant("q-1", TENANT)).thenReturn(Optional.of(vencida));
        pago.attachQuotation("q-1", Instant.now().plusSeconds(900));

        assertThrows(DomainException.class, () -> service.approveAndSubmit(approver, "p-1", null));
        verify(kira, never()).executePayout(anyString(), any(), any());
    }

    @Test
    void sinSaldoSuficienteNoSeIntentaElPago() {
        // 400 inmediato y terminal en Kira: no hay cola ni auto-cancelacion.
        Quotation sinSaldo = new Quotation("q-1", TENANT, "va-1", "rec-1", QuotationRail.WIRE_DOMESTIC,
                new BigDecimal("1000.00"), FeeBreakdown.standard(), Instant.now().plusSeconds(900));
        sinSaldo.applyKiraQuote("qt_1", null, new BigDecimal("1030.00"), new BigDecimal("1000.00"),
                "USD", BigDecimal.ONE, FeeBreakdown.standard(), false, "kraken", null);
        when(quotations.findByIdAndTenant("q-1", TENANT)).thenReturn(Optional.of(sinSaldo));
        pago.attachQuotation("q-1", Instant.now().plusSeconds(900));

        var e = assertThrows(DomainException.class, () -> service.approveAndSubmit(approver, "p-1", null));

        assertTrue(e.getMessage().contains("Saldo insuficiente"), e.getMessage());
        verify(kira, never()).executePayout(anyString(), any(), any());
    }

    @Test
    void elCreadorSigueSinPoderAprobarSuPropioPago() {
        var maker = new AuthenticatedOperator("maker-1", "treasury.maker@juriscop.test", TENANT,
                Role.ADMIN);
        conCotizacion();

        assertThrows(DomainException.class, () -> service.approveAndSubmit(maker, "p-1", null));
        verify(kira, never()).executePayout(anyString(), any(), any());
    }

    @Test
    void seGuardaElComprobanteQueReclamaElClienteFinal() {
        conCotizacion();

        var view = service.approveAndSubmit(approver, "p-1", null);

        assertEquals("IMAD-20260910-001", view.referenceNumber());
        assertEquals("wire", view.paymentMethod());
        assertTrue(view.priceLocked());
    }

    @Test
    void laNaturalezaYElMemoViajanEnSuSitio() {
        conCotizacion();

        service.approveAndSubmit(approver, "p-1", new PayoutCommands.ApprovePayout(
                "revisado por tesoreria", "vendor", "Factura 42", List.of()));

        Map<String, Object> body = cuerpoEnviado();
        assertEquals("vendor", body.get("nature_of_payment"));
        @SuppressWarnings("unchecked")
        Map<String, Object> extra = (Map<String, Object>) body.get("extra_info");
        assertEquals("Factura 42", extra.get("memo"));
        assertEquals("revisado por tesoreria", extra.get("internal_notes"));
    }

    @Test
    void losDocumentosDeSoporteViajanComoDataUri() {
        conCotizacion();

        service.approveAndSubmit(approver, "p-1", new PayoutCommands.ApprovePayout(
                null, "vendor", null,
                List.of(new SupportingDocument("invoice", "data:application/pdf;base64,JVBERi0="))));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> docs =
                (List<Map<String, Object>>) cuerpoEnviado().get("supporting_documents");
        assertEquals(1, docs.size());
        assertEquals("invoice", docs.getFirst().get("type"));
    }

    @Test
    void unaUrlNoValeComoDocumentoDeSoporte() {
        // Aqui, a diferencia del KYB, no se aceptan URLs.
        assertThrows(DomainException.class,
                () -> new SupportingDocument("invoice", "https://ejemplo.com/factura.pdf"));
    }

    @Test
    void unPagoYaEnviadoNoSeReenvia() {
        conCotizacion();
        service.approveAndSubmit(approver, "p-1", null);
        clearInvocations(kira);

        assertThrows(DomainException.class, () -> service.approveAndSubmit(approver, "p-1", null));
        verify(kira, never()).executePayout(anyString(), any(), any());
    }

    @Test
    void aKiraViajanSusIdsYNoLosDelPortal() {
        conCotizacion();

        service.approveAndSubmit(approver, "p-1", null);

        // cuerpoEnviado() ya exige la cuenta kva-1; el destinatario tambien va con su id de Kira.
        assertEquals("krec-1", cuerpoEnviado().get("recipient_id"));
    }

    @Test
    void crearUnPagoConUnaCuentaDeOtraEmpresaSeRechazaAlPreparar() {
        when(accounts.findByIdAndTenant("va-ajena", TENANT)).thenReturn(Optional.empty());

        var e = assertThrows(DomainException.class, () -> service.create(maker,
                new PayoutCommands.CreatePayout("va-ajena", "rec-1", new BigDecimal("100"), "USD", null, null, null)));

        assertEquals("La cuenta virtual no existe.", e.getMessage());
        verify(payouts, never()).save(any());
    }

    @Test
    void conLaMismaClaveDelPortalNoSeCreaUnSegundoPago() {
        String clave = "3f1a9c20-4b5d-4e6f-8a90-1c2d3e4f5a6b";
        var comando = new PayoutCommands.CreatePayout("va-1", "rec-1", new BigDecimal("100"), "USD", null, null, null);
        when(payouts.findByIdempotencyKey(IdempotencyKey.of(clave))).thenReturn(Optional.empty());

        var primero = service.create(maker, comando, clave);
        ArgumentCaptor<Payout> guardado = ArgumentCaptor.forClass(Payout.class);
        verify(payouts).save(guardado.capture());
        assertEquals(clave, guardado.getValue().getIdempotencyKey().value());

        when(payouts.findByIdempotencyKey(IdempotencyKey.of(clave))).thenReturn(Optional.of(guardado.getValue()));
        var segundo = service.create(maker, comando, clave);

        assertEquals(primero.id(), segundo.id());
        verify(payouts, times(1)).save(any());
    }

    @Test
    void unaClaveDelPortalQueNoEsUuidSeRechaza() {
        assertThrows(DomainException.class, () -> service.create(maker,
                new PayoutCommands.CreatePayout("va-1", "rec-1", new BigDecimal("100"), "USD", null, null, null), "doble-clic"));
        verify(payouts, never()).save(any());
    }

    @Test
    void crearUnPagoConUnDestinatarioInexistenteSeRechazaAlPreparar() {
        when(recipients.findByIdAndTenant("rec-x", TENANT)).thenReturn(Optional.empty());

        assertThrows(DomainException.class, () -> service.create(maker,
                new PayoutCommands.CreatePayout("va-1", "rec-x", new BigDecimal("100"), "USD", null, null, null)));
        verify(payouts, never()).save(any());
    }

    @Test
    void elUserDeKiraDelPagoEsElDeLaEmpresa() {
        var view = service.create(maker,
                new PayoutCommands.CreatePayout("va-1", "rec-1", new BigDecimal("100"), "USD", null, null, null));

        ArgumentCaptor<Payout> guardado = ArgumentCaptor.forClass(Payout.class);
        verify(payouts).save(guardado.capture());
        assertEquals("usr_1", guardado.getValue().getKiraUserId());
        assertEquals("va-1", view.virtualAccountId());
    }

    @Test
    void unaCotizacionDeOtroDestinatarioNoSeAtaAlPago() {
        Quotation otra = new Quotation("q-2", TENANT, "va-1", "rec-otro", QuotationRail.WIRE_DOMESTIC,
                new BigDecimal("1000.00"), FeeBreakdown.standard(), Instant.now().plusSeconds(900));
        when(quotations.findByIdAndTenant("q-2", TENANT)).thenReturn(Optional.of(otra));

        assertThrows(DomainException.class, () -> service.create(maker,
                new PayoutCommands.CreatePayout("va-1", "rec-1", new BigDecimal("100"), "USD", "q-2", null, null)));
    }

    // --- G-19: financiar un pago con un deposito cripto en vez de con el saldo de la cuenta ---

    @Test
    void crearUnPagoConRedYTokenLoMarcaComoFinanciadoConCripto() {
        var view = service.create(maker,
                new PayoutCommands.CreatePayout("va-1", "rec-1", new BigDecimal("100"), "USD", null,
                        "polygon", "USDC"));

        assertEquals("polygon", view.fundingNetwork());
        assertEquals("USDC", view.fundingCurrency());
    }

    @Test
    void unParDeRedYTokenInvalidoSeRechazaAlCrear() {
        assertThrows(DomainException.class, () -> service.create(maker,
                new PayoutCommands.CreatePayout("va-1", "rec-1", new BigDecimal("100"), "USD", null,
                        "tron", "USDC")));
    }

    @Test
    void unPagoFinanciadoConCriptoEnviaModoYPaymentInstructionsAKira() {
        pago.requestCryptoFunding("polygon", "USDC");
        pago.attachQuotation("q-1", Instant.now().plusSeconds(900));
        when(kira.executePayout(anyString(), any(), any())).thenReturn(json("""
                { "id": "pay_1", "status": "created", "deposit_instructions":
                  { "address": "0xabc123", "network": "polygon", "currency": "USDC",
                    "expires_at": "2026-09-22T12:00:00.000Z" } }
                """));

        var view = service.approveAndSubmit(approver, "p-1", new PayoutCommands.ApprovePayout(null, null, null, null));

        ArgumentCaptor<Map<String, Object>> cuerpo = ArgumentCaptor.forClass(Map.class);
        verify(kira).executePayout(anyString(), cuerpo.capture(), any());
        assertEquals("CRYPTO", cuerpo.getValue().get("mode"));
        assertEquals(Map.of("network", "polygon", "currency", "USDC"), cuerpo.getValue().get("payment_instructions"));
        assertNotNull(view.depositInstructions());
        assertTrue(view.depositInstructions().contains("0xabc123"));
    }

    // ---------- Vista previa, linea de tiempo e historial de Kira ----------

    @Test
    void laVistaPreviaUsaLosIdsDeKiraYElMargenDeLaPlataforma() {
        when(kira.previewPayout(eq("kva-1"), any())).thenReturn(json("""
                { "amount": "1030.00", "currency": "USD", "recipient_amount": "1000.00",
                  "recipient_currency": "USD", "fees": { "total": "30.00" } }
                """));

        PayoutPreviewView vista = service.preview(maker,
                new PayoutCommands.PreviewPayout("va-1", "rec-1", new BigDecimal("1000.00"), null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(kira).previewPayout(eq("kva-1"), body.capture());
        assertEquals("krec-1", body.getValue().get("recipient_id"));
        // Por defecto, como al cotizar, el importe es lo que recibe el destinatario.
        assertEquals(true, body.getValue().get("inverse_calculation"));
        assertTrue(body.getValue().containsKey("client_markup"));
        assertEquals("1030.00", vista.amount());
        assertEquals("30.00", vista.fees().get("total"));
    }

    @Test
    void laLineaDeTiempoSaleDeLosEventosDelPagoEnKira() {
        conCotizacion();
        service.approveAndSubmit(approver, "p-1", null);
        when(kira.getPayout("pay_1")).thenReturn(json("""
                { "payout_id": "pay_1", "status": "PROCESSING", "events": [
                  { "event_id": "e1", "status": "CREATED", "message": null, "created_at": "2026-09-11T10:00:00Z" },
                  { "event_id": "e2", "status": "PROCESSING", "message": "Enviado al banco", "created_at": "2026-09-11T10:01:00Z" } ] }
                """));

        List<PayoutEventView> eventos = service.events(approver, "p-1");

        assertEquals(2, eventos.size());
        assertEquals("Enviado al banco", eventos.get(1).message());
    }

    @Test
    void unPagoSinEnviarNoTieneLineaDeTiempoEnKira() {
        assertTrue(service.events(approver, "p-1").isEmpty());
        verify(kira, never()).getPayout(anyString());
    }

    @Test
    void elHistorialDeKiraDescartaPagosDeOtrasEmpresasYEnlazaLosDelPortal() {
        conCotizacion();
        service.approveAndSubmit(approver, "p-1", null);
        when(payouts.findByKiraPayoutId("pay_1")).thenReturn(Optional.of(pago));
        when(kira.listPayouts(any())).thenReturn(json("""
                { "payouts": [
                    { "payout_id": "pay_1", "user_id": "usr_1", "status": "COMPLETED", "origin": "payout" },
                    { "payout_id": "pay_ajeno", "user_id": "usr_2", "status": "COMPLETED", "origin": "api" } ],
                  "total": 2, "page": 1, "limit": 20, "total_pages": 1 }
                """));

        KiraPayoutPage pagina = service.kiraHistory(approver, "completed", 1, 20, null, null);

        assertEquals(1, pagina.items().size());
        assertEquals("p-1", pagina.items().getFirst().localPayoutId());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> query = ArgumentCaptor.forClass(Map.class);
        verify(kira).listPayouts(query.capture());
        assertEquals("usr_1", query.getValue().get("user_id"));
        assertEquals("COMPLETED", query.getValue().get("status"));
    }

    @Test
    void unEstadoQueKiraNoConoceSeRechazaAntesDeLlamar() {
        assertThrows(DomainException.class, () -> service.kiraHistory(approver, "RETURNED", 1, 20, null, null));
        verify(kira, never()).listPayouts(any());
    }

    @Test
    void unPagoDetenidoPorUnRfiLoIndicaEnSuDetalle() {
        conCotizacion();
        service.approveAndSubmit(approver, "p-1", null);
        Rfi rfi = new Rfi("rfi-local", TENANT, "rfi_k", "[]", null);
        when(rfis.findOpenBlocking("pay_1")).thenReturn(Optional.of(rfi));

        assertEquals("rfi-local", service.get(approver, "p-1").blockedByRfiId());
    }
}
