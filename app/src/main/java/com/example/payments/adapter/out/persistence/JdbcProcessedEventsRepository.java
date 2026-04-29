package com.example.payments.adapter.out.persistence;

import com.example.payments.domain.port.ProcessedEventsRepository;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProcessedEventsRepository implements ProcessedEventsRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcProcessedEventsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryMarkProcessed(UUID eventId, String aggregateId) {
        int rows =
                jdbc.update(
                        """
                        INSERT INTO processed_events (event_id, aggregate_id)
                        VALUES (:event_id, :agg_id)
                        ON CONFLICT (event_id) DO NOTHING
                        """,
                        new MapSqlParameterSource()
                                .addValue("event_id", eventId)
                                .addValue("agg_id", aggregateId));
        return rows == 1;
    }
}
