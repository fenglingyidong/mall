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
