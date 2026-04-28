package com.example.payments.application.usecase;

import com.example.payments.domain.model.IdempotencyKey;
import com.example.payments.domain.model.MerchantId;
import com.example.payments.domain.model.Money;
import com.example.payments.domain.model.PaymentIntent;
import com.example.payments.domain.port.OutboxRepository;
import com.example.payments.domain.port.PaymentIntentRepository;
import java.time.Clock;

public class CreatePaymentIntent {

    private final PaymentIntentRepository repository;
    private final OutboxRepository outbox;
    private final Clock clock;

    public CreatePaymentIntent(
            PaymentIntentRepository repository, OutboxRepository outbox, Clock clock) {
        this.repository = repository;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Creates a payment intent and persists it together with its emitted events to the outbox in
     * one transaction.
     *
     * <p>TODO: wrap in @Transactional in the Spring-aware caller.
     */
    public PaymentIntent execute(MerchantId merchantId, Money amount, IdempotencyKey key) {
        var intent = PaymentIntent.create(merchantId, amount, key, clock.instant());
        repository.save(intent);
        // outbox.saveAll(intent.pullEvents());
        // TODO: type-safe casting between Object and PaymentIntentEvent
        return intent;
    }
}
