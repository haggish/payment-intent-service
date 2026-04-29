package com.example.payments.domain.exception;

import com.example.payments.domain.model.PaymentIntentId;

public class PaymentIntentNotFoundException extends RuntimeException {
    public PaymentIntentNotFoundException(PaymentIntentId id) {
        super("payment intent not found: %s".formatted(id));
    }
}
