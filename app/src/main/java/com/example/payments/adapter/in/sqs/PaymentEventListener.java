package com.example.payments.adapter.in.sqs;

import com.example.payments.application.usecase.ApplyAuthorizationOutcome;
import com.example.payments.domain.exception.PaymentIntentNotFoundException;
import com.example.payments.domain.model.PaymentIntentEvent;
import com.example.payments.domain.model.PaymentIntentEvent.PaymentIntentConfirmed;
import com.example.payments.domain.model.PaymentState;
import com.example.payments.domain.port.PaymentIntentRepository;
import com.example.payments.domain.port.PaymentProcessor;
import com.example.payments.domain.port.PaymentProcessor.AuthorizationRequest;
import com.example.payments.domain.port.ProcessedEventsRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * Polling consumer for the FIFO event queue. Each message is handled inside its own DB transaction
 * so a failure rolls back both the dedup row and any aggregate writes — SQS redelivery then re-runs
 * cleanly.
 *
 * <p>The SQS message is deleted only after the transaction commits. If the commit succeeds but the
 * delete fails (network blip), redelivery is harmless: the next attempt sees the processed_events
 * row and skips.
 */
@Component
@ConditionalOnExpression("'${outbox.queue-url:}' != ''")
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private final SqsClient sqs;
    private final String queueUrl;
    private final ObjectMapper objectMapper;
    private final ProcessedEventsRepository processedEvents;
    private final PaymentIntentRepository repository;
    private final PaymentProcessor processor;
    private final ApplyAuthorizationOutcome applyOutcome;
    private final TransactionTemplate txTemplate;
    private final int maxMessages;
    private final int waitTimeSeconds;

    public PaymentEventListener(
            SqsClient sqs,
            ObjectMapper objectMapper,
            ProcessedEventsRepository processedEvents,
            PaymentIntentRepository repository,
            PaymentProcessor processor,
            ApplyAuthorizationOutcome applyOutcome,
            TransactionTemplate txTemplate,
            @Value("${outbox.queue-url}") String queueUrl,
            @Value("${consumer.max-messages:10}") int maxMessages,
            @Value("${consumer.wait-time-seconds:2}") int waitTimeSeconds) {
        this.sqs = sqs;
        this.objectMapper = objectMapper;
        this.processedEvents = processedEvents;
        this.repository = repository;
        this.processor = processor;
        this.applyOutcome = applyOutcome;
        this.txTemplate = txTemplate;
        this.queueUrl = queueUrl;
        this.maxMessages = maxMessages;
        this.waitTimeSeconds = waitTimeSeconds;
    }

    @Scheduled(fixedDelayString = "${consumer.poll-interval-ms:1000}")
    public void poll() {
        var response =
                sqs.receiveMessage(
                        ReceiveMessageRequest.builder()
                                .queueUrl(queueUrl)
                                .maxNumberOfMessages(maxMessages)
                                .waitTimeSeconds(waitTimeSeconds)
                                .build());

        for (Message msg : response.messages()) {
            try {
                txTemplate.executeWithoutResult(status -> handleMessage(msg.body()));
                deleteMessage(msg);
            } catch (RuntimeException e) {
                log.error("failed to process message {}; will redeliver", msg.messageId(), e);
            }
        }
    }

    void handleMessage(String body) {
        PaymentIntentEvent event;
        try {
            event = objectMapper.readValue(body, PaymentIntentEvent.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse event payload: " + body, e);
        }

        boolean newEvent =
                processedEvents.tryMarkProcessed(
                        event.eventId(), event.aggregateId().value().toString());
        if (!newEvent) {
            log.debug("event {} already processed, skipping", event.eventId());
            return;
        }

        switch (event) {
            case PaymentIntentConfirmed c -> handleConfirmed(c);
            default -> {
                /* not an authorization trigger; nothing to do */
            }
        }
    }

    private void handleConfirmed(PaymentIntentConfirmed event) {
        var intent =
                repository
                        .findById(event.aggregateId())
                        .orElseThrow(() -> new PaymentIntentNotFoundException(event.aggregateId()));

        if (intent.state() != PaymentState.PROCESSING) {
            log.info(
                    "intent {} is in state {}; skipping authorization",
                    event.aggregateId(),
                    intent.state());
            return;
        }
        if (intent.paymentMethod() == null) {
            log.error("intent {} has no payment method; skipping", event.aggregateId());
            return;
        }

        // intent.id() (not event.eventId()) is the processor idempotency key so reconciliation
        // can call processor.lookup() with a key it can derive from the intent alone — no need
        // to recover the original event id from outbox. Safe because FAILED is terminal: each
        // intent authorizes at most once across its lifecycle.
        var authReq =
                new AuthorizationRequest(
                        event.aggregateId(),
                        intent.amount(),
                        intent.paymentMethod(),
                        intent.id().value());
        var result = processor.authorize(authReq);
        applyOutcome.execute(event.aggregateId(), result);
    }

    private void deleteMessage(Message msg) {
        sqs.deleteMessage(
                DeleteMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .receiptHandle(msg.receiptHandle())
                        .build());
    }
}
