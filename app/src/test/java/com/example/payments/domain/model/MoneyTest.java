package com.example.payments.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Currency;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class MoneyTest {

    // ---------- Properties (jqwik) ----------

    @Property
    boolean addition_is_commutative_within_same_currency(
            @ForAll @LongRange(min = 0, max = 1_000_000_000L) long a,
            @ForAll @LongRange(min = 0, max = 1_000_000_000L) long b) {
        var x = Money.of(a, "EUR");
        var y = Money.of(b, "EUR");
        return x.add(y).equals(y.add(x));
    }

    @Property
    boolean add_then_subtract_round_trips(
            @ForAll @LongRange(min = 0, max = 1_000_000_000L) long base,
            @ForAll @LongRange(min = 0, max = 1_000_000_000L) long delta) {
        var x = Money.of(base, "USD");
        var y = Money.of(delta, "USD");
        return x.add(y).subtract(y).equals(x);
    }

    @Property
    boolean greater_than_is_strict(
            @ForAll @LongRange(min = 0, max = 1_000_000L) long a,
            @ForAll @LongRange(min = 0, max = 1_000_000L) long b) {
        var x = Money.of(a, "GBP");
        var y = Money.of(b, "GBP");
        if (a == b) {
            return !x.isGreaterThan(y) && !y.isGreaterThan(x);
        }
        return x.isGreaterThan(y) != y.isGreaterThan(x);
    }

    // ---------- Examples (JUnit) ----------

    @Test
    void rejects_negative_minor_units() {
        assertThatThrownBy(() -> new Money(-1, Currency.getInstance("EUR")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_null_currency() {
        assertThatThrownBy(() -> new Money(100, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void zero_is_zero() {
        assertThat(Money.of(0, "EUR").isZero()).isTrue();
        assertThat(Money.of(1, "EUR").isZero()).isFalse();
    }

    @Test
    void add_rejects_currency_mismatch() {
        assertThatThrownBy(() -> Money.of(100, "EUR").add(Money.of(100, "USD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency mismatch");
    }

    @Test
    void subtract_rejects_currency_mismatch() {
        assertThatThrownBy(() -> Money.of(100, "EUR").subtract(Money.of(50, "USD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency mismatch");
    }

    @Test
    void subtract_rejects_negative_result() {
        assertThatThrownBy(() -> Money.of(50, "EUR").subtract(Money.of(100, "EUR")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void greater_than_rejects_currency_mismatch() {
        assertThatThrownBy(() -> Money.of(100, "EUR").isGreaterThan(Money.of(100, "USD")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void as_major_units_respects_currency_fraction_digits() {
        assertThat(Money.of(150, "EUR").asMajorUnits().toPlainString()).isEqualTo("1.50");
        // JPY has zero fraction digits
        assertThat(Money.of(150, "JPY").asMajorUnits().toPlainString()).isEqualTo("150");
    }
}
