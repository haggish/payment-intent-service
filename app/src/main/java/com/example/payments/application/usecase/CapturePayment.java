package com.example.payments.application.usecase;

import com.example.payments.domain.exception.PaymentIntentNotFoundException;
import com.example.payments.domain.model.Money;
import com.example.payments.domain.model.PaymentIntent;
import com.example.payments.domain.model.PaymentIntentId;
import com.example.payments.domain.port.OutboxRepository;
import com.example.payments.domain.port.PaymentIntentRepository;
import java.time.Clock;

/**
 * Captures part or all of an authorized intent. The aggregate enforces the "captured ≤ authorized"
 * invariant; this use case only orchestrates load → command → save → outbox.
 */
public class CapturePayment {

    private final PaymentIntentRepository repository;
    private final OutboxRepository outbox;
    private final Clock clock;

    public CapturePayment(
            PaymentIntentRepository repository, OutboxRepository outbox, Clock clock) {
        this.repository = repository;
        this.outbox = outbox;
        this.clock = clock;
    }

    public PaymentIntent execute(PaymentIntentId id, Money captureAmount) {
        var intent =
                repository.findById(id).orElseThrow(() -> new PaymentIntentNotFoundException(id));
        intent.capture(captureAmount, clock.instant());
        repository.save(intent);
        outbox.saveAll(intent.pullEvents());
        return intent;
    }
}
