# Seckill Asset Risk Stopgap Design

## 背景

当前秒杀链路已经进入 Stage 3C 形态：

```text
OceanBase 分桶分片库存 + reservation/outbox + RabbitMQ + MySQL 正式订单 + 结果回传
```

这个方向正确，但当前实现把部分资损 invariant 放在按 `bucket_shard_key` 分片的表里，导致“单用户一次活动只能有一个有效 reservation”“结果消息必须最终确认或释放库存”“回补必须幂等”这些规则没有一个真正全局、可恢复、可测试的 module 承接。

本设计是资损止血版。目标不是补齐完整 Stage 3 治理能力，也不是追求最高 QPS，而是先把库存和订单资格的正确性锁住。

## 目标

- 同一 `requestId` 重试不重复扣库存。
- 同一 `(activityId, userId)` 在 `PROCESSING`、`DEDUCTED`、`CONFIRMED` 状态只能存在一个有效 reservation。
- `bucket_shard_key` 在扣减前持久化，repair 能定位分片。
- 事务状态未知时不释放 guard，不制造超卖或重复购买窗口。
- Rabbit return / confirm 竞态不会把失败消息覆盖为已发送。
- 结果消息使用延迟重试和最大次数，不无限 `requeue=true`。
- 库存释放和回补幂等，重复消息、乱序消息、repair 并发都不能重复加库存。
- 长异常消息不会打爆 `message` / `error_message` 字段，不能阻断释放链路。

## 非目标

- 不在本轮开启中心总账、请求调拨、自动调拨、碎片整理。
- 不把 guard 表设计成高吞吐分片表。
- 不引入 XA 事务。
- 不改变“正式订单事实源在 MySQL，库存资格事实源在 OceanBase/guard”的大方向。
- 不重做完整 Stage 3 库存运营生命周期。

## 核心决策

### 1. Guard 表必须全局唯一

新增 `seckill_reservation_guard`，止血版使用单库单表，不纳入 `bucket_shard_key` 分片规则。

原因：ShardingSphere 下普通唯一索引只能保证单个物理分片内唯一。如果 guard 表也按 `bucket_shard_key` 分片，`UNIQUE(activity_id, active_key)` 仍然不是全局唯一，会重复制造当前问题。

第一版推荐把 guard 表放在单一数据源，例如当前 `ds_0` 或独立控制库，并显式配置为 `SINGLE` 表。后续如必须扩展，可按不可变 `guard_shard_key = user_id` 分片，但本设计不进入该方案。

表结构建议：

```sql
CREATE TABLE seckill_reservation_guard (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    reservation_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    activity_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    guard_shard_key BIGINT NOT NULL,
    active_key VARCHAR(128),
    bucket_id BIGINT,
    bucket_no INT,
    bucket_shard_key BIGINT,
    status VARCHAR(32) NOT NULL,
    fail_reason VARCHAR(255),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_guard_reservation (reservation_id),
    UNIQUE KEY uk_guard_request (request_id),
    UNIQUE KEY uk_guard_activity_active (activity_id, active_key),
    KEY idx_guard_status_updated (status, updated_at),
    KEY idx_guard_bucket_shard (bucket_shard_key, status)
);
```

字段语义：

- `reservation_id`：服务端生成，全链路主键。
- `request_id`：请求幂等号；同一次提交重试必须复用。
- `guard_shard_key`：等于 `user_id`，不可变；为未来分片保留，不参与本轮分片。
- `active_key`：有效资格占用键。`PROCESSING`、`DEDUCTED`、`CONFIRMED` 占用，`FAILED`、`RELEASED` 释放。
- `bucket_shard_key`：选桶后、扣减前持久化，用于 repair 定位原分片。

### 2. requestId 和 reservationId 分工

`reservationId` 是库存资格和订单侧对齐的主键。snapshot、outbox、订单幂等、结果回传都使用 `reservationId`。

`requestId` 是请求幂等号。处理规则：

- `requestId` 已存在：返回该 guard 对应的当前结果，不重新选桶、不扣库存、不写新 outbox。
- `activity_id + active_key` 冲突：返回 `DUPLICATE`、`ALREADY_PROCESSING` 或 `ALREADY_PURCHASED`，不扣库存。
- 两者都不存在：创建新 guard。

如果当前 HTTP 入口还没有客户端幂等号，第一版继续用服务端 `requestId = reservationId`，但网关/客户端重试无法天然幂等。后续可增加可选请求头 `X-Request-Id`。

### 3. Guard 状态机使用 CAS

guard 的所有推进必须带 `WHERE reservation_id = ? AND status IN (expected_states)` 或等价的单状态 CAS 条件。

允许状态：

```text
PROCESSING -> DEDUCTED
PROCESSING -> FAILED
PROCESSING -> CONFIRMED
DEDUCTED -> CONFIRMED
DEDUCTED -> RELEASED
CONFIRMED -> RELEASED   仅订单关闭/取消类消息允许
```

`CONFIRMED` 后继续占用 `active_key`，避免成功后重复购买。

典型 SQL：

```sql
UPDATE seckill_reservation_guard
SET bucket_id = ?, bucket_no = ?, bucket_shard_key = ?, updated_at = NOW()
WHERE reservation_id = ?
  AND status = 'PROCESSING';
```

```sql
UPDATE seckill_reservation_guard
SET status = 'DEDUCTED', updated_at = NOW()
WHERE reservation_id = ?
  AND status = 'PROCESSING';
```

```sql
UPDATE seckill_reservation_guard
SET status = 'FAILED', active_key = NULL, fail_reason = ?, updated_at = NOW()
WHERE reservation_id = ?
  AND status = 'PROCESSING';
```

```sql
UPDATE seckill_reservation_guard
SET status = 'CONFIRMED', updated_at = NOW()
WHERE reservation_id = ?
  AND status IN ('PROCESSING', 'DEDUCTED');
```

```sql
UPDATE seckill_reservation_guard
SET status = 'RELEASED', active_key = NULL, fail_reason = ?, updated_at = NOW()
WHERE reservation_id = ?
  AND status IN ('DEDUCTED');
```

订单关闭场景若允许已确认订单回补，再使用单独方法：

```sql
UPDATE seckill_reservation_guard
SET status = 'RELEASED', active_key = NULL, fail_reason = ?, updated_at = NOW()
WHERE reservation_id = ?
  AND status = 'CONFIRMED';
```

### 4. 提交流程

目标是先全局占资格，再进入分桶扣减。

```text
submit
  -> load activity / sku
  -> create or load guard by requestId
  -> if guard exists: return current result
  -> insert guard(PROCESSING, active_key=userId)
  -> select bucket
  -> persist bucket_id / bucket_no / bucket_shard_key to guard
  -> execute bucket transaction on bucket_shard_key
       insert snapshot(DEDUCTED)
       insert mq_message(NEW)
       deduct business bucket
       insert change_log
       save seckill_result(PROCESSING)
  -> after bucket transaction commits:
       guard PROCESSING -> DEDUCTED
       afterCommit dispatch outbox
  -> return ACCEPTED
```

如果选桶失败或明确库存不足，guard 可释放为 `FAILED` 并清空 `active_key`。

如果桶事务状态未知，guard 必须保留 `PROCESSING`。

### 5. 异常分类

核心规则：

```text
能证明没扣，才释放 guard。
不能证明没扣，保留 PROCESSING，交 repair。
```

可以释放的场景：

- guard 唯一键冲突。
- 选桶阶段明确无 survivor bucket。
- 条件扣减返回 0，且事务正常回滚。
- 业务代码抛异常，事务管理器正常 rollback，rollback 没有异常。

不能释放的场景：

- commit 阶段超时。
- commit 阶段连接断开。
- 数据库返回状态未知。
- ShardingSphere 路由异常后无法确认是否提交。
- OceanBase/MySQL transient 错误发生在提交边界。
- repair 查询分片失败或分片不可达。

对不能释放的场景，HTTP 可返回 `PROCESSING` 或抛出可重试错误，但 guard 保持 `PROCESSING`。

### 6. Repair 设计

新增 guard repair job，扫描 `PROCESSING` 且 `updated_at` 超过探测窗口的记录。

默认窗口：

- 30 秒后开始探测。
- 300 秒后才允许在“所有事实都确认不存在”的前提下释放。

流程：

```text
for PROCESSING guard where updated_at < now - 30s:
  if bucket_shard_key is null:
    if updated_at < now - safetyWindow:
      release as FAILED
    else keep PROCESSING
    continue

  query snapshot by reservationId + bucket_shard_key
  query outbox by reservationId + bucket_shard_key
  query change_log by reservationId + bucket_shard_key

  if any query fails:
    keep PROCESSING and alert

  if snapshot exists and outbox exists:
    CAS guard -> DEDUCTED
    ensure outbox compensation can dispatch

  if snapshot exists and outbox missing:
    do not release
    either rebuild outbox from snapshot or alert manual recovery

  if no snapshot/outbox/change_log:
    if updated_at < now - safetyWindow:
      release guard as FAILED
    else keep PROCESSING
```

注意：查不到不等于不存在。释放资格必须偏保守。

### 7. Snapshot 和回补幂等

`releaseDeduction` 需要区分不同业务结果类型。

结果类型建议：

- `SUCCESS`：订单创建成功。
- `CREATE_FAILED`：订单创建失败。
- `ORDER_CLOSED`：订单超时关闭。
- `ORDER_CANCELED`：用户或系统取消。

状态规则：

- `SUCCESS`：`DEDUCTED -> CONFIRMED`，不回补。
- `CREATE_FAILED`：只能释放 `DEDUCTED`，不能释放 `CONFIRMED`。
- `ORDER_CLOSED` / `ORDER_CANCELED`：是否释放 `CONFIRMED` 由业务规则决定；止血版允许关闭已确认但未支付订单时回补。

回补必须两段式 CAS，避免重复加库存：

```sql
UPDATE seckill_stock_snapshot
SET status = 'RELEASING', updated_at = NOW()
WHERE request_id = ?
  AND bucket_shard_key = ?
  AND status = 'DEDUCTED';
```

只有从 `DEDUCTED -> RELEASING` 成功的线程可以加库存：

```sql
UPDATE seckill_stock_bucket
SET saleable_quantity = saleable_quantity + ?, version = version + 1, status = 'ACTIVE', updated_at = NOW()
WHERE id = ?
  AND shard_key = ?
  AND bucket_type = 'BUCKET';
```

加库存成功后：

```sql
UPDATE seckill_stock_snapshot
SET status = 'RELEASED', updated_at = NOW()
WHERE request_id = ?
  AND bucket_shard_key = ?
  AND status = 'RELEASING';
```

如果允许订单关闭释放 `CONFIRMED`，使用独立方法和独立结果类型，不复用创建失败 release 方法。

### 8. 乱序结果保护

RabbitMQ 重复、乱序、补偿重发都要视为正常情况。

规则：

- `SUCCESS` 到达后，guard/snapshot 进入 `CONFIRMED`。
- 后到的 `CREATE_FAILED` 不能释放 `CONFIRMED`。
- 只有 `ORDER_CLOSED` / `ORDER_CANCELED` 可以处理 `CONFIRMED`，并且必须验证订单侧状态。
- 重复 `CREATE_FAILED` 对 `RELEASED` 不回补。
- 重复 `SUCCESS` 对 `CONFIRMED` 幂等 ack。

### 9. Reliable Message 状态机

当前 `return` 先标 `FAILED`，confirm ack 又可能标 `SENT`。止血版改成 CAS 状态机。

状态：

```text
NEW -> DISPATCHING -> SENT
FAILED -> DISPATCHING -> SENT
DISPATCHING -> FAILED
SENT -> CONSUMED 可选，不作为跨服务消费成功口径
```

状态更新：

- dispatch 前：`NEW/FAILED -> DISPATCHING`
- confirm ack：只允许 `DISPATCHING -> SENT`
- confirm nack：`DISPATCHING -> FAILED`
- return callback：`DISPATCHING -> FAILED`
- send exception：`DISPATCHING -> FAILED`
- confirm ack 不能覆盖 `FAILED`

失败字段：

```text
error_type = RETURNED / CONFIRM_NACK / SEND_EXCEPTION / TIMEOUT
error_message = truncated message
```

补偿任务只扫描 `NEW/FAILED`，不扫描 `DISPATCHING`。另加超时修复：`DISPATCHING` 超过发送确认窗口后标 `FAILED`，避免进程在发送中崩溃导致永远不补偿。

### 10. Result Consumer 延迟重试

结果消费失败不使用无限 `basicNack(requeue=true)`。

新增 retry 记录，推荐持久化到 DB，Rabbit header 只作为辅助。

字段：

```text
message_id
reservation_id
result_type
payload
retry_count
first_failed_at
last_failed_at
last_error
next_retry_at
status
```

重试节奏：

```text
1: 5s
2: 30s
3: 2min
4: 10min
exceeded: DLQ + alert
```

处理分类：

- transient DB / ShardingSphere / Rabbit 异常：写 retry，ack 原消息，延迟后重投。
- poison message：直接 DLQ，告警。
- 幂等已完成：ack。
- 业务非法状态：ack + 告警，不重试制造库存变化。

`SECKILL_ORDER_RESULT_DLQ` 归库存侧或人工补偿工具所有，订单侧不能通用监听并直接 ack。

### 11. 错误文本截断

统一新增消息截断 helper：

- `seckill_stock_snapshot.message`：255。
- `seckill_result.message`：255。
- `mq_message.error_message`：512。
- retry `last_error` 按表字段限制截断。

所有 `exception.getMessage()` 入库前必须经过截断。

## Module 划分

### ReservationGuardRepository

负责：

- 创建 guard。
- requestId 幂等查询。
- active_key 全局唯一占用。
- bucket 信息持久化。
- guard 状态 CAS。
- repair 查询。

Interface 要小：

```java
CreateGuardResult createOrLoad(String requestId, ReservationDraft draft);
boolean attachBucket(String reservationId, BucketRef bucket);
boolean markDeducted(String reservationId);
boolean markConfirmed(String reservationId);
boolean markCreateFailed(String reservationId, String reason);
boolean markReleased(String reservationId, ReleaseReason reason);
List<ReservationGuard> findStaleProcessing(Instant before, int limit);
```

### SeckillDeductionCoordinator

负责串联：

```text
guard -> bucket select -> attach bucket -> bucket transaction -> mark deducted
```

它是提交链路的编排 module。`SeckillRepository` 保持库存事务实现，避免继续膨胀。

### ReliableMessagePublisher / Repository

负责 outbox 状态 CAS，不让调用方理解 return/confirm 竞态。

### SeckillResultRetryRepository / Job

负责结果重试的 DB 记录、延迟投递和超限告警。

### SeckillReservationRepairJob

负责保守修复 `PROCESSING` guard，不直接靠猜测释放资格。

## 数据流

### Submit 成功

```text
HTTP
  -> guard create PROCESSING
  -> select bucket
  -> guard attach bucket_shard_key
  -> bucket transaction writes snapshot/outbox/change_log/result
  -> guard PROCESSING -> DEDUCTED
  -> afterCommit dispatch mq_message
  -> ACCEPTED
```

### Submit 状态未知

```text
HTTP
  -> guard PROCESSING + bucket_shard_key persisted
  -> bucket transaction commit boundary throws unknown exception
  -> keep guard PROCESSING
  -> repair checks shard facts later
```

### Order create success

```text
order result SUCCESS
  -> confirm snapshot DEDUCTED -> CONFIRMED
  -> guard DEDUCTED/PROCESSING -> CONFIRMED
  -> active_key remains userId
  -> save result SUCCESS
```

### Order create failed

```text
order result CREATE_FAILED
  -> snapshot DEDUCTED -> RELEASING
  -> bucket +quantity once
  -> snapshot RELEASING -> RELEASED
  -> guard DEDUCTED -> RELEASED, active_key = null
  -> save result FAILED
```

### Result consumer transient failure

```text
main result queue
  -> processing throws transient
  -> persist retry record retry_count + next_retry_at
  -> ack original message
  -> retry job publishes delayed message
  -> exceeded max retry -> DLQ + alert
```

## 配置

建议新增：

```yaml
mall:
  seckill:
    reservation-guard:
      enabled: true
      processing-probe-after-seconds: 30
      safe-release-after-seconds: 300
      repair-batch-size: 100
    result-retry:
      enabled: true
      delays: 5s,30s,2m,10m
      max-attempts: 4
```

## 迁移

1. 新增 `seckill_reservation_guard`。
2. 新增 result retry 表或扩展现有 `mq_message`，第一版推荐独立 retry 表。
3. 新增 `mq_message.error_type` 和 `DISPATCHING` 状态。
4. 部署前保持旧 submit 入口可回滚。
5. 灰度开启 guard：先 shadow 写 guard，不拦截；验证唯一冲突和状态流转；再启用强拦截。
6. 启用强拦截后，保留 Redisson 锁作为降噪，不作为资损保证。

## 验收测试

### 单元测试

- 同一个 `requestId` 重复提交，只返回同一个 reservation，不重复扣减。
- 同一用户并发提交，即使选到不同 `bucket_shard_key`，只有一个 guard 成功。
- `CONFIRMED` 后 `active_key` 继续占用。
- guard attach bucket 后，桶事务未知异常不释放 guard。
- guard 状态 CAS 被并发 repair/result consumer 同时推进时不会互相覆盖。
- Rabbit return 后 confirm ack 不覆盖 `FAILED`。
- confirm nack 写 `error_type=CONFIRM_NACK`。
- return 写 `error_type=RETURNED`。
- `DISPATCHING` 超时可回到 `FAILED`。
- result consumer transient 错误写 retry，不无限 requeue。
- result retry 超过最大次数进 DLQ。
- 长异常消息截断。

### 集成测试

- snapshot 存在、outbox 存在：repair 推进 guard 到 `DEDUCTED`。
- snapshot 存在、outbox 不存在：repair 不释放，补 outbox 或告警。
- snapshot/outbox/change_log 都不存在且超过安全窗口：repair 释放 guard。
- 任一分片查询异常：repair 不释放。
- `SUCCESS` 后 `CREATE_FAILED` 乱序到达，不释放已 `CONFIRMED` reservation。
- 重复 `CREATE_FAILED` 不重复回补。
- `ORDER_CLOSED` 对 `CONFIRMED` 的释放路径与 `CREATE_FAILED` 分离。
- repair 和 result consumer 并发执行，库存只回补一次。

### 压测验收

- 主链路无重复购买成功记录。
- `seckill_reservation_guard` 中同一 `(activity_id, user_id)` active 状态最多一条。
- Rabbit 主队列清零后，`PROCESSING` guard 数量最终归零或留在告警清单。
- `mq_message` 中长期 `DISPATCHING` 为 0。
- result retry 表无超过最大次数未告警记录。

## 风险和取舍

- 单库 guard 会引入全局写点，降低极限 QPS。这是止血版有意取舍，先保护资损 invariant。
- `requestId` 如果仍由服务端生成，客户端网络重试不会天然命中幂等。后续需要网关传入稳定幂等号。
- repair 释放保守会导致用户资格短时间卡住，但不会制造超卖或重复购买。
- result retry 需要新增队列和表，复杂度上升，但比无限 requeue 更可控。

## 设计自检

- 没有把 guard 表按 `bucket_shard_key` 分片。
- `bucket_shard_key` 在扣减前持久化。
- 事务未知状态不会释放 guard。
- `CONFIRMED` 后继续占用 `active_key`。
- guard 和消息状态都使用 CAS。
- Rabbit return / confirm 竞态有明确防护。
- result consumer 使用延迟重试和最大次数。
- 创建失败和订单关闭释放路径分离。
- 回补路径有 `RELEASING` 中间态，避免重复加库存。
- repair 有安全窗口，并且查询异常时不释放。
- 错误消息截断已纳入设计。
