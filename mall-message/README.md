# mall-message 消息可靠性模块

`mall-message` 封装 RabbitMQ 交换机、队列、可靠投递、消息补偿和消费幂等能力。它是可复用基础模块，不是独立启动的微服务；当前由 `mall-order` 和 `mall-seckill` 引入使用。

更多整体背景见 [../README.md](../README.md) 和 [../docs/architecture.md](../docs/architecture.md)。

## 模块定位

- Maven artifact：`mall-message`
- 是否独立启动：否
- 主要依赖：`mall-common`、Spring AMQP、MyBatis-Plus、MySQL
- 使用方：`mall-order`、`mall-seckill`
- 数据库表：`mq_message`、`consume_record`

## 核心能力

- 声明 RabbitMQ 交换机、业务队列和死信队列。
- 发送消息前先写入 `mq_message` 表。
- RabbitMQ Confirm ACK 后标记消息为 `SENT`。
- RabbitMQ Confirm NACK 或 ReturnCallback 路由失败后标记消息为 `FAILED`。
- 消息投递模式设置为持久化。
- `MessageCompensationJob` 定时扫描 `NEW/FAILED` 消息并按原交换机、Routing Key、Payload、延迟时间重新投递。
- `ConsumeRecordRepository` 基于 `consume_record(message_id)` 唯一约束提供消费幂等。

## RabbitMQ 拓扑

交换机：

| 名称 | 类型 | 说明 |
| --- | --- | --- |
| `mall.exchange` | direct | 普通业务交换机 |
| `mall.delay.exchange` | direct | 延迟消息入口 |
| `mall.dlx` | direct | 死信交换机 |

队列：

| 队列 | Routing Key | 说明 |
| --- | --- | --- |
| `mall.order.close.delay.queue` | `order.close.delay` | 延迟关单入口队列 |
| `mall.order.close.queue` | `order.close` | 关单消费队列 |
| `mall.order.close.dlq` | `order.close.dlq` | 关单死信队列 |
| `mall.seckill.order.create.queue` | `seckill.order.create` | 秒杀异步下单队列 |
| `mall.seckill.order.create.dlq` | `seckill.order.create.dlq` | 秒杀下单死信队列 |
| `mall.seckill.order.result.queue` | `seckill.order.result` | 秒杀订单结果队列 |
| `mall.seckill.order.result.dlq` | `seckill.order.result.dlq` | 秒杀结果死信队列 |

## 关键代码

- `RabbitMessageConfig`：交换机、队列、绑定、消息转换器、手动 ACK Listener 工厂。
- `ReliableMessagePublisher`：可靠消息发布入口。
- `ReliableMessageRepository`：`mq_message` 状态读写。
- `ConsumeRecordRepository`：消费幂等记录。
- `MessageCompensationJob`：消息补偿任务。
- `MessageNames`：交换机、队列和 Routing Key 常量。

## 使用约定

- 业务服务引入本模块后，需要配置 RabbitMQ 和 MySQL。
- 需要补偿时开启：

```yaml
mall:
  message:
    compensation:
      enabled: true
      fixed-delay: 60000
```

- 消费端使用手动 ACK，业务成功后 `basicAck`，异常时 `basicNack(requeue=false)` 进入死信队列。

## 编译

在根目录编译依赖它的模块时会自动编译 `mall-message`：

```bash
mvn -pl mall-order -am package -DskipTests
```

也可以单独编译：

```bash
mvn -pl mall-message -am package -DskipTests
```
