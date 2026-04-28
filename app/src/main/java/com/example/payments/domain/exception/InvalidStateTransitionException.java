package com.example.payments.domain.exception;

import com.example.payments.domain.model.PaymentState;

public class InvalidStateTransitionException extends RuntimeException {

    public InvalidStateTransitionException(PaymentState from, PaymentState to) {
        super("invalid transition: %s -> %s".formatted(from, to));
    }

    public InvalidStateTransitionException(PaymentState from, String operation) {
        super("operation '%s' not allowed from state %s".formatted(operation, from));
    }
}
