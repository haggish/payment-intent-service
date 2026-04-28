package com.example.payments.domain.model;

import java.util.UUID;

/**
 * Strongly-typed identifier for a {@link PaymentIntent}.
 *
 * <p>Wrapping UUIDs in a domain type prevents the classic bug of passing a {@code MerchantId} where
 * a {@code PaymentIntentId} is expected — the compiler catches it.
 */
public record PaymentIntentId(UUID value) {

    public PaymentIntentId {
        if (value == null) {
            throw new IllegalArgumentException("id must not be null");
        }
    }

    public static PaymentIntentId generate() {
        return new PaymentIntentId(UUID.randomUUID());
    }

    public static PaymentIntentId of(String s) {
        return new PaymentIntentId(UUID.fromString(s));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
