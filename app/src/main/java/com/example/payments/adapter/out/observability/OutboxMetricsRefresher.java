package com.example.payments.adapter.out.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically queries the outbox to update gauges that the CloudWatch dashboard and alarms read.
 * Runs in-process (no DB-side instrumentation needed); cheap because both queries hit the
 * unpublished partial index.
 */
@Component
public class OutboxMetricsRefresher {

    private final NamedParameterJdbcTemplate jdbc;
    private final AtomicLong unpublishedCount = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();

    public OutboxMetricsRefresher(NamedParameterJdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        Gauge.builder("outbox.unpublished_count", unpublishedCount, AtomicLong::get)
                .register(registry);
        Gauge.builder("outbox.oldest_unpublished_age_seconds", oldestAgeSeconds, AtomicLong::get)
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${outbox.metrics-refresh-ms:30000}")
    public void refresh() {
        Long count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM outbox WHERE published_at IS NULL",
                        new MapSqlParameterSource(),
                        Long.class);
        unpublishedCount.set(count == null ? 0 : count);

        Long age =
                jdbc.queryForObject(
                        "SELECT COALESCE(EXTRACT(EPOCH FROM (now() - MIN(created_at)))::BIGINT, 0)"
                                + " FROM outbox WHERE published_at IS NULL",
                        new MapSqlParameterSource(),
                        Long.class);
        oldestAgeSeconds.set(age == null ? 0 : age);
    }
}
