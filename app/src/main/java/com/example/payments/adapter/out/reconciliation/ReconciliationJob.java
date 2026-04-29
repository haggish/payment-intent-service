package com.example.payments.adapter.out.reconciliation;

import com.example.payments.application.usecase.ApplyAuthorizationOutcome;
import com.example.payments.domain.model.PaymentIntentId;
import com.example.payments.domain.port.PaymentIntentRepository;
import com.example.payments.domain.port.PaymentProcessor;
import com.example.payments.domain.port.PaymentProcessor.AuthorizationResult;
import com.example.payments.domain.port.PaymentProcessor.DeclineReason;
import com.example.payments.domain.port.PaymentProcessor.ProcessorStatus;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Periodically scans for intents stuck in {@code PROCESSING} and asks the processor what really
 * happened via {@link PaymentProcessor#lookup(String)}. Off by default.
 *
 * <p>The lookup key is the intent id (UUID string) — this matches what {@code PaymentEventListener}
 * passes to {@code processor.authorize}, so reconciliation doesn't need to recover the original
 * event id from outbox.
 */
@Component
@ConditionalOnExpression("'${reconciliation.enabled:false}' == 'true'")
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final PaymentIntentRepository repository;
    private final PaymentProcessor processor;
    private final ApplyAuthorizationOutcome applyOutcome;
    private final TransactionTemplate txTemplate;
    private final Clock clock;
    private final Duration stuckThreshold;
    private final int batchSize;

    public ReconciliationJob(
            PaymentIntentRepository repository,
            PaymentProcessor processor,
            ApplyAuthorizationOutcome applyOutcome,
            TransactionTemplate txTemplate,
            Clock clock,
            @Value("${reconciliation.stuck-threshold-seconds:60}") long stuckThresholdSeconds,
            @Value("${reconciliation.batch-size:50}") int batchSize) {
        this.repository = repository;
        this.processor = processor;
        this.applyOutcome = applyOutcome;
        this.txTemplate = txTemplate;
        this.clock = clock;
        this.stuckThreshold = Duration.ofSeconds(stuckThresholdSeconds);
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${reconciliation.poll-interval-ms:30000}")
    public void reconcile() {
        var olderThan = clock.instant().minus(stuckThreshold);
        var stuck = repository.findStuckProcessing(olderThan, batchSize);
        for (PaymentIntentId id : stuck) {
            try {
                txTemplate.executeWithoutResult(s -> reconcileOne(id));
            } catch (RuntimeException e) {
                log.error("reconciliation failed for {}: {}", id, e.getMessage());
            }
        }
    }

    void reconcileOne(PaymentIntentId id) {
        var status = processor.lookup(id.value().toString()).orElse(null);
        if (status == null) {
            log.debug("processor has no status for {}; will retry on next pass", id);
            return;
        }
        AuthorizationResult result = mapToResult(status);
        if (result != null) {
            applyOutcome.execute(id, result);
        }
    }

    private AuthorizationResult mapToResult(ProcessorStatus status) {
        return switch (status.state()) {
            case "AUTHORIZED" ->
                    new AuthorizationResult.Authorized(
                            status.processorReference(), clock.instant());
            case "DECLINED" ->
                    new AuthorizationResult.Declined(DeclineReason.OTHER, "reconciled decline");
            default -> {
                log.warn("unexpected processor state {} for intent reconciliation", status.state());
                yield null;
            }
        };
    }
}
