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
    'order_info',
    'source_id',
    'ALTER TABLE order_info ADD COLUMN source_id VARCHAR(128) NULL AFTER source'
);

CALL add_index_if_missing(
    'order_info',
    'uk_order_source',
    'ALTER TABLE order_info ADD UNIQUE KEY uk_order_source (source, source_id)'
);

CALL add_column_if_missing(
    'seckill_order',
    'reservation_id',
    'ALTER TABLE seckill_order ADD COLUMN reservation_id VARCHAR(64) NULL AFTER id'
);

CALL add_column_if_missing(
    'seckill_order',
    'bucket_shard_key',
    'ALTER TABLE seckill_order ADD COLUMN bucket_shard_key BIGINT NULL AFTER order_sn'
);

CALL add_index_if_missing(
    'seckill_order',
    'uk_seckill_reservation',
    'ALTER TABLE seckill_order ADD UNIQUE KEY uk_seckill_reservation (reservation_id)'
);

CALL add_index_if_missing(
    'seckill_order',
    'idx_seckill_order_sn',
    'ALTER TABLE seckill_order ADD KEY idx_seckill_order_sn (order_sn)'
);

DROP PROCEDURE IF EXISTS add_column_if_missing;
DROP PROCEDURE IF EXISTS add_index_if_missing;
