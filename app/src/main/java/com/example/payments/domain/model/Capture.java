package com.example.payments.domain.model;

import java.time.Instant;
import java.util.UUID;

public record Capture(UUID id, Money amount, Instant capturedAt) {
    public Capture(Money amount, Instant capturedAt) {
        this(UUID.randomUUID(), amount, capturedAt);
    }
}
