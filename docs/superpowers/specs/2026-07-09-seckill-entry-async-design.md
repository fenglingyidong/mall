# 秒杀入口异步化与库存事实闭合设计

日期：2026-07-09

## 背景

stage3c 压测显示，当前提交入口链路偏重。提交接口同步承担了请求幂等、一人一单、库存扣减、快照状态推进、结果投影和订单创建消息生成等职责。最近一次 `Threads=80`、`Ramp=10`、`Duration=120` 的入口压测中，整体 QPS 约 190，P95 约 695ms，售罄尾段和异步订单闭合还会继续积压 RabbitMQ 与订单侧消费。

架构文档中热点库存链路的核心思想是拆分“扣减快照”和“扣减单据 + 库存实例更新”。本设计把提交入口收窄为“确认库存资格”，把订单创建、结果回写和闭合诊断后移到后台。

本设计不追求提交接口直接返回最终秒杀成功。最终成功只以 `seckill_result.SUCCESS + orderSn` 为准。

## 设计目标

- 降低提交入口同步写入和事务负担。
- 去掉 `seckill_reservation_guard` 的入口同步写。
- 保留 Redis 快速挡重，减少 DB 冲突压力。
- 保留 `seckill_stock_snapshot.request_id` 唯一键，兜底同请求幂等。
- 保留 `seckill_stock_snapshot` 的 active user 唯一约束，兜底 Redis buyer key 失效后的一人一单。
- 保留 OceanBase/MySQL 中 `seckill_stock_bucket` 和 `seckill_stock_change_log` 作为库存事实。
- 后台以 `seckill_stock_change_log.status` 状态游标推进订单 outbox，不把大范围事实推导放到入口或用户查询热路径。
- 前端查询优先读 `seckill_result`，避免实时 join `snapshot + change_log + mq_message + order`。

## 非目标

- 不把库存事实完全迁移到 Redis。
- 不做纯 Redis 预扣再全量异步落库。
- 不取消 DB 层一人一单兜底。
- 不让提交接口等待订单服务创建订单。
- 不把 `snapshot.status` 作为用户最终秒杀状态。
- 不在本设计中优化关单队列吞吐；关单延迟当前按用户已调整的 300s 语义处理。

## 核心职责

`Redis req key`：

- 快速挡同一个 `request_id` 的重复提交。
- 仅作为入口快路径，不作为最终幂等事实。

`Redis buyer key`：

- 快速挡同一个用户重复抢同一活动 SKU。
- Redis 过期、重启或 key 丢失后，由 `snapshot` active user 唯一约束兜底。

`seckill_stock_snapshot`：

- 扣减快照。
- 记录请求登记事实。
- 通过 `request_id` 主键兜底请求幂等。
- 通过 `uk_snapshot_active_user(activity_id, active_key)` 兜底一人一单。
- 记录 bucket 路由信息，保证补偿、释放和重试回到同一个库存桶。

`seckill_stock_change_log`：

- 扣减单据。
- 记录库存扣减或释放事实。
- 后台创建订单 outbox 的主驱动。

`seckill_stock_bucket`：

- 库存实例。
- 记录当前可售库存。
- 条件更新防止超卖。

`mq_message`：

- 可靠消息 outbox。
- 承载 `seckill.order.create`、`seckill.order.result`、`order.close.delay` 等异步消息。

`seckill_result`：

- 前端查询最终结果的投影。
- 用户结果查询热路径只应按 `request_id` 点查它。

## 入口流程

提交接口流程：

1. 校验活动、SKU、用户和购买数量。
2. `SETNX req:{requestId}`，命中表示同请求重试。
3. `SETNX buyer:{activityId}:{skuId}:{userId}`，命中表示同人重复或同人处理中。
4. 选择库存 bucket。
5. 事务 1：插入 `seckill_stock_snapshot`。
6. 事务 2：插入 `seckill_stock_change_log` 并条件扣减 `seckill_stock_bucket`。
7. 返回 `PROCESSING`，由前端继续轮询结果接口。

Redis key 语义：

```text
req:{requestId} -> request marker，短 TTL，挡同 request_id 重试
buyer:{activityId}:{skuId}:{userId} -> request_id，TTL 到活动结束 + buffer
```

Redis 命中处理：

- `req` 命中：查 `seckill_result`；有终态则返回终态，没有则返回 `PROCESSING`。
- `buyer` 命中且值等于当前 `request_id`：同请求重试，返回已有终态或 `PROCESSING`。
- `buyer` 命中且值不等于当前 `request_id`：同人重复，返回 `DUPLICATE_PURCHASE` 或 `PROCESSING`。
- 扣库存失败或售罄：删除 buyer key，避免失败请求长期占住同人资格。
- 扣库存成功：保留 buyer key 到 TTL 到期。

## 事务 1：写扣减快照

事务 1 只写请求登记事实：

```sql
insert into seckill_stock_snapshot(
  request_id,
  activity_id,
  sku_id,
  user_id,
  active_key,
  bucket_id,
  bucket_no,
  bucket_shard_key,
  quantity,
  status
) values (..., 'REGISTERED');
```

字段语义：

- `request_id`：本次提交命令的全局唯一 ID，也是请求幂等主键。
- `activity_id`、`sku_id`：活动和商品维度。
- `user_id`：提交用户，用于审计、结果查询和兜底判断。
- `active_key`：当前生效的一人一单占位。成功或处理中时有值；确认失败且允许重新抢时可释放。
- `bucket_id`、`bucket_no`、`bucket_shard_key`：固定本请求选择的库存桶，后续扣减、释放、补偿都回到同一个桶。
- `quantity`：本次购买数量。
- `status`：快照自身生命周期状态，不表示最终秒杀成功。

唯一约束：

```text
seckill_stock_snapshot(request_id) primary key
uk_snapshot_active_user(activity_id, active_key)
```

冲突语义：

- `request_id` 冲突：同请求重试，查 `seckill_result`；没有终态则返回 `PROCESSING`。
- active user 冲突：同人重复，不进入扣库存事务，返回 `DUPLICATE_PURCHASE` 或 `PROCESSING`。

保留 active user 唯一约束的原因：

- Redis buyer key 是快挡，不是事实。
- Redis 过期、重启或误删后，DB 仍可保证一人一单。
- 该约束复用本来必须写的 `snapshot`，不再额外写 `seckill_reservation_guard`。

## 事务 2：写扣减单据和库存实例

事务 2 只写库存事实：

```sql
begin;

insert into seckill_stock_change_log(
  request_id,
  activity_id,
  sku_id,
  bucket_id,
  bucket_no,
  bucket_shard_key,
  change_type,
  quantity_delta,
  status
) values (..., 'DEDUCT', -1, 'NEW');

update seckill_stock_bucket
set saleable_quantity = saleable_quantity - 1
where id = ?
  and saleable_quantity >= 1;

commit;
```

事务 2 不更新 `snapshot`。扣减是否发生，由 `change_log` 的存在表示。

字段语义：

- `request_id`：库存事实属于哪次提交请求。
- `activity_id`、`sku_id`：冗余业务维度，方便后台不 join `snapshot` 直接处理。
- `bucket_id`、`bucket_no`、`bucket_shard_key`：库存事实作用的桶。
- `change_type`：库存变更类型，例如 `DEDUCT` 或 `RELEASE`。
- `quantity_delta`：库存变化量，扣减为负数，释放为正数。
- `status`：后台闭合状态，例如 `NEW`、`PROCESSING`、`DONE`、`FAILED`。

约束要求：

```text
seckill_stock_change_log(request_id, change_type) unique
seckill_stock_change_log(status, id)
```

`change_log(request_id, change_type)` 防止同一请求被补偿或重试重复扣库存。

`change_log(status, id)` 支持后台 worker 按状态和自增顺序批量推进，不做全表扫描或 filesort。

扣库存失败处理：

- 如果 bucket 条件更新影响行数为 0，事务 2 回滚。
- 删除 Redis buyer key。
- 将 snapshot 标记为失败或释放 `active_key`，允许用户重新抢。
- 返回 `SOLD_OUT` 或库存不足错误。

## 后台闭合流程

后台主流程以 `change_log.status` 做游标，而不是每轮大范围 join 推导。

典型批量查询：

```sql
select id
from seckill_stock_change_log
where status = 'NEW'
order by id
limit 500;
```

有 `change_log(status, id)` 索引时，上述查询可以沿索引读取 `NEW` 状态段，并按 `id` 顺序取固定批次。

闭合流程：

1. 扫描 `change_log.NEW`。
2. 批量抢占为 `PROCESSING`。
3. 按 `request_id` 插入 `mq_message`，routing key 为 `seckill.order.create`，business key 为 `request_id`。
4. 推进 `change_log` 状态。
5. 可靠消息补偿任务投递 MQ。
6. 订单服务创建秒杀订单。
7. 订单结果消息回写 `seckill_result`。

`mq_message` 需要支持按 routing key 和 business key 幂等查询：

```text
mq_message(routing_key, business_key)
```

该索引用于 worker 重试时判断同一个 `request_id` 的订单创建消息是否已经存在，避免重复创建订单消息。

## 前端反馈语义

提交接口返回的是入口受理状态，不是最终秒杀结果。

提交接口返回：

- `PROCESSING`：snapshot 和库存扣减链路已进入，等待后台闭合。
- `SOLD_OUT`：库存不足或 bucket 条件扣减失败。
- `DUPLICATE_PURCHASE`：同人重复。
- `DUPLICATE_REQUEST` 或 `PROCESSING`：同 `request_id` 重试。

前端轮询结果接口：

```sql
select *
from seckill_result
where request_id = ?;
```

需要索引：

```text
seckill_result(request_id)
```

结果语义：

- `SUCCESS + orderSn`：秒杀成功，待支付。
- `FAILED`：秒杀失败。
- `CANCELED`：订单创建成功过，但 300s 内未支付并被关闭。
- 无 result，但 snapshot 或 change_log 存在：返回 `PROCESSING`。

真正的“秒杀成功”只发生在订单服务创建订单成功，并且结果消息回写 `seckill_result.SUCCESS` 之后。

## 补偿与恢复

事实组合语义：

```text
snapshot 存在，change_log 不存在
=> 请求已登记，扣减未完成或事务 2 中断

snapshot 存在，change_log 存在
=> 库存扣减事实已产生，可进入订单 outbox

change_log 存在，mq_message 不存在
=> 库存已扣，后台需补生成订单 outbox

mq_message 存在但未投递或发送失败
=> reliable message 补偿任务重投

seckill_result 存在
=> 前端可见终态或处理中投影
```

补偿规则：

- 事务 1 成功、事务 2 未执行：补偿任务可按 snapshot 固定的 bucket 重试扣减，或标记失败并释放 `active_key`。
- 事务 2 成功、订单 outbox 未生成：后台按 `change_log.NEW` 补生成 `mq_message`。
- MQ 投递失败：可靠消息补偿任务重投。
- 订单创建重复：订单服务必须按 `request_id` 或订单业务键幂等。
- 订单创建失败：写 `seckill_result.FAILED`，并触发库存释放事实。
- 订单 300s 未支付：关闭订单，写 `seckill_result.CANCELED`，并释放库存。

## 索引与约束清单

必需：

```text
seckill_stock_snapshot(request_id) primary key
uk_snapshot_active_user(activity_id, active_key)
seckill_stock_change_log(request_id, change_type) unique
seckill_stock_change_log(status, id)
mq_message(routing_key, business_key)
seckill_result(request_id)
```

建议保留或按使用场景校验：

```text
seckill_stock_snapshot(status, id)
seckill_stock_snapshot(created_at)
```

用途说明：

- `snapshot(request_id)`：请求幂等最终防线。
- `snapshot(activity_id, active_key)`：一人一单最终防线。
- `change_log(request_id, change_type)`：库存扣减幂等最终防线。
- `change_log(status, id)`：后台 worker 高效按状态游标推进。
- `mq_message(routing_key, business_key)`：订单 outbox 幂等和快速点查。
- `seckill_result(request_id)`：前端轮询结果热路径。
- `snapshot(status, id)`、`snapshot(created_at)`：低频补偿、诊断和超时请求扫描。

## 性能预期

入口减少：

- 不再写 `seckill_reservation_guard`。
- 不在入口生成订单 outbox。
- 不在入口等待订单服务。
- 不在成功路径更新 `snapshot` 状态。
- 不在入口写最终 `seckill_result`。

入口仍保留：

- Redis `SETNX` 两次。
- `snapshot` insert。
- `change_log` insert。
- `bucket` 条件 update。

新的入口主要瓶颈：

- bucket 热行竞争。
- `snapshot` 唯一索引冲突判断。
- `change_log` 唯一索引写入。
- DB commit 延迟。

后台主要瓶颈：

- `change_log.NEW` 积压。
- `mq_message.NEW`、`DISPATCHING`、`FAILED` 积压。
- RabbitMQ ready/unacked。
- 订单创建消费速度。
- `seckill_result` 回写延迟。

压测验收不能只看提交 QPS，还要看闭合延迟：从提交返回 `PROCESSING` 到 `seckill_result.SUCCESS/FAILED/CANCELED` 的 P95/P99。

## 测试计划

单元测试：

- Redis `req` 命中时返回已有终态或 `PROCESSING`。
- Redis buyer key 命中同 `request_id` 时按重试处理。
- Redis buyer key 命中不同 `request_id` 时返回同人重复。
- `snapshot.request_id` 冲突时不进入扣库存事务。
- `uk_snapshot_active_user` 冲突时不进入扣库存事务。
- bucket 条件扣减失败时回滚事务 2、删除 buyer key、释放或失败 snapshot active key。
- `change_log(request_id, change_type)` 冲突时不重复扣库存。
- 后台 worker 按 `change_log.status` 批量生成订单 outbox。
- 重复处理同一 `change_log` 时通过 `mq_message(routing_key, business_key)` 保持 outbox 幂等。
- 结果查询优先读 `seckill_result`，缺失时只做 `request_id` 点查兜底。

集成验证：

- 启动 stage3c。
- reset 库存到 20000。
- smoke 提交成功，接口返回 `PROCESSING`。
- 轮询结果最终出现 `SUCCESS` 或业务终态。
- 重复 `request_id` 不重复扣库存。
- 同一用户不同 `request_id` 在 Redis 失效模拟下被 `snapshot` active user 唯一约束挡住。
- 手工制造 `change_log.NEW`，确认后台补生成 `mq_message`。

压测验收：

- 提交入口 HTTP 500 接近 0。
- 提交入口 P95/P99 低于当前同步重链路。
- 售罄尾段主要表现为 `SOLD_OUT`，不是 DB 锁等待或 HTTP 500。
- `change_log.NEW` 不长期积压。
- `mq_message.NEW/DISPATCHING/FAILED` 不长期积压。
- RabbitMQ ready/unacked 可 drain。
- `seckill_result.PROCESSING` 可闭合到 `SUCCESS/FAILED/CANCELED`。

## 实施顺序

1. 校验并补齐 DB 约束和索引。
2. 保留 `snapshot` active user 唯一约束，明确冲突处理。
3. 从入口成功路径移除 `seckill_reservation_guard` 同步写。
4. 实现 Redis `req` 与 `buyer` 双 key。
5. 调整入口为事务 1 写 snapshot、事务 2 写 change_log 和 bucket update。
6. 确保事务 2 不更新 `snapshot` 成功状态。
7. 实现或调整后台 worker，以 `change_log.status` 游标生成订单 outbox。
8. 调整结果查询，优先查 `seckill_result`，缺失时按 `request_id` 点查兜底。
9. 补偿任务覆盖 snapshot 无 change_log、change_log 无 mq_message、mq_message 未投递、result 未闭合。
10. 执行单测、集成验证和 stage3c 压测。

## 风险与缓解

风险：事务 1 成功但事务 2 失败时，active user 唯一约束可能占住用户资格。

缓解：扣库存失败必须释放 buyer key，并将 snapshot 标记失败或释放 `active_key`。补偿任务定期扫描超时 snapshot，避免长期占位。

风险：后台吞吐不足导致用户长时间看到 `PROCESSING`。

缓解：后台以 `change_log(status, id)` 游标推进，批量抢占，必要时按 bucket shard 或 id 区间并发。监控 `change_log.NEW`、`mq_message` 状态和 RabbitMQ ready/unacked。

风险：不更新 snapshot 成功状态后，状态判断需要组合事实。

缓解：组合事实只放到后台补偿和诊断，用户查询热路径优先读 `seckill_result`。

风险：`mq_message(routing_key, business_key)` 缺失时，后台重试可能重复生成订单创建消息。

缓解：补齐唯一或查询索引，并在 worker 中以 routing key + business key 做幂等。

风险：`change_log(request_id, change_type)` 缺失时，补偿或重试可能重复扣库存。

缓解：补齐唯一约束，插入冲突时按已有库存事实处理。

## 自审结果

已检查：

- 设计明确保留 `snapshot` active user 一人一单唯一约束。
- 设计明确移除入口同步写 `seckill_reservation_guard`。
- 设计明确 `snapshot` 不是最终成功状态，最终状态由 `seckill_result` 提供。
- 设计明确事务 2 不更新 `snapshot`，库存扣减事实由 `change_log` 表示。
- 设计明确后台主流程以 `change_log.status` 游标推进，不依赖大范围 join。
- 没有保留未完成占位项。
