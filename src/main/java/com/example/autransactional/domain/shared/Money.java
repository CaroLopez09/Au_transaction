package com.example.autransactional.domain.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;

/**
 * Importe con moneda. Existe para que un monto no viaje nunca separado de su divisa:
 * el esquema guarda DECIMAL(18,4) y una columna de moneda por fila, y sumar dos filas
 * de monedas distintas es el error que este tipo hace imposible.
 */
public record Money(BigDecimal amount, String currency) {

    /** La escala del esquema: DECIMAL(18, 4) en cada columna de importe. */
    public static final int SCALE = 4;

    public Money {
        Objects.requireNonNull(amount, "El importe no puede ser null");
        if (currency == null || currency.isBlank()) {
            throw new DomainException("Todo importe debe llevar moneda.");
        }
        currency = currency.trim().toUpperCase(Locale.ROOT);
        amount = amount.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount, currency);
    }

    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        assertSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        assertSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isLessThan(Money other) {
        assertSameCurrency(other);
        return amount.compareTo(other.amount) < 0;
    }

    private void assertSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new DomainException("No se pueden operar importes en " + currency + " y " + other.currency + ".");
        }
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency;
    }
}
