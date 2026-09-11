package com.example.autransactional.domain.treasury;

import com.example.autransactional.domain.shared.DomainException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Desglose comisional de una transferencia: 15 USD de KiraFin + 15 USD de margen de la
 * plataforma = 30 USD que se cobran al cliente.
 *
 * Se guardan las tres cifras y no solo el total porque el margen de la plataforma es lo
 * unico que la contabilidad propia puede reconocer como ingreso; derivarlo restando
 * obligaria a asumir para siempre que la tarifa de Kira no cambia.
 */
public record FeeBreakdown(BigDecimal kiraFee, BigDecimal platformFee, BigDecimal totalFee) {

    public static final BigDecimal DEFAULT_KIRA_FEE = new BigDecimal("15.0000");
    public static final BigDecimal DEFAULT_PLATFORM_FEE = new BigDecimal("15.0000");

    public FeeBreakdown {
        kiraFee = scaled(kiraFee, "kiraFee");
        platformFee = scaled(platformFee, "platformFee");
        totalFee = totalFee == null ? kiraFee.add(platformFee) : scaled(totalFee, "totalFee");

        if (totalFee.compareTo(kiraFee.add(platformFee)) != 0) {
            throw new DomainException("El cobro total debe ser la suma de la tarifa de Kira y el margen "
                    + "de la plataforma.");
        }
    }

    /**
     * El desglose de referencia: 15 + 15 = 30 USD.
     *
     * Es una estimacion, no un hecho: la tarifa de Kira depende del riel y lleva un tramo
     * porcentual, asi que la cifra real solo se conoce cuando la cotizacion vuelve.
     * Se usa mientras no haya cotizacion.
     */
    public static FeeBreakdown standard() {
        return new FeeBreakdown(DEFAULT_KIRA_FEE, DEFAULT_PLATFORM_FEE, null);
    }

    /**
     * El desglose real, leido de la cotizacion:
     * totals.kira_revenue_total es el ingreso de Kira y totals.client_markup_total el nuestro.
     */
    public static FeeBreakdown fromTotals(BigDecimal kiraRevenueTotal, BigDecimal clientMarkupTotal) {
        return new FeeBreakdown(kiraRevenueTotal, clientMarkupTotal, null);
    }

    /** El margen fijo que la plataforma pide a Kira como client_markup. */
    public static BigDecimal requestedPlatformMarkup() {
        return DEFAULT_PLATFORM_FEE;
    }

    public static FeeBreakdown of(BigDecimal kiraFee, BigDecimal platformFee) {
        return new FeeBreakdown(kiraFee, platformFee, null);
    }

    /** Lo que se debita de la cuenta virtual: el importe enviado mas el cobro total. */
    public BigDecimal totalDebitFor(BigDecimal originAmount) {
        return originAmount.add(totalFee).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal scaled(BigDecimal value, String field) {
        if (value == null || value.signum() < 0) {
            throw new DomainException("La comision " + field + " no puede ser nula ni negativa.");
        }
        return value.setScale(4, RoundingMode.HALF_UP);
    }
}
