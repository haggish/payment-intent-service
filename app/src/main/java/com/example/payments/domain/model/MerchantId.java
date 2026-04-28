package com.example.payments.domain.model;

public record MerchantId(String value) {

    public MerchantId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("merchant id must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
