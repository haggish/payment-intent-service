package com.example.payments.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain events emitted by the {@link PaymentIntent} aggregate.
 *
 * <p>Each event carries an {@code eventId} which becomes the SQS {@code MessageDeduplicationId} and
 * is used by consumers for idempotency dedup. One UUID flows end-to-end through the async pipeline.
 */
public sealed interface PaymentIntentEvent {

    UUID eventId();

    PaymentIntentId aggregateId();

    Instant occurredAt();

    record PaymentIntentCreated(
            UUID eventId, PaymentIntentId aggregateId, Money amount, Instant occurredAt)
            implements PaymentIntentEvent {}

    record PaymentMethodAttached(
            UUID eventId, PaymentIntentId aggregateId, PaymentMethod method, Instant occurredAt)
            implements PaymentIntentEvent {}

    record PaymentIntentConfirmed(UUID eventId, PaymentIntentId aggregateId, Instant occurredAt)
            implements PaymentIntentEvent {}

    record PaymentIntentAuthorized(
            UUID eventId,
            PaymentIntentId aggregateId,
            String processorReference,
            Instant occurredAt)
            implements PaymentIntentEvent {}

    record PaymentIntentActionRequired(
            UUID eventId, PaymentIntentId aggregateId, String challengeDetails, Instant occurredAt)
            implements PaymentIntentEvent {}

    record PaymentIntentCaptured(
            UUID eventId, PaymentIntentId aggregateId, Money amount, Instant occurredAt)
            implements PaymentIntentEvent {}

    record PaymentIntentPartiallyCaptured(
            UUID eventId,
            PaymentIntentId aggregateId,
            Money amount,
            Money totalCaptured,
            Instant occurredAt)
            implements PaymentIntentEvent {}

    record PaymentIntentCanceled(
            UUID eventId, PaymentIntentId aggregateId, String reason, Instant occurredAt)
            implements PaymentIntentEvent {}

    record PaymentIntentFailed(
            UUID eventId, PaymentIntentId aggregateId, String reason, Instant occurredAt)
            implements PaymentIntentEvent {}

    record PaymentRefunded(
            UUID eventId, PaymentIntentId aggregateId, Money amount, Instant occurredAt)
            implements PaymentIntentEvent {}
}
