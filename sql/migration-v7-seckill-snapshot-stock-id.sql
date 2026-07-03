ALTER TABLE seckill_stock_snapshot
    ADD COLUMN stock_id BIGINT NULL AFTER request_id;

UPDATE seckill_stock_snapshot snapshot
JOIN seckill_sku sku
    ON sku.activity_id = snapshot.activity_id
   AND sku.sku_id = snapshot.sku_id
SET snapshot.stock_id = sku.id
WHERE snapshot.stock_id IS NULL;

ALTER TABLE seckill_stock_snapshot
    MODIFY COLUMN stock_id BIGINT NOT NULL;
