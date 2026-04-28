package com.example.payments.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PaymentStateTransitionTest {

    @Test
    void requires_payment_method_can_go_to_requires_confirmation() {
        assertThat(
                        PaymentState.REQUIRES_PAYMENT_METHOD.canTransitionTo(
                                PaymentState.REQUIRES_CONFIRMATION))
                .isTrue();
    }

    @Test
    void cannot_skip_processing_step() {
        assertThat(PaymentState.REQUIRES_CONFIRMATION.canTransitionTo(PaymentState.AUTHORIZED))
                .isFalse();
    }

    @Test
    void canceled_is_terminal() {
        assertThat(PaymentState.CANCELED.isTerminal()).isTrue();
        for (PaymentState target : PaymentState.values()) {
            assertThat(PaymentState.CANCELED.canTransitionTo(target))
                    .as("transition CANCELED -> %s should be forbidden", target)
                    .isFalse();
        }
    }

    @Test
    void captured_is_terminal_for_state_machine() {
        assertThat(PaymentState.CAPTURED.isTerminal()).isTrue();
    }

    @Test
    void partially_captured_can_loop_to_itself() {
        assertThat(PaymentState.PARTIALLY_CAPTURED.canTransitionTo(PaymentState.PARTIALLY_CAPTURED))
                .isTrue();
    }

    @Test
    void authorized_can_be_canceled() {
        assertThat(PaymentState.AUTHORIZED.canTransitionTo(PaymentState.CANCELED)).isTrue();
    }

    @Test
    void failed_is_terminal() {
        assertThat(PaymentState.FAILED.isTerminal()).isTrue();
    }
}
