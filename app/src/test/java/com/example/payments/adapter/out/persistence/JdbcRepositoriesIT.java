package com.example.payments.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.payments.application.usecase.CreatePaymentIntent;
import com.example.payments.domain.model.IdempotencyKey;
import com.example.payments.domain.model.MerchantId;
import com.example.payments.domain.model.Money;
import com.example.payments.domain.model.PaymentIntent;
import com.example.payments.domain.model.PaymentIntentEvent;
import com.example.payments.domain.model.PaymentIntentId;
import com.example.payments.domain.model.PaymentMethod;
import com.example.payments.domain.model.PaymentMethod.PaymentMethodType;
import com.example.payments.domain.model.PaymentState;
import com.example.payments.domain.port.IdempotencyRepository;
import com.example.payments.domain.port.IdempotencyRepository.CompletedResponse;
import com.example.payments.domain.port.IdempotencyRepository.IdempotencyRecord;
import com.example.payments.domain.port.IdempotencyRepository.ReservationContext;
import com.example.payments.domain.port.OutboxRepository;
import com.example.payments.domain.port.PaymentIntentRepository;
import com.example.payments.domain.port.PaymentIntentRepository.OptimisticLockException;
import com.example.payments.domain.port.ProcessedEventsRepository;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIf("postgresReachable")
class JdbcRepositoriesIT {

    @Autowired private PaymentIntentRepository intents;
    @Autowired private OutboxRepository outbox;
    @Autowired private IdempotencyRepository idempotency;
    @Autowired private ProcessedEventsRepository processedEvents;
    @Autowired private CreatePaymentIntent createPaymentIntent;
    @Autowired private JdbcTemplate jdbc;

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @SuppressWarnings("unused")
    static boolean postgresReachable() {
        try (var sock = new Socket()) {
            sock.connect(new InetSocketAddress("localhost", 5432), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeEach
    void clean() {
        jdbc.execute(
                "TRUNCATE TABLE refunds, captures, payment_attempts, payment_intents,"
                        + " idempotency_records, outbox, processed_events RESTART IDENTITY"
                        + " CASCADE");
    }

    private IdempotencyKey freshKey() {
        return new IdempotencyKey("k-" + UUID.randomUUID());
    }

    // ---------- JdbcPaymentIntentRepository ----------

    @Test
    void aggregate_round_trips_through_postgres() {
        var intent =
                PaymentIntent.create(new MerchantId("acme"), Money.of(1000, "EUR"), freshKey(), T0);
        intent.attachPaymentMethod(new PaymentMethod("tok_visa", PaymentMethodType.CARD), T0);
        intent.confirm(T0);
        intent.markAuthorized("proc-ref-1", T0);
        intent.capture(Money.of(400, "EUR"), T0);
        intent.capture(Money.of(600, "EUR"), T0);
        intent.refund(0, Money.of(100, "EUR"), T0);
        intents.save(intent);

        var loaded = intents.findById(intent.id()).orElseThrow();
        assertThat(loaded.id()).isEqualTo(intent.id());
        assertThat(loaded.state()).isEqualTo(PaymentState.CAPTURED);
        assertThat(loaded.amount().minorUnits()).isEqualTo(1000);
        assertThat(loaded.capturedAmount().minorUnits()).isEqualTo(1000);
        assertThat(loaded.captures()).hasSize(2);
        assertThat(loaded.refunds()).hasSize(1);
        assertThat(loaded.refunds().get(0).captureIndex()).isZero();
        assertThat(loaded.attempts()).hasSize(1);
        assertThat(loaded.paymentMethod().token()).isEqualTo("tok_visa");
        assertThat(loaded.isHydrated()).isTrue();
    }

    @Test
    void optimistic_locking_rejects_stale_save() {
        var intent =
                PaymentIntent.create(new MerchantId("acme"), Money.of(500, "EUR"), freshKey(), T0);
        intents.save(intent);

        var v1 = intents.findById(intent.id()).orElseThrow();
        var v2 = intents.findById(intent.id()).orElseThrow();
        v1.cancel("first-writer", T0);
        intents.save(v1);
        v2.cancel("racing-writer", T0);
        assertThatThrownBy(() -> intents.save(v2)).isInstanceOf(OptimisticLockException.class);
    }

    @Test
    void second_save_of_same_aggregate_succeeds_after_mark_persisted() {
        var intent =
                PaymentIntent.create(new MerchantId("acme"), Money.of(500, "EUR"), freshKey(), T0);
        intents.save(intent);
        // Same handle, no reload — markPersisted should have updated versionAtLoad
        intent.cancel("user-requested", T0);
        intents.save(intent);
        assertThat(intents.findById(intent.id()).orElseThrow().state())
                .isEqualTo(PaymentState.CANCELED);
    }

    // ---------- JdbcOutboxRepository ----------

    @Test
    void outbox_save_and_read_unpublished() {
        var intent =
                PaymentIntent.create(new MerchantId("acme"), Money.of(700, "EUR"), freshKey(), T0);
        List<PaymentIntentEvent> events = intent.pullEvents();
        outbox.saveAll(events);

        var batch = outbox.findUnpublishedBatch(100);
        var ids = batch.stream().map(OutboxRepository.OutboxEntry::id).toList();
        assertThat(ids).contains(events.get(0).eventId());
    }

    @Test
    void outbox_mark_published_excludes_row_from_subsequent_reads() {
        var intent =
                PaymentIntent.create(new MerchantId("acme"), Money.of(800, "EUR"), freshKey(), T0);
        var event = intent.pullEvents().get(0);
        outbox.saveAll(List.of(event));
        outbox.markPublished(event.eventId(), Instant.now());

        var batch = outbox.findUnpublishedBatch(100);
        var ids = batch.stream().map(OutboxRepository.OutboxEntry::id).toList();
        assertThat(ids).doesNotContain(event.eventId());
    }

    // ---------- JdbcIdempotencyRepository ----------

    @Test
    void idempotency_reservation_is_race_free() {
        var merchant = new MerchantId("acme");
        var key = freshKey();
        var ctx =
                new ReservationContext("POST", "/v1/x", "hash-A", "node-1", T0.plusSeconds(86400));
        assertThat(idempotency.tryReserve(merchant, key, ctx)).isTrue();
        assertThat(idempotency.tryReserve(merchant, key, ctx)).isFalse();
    }

    @Test
    void idempotency_complete_then_find_returns_completed_record() {
        var merchant = new MerchantId("acme");
        var key = freshKey();
        var ctx =
                new ReservationContext("POST", "/v1/x", "hash-B", "node-1", T0.plusSeconds(86400));
        idempotency.tryReserve(merchant, key, ctx);
        idempotency.complete(merchant, key, new CompletedResponse(201, "{}", "{\"id\":\"abc\"}"));

        var record = idempotency.find(merchant, key).orElseThrow();
        assertThat(record.state()).isEqualTo(IdempotencyRecord.State.COMPLETED);
        assertThat(record.response().orElseThrow().status()).isEqualTo(201);
        assertThat(record.response().orElseThrow().bodyJson()).contains("abc");
    }

    @Test
    void idempotency_release_removes_in_progress_only() {
        var merchant = new MerchantId("acme");
        var key = freshKey();
        var ctx =
                new ReservationContext("POST", "/v1/x", "hash-C", "node-1", T0.plusSeconds(86400));
        idempotency.tryReserve(merchant, key, ctx);
        idempotency.releaseInProgress(merchant, key);
        assertThat(idempotency.find(merchant, key)).isEmpty();
    }

    // ---------- End-to-end via use case ----------

    @Test
    void create_use_case_writes_intent_row_and_outbox_event() {
        var intent =
                createPaymentIntent.execute(
                        new MerchantId("acme"), Money.of(1234, "EUR"), freshKey());
        var loaded = intents.findById(intent.id()).orElseThrow();
        assertThat(loaded.amount().minorUnits()).isEqualTo(1234);

        var batch = outbox.findUnpublishedBatch(1000);
        assertThat(batch)
                .extracting(OutboxRepository.OutboxEntry::aggregateId)
                .contains(intent.id().value().toString());
    }

    @Test
    void unknown_intent_returns_empty() {
        var unknown = new PaymentIntentId(UUID.randomUUID());
        assertThat(intents.findById(unknown)).isEmpty();
    }

    // ---------- JdbcProcessedEventsRepository ----------

    @Test
    void try_mark_processed_first_call_returns_true_then_false() {
        var eventId = UUID.randomUUID();
        assertThat(processedEvents.tryMarkProcessed(eventId, "agg-1")).isTrue();
        assertThat(processedEvents.tryMarkProcessed(eventId, "agg-1")).isFalse();
    }
}
