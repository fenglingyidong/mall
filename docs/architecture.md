# 架构说明

## 普通下单链路

```text
Client
  -> Docker Nginx 反向代理，监听 localhost:8080
  -> Spring Cloud Gateway 校验 Token，透传 X-User-Id / X-Username
  -> Gateway 通过 Nacos + lb://mall-order 路由到订单服务
  -> 订单服务 UserContextFilter 写入 UserContext，并在请求结束后清理
  -> Cart 查询选中商品
  -> Order 确认订单
  -> Order 通过 OpenFeign 调用 mall-cart/mall-product 内部接口
  -> Cart 先查 Redis 缓存，未命中后通过 MyBatis-Plus 回源 MySQL cart_item
  -> Product 通过 MyBatis-Plus JOIN 查询 MySQL 最新价格、库存和商品基础信息
  -> Order 开启 Seata 全局事务
  -> Product TCC Try 预留库存：stock 扣减，locked_stock 增加
  -> Order 写入 MySQL order_info/order_item 并记录状态 CREATED
  -> 全局事务提交后 Product TCC Confirm 扣减 locked_stock
  -> mall-message 写入 mq_message 后发布延迟关单消息
  -> mall.order.close.delay.queue 等待消息过期
  -> RabbitMQ 将过期消息转发到 mall.order.close.queue
  -> Order 手动 ACK 消费关单消息
  -> 仅关闭 CREATED 状态订单，并释放库存
```

## 秒杀链路

```text
Client
  -> Docker Nginx 反向代理，监听 localhost:8080
  -> Spring Cloud Gateway
  -> Gateway 通过 Nacos + lb://mall-seckill 路由到秒杀服务
  -> Seckill 使用 Sentinel 对 seckill-submit 资源限流
  -> Redisson 对同一用户同一活动 SKU 加短租约提交锁
  -> Redis Lua 原子判断库存和重复购买
  -> Seckill 写入 seckill_result(PROCESSING)
  -> mall-message 写入 mq_message 后发布秒杀下单消息
  -> mall.seckill.order.create.queue
  -> Order 手动 ACK 消费消息并创建秒杀订单
  -> Order 写入 order_info/order_item/seckill_order
  -> Order 发布秒杀结果消息
  -> mall.seckill.order.result.queue
  -> Seckill 手动 ACK 消费结果消息，更新 seckill_result 查询状态
  -> consume_record 消费幂等 + seckill_order(activity_id,user_id) 唯一约束防重复下单
```

## 服务治理

当前版本已接入 Spring Cloud 官方组件和 Spring Cloud Alibaba 组件：

- `nginx` 作为 Docker 部署的最外层入口，监听本机 `8080`，将 `/api/**` 反向代理到本机 `mall-gateway:8100`。
- `mall-gateway` 使用 Spring Cloud Gateway，路由目标为 `lb://mall-auth`、`lb://mall-product`、`lb://mall-cart`、`lb://mall-order`、`lb://mall-seckill`。
- 各业务服务使用 Nacos Discovery 注册实例，网关和 Feign 客户端通过服务名发现下游实例。
- `mall-common` 的 `UserContext` 使用 `TransmittableThreadLocal` 保存当前用户；Servlet 请求由 `UserContextFilter` 写入和清理上下文，异步线程池由 TTL 包装后自动传递上下文。
- `mall-product` 使用 OpenFeign 调用 `mall-review` 的评价摘要接口和 `mall-coupon` 的可领取优惠券接口，并用 `CompletableFuture` 并行聚合非核心展示信息；商品详情异步线程池通过 `TtlExecutors` 包装，避免异步任务丢失用户上下文。
- `mall-order` 使用 OpenFeign 调用 `mall-product` 的商品详情、库存扣减、库存释放接口，以及 `mall-cart` 的选中购物车查询和清理接口；普通订单创建用 Seata TCC 协调商品库存 Try/Confirm/Cancel。
- `mall-order` 的 Feign 客户端保留 fallback；商品详情可降级演示，库存扣减/释放降级为失败，避免商品服务不可用时误创建订单。
- `mall-seckill` 使用 Sentinel `SphU.entry("seckill-submit")` 和 `FlowRuleManager` 配置 QPS 规则，超过阈值返回业务码 `429`；同一用户重复秒杀提交由 Redisson 短租约锁保护。
- Gateway 与订单、秒杀服务均配置 Sentinel Dashboard 地址 `localhost:8858`，便于后续在控制台观察资源和规则。

## 数据持久化

当前版本已经把核心状态从内存迁移到 MySQL：

- `mall-product`：商品详情核心信息通过 `sku`、`spu`、`brand`、`category`、`sku_stock` 规范化表 JOIN 构建，不维护冗余详情宽表；库存扣减使用条件 SQL。
- `mall-review`：评价摘要落到 `review_summary`。
- `mall-coupon`：商品优惠券落到 `product_coupon`。
- `mall-cart`：购物车数据落到 `cart_item`，Redis Hash `cart:{userId}` 只作为缓存层。
- `mall-order`：订单主表、订单明细、秒杀订单绑定关系分别落到 `order_info`、`order_item`、`seckill_order`。
- `mall-seckill`：秒杀活动、秒杀商品、异步查询结果分别落到 `seckill_activity`、`seckill_sku`、`seckill_result`。
- `mall-message`：可靠消息和消费幂等分别落到 `mq_message`、`consume_record`。
- `undo_log`、`tcc_fence_log`：为 Seata 事务和 TCC Fence 提供基础表；当前库存 TCC 主要使用 `tcc_fence_log` 处理空回滚、悬挂和二阶段幂等。

## 分布式事务

普通订单创建链路使用 Seata TCC：

- Try：商品服务将 `sku_stock.stock` 扣减并增加 `locked_stock`，表示库存已为当前全局事务预留。
- Confirm：全局事务提交后，商品服务扣减 `locked_stock`，库存正式成交。
- Cancel：全局事务回滚时，商品服务释放 `locked_stock` 并加回 `stock`。
- TCC Fence 通过 `tcc_fence_log` 记录分支状态，避免空回滚、业务悬挂和重复 Confirm/Cancel。
- 订单支付前取消或延迟关单属于业务补偿，发生在全局事务已经提交之后，因此仍调用库存释放接口把已售库存加回。

## 缓存一致性

商品详情缓存目标是最终一致：

- 查询时先读本地缓存
- 本地未命中再读 Redis
- Redis 未命中后通过 MyBatis-Plus JOIN 回源查询 MySQL 构建商品核心详情主体，再查询同 SPU 的 SKU 选项
- 评价摘要和优惠券不进入商品核心缓存，由商品服务每次详情请求异步并行调用 `mall-review`、`mall-coupon` 聚合；异步任务通过 TransmittableThreadLocal 传递请求用户上下文
- 回源阶段用 JVM 互斥锁避免热点 Key 击穿
- MySQL 使用 ROW Binlog 记录商品相关表变更
- Canal Server 连接 MySQL 并拉取 Binlog
- `mall-product` 内置 Canal Client 订阅 `mall\.(sku|sku_stock|spu|brand|category)` 变更
- `sku`、`sku_stock` 变更直接失效对应 SKU 缓存
- `spu`、`brand`、`category` 变更先反查关联 SKU，再批量失效本地缓存和 Redis 缓存
- `/internal/product/cache/invalidate/{skuId}` 保留为手动缓存失效接口

```text
MySQL Binlog
  -> Canal Server
  -> mall-product Canal Client
  -> 解析 table + rowData
  -> 定位受影响 skuId
  -> ProductService.invalidate(skuId)
  -> 删除 JVM 本地缓存和 Redis product:detail:{skuId}
```

## 消息可靠性

当前版本已经接入 RabbitMQ：

- `mall.exchange`：普通业务交换机
- `mall.delay.exchange`：延迟关单入口交换机
- `mall.dlx`：死信交换机
- `mall.order.close.delay.queue`：延迟关单入口队列
- `mall.order.close.queue`：关单消费队列
- `mall.order.close.dlq`：关单死信队列
- `mall.seckill.order.create.queue`：秒杀异步下单队列
- `mall.seckill.order.create.dlq`：秒杀下单死信队列
- `mall.seckill.order.result.queue`：秒杀订单创建结果队列
- `mall.seckill.order.result.dlq`：秒杀结果死信队列

生产端可靠投递：

- `ReliableMessagePublisher` 发送前写入 `mq_message`。
- RabbitMQ Confirm ACK 后将 `mq_message.status` 标记为 `SENT`。
- RabbitMQ Confirm NACK 或 ReturnCallback 路由失败后将 `mq_message.status` 标记为 `FAILED`。
- 消息使用持久化投递模式。
- `MessageCompensationJob` 定时扫描 `NEW/FAILED` 消息，按照原交换机、Routing Key、Payload 和延迟时间重新发送。

消费端可靠处理：

- 订单服务通过 `@RabbitListener` 消费关单和秒杀下单消息。
- 秒杀服务通过 `@RabbitListener` 消费秒杀结果消息。
- 消费成功后手动 `basicAck`。
- 消费异常时 `basicNack(requeue=false)`，消息进入对应死信队列。
- 秒杀下单使用 `consume_record(message_id)` 唯一索引做消费幂等。
- 秒杀查询结果先返回 `PROCESSING`，结果消息消费成功后更新 `seckill_result` 为 `SUCCESS` 或 `FAILED`。
- 延迟关单使用订单状态机做幂等保护，已支付订单不会被误关。
