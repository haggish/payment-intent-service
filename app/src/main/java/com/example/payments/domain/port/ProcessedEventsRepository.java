package com.example.payments.domain.port;

import java.util.UUID;

/**
 * Consumer-side dedup. {@link #tryMarkProcessed} is the third layer of the three-layer idempotency
 * design: API key → outbox UUID → processed-events row.
 *
 * <p>Callers must invoke this inside the same database transaction as the handler's writes so a
 * crash mid-handler rolls back both — the message will be redelivered and processed cleanly.
 */
public interface ProcessedEventsRepository {

    /**
     * @return {@code true} if this event was newly inserted (caller proceeds), {@code false} if it
     *     was already present (caller skips — duplicate delivery).
     */
    boolean tryMarkProcessed(UUID eventId, String aggregateId);
}
