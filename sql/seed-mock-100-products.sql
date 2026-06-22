-- Mock 100 SPU / 300 SKU product data for shopping-guide evaluation.
-- Safe id ranges:
--   brand:    100-109
--   category: 100-109
--   spu:      10000-10099
--   sku:      20000-20299
--   coupon:   30000-30299
--
-- This seed is repeatable. Business tables use INSERT ... ON DUPLICATE KEY UPDATE.

SET NAMES utf8mb4;

DROP TEMPORARY TABLE IF EXISTS mock_product_seq;
DROP TEMPORARY TABLE IF EXISTS mock_product_brand;
DROP TEMPORARY TABLE IF EXISTS mock_product_category;
DROP TEMPORARY TABLE IF EXISTS mock_product_sku_variant;

CREATE TEMPORARY TABLE mock_product_seq (
    seq INT PRIMARY KEY
);

CREATE TEMPORARY TABLE mock_product_brand (
    id BIGINT PRIMARY KEY,
    name VARCHAR(64) NOT NULL
);

CREATE TEMPORARY TABLE mock_product_category (
    id BIGINT PRIMARY KEY,
    parent_id BIGINT NOT NULL,
    name VARCHAR(64) NOT NULL,
    template_name VARCHAR(128) NOT NULL,
    base_price DECIMAL(10,2) NOT NULL
);

CREATE TEMPORARY TABLE mock_product_sku_variant (
    variant_no INT PRIMARY KEY,
    variant_name VARCHAR(64) NOT NULL,
    price_delta DECIMAL(10,2) NOT NULL
);

INSERT INTO mock_product_seq (seq)
SELECT ones.n + tens.n * 10 + 1 AS seq
FROM (
    SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
) ones
CROSS JOIN (
    SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
) tens
ORDER BY seq;

INSERT INTO mock_product_brand (id, name) VALUES
    (100, '云岭'),
    (101, '星野'),
    (102, '北辰'),
    (103, '澄光'),
    (104, '木棉'),
    (105, '童匠'),
    (106, '鲜峰'),
    (107, '墨森'),
    (108, '轻舟'),
    (109, '安和');

INSERT INTO mock_product_category (id, parent_id, name, template_name, base_price) VALUES
    (100, 0, '数码家电', '智能降噪耳机', 299.00),
    (101, 0, '运动户外', '轻量缓震跑鞋', 399.00),
    (102, 0, '家居生活', '多功能收纳架', 129.00),
    (103, 0, '食品饮料', '高蛋白坚果礼盒', 159.00),
    (104, 0, '美妆个护', '温和补水护肤套装', 229.00),
    (105, 0, '母婴玩具', '儿童益智积木套装', 189.00),
    (106, 0, '宠物用品', '全价营养猫粮', 169.00),
    (107, 0, '图书文具', '硬面效率笔记本', 69.00),
    (108, 0, '服饰鞋包', '城市通勤双肩包', 259.00),
    (109, 0, '健康护理', '家用健康监测仪', 349.00);

INSERT INTO mock_product_sku_variant (variant_no, variant_name, price_delta) VALUES
    (1, '标准款', 0.00),
    (2, '升级款', 80.00),
    (3, '礼盒款', 160.00);

INSERT INTO brand (id, name)
SELECT id, name
FROM mock_product_brand
ON DUPLICATE KEY UPDATE
    name = VALUES(name);

INSERT INTO category (id, parent_id, name)
SELECT id, parent_id, name
FROM mock_product_category
ON DUPLICATE KEY UPDATE
    parent_id = VALUES(parent_id),
    name = VALUES(name);

INSERT INTO spu (id, name, category_id, brand_id)
SELECT
    10000 + seq.seq - 1 AS id,
    CONCAT(category.template_name, ' ', LPAD(seq.seq, 3, '0')) AS name,
    category.id AS category_id,
    brand.id AS brand_id
FROM mock_product_seq seq
JOIN mock_product_category category
    ON category.id = 100 + MOD(seq.seq - 1, 10)
JOIN mock_product_brand brand
    ON brand.id = 100 + MOD(seq.seq + 2, 10)
ORDER BY seq.seq
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    category_id = VALUES(category_id),
    brand_id = VALUES(brand_id);

INSERT INTO sku (id, spu_id, name, price)
SELECT
    20000 + (seq.seq - 1) * 3 + variant.variant_no - 1 AS id,
    10000 + seq.seq - 1 AS spu_id,
    CONCAT(category.template_name, ' ', LPAD(seq.seq, 3, '0'), ' ', variant.variant_name) AS name,
    CAST(category.base_price + MOD(seq.seq * 17, 90) + variant.price_delta AS DECIMAL(10,2)) AS price
FROM mock_product_seq seq
JOIN mock_product_category category
    ON category.id = 100 + MOD(seq.seq - 1, 10)
CROSS JOIN mock_product_sku_variant variant
ORDER BY seq.seq, variant.variant_no
ON DUPLICATE KEY UPDATE
    spu_id = VALUES(spu_id),
    name = VALUES(name),
    price = VALUES(price);

INSERT INTO sku_stock (sku_id, stock, locked_stock)
SELECT
    20000 + (seq.seq - 1) * 3 + variant.variant_no - 1 AS sku_id,
    80 + MOD(seq.seq * 13 + variant.variant_no * 17, 420) AS stock,
    0 AS locked_stock
FROM mock_product_seq seq
CROSS JOIN mock_product_sku_variant variant
ORDER BY seq.seq, variant.variant_no
ON DUPLICATE KEY UPDATE
    stock = VALUES(stock),
    locked_stock = VALUES(locked_stock);

INSERT INTO review_summary (sku_id, average_rating, review_count, good_rate, latest_review)
SELECT
    20000 + (seq.seq - 1) * 3 + variant.variant_no - 1 AS sku_id,
    CAST(4.10 + MOD(seq.seq * 7 + variant.variant_no, 80) / 100.0 AS DECIMAL(3,2)) AS average_rating,
    80 + MOD(seq.seq * 37 + variant.variant_no * 19, 1800) AS review_count,
    CAST(90.00 + MOD(seq.seq * 11 + variant.variant_no * 5, 900) / 100.0 AS DECIMAL(5,2)) AS good_rate,
    CONCAT('用户反馈：', category.template_name, ' ', variant.variant_name, '稳定好用，适合日常使用。') AS latest_review
FROM mock_product_seq seq
JOIN mock_product_category category
    ON category.id = 100 + MOD(seq.seq - 1, 10)
CROSS JOIN mock_product_sku_variant variant
ORDER BY seq.seq, variant.variant_no
ON DUPLICATE KEY UPDATE
    average_rating = VALUES(average_rating),
    review_count = VALUES(review_count),
    good_rate = VALUES(good_rate),
    latest_review = VALUES(latest_review);

INSERT INTO product_coupon (
    id,
    sku_id,
    title,
    threshold_amount,
    discount_amount,
    expire_at,
    stock,
    enabled
)
SELECT
    30000 + (seq.seq - 1) * 3 + variant.variant_no - 1 AS id,
    20000 + (seq.seq - 1) * 3 + variant.variant_no - 1 AS sku_id,
    CONCAT('满', FLOOR(category.base_price + variant.price_delta), '减', 10 + MOD(seq.seq, 5) * 2 + variant.variant_no * 5) AS title,
    CAST(category.base_price + variant.price_delta AS DECIMAL(10,2)) AS threshold_amount,
    CAST(10 + MOD(seq.seq, 5) * 2 + variant.variant_no * 5 AS DECIMAL(10,2)) AS discount_amount,
    DATE_ADD(CURRENT_TIMESTAMP, INTERVAL (15 + MOD(seq.seq + variant.variant_no, 45)) DAY) AS expire_at,
    100 + MOD(seq.seq * 9 + variant.variant_no * 21, 900) AS stock,
    1 AS enabled
FROM mock_product_seq seq
JOIN mock_product_category category
    ON category.id = 100 + MOD(seq.seq - 1, 10)
CROSS JOIN mock_product_sku_variant variant
ORDER BY seq.seq, variant.variant_no
ON DUPLICATE KEY UPDATE
    sku_id = VALUES(sku_id),
    title = VALUES(title),
    threshold_amount = VALUES(threshold_amount),
    discount_amount = VALUES(discount_amount),
    expire_at = VALUES(expire_at),
    stock = VALUES(stock),
    enabled = VALUES(enabled);

DROP TEMPORARY TABLE IF EXISTS mock_product_sku_variant;
DROP TEMPORARY TABLE IF EXISTS mock_product_category;
DROP TEMPORARY TABLE IF EXISTS mock_product_brand;
DROP TEMPORARY TABLE IF EXISTS mock_product_seq;
