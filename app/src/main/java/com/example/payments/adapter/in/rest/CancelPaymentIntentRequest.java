package com.example.payments.adapter.in.rest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelPaymentIntentRequest(@NotBlank @Size(max = 256) String reason) {}
