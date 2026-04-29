package com.example.payments.config;

import com.example.payments.adapter.out.observability.MeteringOutboxRepository;
import com.example.payments.application.idempotency.IdempotencyService;
import com.example.payments.application.metrics.PaymentMetrics;
import com.example.payments.application.usecase.ApplyAuthorizationOutcome;
import com.example.payments.application.usecase.CancelPaymentIntent;
import com.example.payments.application.usecase.CapturePayment;
import com.example.payments.application.usecase.ConfirmPaymentIntent;
import com.example.payments.application.usecase.CreatePaymentIntent;
import com.example.payments.application.usecase.CreateRefund;
import com.example.payments.domain.port.IdempotencyRepository;
import com.example.payments.domain.port.OutboxRepository;
import com.example.payments.domain.port.PaymentIntentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires application-layer beans without polluting application classes with Spring annotations. Use
 * cases stay framework-agnostic; this class is the boundary.
 *
 * <p>Each use case receives the OutboxRepository wrapped in {@link MeteringOutboxRepository} so
 * every persisted event batch also increments business metrics — no per-use-case wiring change.
 */
@Configuration
public class ApplicationConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PaymentMetrics paymentMetrics(MeterRegistry registry) {
        return new PaymentMetrics(registry);
    }

    @Bean
    IdempotencyService idempotencyService(IdempotencyRepository repository) {
        return new IdempotencyService(repository);
    }

    @Bean
    CreatePaymentIntent createPaymentIntent(
            PaymentIntentRepository repository,
            OutboxRepository outbox,
            PaymentMetrics metrics,
            Clock clock) {
        return new CreatePaymentIntent(repository, metering(outbox, metrics), clock);
    }

    @Bean
    ConfirmPaymentIntent confirmPaymentIntent(
            PaymentIntentRepository repository,
            OutboxRepository outbox,
            PaymentMetrics metrics,
            Clock clock) {
        return new ConfirmPaymentIntent(repository, metering(outbox, metrics), clock);
    }

    @Bean
    CapturePayment capturePayment(
            PaymentIntentRepository repository,
            OutboxRepository outbox,
            PaymentMetrics metrics,
            Clock clock) {
        return new CapturePayment(repository, metering(outbox, metrics), clock);
    }

    @Bean
    CancelPaymentIntent cancelPaymentIntent(
            PaymentIntentRepository repository,
            OutboxRepository outbox,
            PaymentMetrics metrics,
            Clock clock) {
        return new CancelPaymentIntent(repository, metering(outbox, metrics), clock);
    }

    @Bean
    CreateRefund createRefund(
            PaymentIntentRepository repository,
            OutboxRepository outbox,
            PaymentMetrics metrics,
            Clock clock) {
        return new CreateRefund(repository, metering(outbox, metrics), clock);
    }

    @Bean
    ApplyAuthorizationOutcome applyAuthorizationOutcome(
            PaymentIntentRepository repository,
            OutboxRepository outbox,
            PaymentMetrics metrics,
            Clock clock) {
        return new ApplyAuthorizationOutcome(repository, metering(outbox, metrics), clock);
    }

    private static OutboxRepository metering(OutboxRepository delegate, PaymentMetrics metrics) {
        return new MeteringOutboxRepository(delegate, metrics);
    }
}
