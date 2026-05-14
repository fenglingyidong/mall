INSERT INTO user (id, username, password) VALUES
    (10001, 'alice', 'demo123'),
    (10002, 'bob', 'demo123'),
    (10003, 'carol', 'demo123'),
    (10004, 'dave', 'demo123'),
    (10005, 'erin', 'demo123')
ON DUPLICATE KEY UPDATE username = VALUES(username), password = VALUES(password);

INSERT INTO spu (id, name, category_id, brand_id) VALUES
    (102, '机械键盘', 10, 1),
    (103, '无线鼠标', 10, 1),
    (104, '城市通勤背包', 11, 2),
    (105, '不锈钢保温杯', 11, 2),
    (106, '瑜伽训练垫', 11, 2)
ON DUPLICATE KEY UPDATE name = VALUES(name), category_id = VALUES(category_id), brand_id = VALUES(brand_id);

INSERT INTO sku (id, spu_id, name, price) VALUES
    (3001, 102, '机械键盘 青轴', 299.00),
    (3002, 103, '无线鼠标 静音版', 129.00),
    (3003, 104, '城市通勤背包 20L', 259.00),
    (3004, 105, '不锈钢保温杯 500ml', 89.00),
    (3005, 106, '瑜伽训练垫 加厚款', 159.00)
ON DUPLICATE KEY UPDATE name = VALUES(name), price = VALUES(price);

INSERT INTO sku_stock (sku_id, stock, locked_stock) VALUES
    (3001, 180, 0),
    (3002, 260, 0),
    (3003, 150, 0),
    (3004, 500, 0),
    (3005, 220, 0)
ON DUPLICATE KEY UPDATE stock = VALUES(stock), locked_stock = VALUES(locked_stock);

INSERT INTO seckill_activity (id, name, start_at, end_at) VALUES
    (1, 'Spring Flash Sale', DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 1 DAY))
ON DUPLICATE KEY UPDATE name = VALUES(name), start_at = VALUES(start_at), end_at = VALUES(end_at);

INSERT INTO seckill_sku (id, activity_id, sku_id, sku_name, seckill_price, stock) VALUES
    (1, 1, 1001, 'Headphones Black Flash', 499.00, 50),
    (2, 1, 2001, 'Running Shoes Size 42 Flash', 299.00, 80)
ON DUPLICATE KEY UPDATE sku_name = VALUES(sku_name), seckill_price = VALUES(seckill_price), stock = VALUES(stock);
