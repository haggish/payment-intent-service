package com.example.payments.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.payments.domain.exception.PaymentIntentNotFoundException;
import com.example.payments.domain.model.IdempotencyKey;
import com.example.payments.domain.model.MerchantId;
import com.example.payments.domain.model.Money;
import com.example.payments.domain.model.PaymentIntent;
import com.example.payments.domain.model.PaymentIntentEvent.PaymentIntentCanceled;
import com.example.payments.domain.model.PaymentIntentId;
import com.example.payments.domain.model.PaymentState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CancelPaymentIntentTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final InMemoryPaymentIntentRepository repo = new InMemoryPaymentIntentRepository();
    private final InMemoryOutboxRepository outbox = new InMemoryOutboxRepository();
    private final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
    private final CancelPaymentIntent useCase = new CancelPaymentIntent(repo, outbox, clock);

    private PaymentIntent seedFresh() {
        var intent =
                PaymentIntent.create(
                        new MerchantId("acme"),
                        Money.of(1000, "EUR"),
                        new IdempotencyKey("test-key-1234"),
                        T0);
        intent.pullEvents();
        repo.save(intent);
        return intent;
    }

    @Test
    void cancel_transitions_to_canceled_and_emits_event() {
        var intent = seedFresh();
        var result = useCase.execute(intent.id(), "user-requested");
        assertThat(result.state()).isEqualTo(PaymentState.CANCELED);
        assertThat(outbox.saved).hasOnlyElementsOfType(PaymentIntentCanceled.class);
    }

    @Test
    void unknown_intent_throws_not_found() {
        var unknown = new PaymentIntentId(UUID.randomUUID());
        assertThatThrownBy(() -> useCase.execute(unknown, "any-reason"))
                .isInstanceOf(PaymentIntentNotFoundException.class);
    }
}
