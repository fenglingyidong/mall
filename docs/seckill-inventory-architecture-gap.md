# 秒杀库存架构差异与 Stage 3C 演进记录

## 总览

当前 `mall-seckill` 已从早期的“Redis 资格准入 + MySQL 异步落账”和 Stage 3A 单库分桶实验，演进到当前 Stage 3C 止血版：

- `stage3c-sharding` profile 下，库存事实源是 OceanBase 分桶分片库存，不是 Redis/TairString。
- HTTP 提交链路先创建全局 `seckill_reservation_guard`，再选桶并在扣减前持久化 `bucket_shard_key`。
- `seckill_stock_snapshot`、`seckill_stock_change_log`、秒杀侧 `mq_message` 按 `bucket_shard_key` 通过 ShardingSphere 路由。
- RabbitMQ 异步创建秒杀订单，结果消息再把库存侧 snapshot 和 guard 推进到 `CONFIRMED` 或 `RELEASED`。
- 可靠消息已经改成 `NEW/FAILED -> DISPATCHING -> SENT/FAILED` 的 CAS 状态机；结果消费失败走 `seckill_result_retry` 延迟重试和最大次数兜底。

`inventory_hotspot_seckill_architecture_markdown/inventory_hotspot_seckill_architecture.md` 描述的是资损安全优先的库存扣减系统：先把库存扣减迁到事务型数据库，再通过中心桶 + 分桶模型、分片路由、调拨和账本闭环支撑热点流量。当前实现已经具备分桶分片主链路、guard 止血、可靠消息状态机和结果重试；当前 `stage3c-sharding` 运行态已经打开库存版本缓存、中心桶异步总账、请求触发调拨和后台自动调拨，只有 `reconcile` 仍默认关闭。

两者最大的差别仍然不是组件数量，而是生产级分片能力、调度治理和可运维补偿。当前实现可以验证资损不变量和分桶分片扣减闭环，但不能证明真实分库分片环境里的 8W QPS。

## 当前真实实现

### 1. 库存权威源

当前 Stage3C 主入口不再依赖 Redis/TairString 作为资格准入账本。当前 `stock-cache.enabled=true`、`stock-cache.repair.enabled=true`，Redis/TairString 负责售罄快速失败和后台追平；库存事实仍由 OceanBase 分桶事务承担。

重复购买强约束在 `seckill_reservation_guard(activity_id, active_key)`。当前 guard 表作为 ShardingSphere single table 固定在 `ds_0`，避免分片内唯一索引被误当成全局唯一。

### 2. 快照与可靠消息

`seckill_stock_snapshot` 记录 `requestId`、活动、SKU、用户、数量、状态和分桶扣减位置。订单创建成功后确认快照，订单创建失败或取消路径按快照做精确回补。

当前新增了 `seckill_reservation_guard`：

- 请求幂等：`requestId` 唯一。
- 用户占用：`activity_id + active_key` 唯一。
- 扣减定位：选桶后、扣减前持久化 `bucket_shard_key`。
- 未知事务状态：保持 `PROCESSING`，由 repair 核查 snapshot/outbox/change_log 后再推进。

秒杀订单创建不再调用 `mall-product` 二次扣商品库存，秒杀订单关单时也不释放商品库存，避免 `seckill_sku.stock` 和商品库存双扣/双放。

### 3. 分桶库存与中心桶总账

`seckill_stock_bucket` 中存在一个 `CENTER` 中心桶和多个 `BUCKET` 业务桶：

- C 端提交链路扣业务桶。
- `seckill_stock_change_log` 记录 `DEDUCT`、`RELEASE`、`TRANSFER_OUT`、`TRANSFER_IN` 等变更。
- 中心桶后台消费者可以批量读取 `NEW` 日志，按 `quantity_delta` 聚合后更新中心桶，并把日志标记为 `APPLIED`。
- 当前 `stage3c-sharding` profile 下 `center-ledger.enabled=true`，中心桶总账能力已经参与默认运行态，但仍是异步总账，不重新成为 C 端扣减热点。
- 中心桶是异步总账，只用于展示、监控、调拨依据和账本校验，不能重新成为 C 端扣减热点。

### 4. 调拨与存活桶列表

后台自动调拨、请求触发调拨和 reconcile 都已经有代码实现；当前 `stage3c-sharding` profile 的默认开关是：

- `bucket.transfer.enabled=true`
- `bucket.auto-transfer.enabled=true`
- `bucket.reconcile.enabled=false`

因此当前主压测链路除了分片业务桶扣减、建单和结果回传，也会带着调拨和中心总账治理能力一起运行；但调拨跨分片条件、幂等、survivor 列表和中心总账收敛仍需要专项压测，`reconcile` 也还没有进入默认回归集。

## 与完整阶段三的差距

### 1. 单库模拟不能证明生产级水平扩展

当前通过 ShardingSphere JDBC 把 `seckill_stock_bucket`、`seckill_stock_snapshot`、`seckill_stock_change_log`、秒杀侧 `mq_message` 路由到两个 OceanBase 数据源。它比 Stage 3A 单库分桶更接近真实分片，但仍是本地双数据源压测，不能等价于生产级多实例、多租户或多机房部署，也不能证明文档中的 8W QPS。

### 2. 状态机需要持续回归并发不变量

阶段三的分桶模型不仅需要“能扣、能调拨”，还需要保证桶状态、资格 guard、snapshot、change_log、outbox 和结果消息之间永远一致。当前已补上 guard、事务未知状态保守处理、结果消费状态区分、可靠消息 CAS 和结果延迟重试，但这些不变量仍需要持续回归压测。

### 3. 调拨碎片治理仍需生产化

当前已经具备后台状态整理和后台自动调拨代码，但默认压测 profile 关闭。后续需要在 Stage3C 止血不变量稳定后，单独验证“有碎片、有偏斜、总库存未空”时自动调拨是否有效，并覆盖真实分片路由、跨分片调拨、任务分片、调度限流、失败补偿和多实例并发治理。

Stage 3B 性能调优后，热路径扣减默认不再做业务桶聚合读；请求触发调拨通过 `request-fallback-min-interval-millis` 降级为低频兜底，让后台自动调拨成为主要治理手段。

#### Stage 3B 性能调优 A/B 压测结论

最新 2 分钟稳定库存 A/B 压测结果已经沉淀到 `target/loadtest/stage3b-ab-summary-20260706-232925.json`。本轮库存为 3000000，线程数为 300，warmup 为 30 秒，正式压测为 120 秒；两组使用同一套 Redis/TairString、RabbitMQ 和 OceanBase 环境。

| 指标 | A：旧热路径聚合读 | B：去聚合读 + 500ms 请求兜底 | 变化 |
| --- | ---: | ---: | ---: |
| `hot-path-aggregate-read` | `true` | `false` | - |
| `request-fallback-min-interval-millis` | `0` | `500` | - |
| HTTP QPS | 416.835 | 461.359 | +10.681% |
| HTTP P95 | 1293 ms | 1212 ms | -6.265% |
| HTTP P99 | 1666 ms | 1674 ms | +0.48% |
| `seckill.submit.stock-cache.refresh` count | 50326 | 0 | -50326 |
| `seckill.submit.record.stock.update` P95 | 894.785 ms | 894.785 ms | 0 |
| `seckill.submit.record.stock.update` P99 | 1431.656 ms | 1431.656 ms | 0 |
| 库存/账本验收 | 通过 | 通过 | - |

结论：

- 去掉热路径聚合读后，`stock-cache.refresh` 从请求数级别降为 0，HTTP QPS 和 P95 有明确改善。
- `seckill.submit.record.stock.update` 的 P95/P99 没有改善，说明当前下一层瓶颈已经进入库存扣减主路径内部，而不是 TairString 缓存刷新。
- 下一步需要把 `stock.update` 拆成 `bucket.route/select`、`bucket.db.deduct`、`change-log.insert`、`mq.enqueue/after-commit/send`、`transfer.lock.wait` 等更细粒度指标，再判断慢点来自 OceanBase 条件扣减、变更日志写入、可靠消息 afterCommit 发送还是低频请求侧调拨。

新增细粒度观测点：

- `seckill.submit.record.bucket.route`：业务桶路由和 survivor 桶选择耗时。
- `seckill.submit.record.bucket.db.deduct`：业务桶条件扣减 SQL 耗时。
- `seckill.submit.record.bucket.change-log.insert`：扣减变更日志插入耗时。
- `seckill.submit.record.bucket.stock-version`：扣减后库存版本读取耗时；`hot-path-aggregate-read=false` 时应接近 0 次。
- `seckill.submit.record.bucket.transfer.total`：请求侧低频调拨兜底总耗时。
- `seckill.submit.record.bucket.transfer.lock.wait`：请求侧调拨 Redisson 锁等待耗时。
- `seckill.submit.record.bucket.transfer.db.move`：请求侧调拨 source 扣减和 target 增加耗时。
- `seckill.submit.record.bucket.transfer.change-log.insert`：请求侧调拨 OUT/IN 日志插入耗时。
- `seckill.submit.record.mq.enqueue`：可靠消息入队耗时，包含 outbox 保存和 afterCommit 调度注册。
- `reliable.message.after-commit.dispatch`：事务提交后把可靠消息交给异步发送线程池的耗时。
- `reliable.message.mq.send`：RabbitTemplate `convertAndSend` 真实发送调用耗时。

压测验收口径同步调整：

- `cachePolicy.rawMatchesAggregate` 继续保留严格对齐结果，用于观察 TairString 是否和业务桶聚合值完全一致。
- `cachePolicy.expectedForPolicy` 作为验收项；`hot-path-aggregate-read=true` 时允许最多 1 个版本/库存单位的并发尾部滞后，`hot-path-aggregate-read=false` 时仍要求缓存为空。
- 新增细粒度 timer 已加入 `management.metrics.distribution.percentiles-histogram`，服务重启后 Prometheus 会导出对应 `_bucket`，下一轮压测 summary 才能计算 P95/P99。

### 4. 压测口径需要拆分

稳定库存压测和低库存售罄压测需要分开看：

- 稳定库存压测关注 HTTP QPS、P95/P99、数据库计时器和消息积压。
- 低库存打空压测关注最终库存是否为 0、中心桶是否收敛、变更日志是否全部消费、是否存在不可达库存、售罄后的业务失败是否符合预期。

固定时长压测里，库存打空后的 `Stock not enough` 不能简单视为系统错误；但 HTTP 500、超时、消息堆积和库存残留必须视为真实问题。

## 低库存打空残留问题与状态机修复方案

当前代码已经按本节原则落地了条件标空：`markEmptyIfNoSaleable(id, shardKey)` / `markEmptyIfNoSaleableByShard(id, shardKey)` 都带 `saleable_quantity <= 0` 条件，调拨 source 标空也会传入 `shard_key`。本节保留为问题背景和后续压测验收口径。

### 问题现象

低库存 `Stock=1500`、`transfer=true`、`center-ledger=true` 压测中，最终出现：

- 订单成功数少于 1500。
- 业务桶聚合库存和中心桶均残留少量库存。
- 部分业务桶状态为 `EMPTY`，但 `saleable_quantity > 0`。
- RabbitMQ 队列无堆积，中心桶日志已消费，说明问题不是异步总账滞后，而是业务桶库存变成不可达。

### 根因

扣减失败后会读取桶库存并尝试把空桶摘出 survivor 列表；调拨成功后会给目标桶增加库存并置为 `ACTIVE`。如果这两个动作并发交错，可能发生：

1. 线程 A 读到某桶库存为 0，准备标记 `EMPTY`。
2. 线程 B 调拨库存到同一个桶，并把桶置为 `ACTIVE`。
3. 线程 A 继续执行无条件状态更新，把桶重新标记为 `EMPTY`。
4. 该桶仍有库存，但已经不在 survivor 列表中，请求无法再路由到它。

核心缺陷是状态转移没有把库存数量作为数据库条件，`EMPTY` 标记不是原子条件更新。

### 修复原则

桶状态必须满足以下不变量：

- `saleable_quantity > 0` 的业务桶必须可被恢复为 `ACTIVE` 并进入 survivor 列表。
- 只有数据库当前值满足 `saleable_quantity <= 0` 时，才允许把桶从 `ACTIVE` 标记为 `EMPTY`。
- 只有成功把桶标记为 `EMPTY` 的线程，才允许把该桶从 survivor 列表移除。
- 调拨加库存和回补加库存必须把桶置为 `ACTIVE`，并补回 survivor 列表。
- 中心桶只反映异步总账，不参与 C 端扣减路由，不负责修正业务桶状态。

### 代码方案

1. 在 `SeckillStockBucketMapper` 中新增条件状态更新：
   - `markEmptyIfNoSaleable(id, shardKey)`：`WHERE id = ? AND shard_key = ? AND bucket_type = 'BUCKET' AND saleable_quantity <= 0`。
   - 返回更新行数，作为是否移除 survivor 的依据。

2. 将扣减成功后、扣减失败后、调拨 source 耗尽后的标空逻辑都改为条件标空。

3. 调整 `offlineIfEmpty`：
   - 不再无条件调用通用 `updateStatus`。
   - 只有 `markEmptyIfNoSaleable` 成功时，才调用 `removeSurvivorBucket`。

4. 保留调拨目标桶和回补桶的 `ACTIVE` 恢复逻辑：
   - `addTransferTarget` / `releaseSaleableAndIncreaseVersion` 继续设置 `status = 'ACTIVE'`。
   - `onlineIfAvailable` 继续补回 survivor 列表。

5. 增加测试覆盖：
   - 当桶快照显示库存为 0，但数据库条件标空返回 0 时，不移除 survivor。
   - 调拨 source 标空也必须走条件更新。
   - 低库存压测验收增加 `EMPTY + saleable_quantity > 0` 计数断言。

### 验收标准

低库存打空场景应满足：

- `transfer=false`：成功订单数等于库存数，业务桶聚合库存为 0，中心桶收敛为 0。
- `transfer=true`：成功订单数等于库存数，业务桶聚合库存为 0，中心桶收敛为 0。
- `seckill_stock_change_log` 无长期 `NEW` / `PROCESSING` 堆积。
- `seckill_stock_bucket` 中不存在 `bucket_type = 'BUCKET' AND status = 'EMPTY' AND saleable_quantity > 0`。
- RabbitMQ 秒杀下单、结果和关单队列无长期堆积。

## 推荐后续路线

### A. 先固化资损止血回归

优先把这些不变量做成稳定回归：

- 同一个 `requestId` 重复提交只返回同一笔 reservation，不重复扣减。
- 同一用户成功 `CONFIRMED` 后继续占用 `active_key`。
- guard 已写 `bucket_shard_key` 但扣减事务状态未知时，不释放资格。
- `SUCCESS` 和 `FAILED/CANCELED` 乱序到达时，不释放已确认订单。
- repair 与结果消费者并发时，snapshot、guard 和业务桶库存不能重复推进或重复回补。
- reliable message 的 confirm ack 不能覆盖 return/nack/exception 写入的 `FAILED`。

### B. 固化已开启治理能力，再补 reconcile

在止血回归稳定后，先把已经默认开启的 `center-ledger`、`transfer`、`auto-transfer` 做成独立验收项，确认 `seckill_stock_change_log` 能稳定收敛、低库存尾部不会重复调拨；再开启 `reconcile` 验证 `EMPTY + saleable_quantity > 0` 和 survivor 列表缺失能被修正。

### C. 最后恢复调拨碎片治理和生产级扩展

调拨能力需要单独做低库存专项压测，覆盖源桶/目标桶分片条件、重复调拨、重复释放、中心总账收敛和多实例并发。之后再引入真实分库分片、热点 SKU 隔离、跨分片调拨策略和生产级监控告警。当前本地版本只作为阶段三模型验证，不应宣称已经完整复刻生产级阶段三。
