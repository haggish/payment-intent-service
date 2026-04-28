-- Per-merchant idempotency records for the API layer
CREATE TABLE idempotency_records (
    merchant_id        VARCHAR(64)  NOT NULL,
    idempotency_key    VARCHAR(64)  NOT NULL,
    request_method     VARCHAR(8)   NOT NULL,
    request_path       VARCHAR(256) NOT NULL,
    request_hash       VARCHAR(64)  NOT NULL,
    response_status    INT,
    response_headers   JSONB,
    response_body      JSONB,
    state              VARCHAR(16)  NOT NULL,
    locked_at          TIMESTAMPTZ,
    locked_by          VARCHAR(64),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at       TIMESTAMPTZ,
    expires_at         TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (merchant_id, idempotency_key)
);

CREATE INDEX idx_idempotency_expires ON idempotency_records (expires_at);
