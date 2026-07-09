DROP PROCEDURE IF EXISTS add_column_if_missing;
DROP PROCEDURE IF EXISTS add_index_if_missing;

DELIMITER //
CREATE PROCEDURE add_column_if_missing(
    IN table_name_value VARCHAR(64),
    IN column_name_value VARCHAR(64),
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
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = table_name_value
          AND column_name = column_name_value
    ) THEN
        SET @alter_sql = alter_sql_value;
        PREPARE stmt FROM @alter_sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END//

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

CALL add_column_if_missing(
    'seckill_stock_snapshot',
    'bucket_shard_key',
    'ALTER TABLE seckill_stock_snapshot ADD COLUMN bucket_shard_key BIGINT NULL AFTER bucket_no'
);

CALL add_column_if_missing(
    'seckill_stock_change_log',
    'bucket_shard_key',
    'ALTER TABLE seckill_stock_change_log ADD COLUMN bucket_shard_key BIGINT NOT NULL DEFAULT 0 AFTER bucket_no'
);

CALL add_column_if_missing(
    'mq_message',
    'bucket_shard_key',
    'ALTER TABLE mq_message ADD COLUMN bucket_shard_key BIGINT NULL AFTER payload'
);

CALL add_index_if_missing(
    'mq_message',
    'idx_mq_message_bucket_status',
    'ALTER TABLE mq_message ADD KEY idx_mq_message_bucket_status (bucket_shard_key, status, updated_at)'
);

DROP PROCEDURE IF EXISTS add_column_if_missing;
DROP PROCEDURE IF EXISTS add_index_if_missing;
