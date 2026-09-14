package com.example.autransactional.infrastructure.kira;

import com.example.autransactional.domain.shared.DomainException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Conversion entre los importes del dominio (BigDecimal) y las dos formas de onda que
 * conviven en la API de Kira. Vive en un solo sitio a proposito.
 *
 * Kira expresa los importes en unidades menores mas una precision: 5000000 con
 * precision 2 son 50.000,00 USD, y 49920000000 con precision 6 son 49.920,00 USDC.
 * La formula es amount / 10^precision y NUNCA se calcula con float: un centavo perdido
 * por redondeo binario es un descuadre contable.
 *
 * Y hay una inconsistencia real de la API que este archivo encapsula: el markup del
 * cliente viaja como entero en unidades menores + puntos basicos en POST /v1/quotations,
 * pero como cadena decimal en POST /payout. Convertirlo en dos sitios distintos es
 * garantizar que un dia dejen de coincidir.
 */
public final class KiraAmounts {

    /** Precision de las monedas fiat en la API. */
    public static final int FIAT_PRECISION = 2;

    /** Precision de las stablecoins (USDC, USDT). */
    public static final int STABLECOIN_PRECISION = 6;

    /** Tope de los puntos basicos admitidos por Kira. */
    public static final int MAX_PERCENTAGE_BPS = 10000;

    private KiraAmounts() {
    }

    /** amount / 10^precision, con BigDecimal. Es la formula G5 del documento de integracion. */
    public static BigDecimal fromMinor(long amount, int precision) {
        if (precision < 0) {
            throw new DomainException("La precision de un importe no puede ser negativa.");
        }
        return BigDecimal.valueOf(amount).movePointLeft(precision);
    }

    public static long toMinor(BigDecimal amount, int precision) {
        if (amount == null) {
            throw new DomainException("No se puede convertir un importe nulo.");
        }
        return amount.movePointRight(precision).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /**
     * Formato del campo 'amount': patron ^\d+\.\d{2}$ exactamente. Ni mas ni menos decimales,
     * y "0.00" se rechaza del lado de Kira.
     */
    public static String amountString(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new DomainException("El monto a cotizar debe ser mayor que cero.");
        }
        return amount.setScale(FIAT_PRECISION, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * client_markup para POST /v1/quotations: entero en unidades menores + puntos basicos.
     * Es el ingreso de la plataforma, y vuelve en totals.client_markup_total.
     */
    public static Map<String, Object> markupForQuotation(BigDecimal fixedFee, int percentageBps) {
        if (percentageBps < 0 || percentageBps > MAX_PERCENTAGE_BPS) {
            throw new DomainException("El markup porcentual debe estar entre 0 y " + MAX_PERCENTAGE_BPS + " bps.");
        }
        Map<String, Object> markup = new LinkedHashMap<>();
        markup.put("fixed_minor", toMinor(fixedFee, FIAT_PRECISION));
        markup.put("percentage_bps", percentageBps);
        return markup;
    }

    /**
     * El MISMO markup para POST /payout y /payout/preview, donde la API lo espera como cadenas
     * decimales. Misma cifra, otra forma de onda: por eso las dos funciones estan juntas.
     *
     * percentage_fee es una FRACCION entre 0 y 1 ("0.01" = 1 %), no un porcentaje ni puntos
     * basicos. Por eso se divide entre 10.000 y no entre 100: con 100, 50 bps viajaban como
     * "0.50" y Kira cobraria un 50 %. Cuatro decimales representan exacto cualquier bps.
     */
    public static Map<String, Object> markupForPayout(BigDecimal fixedFee, int percentageBps) {
        if (percentageBps < 0 || percentageBps > MAX_PERCENTAGE_BPS) {
            throw new DomainException("El markup porcentual debe estar entre 0 y " + MAX_PERCENTAGE_BPS + " bps.");
        }
        Map<String, Object> markup = new LinkedHashMap<>();
        markup.put("fixed_fee", fixedFee.setScale(FIAT_PRECISION, RoundingMode.HALF_UP).toPlainString());
        markup.put("percentage_fee", BigDecimal.valueOf(percentageBps).movePointLeft(4).setScale(4).toPlainString());
        return markup;
    }

    /** Precision por defecto de una moneda cuando la respuesta no la trae. */
    public static int precisionOf(String currency) {
        if (currency == null) {
            return FIAT_PRECISION;
        }
        return switch (currency.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "USDC", "USDT" -> STABLECOIN_PRECISION;
            default -> FIAT_PRECISION;
        };
    }
}
