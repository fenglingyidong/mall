# 秒杀 Stage 3C 与完整阶段三差距及下一步

## 结论

当前 `mall-seckill` 已经从早期 Stage 3A 的“单库分桶实验”推进到 Stage 3C：

```text
OceanBase 分桶分片库存 + ShardingSphere JDBC + reservation guard +
可靠消息 CAS 状态机 + RabbitMQ 异步建单 + 结果延迟重试
```

它已经不再是单行 `seckill_sku.stock` 热点扣减主链路。当前 `stage3c-sharding` profile 下，正式提交链路会选业务桶、提前把 `bucket_shard_key` 写入 guard，再按 `bucket_shard_key` 路由到 OceanBase 物理分片扣减业务桶。

但它仍不是完整生产级阶段三：中心桶总账、请求触发调拨、后台自动调拨已经进入当前运行态，碎片整理、真实多实例调度、跨分片调拨生产化、运营库存生命周期和生产级告警仍需继续补齐。

## 当前已经完成的能力

1. 分桶分片主链路

- `seckill_stock_bucket` 支持 `CENTER` 和 `BUCKET`。
- 当前 C 端热路径扣减 `BUCKET`，不扣 `CENTER`。
- `seckill_stock_snapshot`、`seckill_stock_change_log`、秒杀侧 `mq_message` 按 `bucket_shard_key` 路由。
- `seckill_stock_bucket` 按 `shard_key` 路由。
- 当前 ShardingSphere 配置使用 `ds_0`、`ds_1` 两个物理分片。

2. 资损止血 guard

- `seckill_reservation_guard` 当前作为 single table 固定在 `ds_0`，确保 `(activity_id, active_key)` 唯一约束具备全局语义。
- `requestId` 作为请求幂等号，HTTP 可通过 `X-Request-Id` 传入；当前 `reservationId` 默认复用规范化后的 `requestId`。
- 选桶后、扣减前先把 `bucket_id/bucket_no/bucket_shard_key` 持久化到 guard。
- 事务状态未知时保留 `PROCESSING`，交给 `SeckillReservationRepairJob` 根据 snapshot/outbox/change_log 判断，不能直接释放资格。
- `CONFIRMED` 后继续保留 `active_key`，避免成功后重复购买。

3. 可靠消息状态机

- 生产方 outbox 初始为 `NEW`。
- 发送前 CAS：`NEW/FAILED -> DISPATCHING`。
- confirm ack 只允许 `DISPATCHING -> SENT`。
- return、confirm nack、发送异常和发送超时只允许 `DISPATCHING -> FAILED`，并通过 `error_type` 区分 `RETURNED/CONFIRM_NACK/SEND_EXCEPTION/TIMEOUT`。
- `MessageCompensationJob` 会把超时 `DISPATCHING` 标为 `FAILED(TIMEOUT)`，再补偿重发 `NEW/FAILED`。

4. 订单结果消费止血

- `SUCCESS` 必须把 snapshot 推进到 `CONFIRMED` 后才写 `seckill_result=SUCCESS`。
- 建单失败只通过 `releaseDeduction(...)` 释放 `DEDUCTED`。
- 订单关闭/取消只通过 `releaseConfirmedDeduction(...)` 释放 `CONFIRMED`。
- 结果消费失败优先写 `seckill_result_retry` 并投递延迟重试消息，超过 `max-attempts=4` 后标记 `DLQ` 并告警，不做无限 requeue。

5. 后台治理能力

这些能力已经实现。其中 `center-ledger`、请求触发调拨、后台自动调拨已进入当前 `stage3c-sharding` 默认态；`reconcile` 仍默认关闭：

- `SeckillCenterBucketLedgerConsumer` / `SeckillCenterBucketLedgerApplier`
- `SeckillBucketTransferService`
- `SeckillBucketAutoTransferService` / `SeckillBucketAutoTransferJob`
- `SeckillBucketReconcileService` / `SeckillBucketReconcileJob`

## 当前运行态开关

`mall-seckill` 使用 `stage3c-sharding` profile 时：

```text
mall.seckill.bucket.enabled=true
mall.seckill.bucket.routing.physical-shard-count=2
mall.seckill.bucket.hot-path-aggregate-read=false
mall.seckill.reservation-guard.enabled=true
mall.seckill.result-retry.enabled=true
mall.seckill.lock.enabled=false
mall.seckill.stock-cache.enabled=true
mall.seckill.stock-cache.repair.enabled=true
mall.seckill.bucket.center-ledger.enabled=true
mall.seckill.bucket.transfer.enabled=true
mall.seckill.bucket.transfer.max-attempts=1
mall.seckill.bucket.auto-transfer.enabled=true
mall.seckill.bucket.reconcile.enabled=false
```

所以当前默认压测链路是：

```text
guard 占用 -> 选桶并附着 bucket_shard_key -> 分片业务桶扣减 ->
建单 outbox -> RabbitMQ -> MySQL 正式订单 -> 结果回传确认或释放
并且后台同时运行库存缓存修复、中心总账、请求触发调拨和自动调拨
```

## 与完整阶段三的差距

| 差距项 | 完整阶段三目标 | 当前状态 | 影响 |
| --- | --- | --- | --- |
| 默认治理开关 | 中心总账、调拨、碎片整理可在生产压测中稳定开启 | `center-ledger/transfer/auto-transfer` 已默认开启，`reconcile` 仍关闭 | 当前主压测仍缺少 `reconcile` 和治理专项验收 |
| 真实水平扩展 | 多实例、多租户或多物理库稳定承载热点 | 本地 ShardingSphere 双数据源模拟 | 不能直接宣称 8w QPS 生产能力 |
| 跨分片调拨 | 调拨具备跨分片锁、幂等和失败补偿 | 当前主要是单应用内能力 | 低库存尾部治理还需生产化 |
| 运营库存生命周期 | 支持创建、追加、B 端减库存、占用暂存、大事务规避 | 当前重点在 C 端扣减、释放和压测 | 还不是完整库存产品模块 |
| 告警与人工补偿 | DLQ、repair 异常、事实不完整都有告警和操作入口 | 当前以日志和状态记录为主 | 生产可运维性不足 |
| 压测口径 | 同时覆盖入口吞吐、异步追平、库存守恒、重复购买和乱序消息 | 已有多轮本地压测，仍需 Stage3C 专项汇总 | 简历或汇报口径必须限定环境 |

## 推荐下一步

1. 先固化 Stage3C 回归压测脚本

- 固化 `stage3c-sharding` 启动、重置、压测、追平等待和汇总。
- 验收必须覆盖 guard、snapshot、change_log、outbox、result、result_retry、Rabbit 队列和订单表。
- 增加同一 `X-Request-Id` 重复提交、同一用户重复购买、结果消息乱序、repair 与结果消费并发的回归项。

2. 固化已开启治理能力，再补 reconcile

- 先把已打开的 `center-ledger`、请求触发调拨、后台自动调拨做成稳定验收项，确认 `seckill_stock_change_log` 能收敛、低库存尾部不重复调拨。
- 再开 `reconcile`，验证 `EMPTY + saleable_quantity > 0` 能被修正。
- 这些治理能力都不应改变 C 端扣减事实，只做总账、调度和状态治理。

3. 最后恢复调拨能力专项压测

- 先测请求触发调拨，再测后台自动调拨。
- 调拨压测必须关注重复回补、重复调拨、源桶/目标桶分片条件、survivor 列表和中心总账收敛。
- 只有在低库存尾部不变量稳定后，再讨论生产级跨分片调度。

这个顺序的原因是：当前最重要的不变量已经变成“资格不重复、扣减不丢、释放不重、状态未知不误放”。吞吐优化必须在这些不变量可回归验证之后继续推进。
