# 2026-07-10 秒杀 Outbox 直推与恢复验证记录

## 1. 本轮范围

本轮验证对象：

- `sql/migration-v14-seckill-outbox-direct-drain.sql`
- `SeckillDeductCommittedListener`
- `SeckillOrderOutboxCoordinator`
- `SeckillOrderOutboxFromChangeLogService.drainShard`
- `SeckillOrderCreateMessageCompensationJob`

## 2. 静态验证

已通过：

```text
mvn -pl mall-message,mall-seckill -am test
mvn -pl mall-message,mall-seckill -am -DskipTests package
```

说明：

- 先前 `package` 失败并非代码问题，而是本地 `mall-order` / `mall-seckill` Java 进程占用 jar 与日志文件。
- 停掉占用进程后，`package` 已恢复通过。

## 3. 运行态验证

### 3.1 入口压测结果

压测参数：

```text
activityId=1
skuId=1001
stock=100000
bucketCount=16
threads=40
ramp=10
duration=60s
```

入口压测产物：

```text
JTL=target/loadtest/stage3c-current/submit-20260710-184930.jtl
REPORT=target/loadtest/stage3c-current/report-20260710-184930
```

JMeter 汇总：

```text
samples=18105
avg=121ms
throughput≈301 req/s
http 200,true = 18105
jmeter errors = 0%
```

Smoke 提交：

```text
POST /api/seckill/1/1001
status=PROCESSING
http 200
```

结论：

- 提交入口通过
- 没有出现 HTTP 500
- 入口并发链路可以稳定返回 `PROCESSING`

### 3.2 初次异步闭合检查

RabbitMQ：

```text
mall.seckill.order.create.queue ready=0 unacked=0 consumers=8
mall.seckill.order.result.queue ready=0 unacked=0 consumers=8
```

OceanBase 主分片：

```text
seckill_stock_snapshot.REGISTERED = 9049
seckill_stock_change_log.NEW = 9049
```

OceanBase shard1：

```text
seckill_stock_snapshot.REGISTERED = 9057
seckill_stock_change_log.NEW = 9057
```

MySQL：

```text
seckill_order_count = 0
```

结论：

- 入口成功，但闭合完全未跑通
- 结果只能判定为 `仅入口通过`

### 3.3 失败根因

`mall-seckill` 日志明确报错：

```text
Unknown column 'outbox_claim_token' in 'unknown clause'
```

根因判断：

1. 代码已经依赖 `outbox_claim_token` / `outbox_claimed_at`
2. 运行中的 OceanBase 分片库尚未执行 `migration-v14-seckill-outbox-direct-drain.sql`
3. 导致 outbox 恢复与推进任务无法执行，`REGISTERED/NEW` 大量堆积

## 4. 后续修复动作

本轮已经完成：

```text
将 sql/migration-v14-seckill-outbox-direct-drain.sql 执行到：
- mall-oceanbase-ce / mall
- mall-oceanbase-ce-shard1 / test
```

修复后继续复验时，又暴露第二个运行工具问题：

```text
scripts/loadtest/stage3c/reset-seckill-loadtest.ps1
在 Redis 批量删除阶段报：
filename extension too long
```

直接原因：

- 脚本把大量 Redis key 直接拼给 `redis-cli DEL`
- Windows / PowerShell 命令行长度超限

因此当时没能在“已补 v14 迁移”的干净数据面上完成第二轮完整压测。后续已修复该脚本的 Redis 批量删除实现，并完成一次真实 reset 验证通过。

## 5. 第二轮 clean rerun

前置条件：

- 两个 OceanBase 分片均已执行 `migration-v14-seckill-outbox-direct-drain.sql`
- `scripts/loadtest/stage3c/reset-seckill-loadtest.ps1` Redis 批量删除已修复并通过真实 reset

第二轮入口压测产物：

```text
JTL=target/loadtest/stage3c-current/submit-20260710-193215.jtl
REPORT=target/loadtest/stage3c-current/report-20260710-193215
```

入口结果：

```text
samples=14998
avg=146ms
throughput≈249.5 req/s
http 200,true = 14998
jmeter errors = 0%
```

压测结束约 20 秒后：

```text
RabbitMQ:
  mall.seckill.order.create.queue ready=7842 unacked=800
  mall.seckill.order.result.queue ready=0 unacked=4

OceanBase mall:
  seckill_result.SUCCESS = 4341
  seckill_stock_snapshot.CONFIRMED = 2075
  seckill_stock_snapshot.REGISTERED = 5420
  seckill_stock_change_log.APPLIED = 7495
  mq_message.SENT = 7495

OceanBase shard1/test:
  seckill_stock_snapshot.CONFIRMED = 2270
  seckill_stock_snapshot.REGISTERED = 5234
  seckill_stock_change_log.APPLIED = 7504
  mq_message.SENT = 7504

MySQL mall:
  order_info.CREATED = 6264
  seckill_order_count = 6264
  mq_message.DISPATCHING = 19
  mq_message.NEW = 1919
  mq_message.SENT = 10590
```

压测结束约 80 秒后：

```text
RabbitMQ:
  mall.seckill.order.create.queue ready=629 unacked=800
  mall.seckill.order.result.queue ready=0 unacked=4

OceanBase mall:
  seckill_result.SUCCESS = 8275
  seckill_stock_snapshot.CONFIRMED = 4079
  seckill_stock_snapshot.REGISTERED = 3416
  seckill_stock_change_log.APPLIED = 7495
  mq_message.SENT = 7495

OceanBase shard1/test:
  seckill_stock_snapshot.CONFIRMED = 4197
  seckill_stock_snapshot.REGISTERED = 3307
  seckill_stock_change_log.APPLIED = 7504
  mq_message.SENT = 7504

MySQL mall:
  order_info.CREATED = 13467
  seckill_order_count = 13467
  mq_message.DISPATCHING = 24
  mq_message.NEW = 5192
  mq_message.SENT = 21718
```

判断：

- 与第一轮相比，v14 迁移和 reset 脚本修复已经生效，链路不再卡死在 `NEW/REGISTERED` 起点。
- 但第二轮在压测结束 80 秒后仍存在显著 backlog：
  - `mall.seckill.order.create.queue` 仍有 `ready=629`
  - 两个 OceanBase 分片仍有 `REGISTERED=3416/3307`
  - `seckill_result.SUCCESS` 仍明显落后于 `seckill_order_count`
- 因此第二轮仍不能判定为“全链路通过”，并且明显不满足“用户终态 P99 <= 10 秒”的目标。

## 6. 本轮最终结论

```text
不通过
```

原因：

- 第一轮：入口通过，但运行库缺少 v14 迁移，闭合直接失败
- 第二轮：迁移与 reset 修复后，链路开始推进，但压测结束 80 秒后仍有明显 backlog，未达到闭合时延目标

## 7. 建议下一步

优先级：

1. 重点排查为什么 `seckill_stock_change_log.APPLIED` 仅停在约 `7495/7504`，而后续 `REGISTERED` / `SUCCESS` 仍持续滞后。
2. 重点排查 `mall.seckill.order.create.queue` 长时间保留 `unacked=800` 的原因，确认消费者并发、ack 时机和下游耗时。
3. 继续细分时延：`change_log -> mq_message`、`mq_message -> order_info`、`order_info -> seckill_result`。
4. 针对 backlog 根因修复后，再重跑 40 线程基线和更高并发场景。
