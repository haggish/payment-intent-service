package com.example.payments.adapter.out.persistence;

import com.example.payments.domain.model.PaymentIntentEvent;
import com.example.payments.domain.port.OutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOutboxRepository implements OutboxRepository {

    private static final String AGGREGATE_TYPE = "payment_intent";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcOutboxRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void saveAll(List<PaymentIntentEvent> events) {
        for (PaymentIntentEvent event : events) {
            jdbc.update(
                    """
                    INSERT INTO outbox
                      (id, aggregate_type, aggregate_id, event_type, payload, created_at,
                       attempt_count, next_attempt_at)
                    VALUES (:id, :agg_type, :agg_id, :event_type, CAST(:payload AS jsonb),
                            :created_at, 0, :next_attempt_at)
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", event.eventId())
                            .addValue("agg_type", AGGREGATE_TYPE)
                            .addValue("agg_id", event.aggregateId().value().toString())
                            .addValue("event_type", event.getClass().getSimpleName())
                            .addValue("payload", serialize(event))
                            .addValue("created_at", Timestamp.from(event.occurredAt()))
                            .addValue("next_attempt_at", Timestamp.from(event.occurredAt())));
        }
    }

    @Override
    public List<OutboxEntry> findUnpublishedBatch(int batchSize) {
        return jdbc.query(
                """
                SELECT id, aggregate_type, aggregate_id, event_type, payload::text AS payload_text,
                       attempt_count, created_at
                FROM outbox
                WHERE published_at IS NULL AND next_attempt_at <= now()
                ORDER BY next_attempt_at ASC
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """,
                new MapSqlParameterSource("limit", batchSize),
                (rs, n) ->
                        new OutboxEntry(
                                (UUID) rs.getObject("id"),
                                rs.getString("aggregate_type"),
                                rs.getString("aggregate_id"),
                                rs.getString("event_type"),
                                rs.getString("payload_text"),
                                rs.getInt("attempt_count"),
                                rs.getTimestamp("created_at").toInstant()));
    }

    @Override
    public void markPublished(UUID id, Instant publishedAt) {
        jdbc.update(
                "UPDATE outbox SET published_at = :ts WHERE id = :id",
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("ts", Timestamp.from(publishedAt)));
    }

    @Override
    public void markFailed(UUID id, String error, Instant nextAttemptAt) {
        jdbc.update(
                """
                UPDATE outbox SET
                  attempt_count = attempt_count + 1,
                  last_error = :error,
                  next_attempt_at = :next
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("error", error)
                        .addValue("next", Timestamp.from(nextAttemptAt)));
    }

    private String serialize(PaymentIntentEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize event " + event.eventId(), e);
        }
    }
}
