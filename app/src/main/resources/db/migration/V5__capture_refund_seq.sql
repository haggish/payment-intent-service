-- Preserve domain insertion order for captures and refunds.
-- The aggregate exposes captures/refunds as ordered lists; refunds reference
-- captures by ordinal index. UUID PKs are random, so we cannot rely on
-- ORDER BY id to recover that order.

ALTER TABLE captures ADD COLUMN seq INT;
UPDATE captures SET seq = sub.rn - 1
FROM (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY intent_id ORDER BY captured_at, id) AS rn
    FROM captures
) sub
WHERE captures.id = sub.id;
ALTER TABLE captures ALTER COLUMN seq SET NOT NULL;
CREATE UNIQUE INDEX idx_captures_intent_seq ON captures (intent_id, seq);

ALTER TABLE refunds ADD COLUMN seq INT;
UPDATE refunds SET seq = sub.rn - 1
FROM (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY intent_id ORDER BY refunded_at, id) AS rn
    FROM refunds
) sub
WHERE refunds.id = sub.id;
ALTER TABLE refunds ALTER COLUMN seq SET NOT NULL;
CREATE UNIQUE INDEX idx_refunds_intent_seq ON refunds (intent_id, seq);
