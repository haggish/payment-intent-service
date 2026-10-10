package com.example.payments.adapter.out.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.payments.adapter.out.processor.StubPaymentProcessor.StubProcessorProperties;
import com.example.payments.domain.port.PaymentProcessor;
import com.example.payments.domain.port.PaymentProcessor.ProcessorUnavailableException;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.springboot.bulkhead.autoconfigure.BulkheadAutoConfiguration;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot.retry.autoconfigure.RetryAutoConfiguration;
import io.github.resilience4j.springboot.timelimiter.autoconfigure.TimeLimiterAutoConfiguration;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** The real application.yml + Resilience4j auto-configuration produce the intended wrapper. */
class ResilientPaymentProcessorWiringTest {

    @Configuration
    @EnableConfigurationProperties(StubProcessorProperties.class)
    static class ProcessorBeans {}

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withConfiguration(
                            AutoConfigurations.of(
                                    BulkheadAutoConfiguration.class,
                                    CircuitBreakerAutoConfiguration.class,
                                    RetryAutoConfiguration.class,
                                    TimeLimiterAutoConfiguration.class))
                    .withUserConfiguration(
                            ProcessorBeans.class,
                            StubPaymentProcessor.class,
                            ResilientPaymentProcessor.class);

    @Test
    void callers_get_the_resilient_wrapper() {
        runner.run(
                ctx ->
                        assertThat(ctx.getBean(PaymentProcessor.class))
                                .isInstanceOf(ResilientPaymentProcessor.class));
    }

    @Test
    void time_limit_comes_from_configuration() {
        runner.run(
                ctx ->
                        assertThat(
                                        ctx.getBean(TimeLimiterRegistry.class)
                                                .timeLimiter(ResilientPaymentProcessor.INSTANCE)
                                                .getTimeLimiterConfig()
                                                .getTimeoutDuration())
                                .isEqualTo(Duration.ofSeconds(5)));
    }

    @Test
    void registered_retry_uses_configured_backoff_but_only_retries_definite_non_attempts() {
        runner.run(
                ctx -> {
                    RetryConfig config =
                            ctx.getBean(RetryRegistry.class)
                                    .retry(ResilientPaymentProcessor.INSTANCE)
                                    .getRetryConfig();
                    assertThat(config.getMaxAttempts()).isEqualTo(3);
                    // 500ms, then x2: exponential backoff is actually enabled.
                    assertThat(config.getIntervalBiFunction().apply(1, null)).isEqualTo(500L);
                    assertThat(config.getIntervalBiFunction().apply(2, null)).isEqualTo(1000L);

                    var retryable = config.getExceptionPredicate();
                    assertThat(retryable.test(new ProcessorUnavailableException("503"))).isTrue();
                    assertThat(retryable.test(new TimeoutException())).isFalse();
                    assertThat(retryable.test(new RuntimeException())).isFalse();
                });
    }
}
