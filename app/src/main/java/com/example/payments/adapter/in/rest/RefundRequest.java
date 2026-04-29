package com.example.payments.adapter.in.rest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record RefundRequest(
        @PositiveOrZero int captureIndex,
        @Positive long amountMinorUnits,
        @NotBlank @Size(min = 3, max = 3) String currency) {}
