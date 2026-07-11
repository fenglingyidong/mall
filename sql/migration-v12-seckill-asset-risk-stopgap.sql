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

CREATE TABLE IF NOT EXISTS seckill_result_retry (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    message_id VARCHAR(128) NOT NULL,
    reservation_id VARCHAR(64) NOT NULL,
    result_type VARCHAR(32) NOT NULL,
    payload TEXT NOT NULL,
    bucket_shard_key BIGINT,
    retry_count INT NOT NULL DEFAULT 0,
    first_failed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_failed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error VARCHAR(512),
    next_retry_at DATETIME,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_result_retry_message (message_id),
    UNIQUE KEY uk_result_retry_reservation_type (reservation_id, result_type),
    KEY idx_result_retry_status_next (status, next_retry_at),
    KEY idx_result_retry_reservation (reservation_id),
    KEY idx_result_retry_reservation_type (reservation_id, result_type, status)
);

CALL add_column_if_missing(
    'mq_message',
    'error_type',
    'ALTER TABLE mq_message ADD COLUMN error_type VARCHAR(32) NULL AFTER status'
);

CALL add_index_if_missing(
    'mq_message',
    'idx_mq_message_status_updated',
    'ALTER TABLE mq_message ADD KEY idx_mq_message_status_updated (status, updated_at)'
);

CALL add_index_if_missing(
    'seckill_result_retry',
    'uk_result_retry_reservation_type',
    'ALTER TABLE seckill_result_retry ADD UNIQUE KEY uk_result_retry_reservation_type (reservation_id, result_type)'
);

DROP PROCEDURE IF EXISTS add_column_if_missing;
DROP PROCEDURE IF EXISTS add_index_if_missing;
