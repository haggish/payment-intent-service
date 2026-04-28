package com.example.payments.domain.model;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The state machine for a {@link PaymentIntent}.
 *
 * <p>States are arranged so that {@link #CAPTURED} and {@link #PARTIALLY_CAPTURED} are not strictly
 * terminal — refunds against captures are modeled as child entities and do not transition the
 * intent state.
 *
 * <p>The transition table is the single source of truth for which moves are legal.
 */
public enum PaymentState {
    REQUIRES_PAYMENT_METHOD,
    REQUIRES_CONFIRMATION,
    PROCESSING,
    REQUIRES_ACTION,
    AUTHORIZED,
    CAPTURED,
    PARTIALLY_CAPTURED,
    CANCELED,
    FAILED;

    private static final Map<PaymentState, Set<PaymentState>> TRANSITIONS =
            Map.of(
                    REQUIRES_PAYMENT_METHOD, EnumSet.of(REQUIRES_CONFIRMATION, CANCELED),
                    REQUIRES_CONFIRMATION, EnumSet.of(PROCESSING, CANCELED),
                    PROCESSING, EnumSet.of(AUTHORIZED, REQUIRES_ACTION, FAILED),
                    REQUIRES_ACTION, EnumSet.of(PROCESSING, CANCELED, FAILED),
                    AUTHORIZED, EnumSet.of(CAPTURED, PARTIALLY_CAPTURED, CANCELED),
                    PARTIALLY_CAPTURED, EnumSet.of(CAPTURED, PARTIALLY_CAPTURED),
                    CAPTURED, EnumSet.noneOf(PaymentState.class),
                    CANCELED, EnumSet.noneOf(PaymentState.class),
                    FAILED, EnumSet.noneOf(PaymentState.class));

    public boolean canTransitionTo(PaymentState target) {
        return TRANSITIONS.get(this).contains(target);
    }

    public boolean isTerminal() {
        return this == CAPTURED || this == CANCELED || this == FAILED;
    }
}
