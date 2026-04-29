package com.example.payments.application.usecase;

import com.example.payments.domain.exception.PaymentIntentNotFoundException;
import com.example.payments.domain.model.PaymentIntent;
import com.example.payments.domain.model.PaymentIntentId;
import com.example.payments.domain.port.OutboxRepository;
import com.example.payments.domain.port.PaymentIntentRepository;
import java.time.Clock;
import org.springframework.transaction.annotation.Transactional;

public class CancelPaymentIntent {

    private final PaymentIntentRepository repository;
    private final OutboxRepository outbox;
    private final Clock clock;

    public CancelPaymentIntent(
            PaymentIntentRepository repository, OutboxRepository outbox, Clock clock) {
        this.repository = repository;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public PaymentIntent execute(PaymentIntentId id, String reason) {
        var intent =
                repository.findById(id).orElseThrow(() -> new PaymentIntentNotFoundException(id));
        intent.cancel(reason, clock.instant());
        repository.save(intent);
        outbox.saveAll(intent.pullEvents());
        return intent;
    }
}
