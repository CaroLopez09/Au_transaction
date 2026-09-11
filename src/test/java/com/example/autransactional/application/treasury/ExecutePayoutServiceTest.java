package com.example.autransactional.application.treasury;

import com.example.autransactional.domain.shared.DomainException;
import com.example.autransactional.domain.shared.IdempotencyKey;
import com.example.autransactional.domain.shared.Money;
import com.example.autransactional.domain.shared.TenantId;
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

    private ExecutePayoutService service;
    private Payout pago;
    private Quotation cotizacion;

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

        pago = new Payout("p-1", TENANT, "usr_1", "va-1", "rec-1",
                Money.of(new BigDecimal("1000.00"), "USD"), FeeBreakdown.standard(),
                IdempotencyKey.newKey(), "maker-1");

        when(payouts.findByIdAndTenant("p-1", TENANT)).thenAnswer(i -> Optional.of(pago));
        when(payouts.save(any())).thenAnswer(i -> i.getArgument(0));
        when(quotations.findByIdAndTenant("q-1", TENANT)).thenAnswer(i -> Optional.of(cotizacion));
        when(quotations.save(any())).thenAnswer(i -> i.getArgument(0));
        when(kira.executePayout(anyString(), any(), any())).thenReturn(json(RESPUESTA_201));

        service = new ExecutePayoutService(payouts, quotations, kira, audit);
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
        verify(kira).executePayout(eq("va-1"), body.capture(), any(IdempotencyKey.class));
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
}
