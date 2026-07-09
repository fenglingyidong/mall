DROP PROCEDURE IF EXISTS add_index_if_missing;

DELIMITER //
CREATE PROCEDURE add_index_if_missing(
    IN table_name_value VARCHAR(64),
    IN index_name_value VARCHAR(64),
    IN alter_sql_value TEXT
)
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = table_name_value
    ) AND NOT EXISTS (
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = table_name_value
          AND index_name = index_name_value
    ) THEN
        SET @alter_sql = alter_sql_value;
        PREPARE stmt FROM @alter_sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END//
DELIMITER ;

CALL add_index_if_missing(
    'seckill_stock_change_log',
    'uk_change_log_request_type',
    'ALTER TABLE seckill_stock_change_log ADD UNIQUE KEY uk_change_log_request_type (request_id, change_type)'
);

CALL add_index_if_missing(
    'mq_message',
    'idx_mq_message_route_business',
    'ALTER TABLE mq_message ADD KEY idx_mq_message_route_business (routing_key, business_key)'
);

CALL add_index_if_missing(
    'seckill_stock_snapshot',
    'idx_snapshot_status_id',
    'ALTER TABLE seckill_stock_snapshot ADD KEY idx_snapshot_status_id (status, request_id)'
);

CALL add_index_if_missing(
    'seckill_stock_snapshot',
    'idx_snapshot_created_at',
    'ALTER TABLE seckill_stock_snapshot ADD KEY idx_snapshot_created_at (created_at)'
);

DROP PROCEDURE IF EXISTS add_index_if_missing;
