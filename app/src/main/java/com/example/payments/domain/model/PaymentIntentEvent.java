package com.example.payments.domain.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;
import java.util.UUID;

/**
 * Domain events emitted by the {@link PaymentIntent} aggregate.
 *
 * <p>Each event carries an {@code eventId} which becomes the SQS {@code MessageDeduplicationId} and
 * is used by consumers for idempotency dedup. One UUID flows end-to-end through the async pipeline.
 *
 * <p>{@code @type} is the Jackson polymorphism discriminator written to the JSON payload so SQS
 * consumers can deserialize into the correct subtype.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes({
    @JsonSubTypes.Type(
            value = PaymentIntentEvent.PaymentIntentCreated.class,
            name = "PaymentIntentCreated"),
    @JsonSubTypes.Type(
            value = PaymentIntentEvent.PaymentMethodAttached.class,
            name = "PaymentMethodAttached"),
    @JsonSubTypes.Type(
            value = PaymentIntentEvent.PaymentIntentConfirmed.class,
            name = "PaymentIntentConfirmed"),
    @JsonSubTypes.Type(
            value = PaymentIntentEvent.PaymentIntentAuthorized.class,
            name = "PaymentIntentAuthorized"),
    @JsonSubTypes.Type(
            value = PaymentIntentEvent.PaymentIntentActionRequired.class,
            name = "PaymentIntentActionRequired"),
    @JsonSubTypes.Type(
            value = PaymentIntentEvent.PaymentIntentCaptured.class,
            name = "PaymentIntentCaptured"),
    @JsonSubTypes.Type(
            value = PaymentIntentEvent.PaymentIntentPartiallyCaptured.class,
            name = "PaymentIntentPartiallyCaptured"),
    @JsonSubTypes.Type(
            value = PaymentIntentEvent.PaymentIntentCanceled.class,
            name = "PaymentIntentCanceled"),
    @JsonSubTypes.Type(
            value = PaymentIntentEvent.PaymentIntentFailed.class,
            name = "PaymentIntentFailed"),
    @JsonSubTypes.Type(value = PaymentIntentEvent.PaymentRefunded.class, name = "PaymentRefunded")
})
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
