package com.example.payments.adapter.out.persistence;

import com.example.payments.domain.port.OutboxRepository.OutboxEntry;

/** Publishes outbox entries to the message bus (SQS in this implementation). */
public interface OutboxPublisher {
    void publish(OutboxEntry entry);
}
