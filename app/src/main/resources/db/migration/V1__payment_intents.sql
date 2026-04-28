-- Payment intents and child entities
CREATE TABLE payment_intents (
    id              UUID PRIMARY KEY,
    merchant_id     VARCHAR(64)  NOT NULL,
    amount_minor    BIGINT       NOT NULL,
    currency        CHAR(3)      NOT NULL,
    captured_minor  BIGINT       NOT NULL DEFAULT 0,
    state           VARCHAR(32)  NOT NULL,
    payment_method_token VARCHAR(255),
    payment_method_type  VARCHAR(32),
    idempotency_key VARCHAR(64)  NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    UNIQUE (merchant_id, idempotency_key)
);

CREATE INDEX idx_payment_intents_merchant ON payment_intents (merchant_id, created_at DESC);
CREATE INDEX idx_payment_intents_state    ON payment_intents (state) WHERE state IN ('PROCESSING', 'REQUIRES_ACTION');

CREATE TABLE payment_attempts (
    id                   UUID PRIMARY KEY,
    intent_id            UUID NOT NULL REFERENCES payment_intents(id) ON DELETE CASCADE,
    processor_reference  VARCHAR(255),
    outcome              VARCHAR(32) NOT NULL,
    attempted_at         TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_payment_attempts_intent ON payment_attempts (intent_id);

CREATE TABLE captures (
    id              UUID PRIMARY KEY,
    intent_id       UUID NOT NULL REFERENCES payment_intents(id) ON DELETE CASCADE,
    amount_minor    BIGINT NOT NULL,
    captured_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_captures_intent ON captures (intent_id);

CREATE TABLE refunds (
    id              UUID PRIMARY KEY,
    intent_id       UUID NOT NULL REFERENCES payment_intents(id) ON DELETE CASCADE,
    capture_id      UUID NOT NULL REFERENCES captures(id),
    amount_minor    BIGINT NOT NULL,
    refunded_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_refunds_intent  ON refunds (intent_id);
CREATE INDEX idx_refunds_capture ON refunds (capture_id);
