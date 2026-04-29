package com.example.payments.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.payments.domain.exception.PaymentIntentNotFoundException;
import com.example.payments.domain.model.IdempotencyKey;
import com.example.payments.domain.model.MerchantId;
import com.example.payments.domain.model.Money;
import com.example.payments.domain.model.PaymentIntent;
import com.example.payments.domain.model.PaymentIntentEvent.PaymentRefunded;
import com.example.payments.domain.model.PaymentIntentId;
import com.example.payments.domain.model.PaymentMethod;
import com.example.payments.domain.model.PaymentMethod.PaymentMethodType;
import com.example.payments.domain.model.PaymentState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CreateRefundTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final PaymentMethod CARD = new PaymentMethod("tok_visa", PaymentMethodType.CARD);

    private final InMemoryPaymentIntentRepository repo = new InMemoryPaymentIntentRepository();
    private final InMemoryOutboxRepository outbox = new InMemoryOutboxRepository();
    private final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
    private final CreateRefund useCase = new CreateRefund(repo, outbox, clock);

    private PaymentIntent seedCaptured(long minorUnits) {
        var intent =
                PaymentIntent.create(
                        new MerchantId("acme"),
                        Money.of(minorUnits, "EUR"),
                        new IdempotencyKey("test-key-1234"),
                        T0);
        intent.attachPaymentMethod(CARD, T0);
        intent.confirm(T0);
        intent.markAuthorized("proc-ref", T0);
        intent.capture(Money.of(minorUnits, "EUR"), T0);
        intent.pullEvents();
        repo.save(intent);
        return intent;
    }

    @Test
    void refund_records_child_and_emits_event_without_changing_state() {
        var intent = seedCaptured(1000);
        var result = useCase.execute(intent.id(), 0, Money.of(300, "EUR"));
        assertThat(result.state()).isEqualTo(PaymentState.CAPTURED);
        assertThat(result.refunds()).hasSize(1);
        assertThat(outbox.saved).hasOnlyElementsOfType(PaymentRefunded.class);
    }

    @Test
    void unknown_intent_throws_not_found() {
        var unknown = new PaymentIntentId(UUID.randomUUID());
        assertThatThrownBy(() -> useCase.execute(unknown, 0, Money.of(100, "EUR")))
                .isInstanceOf(PaymentIntentNotFoundException.class);
    }
}
