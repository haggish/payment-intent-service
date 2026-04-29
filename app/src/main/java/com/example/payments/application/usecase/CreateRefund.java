package com.example.payments.application.usecase;

import com.example.payments.domain.exception.PaymentIntentNotFoundException;
import com.example.payments.domain.model.Money;
import com.example.payments.domain.model.PaymentIntent;
import com.example.payments.domain.model.PaymentIntentId;
import com.example.payments.domain.port.OutboxRepository;
import com.example.payments.domain.port.PaymentIntentRepository;
import java.time.Clock;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records a refund as a child of the indicated capture. Refunds do not change the intent's state —
 * a CAPTURED intent stays CAPTURED. The aggregate enforces the cumulative per-capture cap.
 */
public class CreateRefund {

    private final PaymentIntentRepository repository;
    private final OutboxRepository outbox;
    private final Clock clock;

    public CreateRefund(PaymentIntentRepository repository, OutboxRepository outbox, Clock clock) {
        this.repository = repository;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public PaymentIntent execute(PaymentIntentId id, int captureIndex, Money refundAmount) {
        var intent =
                repository.findById(id).orElseThrow(() -> new PaymentIntentNotFoundException(id));
        intent.refund(captureIndex, refundAmount, clock.instant());
        repository.save(intent);
        outbox.saveAll(intent.pullEvents());
        return intent;
    }
}
