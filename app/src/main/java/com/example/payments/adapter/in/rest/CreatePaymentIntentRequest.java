package com.example.payments.adapter.in.rest;

public record CreatePaymentIntentRequest(long amountMinorUnits, String currency) {}
