package com.example.payments.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.payments.domain.model.IdempotencyKey;
import com.example.payments.domain.model.MerchantId;
import com.example.payments.domain.model.Money;
import com.example.payments.domain.model.PaymentIntentEvent.PaymentIntentCreated;
import com.example.payments.domain.model.PaymentState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CreatePaymentIntentTest {

    private final InMemoryPaymentIntentRepository repo = new InMemoryPaymentIntentRepository();
    private final InMemoryOutboxRepository outbox = new InMemoryOutboxRepository();
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final CreatePaymentIntent useCase = new CreatePaymentIntent(repo, outbox, clock);

    @Test
    void persists_intent_and_drains_creation_event_to_outbox() {
        var intent =
                useCase.execute(
                        new MerchantId("acme"),
                        Money.of(1000, "EUR"),
                        new IdempotencyKey("test-key-1234"));

        assertThat(intent.state()).isEqualTo(PaymentState.REQUIRES_PAYMENT_METHOD);
        assertThat(repo.findById(intent.id())).isPresent();
        assertThat(outbox.saved).hasOnlyElementsOfType(PaymentIntentCreated.class).hasSize(1);
        // pullEvents drained the buffer when use case ran
        assertThat(intent.pullEvents()).isEmpty();
    }
}
