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

SET @duplicate_seckill_order_create_count = (
    SELECT COUNT(*)
    FROM (
        SELECT bucket_shard_key, routing_key, business_key
        FROM mq_message
        WHERE bucket_shard_key IS NOT NULL
          AND routing_key = 'seckill.order.create'
        GROUP BY bucket_shard_key, routing_key, business_key
        HAVING COUNT(*) > 1
    ) duplicated
);

SET @duplicate_seckill_order_create_message = IF(
    @duplicate_seckill_order_create_count > 0,
    CONCAT('Duplicate seckill.order.create mq_message rows found before adding uk_mq_message_bucket_route_business: ',
        @duplicate_seckill_order_create_count),
    NULL
);

SET @signal_sql = IF(
    @duplicate_seckill_order_create_count > 0,
    CONCAT('SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''',
        REPLACE(@duplicate_seckill_order_create_message, '''', ''''''),
        ''''),
    'DO 0'
);
PREPARE stmt FROM @signal_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CALL add_column_if_missing(
    'seckill_stock_change_log',
    'outbox_claim_token',
    'ALTER TABLE seckill_stock_change_log ADD COLUMN outbox_claim_token VARCHAR(36) NULL AFTER status'
);

CALL add_column_if_missing(
    'seckill_stock_change_log',
    'outbox_claimed_at',
    'ALTER TABLE seckill_stock_change_log ADD COLUMN outbox_claimed_at DATETIME(3) NULL AFTER outbox_claim_token'
);

CALL add_index_if_missing(
    'seckill_stock_change_log',
    'idx_change_log_shard_status_id',
    'ALTER TABLE seckill_stock_change_log ADD KEY idx_change_log_shard_status_id (bucket_shard_key, status, id)'
);

CALL add_index_if_missing(
    'mq_message',
    'uk_mq_message_bucket_route_business',
    'ALTER TABLE mq_message ADD UNIQUE KEY uk_mq_message_bucket_route_business (bucket_shard_key, routing_key, business_key)'
);

DROP PROCEDURE IF EXISTS add_column_if_missing;
DROP PROCEDURE IF EXISTS add_index_if_missing;
