package com.example.payments.domain.port;

import com.example.payments.domain.model.PaymentIntentEvent;
import java.time.Instant;
import java.util.List;

/** Port for the transactional outbox. */
public interface OutboxRepository {

    /** Saves events in the SAME transaction as the aggregate change. */
    void saveAll(List<PaymentIntentEvent> events);

    /**
     * Reads up to {@code batchSize} unpublished rows whose {@code next_attempt_at} is in the past.
     * Implementations should use {@code FOR UPDATE SKIP LOCKED} so multiple dispatchers can poll
     * concurrently without coordination.
     */
    List<OutboxEntry> findUnpublishedBatch(int batchSize);

    void markPublished(java.util.UUID id, Instant publishedAt);

    void markFailed(java.util.UUID id, String error, Instant nextAttemptAt);

    record OutboxEntry(
            java.util.UUID id,
            String aggregateType,
            String aggregateId,
            String eventType,
            String payload,
            int attemptCount,
            Instant createdAt) {}
}
