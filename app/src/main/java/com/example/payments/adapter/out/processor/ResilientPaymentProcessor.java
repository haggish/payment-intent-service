package com.example.payments.adapter.out.processor;

import com.example.payments.domain.port.PaymentProcessor;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Wraps the real processor adapter (the bean qualified {@value #DELEGATE}) with Resilience4j,
 * configured from the {@code paymentProcessor} instances in {@code application.yml}.
 *
 * <p>{@link #authorize} applies, outermost first:
 *
 * <ol>
 *   <li><b>Bulkhead</b> — caps concurrent calls to the processor.
 *   <li><b>Timeout → Unknown</b> — no answer within the time limit means we don't know what
 *       happened, so the result is {@link AuthorizationResult.Unknown} and reconciliation finds out
 *       via {@link #lookup}. Timeouts are never retried: the processor may have acted.
 *   <li><b>Retry</b> — only on {@link ProcessorUnavailableException}, the one signal that the
 *       request definitely wasn't processed. {@code Declined} and {@code Unknown} are results, not
 *       exceptions, so they are never retried.
 *   <li><b>Circuit breaker</b> — records 5xx errors and timeouts; while open, calls fail fast with
 *       {@code CallNotPermittedException} without reaching the processor, and SQS redelivers the
 *       message later.
 * </ol>
 *
 * <p>The retry and result-handling rules are payment-safety rules, so they are fixed here; only
 * tuning (attempts, waits, thresholds, timeout) comes from configuration.
 */
@Component
@Primary
public class ResilientPaymentProcessor implements PaymentProcessor {

    /** Qualifier for the adapter that actually talks to the processor. */
    public static final String DELEGATE = "processorClient";

    static final String INSTANCE = "paymentProcessor";

    private final PaymentProcessor delegate;
    private final Bulkhead bulkhead;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final TimeLimiter timeLimiter;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    public ResilientPaymentProcessor(
            @Qualifier(DELEGATE) PaymentProcessor delegate,
            BulkheadRegistry bulkheads,
            RetryRegistry retries,
            CircuitBreakerRegistry circuitBreakers,
            TimeLimiterRegistry timeLimiters) {
        this(
                delegate,
                bulkheads.bulkhead(INSTANCE),
                registered(retries, safeRetry(retries.retry(INSTANCE).getRetryConfig())),
                circuitBreakers.circuitBreaker(INSTANCE),
                timeLimiters.timeLimiter(INSTANCE));
    }

    /** Swap the safe retry into the registry so metrics and actuator report the one in use. */
    private static Retry registered(RetryRegistry retries, Retry retry) {
        retries.replace(INSTANCE, retry);
        return retry;
    }

    ResilientPaymentProcessor(
            PaymentProcessor delegate,
            Bulkhead bulkhead,
            Retry retry,
            CircuitBreaker circuitBreaker,
            TimeLimiter timeLimiter) {
        this.delegate = delegate;
        this.bulkhead = bulkhead;
        this.retry = retry;
        this.circuitBreaker = circuitBreaker;
        this.timeLimiter = timeLimiter;
    }

    /**
     * Copies only the configured attempts and backoff. The retry predicate is rebuilt from scratch
     * rather than layered onto the configured one: Resilience4j ORs {@code retryExceptions} with
     * {@code retryOnException}, so configuration could otherwise widen it (e.g. to timeouts).
     */
    static Retry safeRetry(RetryConfig configured) {
        return Retry.of(
                INSTANCE,
                RetryConfig.custom()
                        .maxAttempts(configured.getMaxAttempts())
                        .intervalBiFunction(configured.getIntervalBiFunction())
                        .retryOnException(e -> e instanceof ProcessorUnavailableException)
                        .retryOnResult(result -> false)
                        .build());
    }

    @Override
    public AuthorizationResult authorize(AuthorizationRequest request) {
        return bulkhead.executeSupplier(
                () -> {
                    try {
                        return retry.executeSupplier(guarded(() -> delegate.authorize(request)));
                    } catch (ProcessorTimeoutException e) {
                        return new AuthorizationResult.Unknown(
                                "no response within "
                                        + timeLimiter.getTimeLimiterConfig().getTimeoutDuration());
                    }
                });
    }

    /**
     * Read-only, so retrying is safe. A timeout surfaces as an exception; the reconciliation job
     * logs it and tries again on its next pass.
     */
    @Override
    public Optional<ProcessorStatus> lookup(String idempotencyKey) {
        return bulkhead.executeSupplier(
                () -> retry.executeSupplier(guarded(() -> delegate.lookup(idempotencyKey))));
    }

    @Override
    public CaptureResult capture(CaptureRequest request) {
        return delegate.capture(request);
    }

    @Override
    public RefundResult refund(RefundRequest request) {
        return delegate.refund(request);
    }

    /** Circuit breaker around a time-limited call. */
    private <T> Supplier<T> guarded(Supplier<T> call) {
        return CircuitBreaker.decorateSupplier(circuitBreaker, () -> withTimeout(call));
    }

    private <T> T withTimeout(Supplier<T> call) {
        try {
            return timeLimiter.executeFutureSupplier(
                    () -> CompletableFuture.supplyAsync(call, executor));
        } catch (TimeoutException e) {
            throw new ProcessorTimeoutException(e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** Internal: the time limit elapsed. Recorded by the circuit breaker, never retried. */
    static final class ProcessorTimeoutException extends RuntimeException {
        ProcessorTimeoutException(Throwable cause) {
            super(cause);
        }
    }
}
