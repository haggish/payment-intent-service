package com.example.payments.adapter.out.persistence;

import com.example.payments.domain.model.Capture;
import com.example.payments.domain.model.IdempotencyKey;
import com.example.payments.domain.model.MerchantId;
import com.example.payments.domain.model.Money;
import com.example.payments.domain.model.PaymentAttempt;
import com.example.payments.domain.model.PaymentAttempt.AttemptOutcome;
import com.example.payments.domain.model.PaymentIntent;
import com.example.payments.domain.model.PaymentIntentId;
import com.example.payments.domain.model.PaymentMethod;
import com.example.payments.domain.model.PaymentMethod.PaymentMethodType;
import com.example.payments.domain.model.PaymentState;
import com.example.payments.domain.model.Refund;
import com.example.payments.domain.port.PaymentIntentRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPaymentIntentRepository implements PaymentIntentRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcPaymentIntentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PaymentIntent> findById(PaymentIntentId id) {
        var params = new MapSqlParameterSource("id", id.value());
        var headers =
                jdbc.query(
                        """
                        SELECT id, merchant_id, amount_minor, currency, captured_minor, state,
                               payment_method_token, payment_method_type, idempotency_key,
                               version, created_at, updated_at
                        FROM payment_intents WHERE id = :id
                        """,
                        params,
                        (rs, n) ->
                                new HeaderRow(
                                        (UUID) rs.getObject("id"),
                                        rs.getString("merchant_id"),
                                        rs.getLong("amount_minor"),
                                        Currency.getInstance(rs.getString("currency")),
                                        rs.getLong("captured_minor"),
                                        PaymentState.valueOf(rs.getString("state")),
                                        rs.getString("payment_method_token"),
                                        rs.getString("payment_method_type"),
                                        rs.getString("idempotency_key"),
                                        rs.getLong("version"),
                                        rs.getTimestamp("created_at").toInstant(),
                                        rs.getTimestamp("updated_at").toInstant()));
        if (headers.isEmpty()) {
            return Optional.empty();
        }
        var h = headers.get(0);
        var captures = loadCaptures(id, h.currency());
        var refunds = loadRefunds(id, h.currency(), captures);
        var attempts = loadAttempts(id);
        var paymentMethod =
                h.paymentMethodToken() == null
                        ? null
                        : new PaymentMethod(
                                h.paymentMethodToken(),
                                PaymentMethodType.valueOf(h.paymentMethodType()));
        return Optional.of(
                PaymentIntent.hydrate(
                        new PaymentIntentId(h.id()),
                        new MerchantId(h.merchantId()),
                        new Money(h.amountMinor(), h.currency()),
                        new IdempotencyKey(h.idempotencyKey()),
                        h.state(),
                        paymentMethod,
                        new Money(h.capturedMinor(), h.currency()),
                        h.version(),
                        h.createdAt(),
                        h.updatedAt(),
                        attempts,
                        captures,
                        refunds));
    }

    private record HeaderRow(
            UUID id,
            String merchantId,
            long amountMinor,
            Currency currency,
            long capturedMinor,
            PaymentState state,
            String paymentMethodToken,
            String paymentMethodType,
            String idempotencyKey,
            long version,
            Instant createdAt,
            Instant updatedAt) {}

    private List<Capture> loadCaptures(PaymentIntentId id, Currency currency) {
        return jdbc.query(
                """
                SELECT id, amount_minor, captured_at FROM captures
                WHERE intent_id = :id
                ORDER BY seq ASC
                """,
                new MapSqlParameterSource("id", id.value()),
                (rs, n) ->
                        new Capture(
                                (UUID) rs.getObject("id"),
                                new Money(rs.getLong("amount_minor"), currency),
                                rs.getTimestamp("captured_at").toInstant()));
    }

    private List<Refund> loadRefunds(
            PaymentIntentId id, Currency currency, List<Capture> orderedCaptures) {
        Map<UUID, Integer> captureIndexById = new HashMap<>();
        for (int i = 0; i < orderedCaptures.size(); i++) {
            captureIndexById.put(orderedCaptures.get(i).id(), i);
        }
        return jdbc.query(
                """
                SELECT id, capture_id, amount_minor, refunded_at FROM refunds
                WHERE intent_id = :id
                ORDER BY seq ASC
                """,
                new MapSqlParameterSource("id", id.value()),
                (rs, n) -> {
                    UUID captureId = (UUID) rs.getObject("capture_id");
                    Integer captureIndex = captureIndexById.get(captureId);
                    if (captureIndex == null) {
                        throw new IllegalStateException(
                                "refund references unknown capture " + captureId);
                    }
                    return new Refund(
                            (UUID) rs.getObject("id"),
                            captureIndex,
                            new Money(rs.getLong("amount_minor"), currency),
                            rs.getTimestamp("refunded_at").toInstant());
                });
    }

    private List<PaymentAttempt> loadAttempts(PaymentIntentId id) {
        return jdbc.query(
                """
                SELECT id, processor_reference, outcome, attempted_at FROM payment_attempts
                WHERE intent_id = :id
                ORDER BY attempted_at ASC, id ASC
                """,
                new MapSqlParameterSource("id", id.value()),
                (rs, n) ->
                        new PaymentAttempt(
                                (UUID) rs.getObject("id"),
                                rs.getString("processor_reference"),
                                AttemptOutcome.valueOf(rs.getString("outcome")),
                                rs.getTimestamp("attempted_at").toInstant()));
    }

    @Override
    public List<PaymentIntentId> findStuckProcessing(Instant olderThan, int limit) {
        return jdbc.query(
                """
                SELECT id FROM payment_intents
                WHERE state = 'PROCESSING' AND updated_at < :older_than
                ORDER BY updated_at ASC
                LIMIT :limit
                """,
                new MapSqlParameterSource()
                        .addValue("older_than", Timestamp.from(olderThan))
                        .addValue("limit", limit),
                (rs, n) -> new PaymentIntentId((UUID) rs.getObject("id")));
    }

    @Override
    public void save(PaymentIntent intent) {
        if (intent.isHydrated()) {
            updateExisting(intent);
        } else {
            insertNew(intent);
        }
        // Children are append-only with UUID PKs — re-insert all, conflict-skip the existing.
        upsertAttempts(intent);
        upsertCaptures(intent);
        upsertRefunds(intent);
        intent.markPersisted();
    }

    private void insertNew(PaymentIntent intent) {
        jdbc.update(
                """
                INSERT INTO payment_intents
                  (id, merchant_id, amount_minor, currency, captured_minor, state,
                   payment_method_token, payment_method_type, idempotency_key,
                   version, created_at, updated_at)
                VALUES (:id, :merchant_id, :amount_minor, :currency, :captured_minor, :state,
                        :pm_token, :pm_type, :key, :version, :created_at, :updated_at)
                """,
                paramsFor(intent));
    }

    private void updateExisting(PaymentIntent intent) {
        var params = paramsFor(intent).addValue("version_at_load", intent.versionAtLoad());
        int rows =
                jdbc.update(
                        """
                        UPDATE payment_intents SET
                            captured_minor = :captured_minor,
                            state = :state,
                            payment_method_token = :pm_token,
                            payment_method_type = :pm_type,
                            version = :version,
                            updated_at = :updated_at
                        WHERE id = :id AND version = :version_at_load
                        """,
                        params);
        if (rows == 0) {
            throw new OptimisticLockException(intent.id(), intent.versionAtLoad());
        }
    }

    private MapSqlParameterSource paramsFor(PaymentIntent intent) {
        return new MapSqlParameterSource()
                .addValue("id", intent.id().value())
                .addValue("merchant_id", intent.merchantId().value())
                .addValue("amount_minor", intent.amount().minorUnits())
                .addValue("currency", intent.amount().currency().getCurrencyCode())
                .addValue("captured_minor", intent.capturedAmount().minorUnits())
                .addValue("state", intent.state().name())
                .addValue(
                        "pm_token",
                        intent.paymentMethod() == null ? null : intent.paymentMethod().token())
                .addValue(
                        "pm_type",
                        intent.paymentMethod() == null
                                ? null
                                : intent.paymentMethod().type().name())
                .addValue("key", intent.idempotencyKey().value())
                .addValue("version", intent.version())
                .addValue("created_at", Timestamp.from(intent.createdAt()))
                .addValue("updated_at", Timestamp.from(intent.updatedAt()));
    }

    private void upsertAttempts(PaymentIntent intent) {
        for (PaymentAttempt a : intent.attempts()) {
            jdbc.update(
                    """
                    INSERT INTO payment_attempts
                      (id, intent_id, processor_reference, outcome, attempted_at)
                    VALUES (:id, :intent_id, :ref, :outcome, :attempted_at)
                    ON CONFLICT (id) DO NOTHING
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", a.id())
                            .addValue("intent_id", intent.id().value())
                            .addValue("ref", a.processorReference())
                            .addValue("outcome", a.outcome().name())
                            .addValue("attempted_at", Timestamp.from(a.attemptedAt())));
        }
    }

    private void upsertCaptures(PaymentIntent intent) {
        var captures = intent.captures();
        for (int i = 0; i < captures.size(); i++) {
            Capture c = captures.get(i);
            jdbc.update(
                    """
                    INSERT INTO captures (id, intent_id, amount_minor, captured_at, seq)
                    VALUES (:id, :intent_id, :amount, :captured_at, :seq)
                    ON CONFLICT (id) DO NOTHING
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", c.id())
                            .addValue("intent_id", intent.id().value())
                            .addValue("amount", c.amount().minorUnits())
                            .addValue("captured_at", Timestamp.from(c.capturedAt()))
                            .addValue("seq", i));
        }
    }

    private void upsertRefunds(PaymentIntent intent) {
        var orderedCaptures = intent.captures();
        var refunds = intent.refunds();
        for (int i = 0; i < refunds.size(); i++) {
            Refund r = refunds.get(i);
            UUID captureId = orderedCaptures.get(r.captureIndex()).id();
            jdbc.update(
                    """
                    INSERT INTO refunds
                      (id, intent_id, capture_id, amount_minor, refunded_at, seq)
                    VALUES (:id, :intent_id, :capture_id, :amount, :refunded_at, :seq)
                    ON CONFLICT (id) DO NOTHING
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", r.id())
                            .addValue("intent_id", intent.id().value())
                            .addValue("capture_id", captureId)
                            .addValue("amount", r.amount().minorUnits())
                            .addValue("refunded_at", Timestamp.from(r.refundedAt()))
                            .addValue("seq", i));
        }
    }
}
