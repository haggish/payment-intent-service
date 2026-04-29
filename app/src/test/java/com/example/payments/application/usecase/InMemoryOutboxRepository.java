package com.example.payments.application.usecase;

import com.example.payments.domain.model.PaymentIntentEvent;
import com.example.payments.domain.port.OutboxRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Test fake. Records events that were saved; the polling/dispatch path is out of scope. */
class InMemoryOutboxRepository implements OutboxRepository {

    final List<PaymentIntentEvent> saved = new ArrayList<>();

    @Override
    public void saveAll(List<PaymentIntentEvent> events) {
        saved.addAll(events);
    }

    @Override
    public List<OutboxEntry> findUnpublishedBatch(int batchSize) {
        return List.of();
    }

    @Override
    public void markPublished(UUID id, Instant publishedAt) {}

    @Override
    public void markFailed(UUID id, String error, Instant nextAttemptAt) {}
}
