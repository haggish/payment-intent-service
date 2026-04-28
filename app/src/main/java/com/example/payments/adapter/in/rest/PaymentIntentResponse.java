package com.example.payments.adapter.in.rest;

import com.example.payments.domain.model.PaymentIntent;
import com.example.payments.domain.model.PaymentState;

public record PaymentIntentResponse(
        String id, long amountMinorUnits, String currency, PaymentState state, long version) {

    public static PaymentIntentResponse from(PaymentIntent intent) {
        return new PaymentIntentResponse(
                intent.id().toString(),
                intent.amount().minorUnits(),
                intent.amount().currency().getCurrencyCode(),
                intent.state(),
                intent.version());
    }
}
