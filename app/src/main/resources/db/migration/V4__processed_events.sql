-- Consumer-side idempotency: track events already processed so SQS at-least-once
-- redelivery doesn't cause double-processing.
CREATE TABLE processed_events (
    event_id      UUID PRIMARY KEY,
    aggregate_id  VARCHAR(64) NOT NULL,
    processed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_processed_events_aggregate ON processed_events (aggregate_id);
