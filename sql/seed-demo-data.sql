INSERT INTO brand (id, name) VALUES
    (1, 'Mall Labs'),
    (2, 'North Star'),
    (3, 'Aster'),
    (4, 'PeakLife'),
    (5, 'PureNest'),
    (6, 'FreshPeak')
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO category (id, parent_id, name) VALUES
    (10, 0, '数码家电'),
    (11, 0, '运动户外'),
    (12, 0, '家居生活'),
    (13, 0, '食品饮料'),
    (14, 0, '美妆个护'),
    (15, 0, '母婴玩具'),
    (16, 0, '宠物用品'),
    (17, 0, '图书文具')
ON DUPLICATE KEY UPDATE parent_id = VALUES(parent_id), name = VALUES(name);

INSERT INTO user (id, username, password) VALUES
    (10001, 'alice', 'demo123'),
    (10002, 'bob', 'demo123'),
    (10003, 'carol', 'demo123'),
    (10004, 'dave', 'demo123'),
    (10005, 'erin', 'demo123'),
    (10006, 'frank', 'demo123'),
    (10007, 'grace', 'demo123'),
    (10008, 'henry', 'demo123'),
    (10009, 'ivy', 'demo123'),
    (10010, 'jack', 'demo123'),
    (10011, 'kate', 'demo123'),
    (10012, 'leo', 'demo123')
ON DUPLICATE KEY UPDATE username = VALUES(username), password = VALUES(password);

INSERT INTO spu (id, name, category_id, brand_id) VALUES
    (100, '旗舰降噪耳机', 10, 1),
    (101, '轻量跑步鞋', 11, 2),
    (102, '机械键盘', 10, 1),
    (103, '无线鼠标', 10, 1),
    (104, '城市通勤背包', 11, 2),
    (105, '不锈钢保温杯', 12, 3),
    (106, '瑜伽训练垫', 11, 4),
    (107, '便携蓝牙音箱', 10, 3),
    (108, '智能台灯', 12, 4),
    (109, '高蛋白坚果礼盒', 13, 6),
    (110, '洁面补水套装', 14, 3),
    (111, '儿童积木套装', 15, 5),
    (112, '猫粮全价粮', 16, 6),
    (113, '硬面笔记本', 17, 5),
    (114, '电热水壶', 12, 4),
    (115, '真空收纳袋', 12, 5)
ON DUPLICATE KEY UPDATE name = VALUES(name), category_id = VALUES(category_id), brand_id = VALUES(brand_id);

INSERT INTO sku (id, spu_id, name, price) VALUES
    (1001, 100, '旗舰降噪耳机 黑色', 699.00),
    (1002, 100, '旗舰降噪耳机 银色', 729.00),
    (1003, 100, '旗舰降噪耳机 蓝牙升级版', 799.00),
    (2001, 101, '轻量跑步鞋 42码 黑色', 399.00),
    (2002, 101, '轻量跑步鞋 43码 白色', 399.00),
    (2003, 101, '轻量跑步鞋 44码 灰色', 419.00),
    (3001, 102, '机械键盘 青轴 104键', 299.00),
    (3002, 102, '机械键盘 茶轴 104键', 319.00),
    (3003, 102, '机械键盘 红轴 87键', 329.00),
    (3004, 103, '无线鼠标 静音版 黑色', 129.00),
    (3005, 103, '无线鼠标 静音版 白色', 129.00),
    (3006, 104, '城市通勤背包 20L 黑色', 259.00),
    (3007, 104, '城市通勤背包 20L 灰色', 259.00),
    (3008, 105, '不锈钢保温杯 500ml 白色', 89.00),
    (3009, 105, '不锈钢保温杯 750ml 黑色', 109.00),
    (3010, 106, '瑜伽训练垫 加厚款 紫色', 159.00),
    (3011, 106, '瑜伽训练垫 加厚款 灰蓝', 159.00),
    (3012, 107, '便携蓝牙音箱 深空灰', 189.00),
    (3013, 107, '便携蓝牙音箱 迷你版', 159.00),
    (3014, 108, '智能台灯 触控版 白色', 169.00),
    (3015, 108, '智能台灯 护眼版 黑色', 219.00),
    (3016, 109, '高蛋白坚果礼盒 12包', 139.00),
    (3017, 109, '高蛋白坚果礼盒 24包', 239.00),
    (3018, 110, '洁面补水套装 温和型', 199.00),
    (3019, 110, '洁面补水套装 清爽型', 199.00),
    (3020, 111, '儿童积木套装 300片', 149.00),
    (3021, 111, '儿童积木套装 500片', 239.00),
    (3022, 112, '猫粮全价粮 5kg', 189.00),
    (3023, 112, '猫粮全价粮 10kg', 329.00),
    (3024, 113, '硬面笔记本 A5 3本装', 39.00),
    (3025, 113, '硬面笔记本 A4 2本装', 49.00),
    (3026, 114, '电热水壶 1.5L', 119.00),
    (3027, 114, '电热水壶 1.8L', 139.00),
    (3028, 115, '真空收纳袋 4件套', 59.00),
    (3029, 115, '真空收纳袋 8件套', 99.00)
ON DUPLICATE KEY UPDATE name = VALUES(name), price = VALUES(price);

INSERT INTO sku_stock (sku_id, stock, locked_stock) VALUES
    (1001, 200, 0),
    (1002, 120, 0),
    (1003, 80, 0),
    (2001, 300, 0),
    (2002, 260, 0),
    (2003, 180, 0),
    (3001, 180, 0),
    (3002, 160, 0),
    (3003, 120, 0),
    (3004, 260, 0),
    (3005, 220, 0),
    (3006, 150, 0),
    (3007, 140, 0),
    (3008, 500, 0),
    (3009, 380, 0),
    (3010, 220, 0),
    (3011, 210, 0),
    (3012, 160, 0),
    (3013, 140, 0),
    (3014, 130, 0),
    (3015, 90, 0),
    (3016, 300, 0),
    (3017, 180, 0),
    (3018, 260, 0),
    (3019, 240, 0),
    (3020, 160, 0),
    (3021, 110, 0),
    (3022, 340, 0),
    (3023, 200, 0),
    (3024, 600, 0),
    (3025, 420, 0),
    (3026, 190, 0),
    (3027, 170, 0),
    (3028, 310, 0),
    (3029, 210, 0)
ON DUPLICATE KEY UPDATE stock = VALUES(stock), locked_stock = VALUES(locked_stock);

INSERT INTO review_summary (sku_id, average_rating, review_count, good_rate, latest_review) VALUES
    (1001, 4.80, 1268, 98.20, 'Noise cancellation is excellent for commuting.'),
    (1002, 4.70, 638, 96.50, 'Comfortable fit and clean sound.'),
    (1003, 4.76, 418, 97.20, 'Battery life is longer than expected.'),
    (2001, 4.60, 842, 95.10, 'Lightweight shoes with stable support.'),
    (2002, 4.58, 520, 94.80, 'The white color looks clean and neat.'),
    (2003, 4.54, 301, 94.20, 'Good grip and not heavy.'),
    (3001, 4.90, 321, 99.00, 'Solid typing feel and sturdy build.'),
    (3002, 4.82, 286, 98.10, 'Switch feel is balanced for work.'),
    (3003, 4.77, 214, 97.50, 'Compact layout saves desk space.'),
    (3004, 4.50, 416, 93.40, 'Quiet clicks and long battery life.'),
    (3005, 4.48, 233, 93.10, 'Simple look and easy to carry.'),
    (3006, 4.70, 205, 96.20, 'Good size for commuting and business trips.'),
    (3007, 4.68, 190, 95.90, 'Storage pockets are practical.'),
    (3008, 4.85, 980, 98.70, 'Keeps drinks warm for a long time.'),
    (3009, 4.73, 512, 97.10, 'Larger capacity is convenient.'),
    (3010, 4.75, 560, 97.30, 'Cushioning is great for stretching.'),
    (3011, 4.66, 387, 95.60, 'Thickness is comfortable.'),
    (3012, 4.55, 188, 94.60, 'Small but loud enough for the room.'),
    (3013, 4.49, 144, 93.90, 'Good for bedside use.'),
    (3014, 4.65, 244, 95.80, 'Soft light and easy touch controls.'),
    (3015, 4.71, 176, 96.40, 'Eye comfort is noticeably better.'),
    (3016, 4.72, 133, 96.90, 'Tastes fresh and the pack size is handy.'),
    (3017, 4.78, 98, 97.60, 'Good for gifting and stock at home.'),
    (3018, 4.58, 167, 94.90, 'Gentle and comfortable for daily use.'),
    (3019, 4.61, 142, 95.10, 'Feels refreshing after washing.'),
    (3020, 4.83, 212, 98.10, 'Pieces fit well and instructions are clear.'),
    (3021, 4.76, 148, 97.20, 'Enough pieces to keep kids engaged.'),
    (3022, 4.64, 266, 95.40, 'Cats like the smell and texture.'),
    (3023, 4.69, 181, 95.80, 'Large pack is better value.'),
    (3024, 4.57, 320, 94.70, 'Paper is smooth and writing is clean.'),
    (3025, 4.60, 212, 95.00, 'Hard cover feels durable.'),
    (3026, 4.66, 280, 95.60, 'Boils water quickly and is easy to clean.'),
    (3027, 4.59, 190, 94.80, 'Capacity is enough for a family.'),
    (3028, 4.62, 230, 95.20, 'Vacuum effect is decent for home use.'),
    (3029, 4.68, 160, 95.90, 'Eight-piece set is very practical.')
ON DUPLICATE KEY UPDATE
    average_rating = VALUES(average_rating),
    review_count = VALUES(review_count),
    good_rate = VALUES(good_rate),
    latest_review = VALUES(latest_review);

INSERT INTO product_coupon (id, sku_id, title, threshold_amount, discount_amount, expire_at, stock, enabled) VALUES
    (1, 1001, '满599减60', 599.00, 60.00, DATE_ADD(NOW(), INTERVAL 30 DAY), 500, 1),
    (2, 1002, '满699减80', 699.00, 80.00, DATE_ADD(NOW(), INTERVAL 20 DAY), 260, 1),
    (3, 2001, '满299减40', 299.00, 40.00, DATE_ADD(NOW(), INTERVAL 20 DAY), 600, 1),
    (4, 3001, '满199减30', 199.00, 30.00, DATE_ADD(NOW(), INTERVAL 15 DAY), 300, 1),
    (5, 3004, '满99减15', 99.00, 15.00, DATE_ADD(NOW(), INTERVAL 18 DAY), 900, 1),
    (6, 3006, '满199减20', 199.00, 20.00, DATE_ADD(NOW(), INTERVAL 25 DAY), 180, 1),
    (7, 3008, '满79减10', 79.00, 10.00, DATE_ADD(NOW(), INTERVAL 16 DAY), 1000, 1),
    (8, 3014, '满149减25', 149.00, 25.00, DATE_ADD(NOW(), INTERVAL 22 DAY), 250, 1),
    (9, 3016, '满129减20', 129.00, 20.00, DATE_ADD(NOW(), INTERVAL 12 DAY), 500, 1),
    (10, 3026, '满99减15', 99.00, 15.00, DATE_ADD(NOW(), INTERVAL 10 DAY), 220, 1)
ON DUPLICATE KEY UPDATE
    title = VALUES(title),
    threshold_amount = VALUES(threshold_amount),
    discount_amount = VALUES(discount_amount),
    expire_at = VALUES(expire_at),
    stock = VALUES(stock),
    enabled = VALUES(enabled);

INSERT INTO seckill_activity (id, name, start_at, end_at) VALUES
    (1, '春季秒杀', DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 1 DAY)),
    (2, '周末秒杀', DATE_SUB(NOW(), INTERVAL 2 HOUR), DATE_ADD(NOW(), INTERVAL 2 DAY)),
    (3, '夜场秒杀', DATE_SUB(NOW(), INTERVAL 30 MINUTE), DATE_ADD(NOW(), INTERVAL 18 HOUR))
ON DUPLICATE KEY UPDATE name = VALUES(name), start_at = VALUES(start_at), end_at = VALUES(end_at);

INSERT INTO seckill_sku (id, activity_id, sku_id, sku_name, seckill_price, stock) VALUES
    (1, 1, 1001, '旗舰降噪耳机 黑色', 499.00, 50),
    (2, 1, 2001, '轻量跑步鞋 42码 黑色', 299.00, 80),
    (3, 2, 3001, '机械键盘 青轴 104键', 249.00, 60),
    (4, 2, 3008, '不锈钢保温杯 500ml 白色', 59.00, 120),
    (5, 3, 3014, '智能台灯 触控版 白色', 129.00, 70),
    (6, 3, 3026, '电热水壶 1.5L', 99.00, 100)
ON DUPLICATE KEY UPDATE sku_name = VALUES(sku_name), seckill_price = VALUES(seckill_price), stock = VALUES(stock);
