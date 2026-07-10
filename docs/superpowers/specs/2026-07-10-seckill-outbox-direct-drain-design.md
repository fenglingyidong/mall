# 秒杀订单 Outbox 直推与恢复设计

日期：2026-07-10

## 目标

将 `seckill_stock_change_log.DEDUCT/NEW -> mq_message(seckill.order.create)` 从全局、串行、定时轮询改为“事务提交后按逻辑分片直推，定时扫描兜底”的模型。10,000 请求压测中，入口窗口结束后该阶段的 P99 目标为 1–2 秒，用户最终结果 P99 小于 10 秒。

## 不变边界

- 提交入口仍只写 snapshot 和库存扣减事实，返回 `PROCESSING`；不在 HTTP 事务中写订单 outbox 或发送 RabbitMQ。
- `change_log` 是扣减事实和可靠恢复源；`mq_message` 是 RabbitMQ 发送的可靠 outbox。
- `OUTBOXED` 后的中心账本、订单服务消费、订单结果回写、库存释放语义不变。
- 当前仅部署一个 `mall-seckill` 实例；设计仍使用数据库 CAS 与消息幂等，以处理直推任务、恢复任务和崩溃恢复的交错。

## 提交后直推

`SeckillBucketService.afterDeducted()` 在同一库存事务中插入 `DEDUCT/NEW` change log 后，发布 `SeckillDeductCommittedEvent(requestId, bucketShardKey)`。

`@TransactionalEventListener(phase = AFTER_COMMIT)` 只调用 `SeckillOrderOutboxCoordinator.signal(bucketShardKey)`。该调用只设置内存 dirty 标志并向专用有界线程池提交任务；它不得查询数据库、构造消息或等待 RabbitMQ。提交任务被拒绝时记录指标并正常返回，后续恢复扫描负责补偿。

## 分片协调与执行器

每个 `bucketShardKey` 有一个进程内 `ShardSlot`：

- `dirty`：收到新扣减或恢复扫描发现待处理记录；
- `running`：当前是否已经有该分片的 drain 任务。

signal 合并同一分片的高频请求；worker 退出前清除 running 后再次检查 dirty，避免退出竞态漏信号。执行器是独立的 `ThreadPoolTaskExecutor`，初始配置为 core 4、max 8、队列 64、`AbortPolicy`、线程名前缀 `seckill-outbox-`。不可复用 RabbitMQ 发送执行器，不可使用 `CallerRunsPolicy`。

单个任务每批处理 200 条、连续最多 5 批；仍有积压时重新 signal，避免热分片永久占用 worker。每次查询必须带 `bucket_shard_key`，不再全局扫描 `NEW`。

## 批量状态推进与幂等

每个分片批次执行：

1. 查询该分片 `NEW` 的固定数量记录。
2. 批量 claim 为 `OUTBOXING`，并写入 UUID claim token 与毫秒级 claim 时间。
3. 仅处理 claim token 匹配的记录；批量读取 snapshot 和 SKU。
4. 为 DEDUCT 记录批量、幂等写入 `mq_message`；非 DEDUCT 记录不建订单消息，直接通过 outbox 阶段。
5. 以 `OUTBOXING + claim_token` 为条件批量更新为 `OUTBOXED`。
6. `mq_message` 所在事务提交后复用现有 `ReliableMessagePublisher` 的异步首次发送。

`OUTBOXING` 超时由恢复任务重置为 `NEW` 并清空 claim token。旧 worker 的 token 不再匹配，无法覆盖新 worker 的状态。

新增数据库约束：

- `seckill_stock_change_log(bucket_shard_key, status, id)` 索引；
- `seckill_stock_change_log.outbox_claim_token`、`outbox_claimed_at`；
- `mq_message(bucket_shard_key, routing_key, business_key)` 唯一键，用作秒杀建单消息最终去重。

历史数据迁移先检测重复建单消息；存在重复时明确失败，不自动删除可靠消息。

## 恢复与消息补偿

独立 scheduler 每秒按配置的逻辑分片调用 coordinator signal，并负责重置超过 5 秒的 `OUTBOXING` 记录。扫描与直推共用同一 coordinator，避免出现第二套并发模型。

`seckill.order.create` 的 `mq_message.NEW/FAILED` 需要单独的每秒补偿任务；保留通用 60 秒补偿任务给其他消息。这样 executor 拒绝、进程在 after-commit 发送前退出、RabbitMQ nack/returned 都能在 10 秒目标内重试。

## 观测与验收

记录每分片 backlog、最老 `NEW/OUTBOXING` 年龄、signal 合并/拒绝、批次条数/耗时/错误，以及 `change_log.created_at -> mq_message.created_at` 延迟。对 10,000 请求运行态压测验证：入口窗口结束后 change log 到 mq message 的 P99 小于 2 秒，订单结果闭环 P99 小于 10 秒；同时验证无重复消息、无重复订单、无遗留 backlog。
