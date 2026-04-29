package com.example.payments.config;

import com.example.payments.application.idempotency.IdempotencyService;
import com.example.payments.application.usecase.CancelPaymentIntent;
import com.example.payments.application.usecase.CapturePayment;
import com.example.payments.application.usecase.ConfirmPaymentIntent;
import com.example.payments.application.usecase.CreatePaymentIntent;
import com.example.payments.application.usecase.CreateRefund;
import com.example.payments.domain.port.IdempotencyRepository;
import com.example.payments.domain.port.OutboxRepository;
import com.example.payments.domain.port.PaymentIntentRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires application-layer beans without polluting application classes with Spring annotations. Use
 * cases stay framework-agnostic; this class is the boundary.
 */
@Configuration
public class ApplicationConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    IdempotencyService idempotencyService(IdempotencyRepository repository) {
        return new IdempotencyService(repository);
    }

    @Bean
    CreatePaymentIntent createPaymentIntent(
            PaymentIntentRepository repository, OutboxRepository outbox, Clock clock) {
        return new CreatePaymentIntent(repository, outbox, clock);
    }

    @Bean
    ConfirmPaymentIntent confirmPaymentIntent(
            PaymentIntentRepository repository, OutboxRepository outbox, Clock clock) {
        return new ConfirmPaymentIntent(repository, outbox, clock);
    }

    @Bean
    CapturePayment capturePayment(
            PaymentIntentRepository repository, OutboxRepository outbox, Clock clock) {
        return new CapturePayment(repository, outbox, clock);
    }

    @Bean
    CancelPaymentIntent cancelPaymentIntent(
            PaymentIntentRepository repository, OutboxRepository outbox, Clock clock) {
        return new CancelPaymentIntent(repository, outbox, clock);
    }

    @Bean
    CreateRefund createRefund(
            PaymentIntentRepository repository, OutboxRepository outbox, Clock clock) {
        return new CreateRefund(repository, outbox, clock);
    }
}
