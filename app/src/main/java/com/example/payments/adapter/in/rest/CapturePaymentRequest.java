package com.example.payments.adapter.in.rest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CapturePaymentRequest(
        @Positive long amountMinorUnits, @NotBlank @Size(min = 3, max = 3) String currency) {}
