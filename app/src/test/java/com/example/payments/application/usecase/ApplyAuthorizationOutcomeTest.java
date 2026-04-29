package com.example.payments.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.payments.domain.exception.InvalidStateTransitionException;
import com.example.payments.domain.model.IdempotencyKey;
import com.example.payments.domain.model.MerchantId;
import com.example.payments.domain.model.Money;
import com.example.payments.domain.model.PaymentIntent;
import com.example.payments.domain.model.PaymentIntentEvent.PaymentIntentAuthorized;
import com.example.payments.domain.model.PaymentIntentEvent.PaymentIntentFailed;
import com.example.payments.domain.model.PaymentMethod;
import com.example.payments.domain.model.PaymentMethod.PaymentMethodType;
import com.example.payments.domain.model.PaymentState;
import com.example.payments.domain.port.PaymentProcessor.AuthorizationResult;
import com.example.payments.domain.port.PaymentProcessor.DeclineReason;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApplyAuthorizationOutcomeTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private InMemoryPaymentIntentRepository intents;
    private InMemoryOutboxRepository outbox;
    private ApplyAuthorizationOutcome useCase;

    @BeforeEach
    void setUp() {
        intents = new InMemoryPaymentIntentRepository();
        outbox = new InMemoryOutboxRepository();
        useCase = new ApplyAuthorizationOutcome(intents, outbox, Clock.fixed(T0, ZoneOffset.UTC));
    }

    private PaymentIntent processingIntent() {
        var intent =
                PaymentIntent.create(
                        new MerchantId("acme"),
                        Money.of(1000, "EUR"),
                        new IdempotencyKey("k-" + System.nanoTime()),
                        T0);
        intent.attachPaymentMethod(new PaymentMethod("tok_visa", PaymentMethodType.CARD), T0);
        intent.confirm(T0);
        intent.pullEvents();
        intents.save(intent);
        return intent;
    }

    @Test
    void authorized_marks_intent_authorized_and_emits_event() {
        var intent = processingIntent();
        useCase.execute(intent.id(), new AuthorizationResult.Authorized("pi_stub_123", T0));

        assertThat(intent.state()).isEqualTo(PaymentState.AUTHORIZED);
        assertThat(outbox.saved).hasAtLeastOneElementOfType(PaymentIntentAuthorized.class);
    }

    @Test
    void declined_marks_intent_failed_and_emits_event() {
        var intent = processingIntent();
        useCase.execute(
                intent.id(),
                new AuthorizationResult.Declined(DeclineReason.CARD_DECLINED, "no funds"));

        assertThat(intent.state()).isEqualTo(PaymentState.FAILED);
        assertThat(outbox.saved).hasAtLeastOneElementOfType(PaymentIntentFailed.class);
    }

    @Test
    void unknown_does_not_change_state_and_emits_no_events() {
        var intent = processingIntent();
        int saveCountBefore = intents.saveCount;
        int outboxBefore = outbox.saved.size();

        useCase.execute(intent.id(), new AuthorizationResult.Unknown("timeout"));

        assertThat(intent.state()).isEqualTo(PaymentState.PROCESSING);
        assertThat(intents.saveCount).isEqualTo(saveCountBefore);
        assertThat(outbox.saved.size()).isEqualTo(outboxBefore);
    }

    @Test
    void second_authorized_after_terminal_state_throws() {
        var intent = processingIntent();
        useCase.execute(intent.id(), new AuthorizationResult.Authorized("pi_stub_1", T0));

        assertThatThrownBy(
                        () ->
                                useCase.execute(
                                        intent.id(),
                                        new AuthorizationResult.Authorized("pi_stub_2", T0)))
                .isInstanceOf(InvalidStateTransitionException.class);
    }
}
