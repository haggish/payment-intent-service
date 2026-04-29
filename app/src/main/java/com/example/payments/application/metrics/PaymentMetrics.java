package com.example.payments.application.metrics;

import com.example.payments.domain.model.PaymentIntentEvent;
import com.example.payments.domain.port.PaymentProcessor.DeclineReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Records per-event-type counters as the aggregate emits domain events. Counter names match the
 * metric ids referenced in {@code infrastructure/lib/observability/dashboard.ts} and {@code
 * alarms.ts}.
 *
 * <p>Counted events reflect actual state changes (the aggregate decided to emit them), not API call
 * attempts — validation rejections and idempotency replays don't show up here.
 */
public class PaymentMetrics {

    private static final Set<String> DECLINE_REASON_NAMES =
            Arrays.stream(DeclineReason.values()).map(Enum::name).collect(Collectors.toSet());

    private final Counter created;
    private final Counter confirmed;
    private final Counter authorized;
    private final Counter captured;
    private final Counter failed;
    private final Counter declined;
    private final Counter refunded;
    private final Counter canceled;
    private final Counter actionRequired;
    private final Counter methodAttached;

    public PaymentMetrics(MeterRegistry registry) {
        this.created = Counter.builder("payments.created").register(registry);
        this.confirmed = Counter.builder("payments.confirmed").register(registry);
        this.authorized = Counter.builder("payments.authorized").register(registry);
        this.captured = Counter.builder("payments.captured").register(registry);
        this.failed = Counter.builder("payments.failed").register(registry);
        this.declined = Counter.builder("payments.declined").register(registry);
        this.refunded = Counter.builder("payments.refunded").register(registry);
        this.canceled = Counter.builder("payments.canceled").register(registry);
        this.actionRequired = Counter.builder("payments.action_required").register(registry);
        this.methodAttached = Counter.builder("payments.method_attached").register(registry);
    }

    public void recordEvents(List<PaymentIntentEvent> events) {
        for (PaymentIntentEvent event : events) {
            counterFor(event).increment();
        }
    }

    private Counter counterFor(PaymentIntentEvent event) {
        return switch (event) {
            case PaymentIntentEvent.PaymentIntentCreated e -> created;
            case PaymentIntentEvent.PaymentIntentConfirmed e -> confirmed;
            case PaymentIntentEvent.PaymentIntentAuthorized e -> authorized;
            case PaymentIntentEvent.PaymentIntentCaptured e -> captured;
            case PaymentIntentEvent.PaymentIntentPartiallyCaptured e -> captured;
            case PaymentIntentEvent.PaymentIntentFailed e ->
                    isDecline(e.reason()) ? declined : failed;
            case PaymentIntentEvent.PaymentIntentCanceled e -> canceled;
            case PaymentIntentEvent.PaymentRefunded e -> refunded;
            case PaymentIntentEvent.PaymentIntentActionRequired e -> actionRequired;
            case PaymentIntentEvent.PaymentMethodAttached e -> methodAttached;
        };
    }

    /**
     * PaymentIntentFailed covers both genuine failures and processor declines.
     * ApplyAuthorizationOutcome formats the reason as {@code "<DeclineReason>: <message>"} for
     * declines (and free text otherwise) — split on that prefix to count the two separately.
     */
    private static boolean isDecline(String reason) {
        if (reason == null) return false;
        int colon = reason.indexOf(':');
        String prefix = colon == -1 ? reason : reason.substring(0, colon);
        return DECLINE_REASON_NAMES.contains(prefix);
    }
}
