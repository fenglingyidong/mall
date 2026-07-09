# 秒杀压测可信性与异步闭合修复设计

日期：2026-07-09

## 背景

本设计修复 stage3c 秒杀压测暴露的两类问题：

- 压测口径不干净：reset 没清理 `seckill_reservation_guard`、`seckill_result_retry`，导致历史 guard 和 retry 状态污染新一轮结果，出现非本轮业务事实导致的 `Duplicate purchase` 和闭合残留。
- 异步链路不闭合：可靠消息 after-commit 派发线程池饱和时抛出拒绝异常并冒泡成 HTTP 500；部分结果消息重试耗尽后只进入 retry/DLQ 记录，没有把 `seckill_result.PROCESSING`、`seckill_stock_snapshot.DEDUCTED` 和 `mq_message.NEW` 推进到可诊断终态。

最近一轮全链路闭合观察使用参数：

- `ActivityId=1`
- `SkuId=1001`
- `Stock=20000`
- `BucketCount=16`
- `Threads=80`
- `Ramp=10`
- `Duration=120`

关键现象：

- `samples=46475`
- `success=18727`
- `failure=27748`
- `HTTP 500=1316`
- `Stock not enough=24531`
- `Duplicate purchase=1901`
- drain 后仍有 `seckill_result.PROCESSING=1304`
- 主库 `seckill_stock_snapshot.DEDUCTED=560`
- 分片库 `seckill_stock_snapshot.DEDUCTED=744`
- 主库 `mq_message.NEW=560`
- 分片库 `mq_message.NEW=744`

其中 `560 + 744 = 1304`，说明卡住的结果、快照和可靠消息同源，不是单纯关单消费者慢。

## 目标

本次修复同时满足两个目标：

- 压测结果可信：每轮 reset 后基线干净，JMeter 用户身份不会被历史状态污染，汇总结果能区分业务售罄、重复购买和系统错误。
- 异步链路可闭合：高压下提交入口不因可靠消息派发短暂背压返回 HTTP 500；结果消息失败有补偿和终态，drain 后核心状态接近 0。

## 非目标

本设计不实现完整 JMeter 全链路轮询 JMX。当前仍以“提交入口压测 + 异步闭合观察”为验收口径。

本设计不优化关单队列吞吐。`mall.order.close.queue` 的消费能力可以作为后续专项处理，本次只确保它不会掩盖秒杀结果闭合问题。

本设计不改变库存扣减算法、分桶路由策略、自动调拨策略。

## 方案选择

采用完整修复方案，分三条线推进：

- 压测基线修复
- 可靠消息派发修复
- 结果闭合终态修复

只修压测脚本会让指标更干净，但不能消除 HTTP 500 和 `PROCESSING` 卡死。只修业务链路会让系统更稳，但压测结论仍可能被历史 guard/retry 表污染。因此本次合并处理，但保持每个改动边界小、可独立验证。

## 压测基线设计

### reset 覆盖范围

`target/loadtest/reset-stage3c-async.ps1` 需要清理以下秒杀侧表：

- `mq_message`
- `seckill_stock_snapshot`
- `seckill_stock_change_log`
- `seckill_stock_bucket`
- `seckill_bucket_config`
- `seckill_result`
- `seckill_reservation_guard`
- `seckill_result_retry`

主库 `mall-oceanbase-ce` 和分片库 `mall-oceanbase-ce-shard1` 都执行同一组存在性安全清理。若某个历史环境没有新表，脚本应给出清楚提示并继续清理其他表，不能让旧环境直接中断 reset。

订单侧继续清理：

- `mq_message`
- `consume_record`
- `seckill_order`
- `order_item`
- `order_info`

RabbitMQ 继续清理秒杀建单、结果、关单相关队列。

TairString 继续删除库存缓存 key。

### reset 后校验

reset 脚本完成后输出结构化摘要，至少包含：

- `seckill_result` 总数
- `seckill_reservation_guard` 总数
- `seckill_result_retry` 总数
- 主库和分片库 `seckill_stock_snapshot` 总数
- 主库和分片库 `mq_message` 总数
- 订单库 `seckill_order` 总数
- RabbitMQ 关键队列 ready/unacked

全链路闭合压测开始前，以上计数必须为 0，分桶库存和分桶配置除外。

### JMeter 用户身份

`target/loadtest/stage3c-submit.jmx` 当前使用全局 `AtomicLong` 生成递增 `userId`，这本身可以保留。为了让压测结果可复现，运行脚本应记录：

- `UserIdStart`
- `Threads`
- `Ramp`
- `Duration`
- JTL 路径
- reset 参数

`Duplicate purchase` 仍可能来自同一用户真实重复提交，或者来自测试脚本重启后复用旧用户区间。reset 清理 guard 后，默认 run 应不再因历史状态产生 `Duplicate purchase`。

### 汇总脚本

JTL 汇总保留 `qps`、`successQps`、`P95`、`P99`。额外明确输出以下分类：

- `HTTP 500`
- `Stock not enough`
- `Duplicate purchase`
- `ExecutorService in active state did not accept task`
- `Lock wait timeout`
- `Seckill bucket snapshot update failed`

全链路闭合结论不能只看入口 P95，必须附带 drain 状态。

## 可靠消息派发设计

### 问题

`ReliableMessagePublisher.enqueue(...)` 在事务内落库 `mq_message.NEW`，然后注册 afterCommit 回调异步发送 MQ。

当前 `dispatchAsync(...)` 直接调用：

```java
dispatchExecutor.execute(() -> send(message));
```

线程池满时会抛出 `TaskRejectedException` 或其他 `RuntimeException`。异常发生在 afterCommit 阶段，已经完成业务落库，但异常会冒泡到提交入口，形成 HTTP 500。此时消息仍可能停留在 `NEW`，需要补偿任务接管。

### 设计

`dispatchAsync(...)` 捕获 executor 拒绝和运行时异常。

处理策略：

- 不向提交接口冒泡 executor 拒绝异常。
- 保持消息为 `NEW`，由补偿任务扫描后重发。
- 记录日志和指标，保留 messageId、businessKey、routingKey、bucketShardKey。

保持 `send(...)` 内部语义不变：

- 发送前从 `NEW/FAILED` CAS 到 `DISPATCHING`。
- RabbitMQ confirm ack 后标记 `SENT`。
- confirm nack、returned、发送异常标记 `FAILED`。

这能保证 afterCommit 的短暂背压不会变成用户可见 500，同时不丢可靠消息。

### 补偿任务

stage3c 秒杀服务必须显式启用：

```yaml
mall:
  message:
    compensation:
      enabled: true
      bucket-shard-keys: "1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16"
      dispatching-timeout-seconds: 30
```

补偿任务扫描 `NEW/FAILED`，并按 bucketShardKey 分片重发。`DISPATCHING` 超过超时时间后先标成 `FAILED`，再进入重发路径。

订单服务已有 compensation enabled 配置，保持不变。

## 结果闭合终态设计

### 问题

`SeckillResultMessageListener` 消费订单结果消息：

- `SUCCESS`：确认扣减，保存 `seckill_result.SUCCESS`
- `ORDER_CLOSED`、`ORDER_CANCELED`、`CANCELED`：释放已确认扣减，保存取消结果
- 其他失败：释放未确认扣减，保存失败结果

当快照缺失、状态不匹配、释放失败时，监听器会抛异常并进入 result retry。retry 超过最大次数后，当前逻辑 ack 原消息并记录 retry/DLQ，但没有强制把对应 `seckill_result.PROCESSING` 推进到终态，也没有释放 guard active key。这会留下长期 `PROCESSING` 和 `DEDUCTED`。

### 设计

新增“结果失败终态处理”逻辑，仅在 retry 超过最大次数时触发。

处理输入：

- result message payload
- `reservationId`
- `requestId`
- `status`
- `bucketShardKey`
- 最后异常原因

处理输出：

- `seckill_result` 写入 `FAILED`
- message 包含短错误原因，例如 `Result retry exhausted: snapshot missing for success result`
- guard 从 `PROCESSING/DEDUCTED` 释放为 `FAILED` 或 `RELEASED`
- 对应 retry 记录保持 `DLQ`，用于审计

库存安全规则：

- 如果失败结果可以证明库存应释放，继续走现有释放路径。
- 如果 `SUCCESS` 结果无法确认快照，不能凭空回补库存，只把用户可见结果推进为 `FAILED`，并留下 retry/DLQ 诊断记录。
- 如果快照仍是 `DEDUCTED` 且无法安全释放，保留快照状态用于人工和后续修复任务，不让 `seckill_result` 永远停在 `PROCESSING`。

guard 安全规则：

- 对无法闭合的 `SUCCESS` 失败终态，释放 active key，避免用户长期被占用。
- 对已确认订单但后续关单释放失败的场景，优先保留库存事实，不做不确定回补。

### 与 repair job 的关系

`SeckillReservationRepairJob` 继续用于陈旧 `PROCESSING` guard 的事实核查。本次不扩大 repair 职责。retry 耗尽后的终态由 result listener 负责，因为它拥有失败 payload、异常原因、bucketShardKey 和重试上下文。

## 数据流

提交入口成功路径：

1. 创建 reservation guard。
2. 选择 bucket。
3. 插入 `seckill_stock_snapshot.DEDUCTED`。
4. 扣减分桶库存。
5. 写 `seckill_result.PROCESSING`。
6. 写 `mq_message.NEW`。
7. afterCommit 尝试异步发送建单消息。
8. executor 可用时发送 MQ；executor 拒绝时保留 `NEW`，补偿任务稍后发送。
9. 订单服务创建订单并发送结果消息。
10. 秒杀结果监听器确认或释放快照，写入终态结果。

结果消息失败路径：

1. result listener 处理失败。
2. 记录 retry 并投递 delay retry。
3. 未超过最大次数时 ack 原消息，等待重试。
4. 超过最大次数时写 retry `DLQ`，执行失败终态处理，ack 原消息。

## 错误处理

afterCommit executor 拒绝：

- 不返回 HTTP 500。
- 记录 warn。
- 消息保持 `NEW`。
- 补偿任务重发。

RabbitMQ 发送异常：

- 保持现有 `FAILED` 标记。
- 补偿任务重发。

RabbitMQ confirm nack 或 returned：

- 保持现有 `FAILED` 标记。
- 补偿任务重发。

结果消息重试耗尽：

- 写 `seckill_result.FAILED`。
- 释放或失败 guard。
- 保留 retry `DLQ` 记录。
- 不做不确定库存回补。

reset 清理失败：

- 对缺表环境输出表名和错误。
- 对当前 stage3c 必需表失败时中断 reset，避免带脏状态压测。

## 测试计划

单元测试：

- `ReliableMessagePublisherTest` 增加 executor 拒绝场景，断言不抛出到调用方，消息仍可补偿。
- `ReliableMessagePublisherTest` 保留发送异常标记 `FAILED` 的行为。
- `SeckillResultMessageListenerTest` 增加 retry 耗尽场景，断言 `seckill_result` 不再停留 `PROCESSING`。
- `SeckillResultMessageListenerTest` 覆盖 `SUCCESS` 快照缺失、失败结果释放失败、bucketShardKey 路由存在三类场景。
- reset 脚本可通过 smoke 验证，不新增复杂脚本测试。

集成验证：

- `rtk mvn -pl mall-message,mall-seckill -am test`
- `rtk mvn -pl mall-order,mall-seckill -am -DskipTests package`
- 启动 stage3c。
- 执行 reset，确认 guard/retry/result/snapshot/mq_message 清零。
- 跑 smoke。
- 跑全链路闭合观察。

压测验收：

- `HTTP 500=0`
- `Duplicate purchase` 不来自历史 guard 污染
- `ExecutorService in active state did not accept task=0`
- drain 后 `seckill_result.PROCESSING` 接近 0
- drain 后 `seckill_stock_snapshot.DEDUCTED` 接近 0
- drain 后秒杀库 `mq_message.NEW/DISPATCHING/FAILED` 接近 0
- drain 后订单库 `mq_message.NEW/DISPATCHING/FAILED` 接近 0
- JTL 汇总记录 JTL 路径、reset 参数、JMeter 参数

## 实施顺序

1. 修 reset 脚本和压测汇总，先保证新一轮数据可信。
2. 修 `ReliableMessagePublisher` executor 拒绝处理，并启用 stage3c 秒杀侧 compensation。
3. 修 result retry 耗尽终态处理。
4. 补单元测试。
5. 打包、启动、reset、smoke。
6. 全链路闭合重跑并记录对比。

## 风险与缓解

风险：把 retry 耗尽的 `SUCCESS` 消息标记为失败，可能与后续迟到事实冲突。

缓解：只在 retry 耗尽后执行；不回补不确定库存；保留 retry `DLQ` 和错误原因，后续迟到消息按现有幂等逻辑处理。

风险：补偿任务重发 `NEW/FAILED` 可能增加 RabbitMQ 压力。

缓解：保持现有批量上限；按 bucketShardKey 分片扫描；压测时观察 `mq_message.NEW` 和队列 ready/unacked。

风险：reset 清理新表可能影响手工保留的诊断数据。

缓解：reset 仅用于本地或压测环境，压测指南继续强调不要在生产环境执行。

## 自审结果

已检查文档完整性、需求明确、状态流不互相矛盾。范围保持在压测可信性、可靠消息派发、结果闭合终态三块，不包含完整全链路 JMX 和关单吞吐优化。
