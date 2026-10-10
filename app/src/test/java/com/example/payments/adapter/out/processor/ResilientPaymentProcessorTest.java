package com.example.payments.adapter.out.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.payments.domain.model.Money;
import com.example.payments.domain.model.PaymentIntentId;
import com.example.payments.domain.model.PaymentMethod;
import com.example.payments.domain.model.PaymentMethod.PaymentMethodType;
import com.example.payments.domain.port.PaymentProcessor;
import com.example.payments.domain.port.PaymentProcessor.AuthorizationRequest;
import com.example.payments.domain.port.PaymentProcessor.AuthorizationResult;
import com.example.payments.domain.port.PaymentProcessor.DeclineReason;
import com.example.payments.domain.port.PaymentProcessor.ProcessorStatus;
import com.example.payments.domain.port.PaymentProcessor.ProcessorUnavailableException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The retry policy is a payment-safety property: retrying anything other than a definite
 * non-attempt risks charging twice. Each case below pins one rule.
 */
class ResilientPaymentProcessorTest {

    private static final Duration TIMEOUT = Duration.ofMillis(200);

    private static final AuthorizationRequest REQUEST =
            new AuthorizationRequest(
                    PaymentIntentId.of(UUID.randomUUID().toString()),
                    Money.of(1000, "EUR"),
                    new PaymentMethod("tok_visa", PaymentMethodType.CARD),
                    UUID.randomUUID());

    private ScriptedProcessor delegate;
    private CircuitBreaker circuitBreaker;
    private ResilientPaymentProcessor processor;

    @BeforeEach
    void setUp() {
        delegate = new ScriptedProcessor();
        circuitBreaker =
                CircuitBreaker.of(
                        "test",
                        CircuitBreakerConfig.custom()
                                .slidingWindowSize(3)
                                .minimumNumberOfCalls(3)
                                .failureRateThreshold(50)
                                .waitDurationInOpenState(Duration.ofMinutes(1))
                                .build());
        processor =
                new ResilientPaymentProcessor(
                        delegate,
                        Bulkhead.ofDefaults("test"),
                        ResilientPaymentProcessor.safeRetry(
                                RetryConfig.custom()
                                        .maxAttempts(3)
                                        .waitDuration(Duration.ofMillis(5))
                                        // Even if configuration asked to retry everything, the
                                        // wrapper must narrow it to definite non-attempts.
                                        .retryExceptions(Throwable.class)
                                        .build()),
                        circuitBreaker,
                        TimeLimiter.of(
                                TimeLimiterConfig.custom().timeoutDuration(TIMEOUT).build()));
    }

    @Test
    void retries_a_definite_non_attempt_until_it_succeeds() {
        delegate.script(unavailable(), unavailable(), () -> authorized());

        assertThat(processor.authorize(REQUEST)).isInstanceOf(AuthorizationResult.Authorized.class);
        assertThat(delegate.authorizeCalls()).isEqualTo(3);
    }

    @Test
    void gives_up_after_max_attempts_and_lets_the_message_redeliver() {
        delegate.script(unavailable(), unavailable(), unavailable(), unavailable());

        assertThatThrownBy(() -> processor.authorize(REQUEST))
                .isInstanceOf(ProcessorUnavailableException.class);
        assertThat(delegate.authorizeCalls()).isEqualTo(3);
    }

    @Test
    void never_retries_unknown() {
        delegate.script(
                () -> new AuthorizationResult.Unknown("processor timeout"), () -> authorized());

        assertThat(processor.authorize(REQUEST)).isInstanceOf(AuthorizationResult.Unknown.class);
        assertThat(delegate.authorizeCalls()).isEqualTo(1);
    }

    @Test
    void never_retries_a_decline() {
        delegate.script(
                () -> new AuthorizationResult.Declined(DeclineReason.CARD_DECLINED, "no"),
                () -> authorized());

        assertThat(processor.authorize(REQUEST)).isInstanceOf(AuthorizationResult.Declined.class);
        assertThat(delegate.authorizeCalls()).isEqualTo(1);
    }

    @Test
    void never_retries_an_unexpected_error() {
        delegate.script(
                () -> {
                    throw new IllegalArgumentException("bad request");
                },
                () -> authorized());

        assertThatThrownBy(() -> processor.authorize(REQUEST))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(delegate.authorizeCalls()).isEqualTo(1);
    }

    @Test
    void a_hang_becomes_unknown_within_the_time_limit_and_is_not_retried() {
        delegate.script(
                () -> {
                    sleep(Duration.ofSeconds(2));
                    return authorized();
                },
                () -> authorized());

        long start = System.nanoTime();
        var result = processor.authorize(REQUEST);
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(result).isInstanceOf(AuthorizationResult.Unknown.class);
        assertThat(took).isLessThan(Duration.ofSeconds(1));
        assertThat(delegate.authorizeCalls()).isEqualTo(1);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void an_open_circuit_fails_fast_without_calling_the_processor() {
        // One exhausted authorization = 3 recorded failures, enough to open the breaker.
        delegate.script(unavailable(), unavailable(), unavailable());
        assertThatThrownBy(() -> processor.authorize(REQUEST))
                .isInstanceOf(ProcessorUnavailableException.class);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int callsBefore = delegate.authorizeCalls();

        assertThatThrownBy(() -> processor.authorize(REQUEST))
                .isInstanceOf(CallNotPermittedException.class);
        assertThat(delegate.authorizeCalls()).isEqualTo(callsBefore);
    }

    @Test
    void lookup_is_retried_on_a_definite_non_attempt() {
        var status = new ProcessorStatus("AUTHORIZED", "pi_1");
        AtomicInteger calls = new AtomicInteger();
        delegate.onLookup(
                () -> {
                    if (calls.incrementAndGet() == 1) {
                        throw new ProcessorUnavailableException("503");
                    }
                    return Optional.of(status);
                });

        assertThat(processor.lookup("key")).contains(status);
        assertThat(calls).hasValue(2);
    }

    private static Supplier<AuthorizationResult> unavailable() {
        return () -> {
            throw new ProcessorUnavailableException("simulated 5xx");
        };
    }

    private static AuthorizationResult authorized() {
        return new AuthorizationResult.Authorized("pi_ok", Instant.now());
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Fake processor that plays back one scripted behaviour per authorize call. */
    private static final class ScriptedProcessor implements PaymentProcessor {
        private final Deque<Supplier<AuthorizationResult>> script = new ArrayDeque<>();
        private final AtomicInteger authorizeCalls = new AtomicInteger();
        private Supplier<Optional<ProcessorStatus>> lookup = Optional::empty;

        @SafeVarargs
        final void script(Supplier<AuthorizationResult>... steps) {
            script.clear();
            for (var step : steps) {
                script.add(step);
            }
        }

        void onLookup(Supplier<Optional<ProcessorStatus>> behaviour) {
            this.lookup = behaviour;
        }

        int authorizeCalls() {
            return authorizeCalls.get();
        }

        @Override
        public AuthorizationResult authorize(AuthorizationRequest request) {
            authorizeCalls.incrementAndGet();
            return script.removeFirst().get();
        }

        @Override
        public Optional<ProcessorStatus> lookup(String idempotencyKey) {
            return lookup.get();
        }

        @Override
        public CaptureResult capture(CaptureRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RefundResult refund(RefundRequest request) {
            throw new UnsupportedOperationException();
        }
    }
}
