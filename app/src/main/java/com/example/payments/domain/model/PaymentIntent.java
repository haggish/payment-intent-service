package com.example.payments.domain.model;

import com.example.payments.domain.exception.InvalidStateTransitionException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Aggregate root for a payment intent.
 *
 * <p>Owns the state machine and all business invariants. State transitions are enforced via {@link
 * PaymentState#canTransitionTo} — invalid transitions throw {@link
 * InvalidStateTransitionException}.
 *
 * <p>Domain events accumulated during command handling are exposed via {@link #pullEvents}; the
 * application layer is expected to persist them to the outbox in the same transaction as the
 * aggregate state change.
 *
 * <p>Concurrency control is via the {@code version} field — see optimistic locking in the JDBC
 * repository.
 */
public class PaymentIntent {

    private final PaymentIntentId id;
    private final MerchantId merchantId;
    private final Money amount;
    private final IdempotencyKey idempotencyKey;
    private PaymentState state;
    private PaymentMethod paymentMethod;
    private Money capturedAmount;
    private long version;
    private final Instant createdAt;
    private Instant updatedAt;

    private final List<PaymentAttempt> attempts = new ArrayList<>();
    private final List<Capture> captures = new ArrayList<>();
    private final List<Refund> refunds = new ArrayList<>();
    private final List<Object> uncommittedEvents = new ArrayList<>();

    private PaymentIntent(
            PaymentIntentId id,
            MerchantId merchantId,
            Money amount,
            IdempotencyKey idempotencyKey,
            Instant now) {
        this.id = id;
        this.merchantId = merchantId;
        this.amount = amount;
        this.idempotencyKey = idempotencyKey;
        this.state = PaymentState.REQUIRES_PAYMENT_METHOD;
        this.capturedAmount = Money.of(0, amount.currency().getCurrencyCode());
        this.version = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Factory: creates a new intent in REQUIRES_PAYMENT_METHOD. */
    public static PaymentIntent create(
            MerchantId merchantId, Money amount, IdempotencyKey idempotencyKey, Instant now) {
        // TODO: enforce currency-specific minimum amount (e.g., €0.50 for EUR)
        var intent =
                new PaymentIntent(
                        PaymentIntentId.generate(), merchantId, amount, idempotencyKey, now);
        // TODO: emit PaymentIntentCreated event
        return intent;
    }

    public void attachPaymentMethod(PaymentMethod method, Instant now) {
        transitionTo(PaymentState.REQUIRES_CONFIRMATION, now);
        this.paymentMethod = method;
        // TODO: emit PaymentMethodAttached event
    }

    public void confirm(Instant now) {
        transitionTo(PaymentState.PROCESSING, now);
        // TODO: emit PaymentIntentConfirmed event (consumed by async authorization worker)
    }

    public void markAuthorized(String processorReference, Instant now) {
        transitionTo(PaymentState.AUTHORIZED, now);
        // TODO: append PaymentAttempt with processorReference, emit PaymentIntentAuthorized
    }

    public void markFailed(String reason, Instant now) {
        transitionTo(PaymentState.FAILED, now);
        // TODO: emit PaymentIntentFailed
    }

    public void markRequiresAction(String challengeDetails, Instant now) {
        transitionTo(PaymentState.REQUIRES_ACTION, now);
        // TODO: emit PaymentIntentActionRequired
    }

    public void capture(Money captureAmount, Instant now) {
        if (state != PaymentState.AUTHORIZED && state != PaymentState.PARTIALLY_CAPTURED) {
            throw new InvalidStateTransitionException(state, "capture");
        }
        var newCaptured = capturedAmount.add(captureAmount);
        if (newCaptured.isGreaterThan(amount)) {
            throw new IllegalStateException("capture would exceed authorized amount");
        }
        this.capturedAmount = newCaptured;
        captures.add(new Capture(captureAmount, now));
        var nextState =
                newCaptured.equals(amount)
                        ? PaymentState.CAPTURED
                        : PaymentState.PARTIALLY_CAPTURED;
        transitionTo(nextState, now);
        // TODO: emit PaymentIntentCaptured / PaymentIntentPartiallyCaptured
    }

    public void cancel(String reason, Instant now) {
        if (state.isTerminal()) {
            throw new InvalidStateTransitionException(state, "cancel");
        }
        transitionTo(PaymentState.CANCELED, now);
        // TODO: emit PaymentIntentCanceled
    }

    public void refund(int captureIndex, Money refundAmount, Instant now) {
        if (captureIndex < 0 || captureIndex >= captures.size()) {
            throw new IllegalArgumentException("no such capture");
        }
        var capture = captures.get(captureIndex);
        // TODO: enforce: total refunds against this capture <= capture amount
        refunds.add(new Refund(captureIndex, refundAmount, now));
        // Note: refund does NOT change intent state; CAPTURED stays CAPTURED
        this.updatedAt = now;
        // TODO: emit PaymentRefunded
    }

    private void transitionTo(PaymentState target, Instant now) {
        if (!state.canTransitionTo(target)) {
            throw new InvalidStateTransitionException(state, target);
        }
        this.state = target;
        this.updatedAt = now;
        this.version++;
    }

    public List<Object> pullEvents() {
        var copy = List.copyOf(uncommittedEvents);
        uncommittedEvents.clear();
        return copy;
    }

    // ---------- Accessors ----------

    public PaymentIntentId id() {
        return id;
    }

    public MerchantId merchantId() {
        return merchantId;
    }

    public Money amount() {
        return amount;
    }

    public Money capturedAmount() {
        return capturedAmount;
    }

    public PaymentState state() {
        return state;
    }

    public IdempotencyKey idempotencyKey() {
        return idempotencyKey;
    }

    public PaymentMethod paymentMethod() {
        return paymentMethod;
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public List<Capture> captures() {
        return Collections.unmodifiableList(captures);
    }

    public List<Refund> refunds() {
        return Collections.unmodifiableList(refunds);
    }

    public List<PaymentAttempt> attempts() {
        return Collections.unmodifiableList(attempts);
    }
}
