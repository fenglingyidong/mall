# 接口调用示例

以下示例默认通过 Docker Nginx 入口 `http://localhost:8080` 访问；也可以直接访问网关 `http://localhost:8100`。

## 数据库准备

首次启动 Nginx、MySQL、Redis、RabbitMQ、Nacos、Sentinel、Seata、Canal：

```powershell
docker compose up -d nginx mysql redis rabbitmq nacos sentinel seata canal
```

业务服务启动后，先确认 Nacos 服务列表能看到 `mall-auth`、`mall-product`、`mall-review`、`mall-coupon`、`mall-cart`、`mall-order`、`mall-seckill`，再通过 Nginx `8080` 调接口。Nginx 容器会把 `/api/**` 反向代理到本机 `mall-gateway:8100`。

如果服务启动时报 `nacos registry ... register failed`，先确认 Nacos 容器已经按当前 Compose 配置重建。Nacos 2.x 需要同时暴露 `8848` 和 `9848`：

```powershell
docker compose up -d --force-recreate nacos
```

如果之前已经创建过 MySQL 容器，重建本地演示容器让 Binlog 配置生效：

```powershell
docker compose up -d --force-recreate mysql canal
```

重建会清空容器内演示数据，之后重新导入 `schema.sql` 即可。

已有旧表结构时，先执行当前版本迁移脚本：

```powershell
docker compose exec -T -e MYSQL_PWD=root mysql mysql --default-character-set=utf8mb4 -uroot mall -e "source /docker-entrypoint-initdb.d/migration-v2-mybatis-message.sql"
```

如果旧库的 `cart_item` 表还没有 `sku_name`、`price` 字段，再执行：

```powershell
docker compose exec -T -e MYSQL_PWD=root mysql mysql --default-character-set=utf8mb4 -uroot mall -e "source /docker-entrypoint-initdb.d/migration-v3-cart-mysql.sql"
```

如果旧库还没有商品详情回源和缓存失效反查所需索引，再执行：

```powershell
docker compose exec -T -e MYSQL_PWD=root mysql mysql --default-character-set=utf8mb4 -uroot mall -e "source /docker-entrypoint-initdb.d/migration-v4-product-indexes.sql"
```

如果旧库还没有评价摘要和商品优惠券表，再执行：

```powershell
docker compose exec -T -e MYSQL_PWD=root mysql mysql --default-character-set=utf8mb4 -uroot mall -e "source /docker-entrypoint-initdb.d/migration-v5-review-coupon.sql"
```

如果旧库还没有 Seata TCC Fence 表，再执行：

```powershell
docker compose exec -T -e MYSQL_PWD=root mysql mysql --default-character-set=utf8mb4 -uroot mall -e "source /docker-entrypoint-initdb.d/migration-v6-seata-tcc.sql"
```

迁移脚本会重建 `mq_message`、`consume_record` 和秒杀相关表，适合本地演示库升级。

如果创建订单或消息补偿任务报下面这类错误，说明当前库里的 `mq_message` 还是旧表结构，也执行同一个迁移脚本：

```text
Unknown column 'exchange_name' in 'field list'
```

## Git Bash / macOS / Linux curl

### 登录

```bash
curl -s -X POST "http://localhost:8080/api/auth/login" \
  -H "Content-Type: application/json" \
  --data-raw '{"username":"alice","password":"123456"}'
```

复制返回结果里的 `data.token`：

```bash
TOKEN="替换成登录接口返回的真实token"
```

### 添加购物车

```bash
curl -s -X POST "http://localhost:8080/api/cart/items" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  --data-raw '{"skuId":1001,"skuName":"Headphones Black","price":699.00,"quantity":1}'
```

### 创建订单

```bash
curl -s -X POST "http://localhost:8080/api/order/confirm" \
  -H "Authorization: Bearer $TOKEN"

curl -s -X POST "http://localhost:8080/api/order/create" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  --data-raw '{}'
```

### 秒杀

```bash
curl -s "http://localhost:8080/api/seckill/activities"

curl -s -X POST "http://localhost:8080/api/seckill/1/1001" \
  -H "Authorization: Bearer $TOKEN"
```

提交成功后会先返回 `ACCEPTED`，说明秒杀请求已受理。复制返回结果里的 `data.requestId`：

```bash
REQUEST_ID="替换成秒杀接口返回的requestId"

curl -s "http://localhost:8080/api/seckill/result/$REQUEST_ID" \
  -H "Authorization: Bearer $TOKEN"
```

结果说明：

- `PROCESSING`：订单服务还没消费完秒杀下单消息，或秒杀服务还没消费到结果消息。
- `SUCCESS`：订单已创建，`orderSn` 有值。
- `FAILED`：订单创建失败或重复消费被拒绝。

## Canal 缓存失效验证

先查询一次商品，让商品详情进入本地缓存和 Redis：

```bash
curl -s "http://localhost:8080/api/product/1001"
```

修改商品价格，触发 MySQL Binlog：

```powershell
docker compose exec -T -e MYSQL_PWD=root mysql mysql --default-character-set=utf8mb4 -uroot mall -e "UPDATE sku SET price = price + 1 WHERE id = 1001;"
```

再次查询商品，`mall-product` 的 Canal Client 会消费 Binlog 并清理 `product:detail:1001` 缓存，返回值应反映数据库里的新价格：

```bash
curl -s "http://localhost:8080/api/product/1001"
```
