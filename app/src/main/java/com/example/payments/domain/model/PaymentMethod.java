package com.example.payments.domain.model;

/**
 * Reference to a tokenized payment method.
 *
 * <p>The service deliberately never sees raw card data — tokenization happens upstream (client UI
 * exchanges card data with the processor and receives a token). This keeps the service out of PCI
 * DSS scope.
 */
public record PaymentMethod(String token, PaymentMethodType type) {

    public PaymentMethod {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token must not be blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
    }

    public enum PaymentMethodType {
        CARD,
        BANK_ACCOUNT,
        WALLET
    }
}
