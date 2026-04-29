package com.example.payments.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.payments.domain.exception.InvalidStateTransitionException;
import com.example.payments.domain.model.PaymentIntentEvent.PaymentIntentAuthorized;
import com.example.payments.domain.model.PaymentIntentEvent.PaymentIntentCanceled;
import com.example.payments.domain.model.PaymentIntentEvent.PaymentIntentCaptured;
import com.example.payments.domain.model.PaymentIntentEvent.PaymentIntentConfirmed;
import com.example.payments.domain.model.PaymentIntentEvent.PaymentIntentCreated;
import com.example.payments.domain.model.PaymentIntentEvent.PaymentIntentPartiallyCaptured;
import com.example.payments.domain.model.PaymentIntentEvent.PaymentMethodAttached;
import com.example.payments.domain.model.PaymentIntentEvent.PaymentRefunded;
import com.example.payments.domain.model.PaymentMethod.PaymentMethodType;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PaymentIntentInvariantTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final MerchantId MERCHANT = new MerchantId("acme");
    private static final IdempotencyKey KEY = new IdempotencyKey("test-key-1234");
    private static final PaymentMethod CARD = new PaymentMethod("tok_visa", PaymentMethodType.CARD);

    private static PaymentIntent fresh(long minorUnits, String currency) {
        return PaymentIntent.create(MERCHANT, Money.of(minorUnits, currency), KEY, T0);
    }

    private static PaymentIntent authorized(long minorUnits, String currency) {
        var intent = fresh(minorUnits, currency);
        intent.attachPaymentMethod(CARD, T0);
        intent.confirm(T0);
        intent.markAuthorized("proc-ref-1", T0);
        return intent;
    }

    // ---------- Creation ----------

    @Test
    void create_starts_in_requires_payment_method_with_version_zero() {
        var intent = fresh(1000, "EUR");
        assertThat(intent.state()).isEqualTo(PaymentState.REQUIRES_PAYMENT_METHOD);
        assertThat(intent.version()).isZero();
        assertThat(intent.capturedAmount().isZero()).isTrue();
    }

    @Test
    void create_emits_payment_intent_created_event() {
        var intent = fresh(1000, "EUR");
        var events = intent.pullEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0))
                .asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.type(
                                PaymentIntentCreated.class))
                .extracting(PaymentIntentCreated::aggregateId)
                .isEqualTo(intent.id());
    }

    @Test
    void create_rejects_zero_amount() {
        assertThatThrownBy(() -> fresh(0, "EUR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
    }

    @Test
    void pull_events_drains_the_buffer() {
        var intent = fresh(1000, "EUR");
        assertThat(intent.pullEvents()).hasSize(1);
        assertThat(intent.pullEvents()).isEmpty();
    }

    // ---------- Happy path event sequence ----------

    @Test
    void full_lifecycle_emits_events_in_order() {
        var intent = fresh(1000, "EUR");
        intent.attachPaymentMethod(CARD, T0);
        intent.confirm(T0);
        intent.markAuthorized("proc-ref", T0);
        intent.capture(Money.of(1000, "EUR"), T0);

        var events = intent.pullEvents();
        assertThat(events).hasSize(5);
        assertThat(events)
                .hasOnlyElementsOfTypes(
                        PaymentIntentCreated.class,
                        PaymentMethodAttached.class,
                        PaymentIntentConfirmed.class,
                        PaymentIntentAuthorized.class,
                        PaymentIntentCaptured.class);
    }

    @Test
    void mark_authorized_appends_a_payment_attempt() {
        var intent = authorized(1000, "EUR");
        assertThat(intent.attempts()).hasSize(1);
        assertThat(intent.attempts().get(0).processorReference()).isEqualTo("proc-ref-1");
        assertThat(intent.attempts().get(0).outcome())
                .isEqualTo(PaymentAttempt.AttemptOutcome.AUTHORIZED);
    }

    @Test
    void each_command_increments_version() {
        var intent = fresh(1000, "EUR");
        long v0 = intent.version();
        intent.attachPaymentMethod(CARD, T0);
        long v1 = intent.version();
        intent.confirm(T0);
        long v2 = intent.version();
        intent.markAuthorized("proc-ref", T0);
        long v3 = intent.version();
        assertThat(v1).isGreaterThan(v0);
        assertThat(v2).isGreaterThan(v1);
        assertThat(v3).isGreaterThan(v2);
    }

    // ---------- Capture invariants ----------

    @Test
    void capture_rejects_amount_exceeding_authorized() {
        var intent = authorized(1000, "EUR");
        assertThatThrownBy(() -> intent.capture(Money.of(1001, "EUR"), T0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceed authorized");
    }

    @Test
    void partial_capture_then_full_capture_reaches_captured_state() {
        var intent = authorized(1000, "EUR");
        intent.capture(Money.of(400, "EUR"), T0);
        assertThat(intent.state()).isEqualTo(PaymentState.PARTIALLY_CAPTURED);
        intent.capture(Money.of(600, "EUR"), T0);
        assertThat(intent.state()).isEqualTo(PaymentState.CAPTURED);
        assertThat(intent.captures()).hasSize(2);
    }

    @Test
    void partial_capture_emits_partially_captured_event() {
        var intent = authorized(1000, "EUR");
        intent.pullEvents(); // drain creation/auth events
        intent.capture(Money.of(400, "EUR"), T0);
        var events = intent.pullEvents();
        assertThat(events).hasOnlyElementsOfType(PaymentIntentPartiallyCaptured.class);
    }

    @Test
    void capture_only_allowed_from_authorized_or_partially_captured() {
        var intent = fresh(1000, "EUR");
        assertThatThrownBy(() -> intent.capture(Money.of(100, "EUR"), T0))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    // ---------- Refund invariants ----------

    @Test
    void refund_within_capture_amount_is_allowed_and_does_not_change_state() {
        var intent = authorized(1000, "EUR");
        intent.capture(Money.of(1000, "EUR"), T0);
        intent.refund(0, Money.of(300, "EUR"), T0);
        assertThat(intent.state()).isEqualTo(PaymentState.CAPTURED);
        assertThat(intent.refunds()).hasSize(1);
    }

    @Test
    void refund_emits_payment_refunded_event() {
        var intent = authorized(1000, "EUR");
        intent.capture(Money.of(1000, "EUR"), T0);
        intent.pullEvents();
        intent.refund(0, Money.of(300, "EUR"), T0);
        assertThat(intent.pullEvents()).hasOnlyElementsOfType(PaymentRefunded.class);
    }

    @Test
    void refund_rejects_amount_exceeding_capture() {
        var intent = authorized(1000, "EUR");
        intent.capture(Money.of(1000, "EUR"), T0);
        assertThatThrownBy(() -> intent.refund(0, Money.of(1001, "EUR"), T0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceed capture amount");
    }

    @Test
    void cumulative_refunds_against_a_capture_cannot_exceed_capture_amount() {
        var intent = authorized(1000, "EUR");
        intent.capture(Money.of(1000, "EUR"), T0);
        intent.refund(0, Money.of(700, "EUR"), T0);
        assertThatThrownBy(() -> intent.refund(0, Money.of(400, "EUR"), T0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceed capture amount");
        // The 700 refund stuck; second one rolled back
        assertThat(intent.refunds()).hasSize(1);
    }

    @Test
    void refund_against_unknown_capture_index_throws() {
        var intent = authorized(1000, "EUR");
        intent.capture(Money.of(1000, "EUR"), T0);
        assertThatThrownBy(() -> intent.refund(5, Money.of(100, "EUR"), T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- Cancel invariants ----------

    @Test
    void cancel_from_non_terminal_transitions_to_canceled() {
        var intent = fresh(1000, "EUR");
        intent.cancel("user-requested", T0);
        assertThat(intent.state()).isEqualTo(PaymentState.CANCELED);
    }

    @Test
    void cancel_emits_payment_intent_canceled_event() {
        var intent = fresh(1000, "EUR");
        intent.pullEvents();
        intent.cancel("user-requested", T0);
        assertThat(intent.pullEvents()).hasOnlyElementsOfType(PaymentIntentCanceled.class);
    }

    @Test
    void cancel_from_captured_throws() {
        var intent = authorized(1000, "EUR");
        intent.capture(Money.of(1000, "EUR"), T0);
        assertThatThrownBy(() -> intent.cancel("too-late", T0))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void cancel_from_canceled_throws() {
        var intent = fresh(1000, "EUR");
        intent.cancel("first", T0);
        assertThatThrownBy(() -> intent.cancel("again", T0))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void cancel_from_failed_throws() {
        var intent = fresh(1000, "EUR");
        intent.attachPaymentMethod(CARD, T0);
        intent.confirm(T0);
        intent.markFailed("processor-error", T0);
        assertThatThrownBy(() -> intent.cancel("too-late", T0))
                .isInstanceOf(InvalidStateTransitionException.class);
    }
}
