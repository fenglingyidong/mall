# mall-seckill 秒杀服务

`mall-seckill` 负责秒杀活动查询、秒杀提交、库存预扣、一人一单控制和异步结果查询。它通过 Sentinel 做入口流控，Redisson 做同用户重复提交保护，Redis Lua 做原子库存扣减，RabbitMQ 把真正创建订单的动作异步交给 `mall-order`。

更多整体背景见 [../README.md](../README.md) 和 [../docs/architecture.md](../docs/architecture.md)。

## 模块定位

- 服务名：`mall-seckill`
- 默认端口：`8105`
- 注册中心：Nacos `localhost:8848`
- Sentinel Dashboard：`localhost:8858`
- 数据库：`seckill_activity`、`seckill_sku`、`seckill_result`
- Redis Key：`seckill:stock:{activityId}:{skuId}`、`seckill:user:{activityId}:{skuId}`
- 分布式锁：`seckill:submit:lock:{activityId}:{skuId}:{userId}`
- 主要依赖：`mall-common`、`mall-message`、Spring Web、MyBatis-Plus、Redis、RabbitMQ、Nacos、Sentinel、Redisson

## 核心功能

- 查询秒杀活动和活动下的秒杀 SKU。
- 校验活动时间窗口。
- 使用 Sentinel 对 `seckill-submit` 资源做 QPS 流控。
- 使用 Redisson 短租约锁拦截同一用户同一活动 SKU 的重复提交。
- 使用 Redis Lua 原子完成库存扣减和一人一单标记。
- Redis 不可用时降级到 MySQL 条件扣减。
- 提交成功后写入 `seckill_result(PROCESSING)`，并发布秒杀下单消息。
- 消费订单服务返回的秒杀结果消息，更新查询结果为 `SUCCESS` 或 `FAILED`。

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
秒杀提交成功
  -> mall-message 写入 mq_message
  -> 发布 mall.seckill.order.create.queue
  -> mall-order 消费并创建秒杀订单
  -> mall-order 发布 mall.seckill.order.result.queue
  -> mall-seckill 消费结果消息
  -> 更新 seckill_result
```

消费成功后手动 ACK；异常时 `basicNack(requeue=false)`，消息进入对应死信队列。

## 关键代码

- `SeckillController`：秒杀 HTTP 接口。
- `SeckillServiceImpl`：活动校验、限流、加锁、扣库存、发布下单消息。
- `SentinelSeckillGuard`、`SentinelFlowRuleConfig`：秒杀入口限流。
- `RedisSeckillExecutor`：Redis Lua 原子扣库存和一人一单标记。
- `RedissonConfig`：分布式锁客户端。
- `SeckillResultMessageListener`：消费订单创建结果消息。
- `SeckillRepository`：秒杀活动、库存和结果持久化。

## 启动

先启动 Nacos、MySQL、Redis、RabbitMQ、Sentinel，并确保 `mall-order` 可用，再运行：

```bash
java -jar target/mall-seckill-0.0.1-SNAPSHOT.jar
```

也可以在根目录编译指定模块：

```bash
mvn -pl mall-seckill -am package -DskipTests
```
