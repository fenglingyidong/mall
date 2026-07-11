# 秒杀入口事实写入重构设计

## 背景

当前秒杀入口热路径的 SQL 分散在多个类和函数中：

- `SeckillServiceImpl.doSubmitWithAsyncEntry()` 编排入口流程。
- `SeckillRepository.registerBucketSnapshot()` 写 `seckill_stock_snapshot`。
- `SeckillRepository.recordBucketDeductionFact()` 提供扣减事务入口。
- `SeckillBucketService.deductSelectedAndRecordChangeLog()` 执行 bucket 扣减并写 `seckill_stock_change_log`。

这会导致 review 时必须跨文件追踪 SQL 顺序和事务边界。当前核心事实写入应被视为一个入口用例，而不是分散在 repository 和 bucket service 里的若干片段。

## 目标

引入一个高内聚入口事实写入组件，让 review 可以在一个类中确认入口 DB 写入顺序、事务边界和异常语义。

目标 SQL 顺序：

1. `INSERT seckill_stock_snapshot`
2. `UPDATE seckill_stock_bucket`
3. `INSERT seckill_stock_change_log`
4. 发布 `SeckillDeductCommittedEvent`，由 `AFTER_COMMIT` listener 唤醒 outbox 派发

目标事务边界：

- `snapshot insert`、`bucket update`、`change_log insert` 位于同一个本地事务。
- `bucket update` 和 `change_log insert` 必须一起成功或一起失败。
- after-commit 事件只在事务提交后生效。

## 非目标

本轮不重构以下链路：

- 订单结果确认：`CONFIRMED/SUCCESS`
- 库存释放：`RELEASED/CANCELED`
- bucket 调拨后台任务
- snapshot repair
- change_log outbox worker
- RabbitMQ 派发模型

本轮只处理秒杀入口热路径的事实写入边界。

## 目标架构

新增 `SeckillEntryFactWriter`，作为入口 DB 事实写入用例类。

建议位置：

```text
mall-seckill/src/main/java/com/mall/seckill/service/impl/SeckillEntryFactWriter.java
```

对外只暴露一个入口方法：

```java
@Transactional(rollbackFor = Exception.class)
public EntryFactResult recordAcceptedEntry(EntryFactCommand command)
```

职责：

- 构造并插入 `seckill_stock_snapshot`
- 执行已选 bucket 的条件扣减
- 构造并插入 `seckill_stock_change_log`
- 处理入口事实写入的结果语义
- 发布 `SeckillDeductCommittedEvent`

`SeckillServiceImpl` 不再分别调用 `registerBucketSnapshot()` 和 `recordBucketDeductionFact()`，而是调用：

```java
EntryFactResult result = entryFactWriter.recordAcceptedEntry(command);
```

`SeckillBucketService` 保留选桶、空桶标记、调拨兜底等库存领域能力，但入口事实写入的主 SQL 顺序应收敛到 `SeckillEntryFactWriter`。

## 数据结构

### EntryFactCommand

输入命令对象，字段固定为入口事实写入所需最小集合：

```text
requestId
stockId
activityId
skuId
userId
quantity
selectedBucket
```

`selectedBucket` 使用当前 `SeckillBucketService.SelectedBucket`，包含：

```text
bucketId
bucketNo
bucketShardKey
strategyVersion
configId
```

### EntryFactResult

输出结果应表达入口事实写入 outcome：

```text
CREATED
REQUEST_DUPLICATE
ACTIVE_DUPLICATE
STOCK_NOT_ENOUGH
DEDUCT_DUPLICATE
```

携带字段：

```text
outcome
stockVersion
snapshot
selectedBucket
changeLogId
```

`DEDUCT_DUPLICATE` 用于 `change_log` 唯一索引冲突兜底。入口不做扣前 `change_log` 幂等读，但仍保留数据库唯一约束作为最终保护。

## 事务与 SQL 顺序

`recordAcceptedEntry()` 的成功路径为：

```text
1. INSERT seckill_stock_snapshot(status = REGISTERED)
2. UPDATE seckill_stock_bucket
   SET saleable_quantity = saleable_quantity - quantity,
       version = version + 1
   WHERE id = selectedBucket.bucketId
     AND shard_key = selectedBucket.bucketShardKey
     AND bucket_type = 'BUCKET'
     AND status = 'ACTIVE'
     AND saleable_quantity >= quantity
3. INSERT seckill_stock_change_log(change_type = DEDUCT, status = NEW)
4. publish SeckillDeductCommittedEvent
5. return CREATED
```

失败路径：

- `snapshot request_id` 唯一键冲突：查询现有 snapshot，返回 `REQUEST_DUPLICATE`。
- `snapshot active_key` 唯一键冲突：返回 `ACTIVE_DUPLICATE`。
- bucket update 返回 `0`：标记/通知空桶按现有策略处理，然后抛出或返回 `STOCK_NOT_ENOUGH`。事务内不保留本次 snapshot。
- `change_log` 唯一键冲突：事务回滚 bucket 扣减，返回 `DEDUCT_DUPLICATE`。

设计取舍：

- `STOCK_NOT_ENOUGH` 不保留 `REGISTERED -> FAILED` snapshot。原因是三条入口事实写入被定义为一个原子用例，bucket 扣减失败时没有有效库存事实，保留 snapshot 反而需要额外补偿语义。
- 如果后续业务明确需要查询失败快照，可以另行设计失败投影，不应混入扣减事实事务。

## 类边界

### SeckillServiceImpl

保留职责：

- Redis request guard
- buyer guard
- stock cache sold-out 快速失败
- 调用选桶
- 调用 `SeckillEntryFactWriter`
- 根据 outcome 释放 buyer 或返回响应

不再承担：

- 判断 snapshot 写入细节
- 调用 bucket 扣减事实写入细节

### SeckillEntryFactWriter

承担职责：

- 入口事实写入事务
- 三条核心 SQL 的顺序
- duplicate outcome 映射
- bucket update 失败 outcome 映射
- change_log insert 失败时回滚事务
- after-commit 事件发布

### SeckillBucketService

保留职责：

- 选桶
- bucket 可用性判断
- 空桶标记
- 调拨兜底
- release 路径的 bucket 操作

入口扣减主路径中，`SeckillBucketService` 不再暴露“扣减并写 change_log”的 public 方法。入口事实写入类可以直接使用 mapper，或调用一个只做 bucket update/空桶标记的窄接口。

### SeckillRepository

逐步退回数据查询和结果投影相关职责。

入口热路径迁移后，以下方法应删除或停止被入口使用：

- `registerBucketSnapshot()`
- `recordBucketDeductionFact()`

如果 confirm/release 仍依赖 `hasDeductChangeLog()`，该方法可以保留在 repository 内，后续单独重构。

## 指标

保留现有阶段指标，但按新类命名更清晰：

```text
seckill.entry.fact.snapshot.insert
seckill.entry.fact.bucket.deduct
seckill.entry.fact.change-log.insert
seckill.entry.fact.total
```

`total` 覆盖 `recordAcceptedEntry()` 整体事务耗时。

## 测试策略

新增或迁移单测：

- 成功路径按顺序执行 snapshot insert、bucket update、change_log insert。
- snapshot request duplicate 返回 `REQUEST_DUPLICATE`，不扣 bucket。
- snapshot active duplicate 返回 `ACTIVE_DUPLICATE`，不扣 bucket。
- bucket update 返回 0 时不插入 change_log，并返回或抛出库存不足。
- change_log insert `DuplicateKeyException` 时事务回滚，返回 `DEDUCT_DUPLICATE`。
- after-commit event 只在成功写入 change_log 后发布。

保留现有 service 层测试，调整为验证 `SeckillServiceImpl` 只调用 `SeckillEntryFactWriter` 一个入口写入接口。

## 迁移步骤

1. 新增 `SeckillEntryFactWriter`、`EntryFactCommand`、`EntryFactResult`。
2. 把 `registerBucketSnapshot()` 的 snapshot 构造和插入逻辑迁入 writer。
3. 把 `deductSelectedAndRecordChangeLog()` 的 bucket update 和 change_log insert 逻辑迁入 writer。
4. 修改 `SeckillServiceImpl`，用 `entryFactWriter.recordAcceptedEntry()` 替代两次 repository 调用。
5. 删除或废弃入口不再使用的方法。
6. 跑 `mall-seckill` 聚焦测试和全量模块测试。

## Review 预期

重构后，review 入口事实写入只需检查：

```text
SeckillServiceImpl: 入口 guard 和响应编排
SeckillEntryFactWriter: 三条核心 SQL 和事务语义
SeckillBucketService: 选桶和库存辅助规则
```

核心 SQL 顺序不再分散在多个 public 方法之间。
