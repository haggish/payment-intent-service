package com.example.payments.adapter.out.persistence;

import com.example.payments.domain.port.OutboxRepository;
import com.example.payments.domain.port.OutboxRepository.OutboxEntry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Polls the outbox table for unpublished events and forwards them to SQS.
 *
 * <p>Uses {@code SELECT ... FOR UPDATE SKIP LOCKED} (in the repository) so multiple instances can
 * poll concurrently without coordination — each instance grabs its own batch.
 *
 * <p>Failed publishes get exponential backoff with jitter, capped at 5 minutes between attempts.
 * After {@link #MAX_ATTEMPTS} attempts a row is left for human review and a CloudWatch alarm fires
 * (alarm wired in CDK).
 *
 * <p>This in-process scheduled implementation is deliberately simple. At higher scale, swap for
 * Debezium with the Outbox Event Router SMT, or a dedicated relay service.
 */
@Component
@ConditionalOnExpression("'${outbox.queue-url:}' != ''")
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final int MAX_ATTEMPTS = 10;
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final OutboxRepository repository;
    private final OutboxPublisher publisher;
    private final int batchSize;

    public OutboxDispatcher(
            OutboxRepository repository,
            OutboxPublisher publisher,
            @Value("${outbox.batch-size:50}") int batchSize) {
        this.repository = repository;
        this.publisher = publisher;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:1000}")
    @Transactional
    public void dispatch() {
        List<OutboxEntry> batch = repository.findUnpublishedBatch(batchSize);
        if (batch.isEmpty()) {
            return;
        }
        for (OutboxEntry entry : batch) {
            try {
                publisher.publish(entry);
                repository.markPublished(entry.id(), Instant.now());
            } catch (Exception e) {
                int nextAttempt = entry.attemptCount() + 1;
                Instant nextAt = Instant.now().plus(backoff(nextAttempt));
                repository.markFailed(entry.id(), e.getMessage(), nextAt);
                if (nextAttempt >= MAX_ATTEMPTS) {
                    log.error(
                            "outbox row {} exceeded max attempts; manual intervention required",
                            entry.id(),
                            e);
                }
            }
        }
    }

    /** Exponential backoff with full jitter, capped at {@link #MAX_BACKOFF}. */
    private Duration backoff(int attempt) {
        long max = Math.min((long) Math.pow(2, attempt), MAX_BACKOFF.getSeconds());
        long seconds = ThreadLocalRandom.current().nextLong(1, Math.max(2, max + 1));
        return Duration.ofSeconds(seconds);
    }
}
