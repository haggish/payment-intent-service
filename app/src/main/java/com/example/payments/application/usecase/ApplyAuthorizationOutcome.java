package com.example.payments.application.usecase;

import com.example.payments.domain.exception.PaymentIntentNotFoundException;
import com.example.payments.domain.model.PaymentIntent;
import com.example.payments.domain.model.PaymentIntentId;
import com.example.payments.domain.port.OutboxRepository;
import com.example.payments.domain.port.PaymentIntentRepository;
import com.example.payments.domain.port.PaymentProcessor.AuthorizationResult;
import java.time.Clock;

/**
 * Applies an authorization outcome to an intent currently awaiting authorization.
 *
 * <ul>
 *   <li>{@link AuthorizationResult.Authorized} → markAuthorized
 *   <li>{@link AuthorizationResult.Declined} → markFailed
 *   <li>{@link AuthorizationResult.Unknown} → no-op (reconciliation will retry via {@code lookup})
 * </ul>
 *
 * <p>If the intent is no longer in a state that accepts the transition, the aggregate throws {@link
 * com.example.payments.domain.exception.InvalidStateTransitionException}. The caller decides what
 * to do — consumer rolls back its transaction and lets SQS redeliver; reconciliation logs and gives
 * up.
 */
public class ApplyAuthorizationOutcome {

    private final PaymentIntentRepository repository;
    private final OutboxRepository outbox;
    private final Clock clock;

    public ApplyAuthorizationOutcome(
            PaymentIntentRepository repository, OutboxRepository outbox, Clock clock) {
        this.repository = repository;
        this.outbox = outbox;
        this.clock = clock;
    }

    public PaymentIntent execute(PaymentIntentId id, AuthorizationResult result) {
        var intent =
                repository.findById(id).orElseThrow(() -> new PaymentIntentNotFoundException(id));
        var now = clock.instant();
        switch (result) {
            case AuthorizationResult.Authorized a ->
                    intent.markAuthorized(a.processorReference(), now);
            case AuthorizationResult.Declined d ->
                    intent.markFailed(d.reason().name() + ": " + d.processorMessage(), now);
            case AuthorizationResult.Unknown u -> {
                return intent;
            }
        }
        repository.save(intent);
        outbox.saveAll(intent.pullEvents());
        return intent;
    }
}
