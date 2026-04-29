package com.example.payments.adapter.out.messaging;

import com.example.payments.adapter.out.persistence.OutboxPublisher;
import com.example.payments.domain.port.OutboxRepository.OutboxEntry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * Publishes outbox entries to a FIFO SQS queue.
 *
 * <p>Uses {@code aggregate_id} as {@code MessageGroupId} (per-intent ordering) and the outbox row's
 * UUID as {@code MessageDeduplicationId} (protects against double-publish if the dispatcher crashes
 * between SQS accept and the DB update).
 */
@Component
@ConditionalOnExpression("'${outbox.queue-url:}' != ''")
public class SqsOutboxPublisher implements OutboxPublisher {

    private final SqsClient sqs;
    private final String queueUrl;

    public SqsOutboxPublisher(SqsClient sqs, @Value("${outbox.queue-url}") String queueUrl) {
        this.sqs = sqs;
        this.queueUrl = queueUrl;
    }

    @Override
    public void publish(OutboxEntry entry) {
        sqs.sendMessage(
                SendMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .messageBody(entry.payload())
                        .messageGroupId(entry.aggregateId())
                        .messageDeduplicationId(entry.id().toString())
                        .build());
    }
}
