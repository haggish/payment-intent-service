package com.example.payments.domain.model;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * Currency-safe money value object.
 *
 * <p>All arithmetic operations enforce matching currencies; mixing currencies throws.
 *
 * <p>Stored internally as minor units (cents/pence) to avoid floating point drift.
 */
public record Money(long minorUnits, Currency currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (minorUnits < 0) {
            throw new IllegalArgumentException("amount must be non-negative");
        }
    }

    public static Money of(long minorUnits, String currencyCode) {
        return new Money(minorUnits, Currency.getInstance(currencyCode));
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(this.minorUnits + other.minorUnits, currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        if (other.minorUnits > this.minorUnits) {
            throw new IllegalArgumentException("subtraction would yield negative amount");
        }
        return new Money(this.minorUnits - other.minorUnits, currency);
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return this.minorUnits > other.minorUnits;
    }

    public boolean isZero() {
        return minorUnits == 0;
    }

    public BigDecimal asMajorUnits() {
        return BigDecimal.valueOf(minorUnits, currency.getDefaultFractionDigits());
    }

    private void requireSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "currency mismatch: %s vs %s".formatted(this.currency, other.currency));
        }
    }
}
