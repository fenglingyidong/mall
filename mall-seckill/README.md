# mall-seckill 秒杀服务

`mall-seckill` 负责秒杀活动查询、秒杀提交、库存扣减、一人一单控制、扣减快照和异步结果查询。它通过 Sentinel 做入口流控，Redisson 做同用户重复提交保护，交易型数据库记录秒杀库存事实和扣减快照，TairString 按数据库版本维护库存读缓存，RabbitMQ 把真正创建订单的动作异步交给 `mall-order`。

更多整体背景见 [../README.md](../README.md) 和 [../docs/architecture.md](../docs/architecture.md)。

## 模块定位

- 服务名：`mall-seckill`
- 默认端口：`8105`
- 注册中心：Nacos `localhost:8848`
- Sentinel Dashboard：`localhost:8858`
- 数据库：`seckill_activity`、`seckill_sku(stock/version)`、`seckill_stock_snapshot`、`seckill_result`
- TairString Key：`seckill:stock-cache:{activityId}:{skuId}`
- 分布式锁：`seckill:submit:lock:{activityId}:{skuId}:{userId}`
- 主要依赖：`mall-common`、`mall-message`、Spring Web、MyBatis-Plus、OceanBase Connector/J、Redis/TairString、RabbitMQ、Nacos、Sentinel、Redisson

## 核心功能

- 查询秒杀活动和活动下的秒杀 SKU。
- 校验活动时间窗口。
- 使用 Sentinel 对 `seckill-submit` 资源做 QPS 流控。
- 使用 Redisson 锁拦截同一用户同一活动 SKU 的重复提交，默认由 watchdog 自动续约。
- 提交事务内条件扣减 `seckill_sku.stock` 并递增 `version`，库存不足直接失败。
- 写入 `seckill_stock_snapshot(DEDUCTED)` 作为扣减账本，并写入 `seckill_result(PROCESSING)`。
- 使用 TairString 保存 `stock/version` 库存缓存，低版本刷新不会覆盖高版本缓存。
- 提交成功后发布秒杀下单消息。
- 消费订单服务返回的秒杀结果消息：成功时只确认快照，失败时按快照回补秒杀库存并递增版本，再更新查询结果为 `SUCCESS` 或 `FAILED`。

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/seckill/activities` | 查询秒杀活动 |
| `POST` | `/api/seckill/{activityId}/{skuId}` | 提交秒杀请求 |
| `GET` | `/api/seckill/result/{requestId}` | 查询秒杀异步结果 |

结果状态：

- `ACCEPTED` / `PROCESSING`：请求已受理，订单仍在异步创建。
- `SUCCESS`：订单创建成功，返回 `orderSn`。
- `FAILED`：库存不足、重复购买或订单创建失败。

## 消息链路

```text
秒杀提交请求
  -> TairString 已售罄缓存快速失败
  -> 交易型数据库条件扣减 seckill_sku.stock/version
  -> 写入 seckill_stock_snapshot(DEDUCTED)
  -> 写入 seckill_result(PROCESSING)
  -> 按数据库 version 刷新 TairString 库存缓存
  -> mall-message 写入 mq_message
  -> 发布 mall.seckill.order.create.queue
  -> mall-order 消费并创建秒杀订单
  -> mall-order 发布 mall.seckill.order.result.queue
  -> mall-seckill 消费结果消息
  -> 确认快照，或释放快照并回补 seckill_sku.stock/version
  -> 按数据库 version 刷新 TairString 库存缓存
  -> 更新 seckill_result
```

消费成功后手动 ACK；异常时 `basicNack(requeue=false)`，消息进入对应死信队列。

## 关键代码

- `SeckillController`：秒杀 HTTP 接口。
- `SeckillServiceImpl`：活动校验、限流、加锁、扣库存、发布下单消息。
- `SentinelSeckillGuard`、`SentinelFlowRuleConfig`：秒杀入口限流。
- `SeckillStockCache`：TairString 版本化库存缓存，支持已售罄快速失败和低版本覆盖保护。
- `TairStringCommands`、`RedisTairStringCommands`：TairString `EXGET/EXSET` 最小命令适配。
- `RedissonConfig`：分布式锁客户端。
- `SeckillResultMessageListener`：消费订单创建结果消息，确认或释放扣减快照。
- `SeckillRepository`：秒杀活动、库存、扣减快照和结果持久化。

## OceanBase CE + TairString 阶段一模式

默认 `application.yml` 仍使用本地 MySQL 和普通 Redis，`mall.seckill.stock-cache.enabled=false`。如需按阶段一模式运行，可启用 `oceanbase` profile：

```bash
java -jar target/mall-seckill-0.0.1-SNAPSHOT.jar --spring.profiles.active=oceanbase
```

`application-oceanbase.yml` 使用 OceanBase CE MySQL 模式连接串，并把 Redis 端口指向本地 TairString 兼容实例。阶段一的库存事实源是 `seckill_sku.stock/version`；TairString 只承担读缓存和已售罄快速失败，不再作为扣减事实源。

## 锁配置

`mall.seckill.lock.lease-millis` 控制 Redisson 锁租约：

- `lease-millis <= 0`：使用 Redisson watchdog 自动续约，请求结束后主动释放锁。
- `lease-millis > 0`：使用固定租约，适合明确知道临界区耗时上限的场景。

## 启动

先启动 Nacos、MySQL、Redis、RabbitMQ、Sentinel，并确保 `mall-order` 可用，再运行：

```bash
java -jar target/mall-seckill-0.0.1-SNAPSHOT.jar
```

也可以在根目录编译指定模块：

```bash
mvn -pl mall-seckill -am package -DskipTests
```
