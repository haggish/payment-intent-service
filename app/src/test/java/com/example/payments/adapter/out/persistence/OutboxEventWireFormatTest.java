package com.example.payments.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.payments.domain.model.Money;
import com.example.payments.domain.model.PaymentIntentEvent;
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
import com.example.payments.domain.model.PaymentIntentId;
import com.example.payments.domain.model.PaymentMethod;
import com.example.payments.domain.model.PaymentMethod.PaymentMethodType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the JSON wire format of outbox events.
 *
 * <p>Outbox payloads cross a deploy boundary: during a rolling deploy, rows written by the old
 * version are published and consumed by the new one, and vice versa. The golden file was produced
 * by the Spring Boot 3 / Jackson 2 release; if this test fails, the change would break consumers
 * mid-deploy and needs an expand/contract rollout rather than a fixture update.
 */
class OutboxEventWireFormatTest {

    private static final String GOLDEN = "/wire/payment-intent-events.jsonl";

    private static final PaymentIntentId ID =
            PaymentIntentId.of("7b0c2a4e-1f3d-4c5b-9a8e-0d1c2b3a4f5e");
    private static final Instant AT = Instant.parse("2026-10-10T12:34:56.789123Z");

    /** One of each event type, in the same order as the golden file. */
    private static List<PaymentIntentEvent> events() {
        return List.of(
                new PaymentIntentCreated(id(1), ID, Money.of(1234, "EUR"), AT),
                new PaymentMethodAttached(
                        id(2), ID, new PaymentMethod("tok_visa", PaymentMethodType.CARD), AT),
                new PaymentIntentConfirmed(id(3), ID, AT),
                new PaymentIntentAuthorized(id(4), ID, "proc_123", AT),
                new PaymentIntentActionRequired(id(5), ID, "3ds", AT),
                new PaymentIntentCaptured(id(6), ID, Money.of(1234, "EUR"), AT),
                new PaymentIntentPartiallyCaptured(
                        id(7), ID, Money.of(200, "EUR"), Money.of(600, "EUR"), AT),
                new PaymentIntentCanceled(id(8), ID, "requested_by_customer", AT),
                new PaymentIntentFailed(id(9), ID, "declined", AT),
                new PaymentRefunded(id(10), ID, Money.of(100, "EUR"), AT));
    }

    private static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012x", n));
    }

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class));

    @Test
    void serializes_every_event_type_exactly_as_the_golden_file() {
        runner.run(
                ctx -> {
                    ObjectMapper mapper = ctx.getBean(ObjectMapper.class);
                    List<String> golden = goldenLines();
                    List<PaymentIntentEvent> events = events();
                    assertThat(golden).hasSameSizeAs(events);
                    for (int i = 0; i < events.size(); i++) {
                        assertThat(mapper.writeValueAsString(events.get(i)))
                                .isEqualTo(golden.get(i));
                    }
                });
    }

    @Test
    void deserializes_golden_payloads_back_to_the_same_events() {
        runner.run(
                ctx -> {
                    ObjectMapper mapper = ctx.getBean(ObjectMapper.class);
                    List<String> golden = goldenLines();
                    List<PaymentIntentEvent> events = events();
                    for (int i = 0; i < events.size(); i++) {
                        assertThat(mapper.readValue(golden.get(i), PaymentIntentEvent.class))
                                .isEqualTo(events.get(i));
                    }
                });
    }

    private static List<String> goldenLines() throws IOException {
        try (InputStream in = OutboxEventWireFormatTest.class.getResourceAsStream(GOLDEN)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }
}
