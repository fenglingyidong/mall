# mall-order 订单服务

`mall-order` 负责普通订单确认、创建、支付、取消、超时关单，以及秒杀订单的异步创建。普通下单链路使用 Seata TCC 协调商品库存预留，订单创建后通过 RabbitMQ 延迟消息完成超时关单；秒杀订单的库存已经由 `mall-seckill` 预扣，订单侧不再二次扣商品库存。

更多整体背景见 [../README.md](../README.md) 和 [../docs/architecture.md](../docs/architecture.md)。

## 模块定位

- 服务名：`mall-order`
- 默认端口：`8104`
- 注册中心：Nacos `localhost:8848`
- Sentinel Dashboard：`localhost:8858`
- Seata TC：`localhost:8091`
- 数据库：`order_info`、`order_item`、`seckill_order`
- 消息表：`mq_message`、`consume_record`
- 主要依赖：`mall-common`、`mall-message`、Spring Web、OpenFeign、MyBatis-Plus、RabbitMQ、Nacos、Sentinel、Seata

## 核心功能

- 根据购物车已勾选商品确认订单金额。
- 创建普通订单：查询购物车、查询商品详情、TCC 预留库存、保存订单、清空购物车、发送延迟关单消息。
- 支付订单：`CREATED -> PAID`。
- 取消订单：`CREATED -> CANCELED`，普通订单释放商品库存，秒杀订单不释放商品库存。
- 超时关单：消费延迟关单消息，只关闭仍为 `CREATED` 的订单。
- 创建秒杀订单：消费秒杀下单消息，做消费幂等和一人一单校验，不再二次扣商品库存，成功后发送秒杀结果消息。

## 接口

对外接口：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/order/confirm` | 确认订单 |
| `POST` | `/api/order/create` | 创建普通订单 |
| `GET` | `/api/order/{orderSn}` | 查询订单 |
| `POST` | `/api/order/{orderSn}/pay` | 支付订单 |
| `POST` | `/api/order/{orderSn}/cancel` | 取消订单 |

内部接口：

| 方法 | 路径 | 调用方 | 说明 |
| --- | --- | --- | --- |
| `POST` | `/internal/order/close/{orderSn}` | 消息消费/内部联调 | 关闭未支付订单 |
| `POST` | `/internal/order/seckill` | 秒杀链路 | 创建秒杀订单 |

## 服务调用

- 调用 `mall-cart`：
  - `/internal/cart/selected`
  - `/internal/cart/selected/clear`
- 调用 `mall-product`：
  - `/internal/product/sku/{skuId}`
  - `/internal/product/stock/deduct`
  - `/internal/product/stock/release`

普通订单创建时，`@GlobalTransactional` 开启 Seata 全局事务；调用商品库存扣减接口时处于全局事务内，商品服务执行 TCC Try/Confirm/Cancel。秒杀订单创建只落订单数据，库存结果由 `mall-seckill` 的扣减快照确认或回补。

## 消息

消费队列：

- `mall.order.close.queue`：延迟关单。
- `mall.seckill.order.create.queue`：秒杀异步下单。
- 死信队列：`mall.order.close.dlq`、`mall.seckill.order.create.dlq`、`mall.seckill.order.result.dlq`。

生产消息：

- 普通订单和秒杀订单创建后发送延迟关单消息。
- 秒杀订单创建成功或失败后发送秒杀结果消息。

## 关键代码

- `OrderController`：订单 HTTP 接口。
- `OrderServiceImpl`：普通订单、秒杀订单、状态流转和库存释放。
- `OrderMessageListener`：RabbitMQ 消费关单消息和秒杀下单消息。
- `ProductClient`、`CartClient`：OpenFeign 内部服务调用。
- `OrderRepository`：订单、订单明细、秒杀订单绑定持久化。

## 启动

先启动 Nacos、MySQL、RabbitMQ、Seata、Sentinel，并确保 `mall-product`、`mall-cart` 可用，再运行：

```bash
java -jar target/mall-order-0.0.1-SNAPSHOT.jar
```

也可以在根目录编译指定模块：

```bash
mvn -pl mall-order -am package -DskipTests
```
