CREATE TABLE IF NOT EXISTS seckill_bucket_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    activity_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    bucket_count INT NOT NULL,
    route_mode VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    strategy_version BIGINT NOT NULL DEFAULT 1,
    survivor_buckets VARCHAR(1024),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_bucket_config_activity_sku (activity_id, sku_id)
);

CREATE TABLE IF NOT EXISTS seckill_stock_bucket (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    activity_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    bucket_no INT NOT NULL,
    bucket_type VARCHAR(16) NOT NULL,
    shard_key BIGINT NOT NULL DEFAULT 0,
    saleable_quantity INT NOT NULL DEFAULT 0,
    occupy_quantity INT NOT NULL DEFAULT 0,
    setting_quantity INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_bucket_activity_sku_no (activity_id, sku_id, bucket_no),
    KEY idx_bucket_activity_sku_status (activity_id, sku_id, bucket_type, status),
    KEY idx_bucket_activity_sku_shard (activity_id, sku_id, shard_key)
);

CREATE TABLE IF NOT EXISTS seckill_stock_change_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id VARCHAR(64),
    activity_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    bucket_id BIGINT NOT NULL,
    bucket_no INT NOT NULL,
    bucket_shard_key BIGINT NOT NULL DEFAULT 0,
    change_type VARCHAR(32) NOT NULL,
    quantity_delta INT NOT NULL,
    after_quantity INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_change_log_status (status, id),
    KEY idx_change_log_request (request_id),
    KEY idx_change_log_bucket (bucket_id)
);

ALTER TABLE seckill_stock_snapshot
    ADD COLUMN bucket_id BIGINT NULL AFTER stock_id,
    ADD COLUMN bucket_no INT NULL AFTER bucket_id,
    ADD COLUMN bucket_shard_key BIGINT NULL AFTER bucket_no,
    ADD COLUMN strategy_version BIGINT NULL AFTER bucket_shard_key,
    ADD COLUMN change_id BIGINT NULL AFTER strategy_version;
