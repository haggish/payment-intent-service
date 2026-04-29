package com.example.payments.domain.model;

import com.example.payments.domain.exception.InvalidStateTransitionException;
import com.example.payments.domain.model.PaymentAttempt.AttemptOutcome;
import com.example.payments.domain.model.PaymentIntentEvent.PaymentIntentActionRequired;
import com.example.payments.domain.model.PaymentIntentEvent.PaymentIntentAuthorized;
import com.example.payments.domain.model.PaymentIntentEvent.PaymentIntentCanceled;
import com.example.payments.domain.model.PaymentIntentEvent.PaymentIntentCaptured;
import com.example.payments.domain.model.PaymentIntentEvent.PaymentIntentConfirmed;
import com.example.payments.domain.model.PaymentIntentEvent.PaymentIntentCreated;
import com.example.payments.domain.model.PaymentIntentEvent.PaymentIntentFailed;
import com.example.payments.domain.model.PaymentIntentEvent.PaymentIntentPartiallyCaptured;
import com.example.payments.domain.model.PaymentIntentEvent.PaymentMethodAttached;
import com.example.payments.domain.model.PaymentIntentEvent.PaymentRefunded;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

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
    private long versionAtLoad;
    private boolean isHydrated;
    private final Instant createdAt;
    private Instant updatedAt;

    private final List<PaymentAttempt> attempts = new ArrayList<>();
    private final List<Capture> captures = new ArrayList<>();
    private final List<Refund> refunds = new ArrayList<>();
    private final List<PaymentIntentEvent> uncommittedEvents = new ArrayList<>();

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
        if (amount.isZero()) {
            throw new IllegalArgumentException("amount must be greater than zero");
        }
        var intent =
                new PaymentIntent(
                        PaymentIntentId.generate(), merchantId, amount, idempotencyKey, now);
        intent.uncommittedEvents.add(
                new PaymentIntentCreated(UUID.randomUUID(), intent.id, amount, now));
        return intent;
    }

    public void attachPaymentMethod(PaymentMethod method, Instant now) {
        transitionTo(PaymentState.REQUIRES_CONFIRMATION, now);
        this.paymentMethod = method;
        uncommittedEvents.add(new PaymentMethodAttached(UUID.randomUUID(), id, method, now));
    }

    public void confirm(Instant now) {
        transitionTo(PaymentState.PROCESSING, now);
        uncommittedEvents.add(new PaymentIntentConfirmed(UUID.randomUUID(), id, now));
    }

    public void markAuthorized(String processorReference, Instant now) {
        transitionTo(PaymentState.AUTHORIZED, now);
        attempts.add(new PaymentAttempt(processorReference, AttemptOutcome.AUTHORIZED, now));
        uncommittedEvents.add(
                new PaymentIntentAuthorized(UUID.randomUUID(), id, processorReference, now));
    }

    public void markFailed(String reason, Instant now) {
        transitionTo(PaymentState.FAILED, now);
        uncommittedEvents.add(new PaymentIntentFailed(UUID.randomUUID(), id, reason, now));
    }

    public void markRequiresAction(String challengeDetails, Instant now) {
        transitionTo(PaymentState.REQUIRES_ACTION, now);
        uncommittedEvents.add(
                new PaymentIntentActionRequired(UUID.randomUUID(), id, challengeDetails, now));
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
        if (nextState == PaymentState.CAPTURED) {
            uncommittedEvents.add(
                    new PaymentIntentCaptured(UUID.randomUUID(), id, captureAmount, now));
        } else {
            uncommittedEvents.add(
                    new PaymentIntentPartiallyCaptured(
                            UUID.randomUUID(), id, captureAmount, newCaptured, now));
        }
    }

    public void cancel(String reason, Instant now) {
        if (state.isTerminal()) {
            throw new InvalidStateTransitionException(state, "cancel");
        }
        transitionTo(PaymentState.CANCELED, now);
        uncommittedEvents.add(new PaymentIntentCanceled(UUID.randomUUID(), id, reason, now));
    }

    public void refund(int captureIndex, Money refundAmount, Instant now) {
        if (captureIndex < 0 || captureIndex >= captures.size()) {
            throw new IllegalArgumentException("no such capture");
        }
        var capture = captures.get(captureIndex);
        var alreadyRefunded =
                refunds.stream()
                        .filter(r -> r.captureIndex() == captureIndex)
                        .map(Refund::amount)
                        .reduce(
                                Money.of(0, capture.amount().currency().getCurrencyCode()),
                                Money::add);
        if (alreadyRefunded.add(refundAmount).isGreaterThan(capture.amount())) {
            throw new IllegalStateException("refund would exceed capture amount");
        }
        refunds.add(new Refund(captureIndex, refundAmount, now));
        // Note: refund does NOT change intent state; CAPTURED stays CAPTURED
        this.updatedAt = now;
        uncommittedEvents.add(new PaymentRefunded(UUID.randomUUID(), id, refundAmount, now));
    }

    private void transitionTo(PaymentState target, Instant now) {
        if (!state.canTransitionTo(target)) {
            throw new InvalidStateTransitionException(state, target);
        }
        this.state = target;
        this.updatedAt = now;
        this.version++;
    }

    public List<PaymentIntentEvent> pullEvents() {
        var copy = List.copyOf(uncommittedEvents);
        uncommittedEvents.clear();
        return copy;
    }

    /**
     * Reconstructs an aggregate from persistent state. Bypasses invariants and event emission —
     * persistence is not a domain action. Called only by the persistence layer.
     */
    public static PaymentIntent hydrate(
            PaymentIntentId id,
            MerchantId merchantId,
            Money amount,
            IdempotencyKey idempotencyKey,
            PaymentState state,
            PaymentMethod paymentMethod,
            Money capturedAmount,
            long version,
            Instant createdAt,
            Instant updatedAt,
            List<PaymentAttempt> attempts,
            List<Capture> captures,
            List<Refund> refunds) {
        var intent = new PaymentIntent(id, merchantId, amount, idempotencyKey, createdAt);
        intent.state = state;
        intent.paymentMethod = paymentMethod;
        intent.capturedAmount = capturedAmount;
        intent.version = version;
        intent.versionAtLoad = version;
        intent.isHydrated = true;
        intent.updatedAt = updatedAt;
        intent.attempts.addAll(attempts);
        intent.captures.addAll(captures);
        intent.refunds.addAll(refunds);
        return intent;
    }

    /**
     * Called by the persistence layer after a successful save to seal the optimistic-lock baseline.
     */
    public void markPersisted() {
        this.versionAtLoad = this.version;
        this.isHydrated = true;
    }

    public long versionAtLoad() {
        return versionAtLoad;
    }

    public boolean isHydrated() {
        return isHydrated;
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
