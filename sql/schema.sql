CREATE USER IF NOT EXISTS 'canal'@'%' IDENTIFIED BY 'canal';
GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'canal'@'%';
FLUSH PRIVILEGES;

CREATE TABLE IF NOT EXISTS user (
    id BIGINT PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password VARCHAR(128) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS category (
    id BIGINT PRIMARY KEY,
    parent_id BIGINT NOT NULL DEFAULT 0,
    name VARCHAR(64) NOT NULL,
    KEY idx_category_parent (parent_id)
);

CREATE TABLE IF NOT EXISTS brand (
    id BIGINT PRIMARY KEY,
    name VARCHAR(64) NOT NULL
);

CREATE TABLE IF NOT EXISTS spu (
    id BIGINT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    category_id BIGINT NOT NULL,
    brand_id BIGINT NOT NULL,
    KEY idx_spu_category (category_id),
    KEY idx_spu_brand (brand_id)
);

CREATE TABLE IF NOT EXISTS sku (
    id BIGINT PRIMARY KEY,
    spu_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    price DECIMAL(10, 2) NOT NULL,
    KEY idx_sku_spu (spu_id)
);

CREATE TABLE IF NOT EXISTS sku_stock (
    sku_id BIGINT PRIMARY KEY,
    stock INT NOT NULL,
    locked_stock INT NOT NULL DEFAULT 0,
    CHECK (stock >= 0)
);

CREATE TABLE IF NOT EXISTS review_summary (
    sku_id BIGINT PRIMARY KEY,
    average_rating DECIMAL(3, 2) NOT NULL DEFAULT 0.00,
    review_count INT NOT NULL DEFAULT 0,
    good_rate DECIMAL(5, 2) NOT NULL DEFAULT 0.00,
    latest_review VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS product_coupon (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    sku_id BIGINT NOT NULL,
    title VARCHAR(128) NOT NULL,
    threshold_amount DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    discount_amount DECIMAL(10, 2) NOT NULL,
    expire_at DATETIME NOT NULL,
    stock INT NOT NULL DEFAULT 0,
    enabled TINYINT NOT NULL DEFAULT 1,
    KEY idx_product_coupon_sku (sku_id),
    KEY idx_product_coupon_expire (expire_at)
);

CREATE TABLE IF NOT EXISTS cart_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    sku_name VARCHAR(128) NOT NULL,
    price DECIMAL(10, 2) NOT NULL,
    quantity INT NOT NULL,
    checked TINYINT NOT NULL DEFAULT 1,
    UNIQUE KEY uk_cart_user_sku (user_id, sku_id)
);

CREATE TABLE IF NOT EXISTS order_info (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_sn VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    total_amount DECIMAL(10, 2) NOT NULL,
    source VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_order_sn (order_sn)
);

CREATE TABLE IF NOT EXISTS order_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_sn VARCHAR(64) NOT NULL,
    sku_id BIGINT NOT NULL,
    sku_name VARCHAR(128) NOT NULL,
    price DECIMAL(10, 2) NOT NULL,
    quantity INT NOT NULL,
    amount DECIMAL(10, 2) NOT NULL
);

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

CREATE TABLE IF NOT EXISTS seckill_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    activity_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    order_sn VARCHAR(64) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_seckill_user (activity_id, user_id)
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

CREATE TABLE IF NOT EXISTS undo_log (
    branch_id BIGINT NOT NULL COMMENT 'branch transaction id',
    xid VARCHAR(128) NOT NULL COMMENT 'global transaction id',
    context VARCHAR(128) NOT NULL COMMENT 'undo_log context,such as serialization',
    rollback_info LONGBLOB NOT NULL COMMENT 'rollback info',
    log_status INT NOT NULL COMMENT '0:normal status,1:defense status',
    log_created DATETIME(6) NOT NULL COMMENT 'create datetime',
    log_modified DATETIME(6) NOT NULL COMMENT 'modify datetime',
    UNIQUE KEY ux_undo_log (xid, branch_id)
);

CREATE TABLE IF NOT EXISTS tcc_fence_log (
    xid VARCHAR(128) NOT NULL COMMENT 'global transaction id',
    branch_id BIGINT NOT NULL COMMENT 'branch transaction id',
    action_name VARCHAR(64) NOT NULL COMMENT 'tcc action name',
    status TINYINT NOT NULL COMMENT 'tried:1; committed:2; rollbacked:3; suspended:4',
    gmt_create DATETIME(3) NOT NULL COMMENT 'create time',
    gmt_modified DATETIME(3) NOT NULL COMMENT 'update time',
    PRIMARY KEY (xid, branch_id),
    KEY idx_gmt_modified (gmt_modified),
    KEY idx_status (status)
);

INSERT INTO brand (id, name) VALUES (1, 'Mall Labs'), (2, 'North Star')
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO category (id, parent_id, name) VALUES (10, 0, '数码家电'), (11, 0, '运动户外')
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO user (id, username, password) VALUES
    (10001, 'alice', 'demo123'),
    (10002, 'bob', 'demo123'),
    (10003, 'carol', 'demo123'),
    (10004, 'dave', 'demo123'),
    (10005, 'erin', 'demo123')
ON DUPLICATE KEY UPDATE username = VALUES(username), password = VALUES(password);

INSERT INTO spu (id, name, category_id, brand_id) VALUES
    (100, '旗舰降噪耳机', 10, 1),
    (101, '轻量跑步鞋', 11, 2),
    (102, '机械键盘', 10, 1),
    (103, '无线鼠标', 10, 1),
    (104, '城市通勤背包', 11, 2),
    (105, '不锈钢保温杯', 11, 2),
    (106, '瑜伽训练垫', 11, 2)
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO sku (id, spu_id, name, price) VALUES
    (1001, 100, '旗舰降噪耳机 黑色', 699.00),
    (1002, 100, '旗舰降噪耳机 银色', 729.00),
    (2001, 101, '轻量跑步鞋 42码', 399.00),
    (3001, 102, '机械键盘 青轴', 299.00),
    (3002, 103, '无线鼠标 静音版', 129.00),
    (3003, 104, '城市通勤背包 20L', 259.00),
    (3004, 105, '不锈钢保温杯 500ml', 89.00),
    (3005, 106, '瑜伽训练垫 加厚款', 159.00)
ON DUPLICATE KEY UPDATE name = VALUES(name), price = VALUES(price);

INSERT INTO sku_stock (sku_id, stock, locked_stock) VALUES
    (1001, 200, 0),
    (1002, 120, 0),
    (2001, 300, 0),
    (3001, 180, 0),
    (3002, 260, 0),
    (3003, 150, 0),
    (3004, 500, 0),
    (3005, 220, 0)
ON DUPLICATE KEY UPDATE stock = VALUES(stock);

INSERT INTO review_summary (sku_id, average_rating, review_count, good_rate, latest_review) VALUES
    (1001, 4.80, 1268, 98.20, 'Noise cancellation is excellent for commuting.'),
    (1002, 4.70, 638, 96.50, 'Comfortable fit and clean sound.'),
    (2001, 4.60, 842, 95.10, 'Lightweight shoes with stable support.'),
    (3001, 4.90, 321, 99.00, 'Solid typing feel and sturdy build.'),
    (3002, 4.50, 416, 93.40, 'Quiet clicks and long battery life.')
ON DUPLICATE KEY UPDATE
    average_rating = VALUES(average_rating),
    review_count = VALUES(review_count),
    good_rate = VALUES(good_rate),
    latest_review = VALUES(latest_review);

INSERT INTO product_coupon (id, sku_id, title, threshold_amount, discount_amount, expire_at, stock, enabled) VALUES
    (1, 1001, '60 off over 599', 599.00, 60.00, DATE_ADD(NOW(), INTERVAL 30 DAY), 500, 1),
    (2, 1001, '30 off over 299', 299.00, 30.00, DATE_ADD(NOW(), INTERVAL 15 DAY), 800, 1),
    (3, 1002, '50 off over 599', 599.00, 50.00, DATE_ADD(NOW(), INTERVAL 30 DAY), 300, 1),
    (4, 2001, '40 off over 299', 299.00, 40.00, DATE_ADD(NOW(), INTERVAL 20 DAY), 600, 1),
    (5, 3001, '20 off over 199', 199.00, 20.00, DATE_ADD(NOW(), INTERVAL 10 DAY), 200, 1)
ON DUPLICATE KEY UPDATE
    title = VALUES(title),
    threshold_amount = VALUES(threshold_amount),
    discount_amount = VALUES(discount_amount),
    expire_at = VALUES(expire_at),
    stock = VALUES(stock),
    enabled = VALUES(enabled);

INSERT INTO seckill_activity (id, name, start_at, end_at) VALUES
    (1, 'Spring Flash Sale', DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 1 DAY))
ON DUPLICATE KEY UPDATE name = VALUES(name), start_at = VALUES(start_at), end_at = VALUES(end_at);

INSERT INTO seckill_sku (id, activity_id, sku_id, sku_name, seckill_price, stock) VALUES
    (1, 1, 1001, 'Headphones Black Flash', 499.00, 50),
    (2, 1, 2001, 'Running Shoes Size 42 Flash', 299.00, 80)
ON DUPLICATE KEY UPDATE sku_name = VALUES(sku_name), seckill_price = VALUES(seckill_price), stock = VALUES(stock);
