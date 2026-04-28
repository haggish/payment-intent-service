package com.example.payments.domain.model;

import java.time.Instant;
import java.util.UUID;

public record PaymentAttempt(
        UUID id, String processorReference, AttemptOutcome outcome, Instant attemptedAt) {

    public PaymentAttempt(String processorReference, AttemptOutcome outcome, Instant attemptedAt) {
        this(UUID.randomUUID(), processorReference, outcome, attemptedAt);
    }

    public enum AttemptOutcome {
        AUTHORIZED,
        DECLINED,
        UNKNOWN,
        FAILED
    }
}
