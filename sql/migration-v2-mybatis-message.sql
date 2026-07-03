DROP TABLE IF EXISTS mq_message;
DROP TABLE IF EXISTS consume_record;
DROP TABLE IF EXISTS seckill_stock_snapshot;
DROP TABLE IF EXISTS seckill_result;
DROP TABLE IF EXISTS seckill_sku;
DROP TABLE IF EXISTS seckill_activity;

CREATE TABLE IF NOT EXISTS seckill_activity (
    id BIGINT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    start_at DATETIME NOT NULL,
    end_at DATETIME NOT NULL
);

CREATE TABLE IF NOT EXISTS seckill_sku (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    activity_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    sku_name VARCHAR(128) NOT NULL,
    seckill_price DECIMAL(10, 2) NOT NULL,
    stock INT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_activity_sku (activity_id, sku_id)
);

CREATE TABLE IF NOT EXISTS seckill_result (
    request_id VARCHAR(64) PRIMARY KEY,
    status VARCHAR(32) NOT NULL,
    order_sn VARCHAR(64),
    message VARCHAR(255),
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS seckill_stock_snapshot (
    request_id VARCHAR(64) PRIMARY KEY,
    stock_id BIGINT NOT NULL,
    activity_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    order_sn VARCHAR(64),
    message VARCHAR(255),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_snapshot_user (activity_id, sku_id, user_id),
    KEY idx_snapshot_status (status)
);

CREATE TABLE IF NOT EXISTS mq_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    message_id VARCHAR(64) NOT NULL,
    exchange_name VARCHAR(128) NOT NULL,
    routing_key VARCHAR(128) NOT NULL,
    business_key VARCHAR(128) NOT NULL,
    payload TEXT NOT NULL,
    delay_millis BIGINT,
    status VARCHAR(32) NOT NULL,
    error_message VARCHAR(512),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_message_id (message_id)
);

CREATE TABLE IF NOT EXISTS consume_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    message_id VARCHAR(128) NOT NULL,
    consumed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_consume_message (message_id)
);

INSERT INTO seckill_activity (id, name, start_at, end_at) VALUES
    (1, 'Spring Flash Sale', DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 1 DAY))
ON DUPLICATE KEY UPDATE name = VALUES(name), start_at = VALUES(start_at), end_at = VALUES(end_at);

INSERT INTO seckill_sku (id, activity_id, sku_id, sku_name, seckill_price, stock) VALUES
    (1, 1, 1001, 'Headphones Black Flash', 499.00, 50),
    (2, 1, 2001, 'Running Shoes Size 42 Flash', 299.00, 80)
ON DUPLICATE KEY UPDATE sku_name = VALUES(sku_name), seckill_price = VALUES(seckill_price), stock = VALUES(stock);
