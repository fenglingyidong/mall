ALTER TABLE seckill_stock_snapshot
    ADD COLUMN active_key BIGINT NULL AFTER user_id;

UPDATE seckill_stock_snapshot
SET active_key = CASE
    WHEN status IN ('DEDUCTED', 'CONFIRMED') THEN user_id
    ELSE NULL
END;

ALTER TABLE seckill_stock_snapshot
    ADD UNIQUE KEY uk_snapshot_active_user (activity_id, active_key);
