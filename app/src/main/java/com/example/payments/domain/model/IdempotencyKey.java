package com.example.payments.domain.model;

public record IdempotencyKey(String value) {

    private static final int MAX_LENGTH = 64;
    private static final int MIN_LENGTH = 8;

    public IdempotencyKey {
        if (value == null) {
            throw new IllegalArgumentException("idempotency key must not be null");
        }
        if (value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "idempotency key length must be between %d and %d"
                            .formatted(MIN_LENGTH, MAX_LENGTH));
        }
        if (!value.matches("[\\x21-\\x7E]+")) {
            throw new IllegalArgumentException("idempotency key must be ASCII printable");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
