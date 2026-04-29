package com.example.payments.adapter.in.rest;

import com.example.payments.domain.model.PaymentMethod.PaymentMethodType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ConfirmPaymentIntentRequest(@Valid PaymentMethodPayload paymentMethod) {

    public record PaymentMethodPayload(@NotBlank String token, @NotNull PaymentMethodType type) {}
}
