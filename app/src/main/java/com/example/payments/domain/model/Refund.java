package com.example.payments.domain.model;

import java.time.Instant;
import java.util.UUID;

public record Refund(UUID id, int captureIndex, Money amount, Instant refundedAt) {
    public Refund(int captureIndex, Money amount, Instant refundedAt) {
        this(UUID.randomUUID(), captureIndex, amount, refundedAt);
    }
}
