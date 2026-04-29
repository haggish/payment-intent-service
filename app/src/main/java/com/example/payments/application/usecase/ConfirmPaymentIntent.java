package com.example.payments.application.usecase;

import com.example.payments.domain.exception.PaymentIntentNotFoundException;
import com.example.payments.domain.model.PaymentIntent;
import com.example.payments.domain.model.PaymentIntentId;
import com.example.payments.domain.model.PaymentMethod;
import com.example.payments.domain.port.OutboxRepository;
import com.example.payments.domain.port.PaymentIntentRepository;
import java.time.Clock;
import java.util.Optional;

/**
 * Optionally attaches a payment method, then transitions REQUIRES_CONFIRMATION → PROCESSING.
 *
 * <p>Authorization is async — the {@code PaymentIntentConfirmed} event drives a downstream worker
 * that calls the processor. The use case itself only mutates the aggregate.
 */
public class ConfirmPaymentIntent {

    private final PaymentIntentRepository repository;
    private final OutboxRepository outbox;
    private final Clock clock;

    public ConfirmPaymentIntent(
            PaymentIntentRepository repository, OutboxRepository outbox, Clock clock) {
        this.repository = repository;
        this.outbox = outbox;
        this.clock = clock;
    }

    public PaymentIntent execute(PaymentIntentId id, Optional<PaymentMethod> method) {
        var intent =
                repository.findById(id).orElseThrow(() -> new PaymentIntentNotFoundException(id));
        var now = clock.instant();
        method.ifPresent(m -> intent.attachPaymentMethod(m, now));
        intent.confirm(now);
        repository.save(intent);
        outbox.saveAll(intent.pullEvents());
        return intent;
    }
}
