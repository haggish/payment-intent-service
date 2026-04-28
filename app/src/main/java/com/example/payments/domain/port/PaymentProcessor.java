package com.example.payments.domain.port;

import com.example.payments.domain.model.Money;
import com.example.payments.domain.model.PaymentIntentId;
import com.example.payments.domain.model.PaymentMethod;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Port abstracting the external payment processor (Stripe, Adyen, etc.).
 *
 * <p>Three outcome types matter, not two: {@link AuthorizationResult.Authorized} and {@link
 * AuthorizationResult.Declined} are deterministic; {@link AuthorizationResult.Unknown} means we
 * don't know what happened (timeout, network error). Unknown must NEVER be retried by the calling
 * thread — that risks double-charging. The correct response is reconciliation via {@link #lookup}.
 */
public interface PaymentProcessor {

    AuthorizationResult authorize(AuthorizationRequest request);

    CaptureResult capture(CaptureRequest request);

    RefundResult refund(RefundRequest request);

    /**
     * Reconciliation endpoint. After an {@link AuthorizationResult.Unknown} outcome, callers use
     * this to discover what actually happened.
     */
    Optional<ProcessorStatus> lookup(String idempotencyKey);

    record AuthorizationRequest(
            PaymentIntentId intentId, Money amount, PaymentMethod method, UUID idempotencyKey) {}

    record CaptureRequest(
            PaymentIntentId intentId,
            String authorizationReference,
            Money amount,
            UUID idempotencyKey) {}

    record RefundRequest(
            PaymentIntentId intentId, String captureReference, Money amount, UUID idempotencyKey) {}

    sealed interface AuthorizationResult {
        record Authorized(String processorReference, Instant authorizedAt)
                implements AuthorizationResult {}

        record Declined(DeclineReason reason, String processorMessage)
                implements AuthorizationResult {}

        record Unknown(String reason) implements AuthorizationResult {}
    }

    enum DeclineReason {
        INSUFFICIENT_FUNDS,
        CARD_DECLINED,
        FRAUD_SUSPECTED,
        EXPIRED_CARD,
        OTHER
    }

    sealed interface CaptureResult {
        record Captured(String processorReference, Instant capturedAt) implements CaptureResult {}

        record Failed(String reason) implements CaptureResult {}

        record Unknown(String reason) implements CaptureResult {}
    }

    sealed interface RefundResult {
        record Refunded(String processorReference, Instant refundedAt) implements RefundResult {}

        record Failed(String reason) implements RefundResult {}

        record Unknown(String reason) implements RefundResult {}
    }

    /** Status returned by {@link #lookup}. */
    record ProcessorStatus(String state, String processorReference) {}
}
