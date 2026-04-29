package com.example.payments.adapter.out.observability;

import com.example.payments.application.metrics.PaymentMetrics;
import com.example.payments.domain.model.PaymentIntentEvent;
import com.example.payments.domain.port.OutboxRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Decorates an {@link OutboxRepository} so every persisted batch of events also gets recorded as
 * Micrometer counters. Use cases stay unchanged; {@code ApplicationConfig} hands them the wrapped
 * instance.
 */
public class MeteringOutboxRepository implements OutboxRepository {

    private final OutboxRepository delegate;
    private final PaymentMetrics metrics;

    public MeteringOutboxRepository(OutboxRepository delegate, PaymentMetrics metrics) {
        this.delegate = delegate;
        this.metrics = metrics;
    }

    @Override
    public void saveAll(List<PaymentIntentEvent> events) {
        delegate.saveAll(events);
        metrics.recordEvents(events);
    }

    @Override
    public List<OutboxEntry> findUnpublishedBatch(int batchSize) {
        return delegate.findUnpublishedBatch(batchSize);
    }

    @Override
    public void markPublished(UUID id, Instant publishedAt) {
        delegate.markPublished(id, publishedAt);
    }

    @Override
    public void markFailed(UUID id, String error, Instant nextAttemptAt) {
        delegate.markFailed(id, error, nextAttemptAt);
    }
}
