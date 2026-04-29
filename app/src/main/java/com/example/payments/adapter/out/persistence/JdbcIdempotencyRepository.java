package com.example.payments.adapter.out.persistence;

import com.example.payments.domain.model.IdempotencyKey;
import com.example.payments.domain.model.MerchantId;
import com.example.payments.domain.port.IdempotencyRepository;
import java.sql.Timestamp;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcIdempotencyRepository implements IdempotencyRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcIdempotencyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryReserve(
            MerchantId merchantId, IdempotencyKey key, ReservationContext context) {
        int rows =
                jdbc.update(
                        """
                        INSERT INTO idempotency_records
                          (merchant_id, idempotency_key, request_method, request_path,
                           request_hash, state, locked_at, locked_by, expires_at)
                        VALUES (:merchant, :key, :method, :path, :hash, 'IN_PROGRESS',
                                now(), :locked_by, :expires)
                        ON CONFLICT (merchant_id, idempotency_key) DO NOTHING
                        """,
                        new MapSqlParameterSource()
                                .addValue("merchant", merchantId.value())
                                .addValue("key", key.value())
                                .addValue("method", context.method())
                                .addValue("path", context.path())
                                .addValue("hash", context.requestHash())
                                .addValue("locked_by", context.lockedBy())
                                .addValue("expires", Timestamp.from(context.expiresAt())));
        return rows == 1;
    }

    @Override
    public Optional<IdempotencyRecord> find(MerchantId merchantId, IdempotencyKey key) {
        var rows =
                jdbc.query(
                        """
                        SELECT request_hash, state, locked_at,
                               response_status, response_headers::text AS response_headers,
                               response_body::text AS response_body, expires_at
                        FROM idempotency_records
                        WHERE merchant_id = :merchant AND idempotency_key = :key
                        """,
                        new MapSqlParameterSource()
                                .addValue("merchant", merchantId.value())
                                .addValue("key", key.value()),
                        (rs, n) -> {
                            var state = IdempotencyRecord.State.valueOf(rs.getString("state"));
                            var lockedAtTs = rs.getTimestamp("locked_at");
                            var lockedAt = lockedAtTs == null ? null : lockedAtTs.toInstant();
                            Optional<CompletedResponse> response;
                            int status = rs.getInt("response_status");
                            if (rs.wasNull()) {
                                response = Optional.empty();
                            } else {
                                response =
                                        Optional.of(
                                                new CompletedResponse(
                                                        status,
                                                        rs.getString("response_headers"),
                                                        rs.getString("response_body")));
                            }
                            return new IdempotencyRecord(
                                    rs.getString("request_hash"),
                                    state,
                                    lockedAt,
                                    response,
                                    rs.getTimestamp("expires_at").toInstant());
                        });
        return rows.stream().findFirst();
    }

    @Override
    public void complete(MerchantId merchantId, IdempotencyKey key, CompletedResponse response) {
        jdbc.update(
                """
                UPDATE idempotency_records SET
                  state = 'COMPLETED',
                  response_status = :status,
                  response_headers = CAST(:headers AS jsonb),
                  response_body = CAST(:body AS jsonb),
                  completed_at = now()
                WHERE merchant_id = :merchant AND idempotency_key = :key
                """,
                new MapSqlParameterSource()
                        .addValue("merchant", merchantId.value())
                        .addValue("key", key.value())
                        .addValue("status", response.status())
                        .addValue("headers", response.headersJson())
                        .addValue("body", response.bodyJson()));
    }

    @Override
    public void releaseInProgress(MerchantId merchantId, IdempotencyKey key) {
        jdbc.update(
                """
                DELETE FROM idempotency_records
                WHERE merchant_id = :merchant
                  AND idempotency_key = :key
                  AND state = 'IN_PROGRESS'
                """,
                new MapSqlParameterSource()
                        .addValue("merchant", merchantId.value())
                        .addValue("key", key.value()));
    }

    @Override
    public int deleteExpired() {
        return jdbc.update(
                "DELETE FROM idempotency_records WHERE expires_at < now()",
                new MapSqlParameterSource());
    }
}
