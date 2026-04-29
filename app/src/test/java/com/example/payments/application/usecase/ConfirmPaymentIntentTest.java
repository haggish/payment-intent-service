package com.example.payments.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.payments.domain.exception.PaymentIntentNotFoundException;
import com.example.payments.domain.model.IdempotencyKey;
import com.example.payments.domain.model.MerchantId;
import com.example.payments.domain.model.Money;
import com.example.payments.domain.model.PaymentIntent;
import com.example.payments.domain.model.PaymentIntentEvent.PaymentIntentConfirmed;
import com.example.payments.domain.model.PaymentIntentEvent.PaymentMethodAttached;
import com.example.payments.domain.model.PaymentIntentId;
import com.example.payments.domain.model.PaymentMethod;
import com.example.payments.domain.model.PaymentMethod.PaymentMethodType;
import com.example.payments.domain.model.PaymentState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConfirmPaymentIntentTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final PaymentMethod CARD = new PaymentMethod("tok_visa", PaymentMethodType.CARD);

    private final InMemoryPaymentIntentRepository repo = new InMemoryPaymentIntentRepository();
    private final InMemoryOutboxRepository outbox = new InMemoryOutboxRepository();
    private final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
    private final ConfirmPaymentIntent useCase = new ConfirmPaymentIntent(repo, outbox, clock);

    private PaymentIntent seedFreshIntent() {
        var intent =
                PaymentIntent.create(
                        new MerchantId("acme"),
                        Money.of(1000, "EUR"),
                        new IdempotencyKey("test-key-1234"),
                        T0);
        intent.pullEvents(); // simulate prior persistence draining
        repo.save(intent);
        return intent;
    }

    @Test
    void with_method_attaches_then_confirms_to_processing() {
        var intent = seedFreshIntent();
        var result = useCase.execute(intent.id(), Optional.of(CARD));
        assertThat(result.state()).isEqualTo(PaymentState.PROCESSING);
        assertThat(outbox.saved)
                .hasSize(2)
                .hasOnlyElementsOfTypes(PaymentMethodAttached.class, PaymentIntentConfirmed.class);
    }

    @Test
    void without_method_assumes_attached_already_and_just_confirms() {
        var intent = seedFreshIntent();
        intent.attachPaymentMethod(CARD, T0);
        intent.pullEvents();
        repo.save(intent);
        var result = useCase.execute(intent.id(), Optional.empty());
        assertThat(result.state()).isEqualTo(PaymentState.PROCESSING);
        assertThat(outbox.saved).hasOnlyElementsOfType(PaymentIntentConfirmed.class);
    }

    @Test
    void unknown_intent_throws_not_found() {
        var unknown = new PaymentIntentId(UUID.randomUUID());
        assertThatThrownBy(() -> useCase.execute(unknown, Optional.of(CARD)))
                .isInstanceOf(PaymentIntentNotFoundException.class);
    }
}
