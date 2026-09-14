package com.example.autransactional.infrastructure.kira;

import com.example.autransactional.domain.shared.DomainException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * La conversion de importes vive en un solo sitio porque la API expresa el MISMO markup
 * de dos formas distintas segun el endpoint. Si algun dia dejan de coincidir, falla aqui.
 */
class KiraAmountsTest {

    @Test
    void convierteUnidadesMenoresConSuPrecision() {
        // Los dos ejemplos del documento de integracion.
        assertEquals(new BigDecimal("50000.00"), KiraAmounts.fromMinor(5000000L, 2));
        assertEquals(new BigDecimal("49920.000000"), KiraAmounts.fromMinor(49920000000L, 6));
    }

    @Test
    void laConversionEsExactaYNoPierdeCentavos() {
        // Con double, 0.1 + 0.2 no da 0.3. Con BigDecimal, un centavo es un centavo.
        BigDecimal valor = KiraAmounts.fromMinor(1L, 2);

        assertEquals(new BigDecimal("0.01"), valor);
        assertEquals(1L, KiraAmounts.toMinor(valor, 2));
    }

    @Test
    void idaYVueltaConservaElImporte() {
        BigDecimal original = new BigDecimal("1234.56");

        assertEquals(0, original.compareTo(
                KiraAmounts.fromMinor(KiraAmounts.toMinor(original, 2), 2)));
    }

    @Test
    void elMontoViajaSiempreConDosDecimales() {
        assertEquals("1000.00", KiraAmounts.amountString(new BigDecimal("1000")));
        assertEquals("1000.50", KiraAmounts.amountString(new BigDecimal("1000.5")));
        assertEquals("1000.46", KiraAmounts.amountString(new BigDecimal("1000.456")));
    }

    @Test
    void unMontoCeroNoSeCotiza() {
        assertThrows(DomainException.class, () -> KiraAmounts.amountString(BigDecimal.ZERO));
        assertThrows(DomainException.class, () -> KiraAmounts.amountString(new BigDecimal("-5")));
    }

    @Test
    void elMismoMarkupEnLasDosFormasDeOndaDeLaApi() {
        BigDecimal quince = new BigDecimal("15.00");

        Map<String, Object> cotizacion = KiraAmounts.markupForQuotation(quince, 50);
        Map<String, Object> pago = KiraAmounts.markupForPayout(quince, 50);

        // En /v1/quotations: entero en unidades menores + puntos basicos.
        assertEquals(1500L, cotizacion.get("fixed_minor"));
        assertEquals(50, cotizacion.get("percentage_bps"));
        // En /payout: cadenas decimales. Misma cifra, otra forma.
        assertEquals("15.00", pago.get("fixed_fee"));
        // 50 bps = 0,5 % = fraccion 0.005. "0.50" seria un 50 %.
        assertEquals("0.0050", pago.get("percentage_fee"));
    }

    @Test
    void elPorcentajeDelPagoEsUnaFraccionEntreCeroYUno() {
        BigDecimal quince = new BigDecimal("15.00");

        // Ejemplo de la documentacion de Kira: "0.01" = 1 %.
        assertEquals(0, new BigDecimal("0.01").compareTo(
                new BigDecimal((String) KiraAmounts.markupForPayout(quince, 100).get("percentage_fee"))));
        assertEquals("0.0001", KiraAmounts.markupForPayout(quince, 1).get("percentage_fee"));
        assertEquals("0.0000", KiraAmounts.markupForPayout(quince, 0).get("percentage_fee"));
        assertEquals("1.0000", KiraAmounts.markupForPayout(quince, KiraAmounts.MAX_PERCENTAGE_BPS).get("percentage_fee"));
    }

    @Test
    void elMarkupPorcentualTieneTope() {
        assertThrows(DomainException.class,
                () -> KiraAmounts.markupForQuotation(new BigDecimal("15.00"), 10001));
        assertThrows(DomainException.class,
                () -> KiraAmounts.markupForPayout(new BigDecimal("15.00"), -1));
    }

    @Test
    void lasStablecoinsUsanSeisDecimales() {
        assertEquals(6, KiraAmounts.precisionOf("USDC"));
        assertEquals(6, KiraAmounts.precisionOf("usdt"));
        assertEquals(2, KiraAmounts.precisionOf("USD"));
        assertEquals(2, KiraAmounts.precisionOf(null));
    }
}
