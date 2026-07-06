# Seckill Stage 3B Auto Transfer And Fragmentation Design

## 背景

`mall-seckill` 当前已经完成本地 Stage 3A 的核心闭环：

- `seckill_stock_bucket` 已有 `CENTER` 中心桶和多个 `BUCKET` 业务桶。
- C 端扣减业务桶，扣减位置写入 `seckill_stock_snapshot`。
- `seckill_stock_change_log` 已记录 `DEDUCT`、`RELEASE`、`TRANSFER_OUT`、`TRANSFER_IN`。
- 中心桶异步消费变更日志，按 `quantity_delta` 汇总总账。
- 请求触发型调拨已经存在，低库存打空压测已经验证 `transfer=true` 下库存可售归零、中心桶归零、调拨日志成对消费。
- 已修复 `EMPTY + saleable_quantity > 0` 的并发状态问题，并在压测脚本中增加 `emptyPositiveBuckets` 验收项。

阶段三原文强调分桶架构不只是多行随机扣减，还包括存活桶列表、调拨、异步总账、碎片整理、下线/上线和分片路由。当前项目还缺少后台碎片整理和后台自动调拨。本设计把 Stage 3B 定义为“单库语义完整复刻”：在当前单 OceanBase/MySQL 环境中补齐后台状态整理和自动调拨能力，先保证调度语义、账本闭环和压测验收稳定；真实多分片路由留到 Stage 3C/3D。

## 目标

Stage 3B 的目标是让业务桶状态、survivor 列表和库存分布在后台持续收敛：

- 周期性修复桶状态与库存数量不一致的问题。
- 周期性重建 `survivor_buckets`，让它与真实可用业务桶一致。
- 在低水位或空桶场景下，后台主动从富余桶调拨少量库存到目标桶。
- 自动调拨复用现有 `TRANSFER_OUT` / `TRANSFER_IN` 日志，使中心桶异步总账保持净变化为 0。
- 自动调拨不能改变全局库存总量，不能让中心桶参与 C 端实时扣减。
- 后台任务必须可开关、限批、低频、幂等，不能与 C 端请求抢占过多数据库资源。

## 非目标

本阶段不做：

- 真实多数据源或多物理分片路由。
- 跨分片调拨 saga 或补偿状态机。
- 从中心桶向业务桶补货。
- 自动追加库存、减少库存或修改 `setting_quantity`。
- 将所有业务桶做严格均衡。
- 以本地单库压测结果宣称达到阶段三原文中的 8w QPS。

## 方案选择

### 方案 A：只做状态整理

后台只修复 `EMPTY + saleable_quantity > 0`、`ACTIVE + saleable_quantity <= 0` 和 survivor 列表。

优点是风险最低，不改变库存分布，不写调拨日志。缺点是不能主动改善低水位桶分布，也不能复刻阶段三的自动调拨能力。

### 方案 B：状态整理 + 低水位自动调拨，推荐

后台先做状态整理，再选择低水位桶作为目标，从富余桶搬少量库存过去。每轮每个 SKU 限制调拨次数，调拨写成对变更日志。

优点是能补齐阶段三的调拨和碎片治理语义，且仍保持单库事务闭环。缺点是会增加后台写入和变更日志，需要严格限流和监控。

### 方案 C：完整均衡型调拨

后台周期性计算所有桶库存分布，把业务桶调整到接近平均。

优点是库存分布最整齐。缺点是写放大明显，容易和 C 端扣减竞争，低库存秒杀场景下收益不如低水位补货明确。

本阶段采用方案 B。

## 配置设计

新增配置，默认关闭：

```yaml
mall:
  seckill:
    bucket:
      reconcile:
        enabled: false
        fixed-delay: 60000
        batch-size: 100
      auto-transfer:
        enabled: false
        fixed-delay: 60000
        batch-size: 100
        max-pairs-per-sku: 8
        low-watermark: 0
        transfer-size: 8
        source-reserve-quantity: 1
        lock-wait-millis: 20
        lock-lease-millis: 500
```

字段含义：

| 字段 | 含义 |
| --- | --- |
| `bucket.reconcile.enabled` | 是否启用后台状态整理 |
| `bucket.reconcile.fixed-delay` | 状态整理固定间隔 |
| `bucket.reconcile.batch-size` | 每轮最多扫描多少个 bucket config |
| `bucket.auto-transfer.enabled` | 是否启用后台自动调拨 |
| `bucket.auto-transfer.fixed-delay` | 自动调拨固定间隔 |
| `bucket.auto-transfer.batch-size` | 每轮最多扫描多少个 bucket config |
| `bucket.auto-transfer.max-pairs-per-sku` | 每个 SKU 每轮最多完成多少组成对调拨 |
| `bucket.auto-transfer.low-watermark` | 目标桶低水位阈值，`<=` 该值可作为调拨目标 |
| `bucket.auto-transfer.transfer-size` | 单次调拨数量上限 |
| `bucket.auto-transfer.source-reserve-quantity` | 来源桶调拨后至少保留的库存 |
| `bucket.auto-transfer.lock-wait-millis` | 获取 SKU 级后台调拨锁的最大等待时间 |
| `bucket.auto-transfer.lock-lease-millis` | 后台调拨锁租约 |

## 组件设计

### `SeckillBucketReconcileJob`

可开关后台任务，负责状态整理。

职责：

- 扫描启用的 `seckill_bucket_config`。
- 对每个 `activityId + skuId` 执行状态修复。
- 重建 survivor 列表。
- 记录修复数量和耗时指标。

它不改变库存数量，不写 `seckill_stock_change_log`。

### `SeckillBucketAutoTransferJob`

可开关后台任务，负责低水位自动调拨。

职责：

- 扫描启用的 `seckill_bucket_config`。
- 对每个 `activityId + skuId` 获取 SKU 级分布式锁。
- 先调用状态整理，保证路由列表尽量准确。
- 找到低水位目标桶和富余来源桶。
- 调用自动调拨服务完成成对调拨。
- 限制每个 SKU 每轮调拨数量。

### `SeckillBucketAutoTransferService`

执行单 SKU 内的一组自动调拨，事务边界在单次调拨内。

职责：

- 选择目标桶：`bucket_type='BUCKET' AND saleable_quantity <= lowWatermark`。
- 选择来源桶：`status='ACTIVE' AND saleable_quantity >= transferSize + sourceReserveQuantity`。
- 来源桶优先库存最高，目标桶优先库存最低。
- 在本地事务内完成 source 扣减、target 增加、日志写入和 survivor 更新。

## 状态整理流程

对每个启用的 `activityId + skuId`：

1. 将 `bucket_type='BUCKET' AND saleable_quantity > 0 AND status <> 'ACTIVE'` 修正为 `ACTIVE`。
2. 将 `bucket_type='BUCKET' AND saleable_quantity <= 0 AND status = 'ACTIVE'` 修正为 `EMPTY`。
3. 查询 `bucket_type='BUCKET' AND status='ACTIVE' AND saleable_quantity > 0` 的 `bucket_no`。
4. 按升序格式化为 `survivor_buckets`。
5. 更新 `seckill_bucket_config.survivor_buckets`。

该流程幂等。重复执行不会改变库存数量，也不会写变更日志。

## 自动调拨流程

对每个启用的 `activityId + skuId`：

1. 获取锁：

```text
seckill:bucket:auto-transfer:{activityId}:{skuId}
```

2. 执行一次状态整理。
3. 循环最多 `maxPairsPerSku` 次：
   - 查询目标桶。
   - 查询来源桶。
   - 如果任一不存在，结束该 SKU 本轮自动调拨。
   - 计算 `moveQuantity = min(transferSize, source.saleable_quantity - sourceReserveQuantity)`。
   - 如果 `moveQuantity <= 0`，结束。
   - 执行单次调拨事务。

单次调拨事务内：

1. 条件扣来源桶：

```sql
UPDATE seckill_stock_bucket
SET saleable_quantity = saleable_quantity - :moveQuantity,
    version = version + 1,
    updated_at = NOW()
WHERE id = :sourceId
  AND bucket_type = 'BUCKET'
  AND status = 'ACTIVE'
  AND saleable_quantity >= :moveQuantity + :sourceReserveQuantity
```

2. 增加目标桶：

```sql
UPDATE seckill_stock_bucket
SET saleable_quantity = saleable_quantity + :moveQuantity,
    version = version + 1,
    status = 'ACTIVE',
    updated_at = NOW()
WHERE id = :targetId
  AND bucket_type = 'BUCKET'
```

3. 读取 source/target 更新后状态。
4. 写 `TRANSFER_OUT(-moveQuantity)`。
5. 写 `TRANSFER_IN(+moveQuantity)`。
6. 将目标桶加入 survivor。
7. 如果来源桶已无可售，走 `markEmptyIfNoSaleable` 条件标空，成功后移除 survivor。

两条调拨日志净变化必须为 0。中心桶消费后 `centerSaleable` 不应因调拨改变。

## 二次确认与调拨风暴控制

阶段三原文提到库存不足时要二次确认，避免因旧 survivor 或主从延迟造成无效调拨。当前本地单库版本没有主从读写分离，但仍保留同样语义：

- 自动调拨每次都从主库查询目标桶和来源桶。
- source 扣减 SQL 必须带库存条件。
- target 更新失败时整笔事务回滚。
- 后台自动调拨使用 SKU 级锁，请求触发调拨继续使用目标桶锁。
- 后台拿不到锁直接跳过，不等待过久。
- 每个 SKU 每轮最多调拨 `maxPairsPerSku` 次。

## 幂等与一致性

状态整理幂等：

- 状态由当前库存数量推导。
- survivor 列表由当前 `ACTIVE + saleable_quantity > 0` 业务桶推导。
- 不依赖上次执行结果。

自动调拨幂等：

- 单次调拨不是重复执行幂等操作，但受锁、条件扣减和每轮上限保护。
- 调拨成功必须 source/target 更新和两条日志同事务提交。
- 调拨失败必须整体回滚，不能留下单边库存变化或单边日志。
- 调拨日志进入中心桶消费者后净变化为 0。

## 失败处理

- 扫描配置失败：记录日志，本轮结束。
- 单 SKU 获取锁失败：跳过该 SKU。
- 找不到目标桶或来源桶：结束该 SKU 本轮调拨。
- source 条件扣减失败：跳过该次调拨，继续下一轮或结束。
- target 更新失败：抛异常并回滚事务。
- 日志写入失败：抛异常并回滚事务。
- survivor 更新失败：抛异常并回滚事务。

后台任务不能影响 C 端提交链路。任务失败只记录日志和指标。

## 指标与观测

建议新增 Micrometer 指标：

| 指标 | 含义 |
| --- | --- |
| `seckill.bucket.reconcile.total` | 状态整理总耗时 |
| `seckill.bucket.reconcile.config.count` | 每轮扫描配置数量 |
| `seckill.bucket.reconcile.status.fixed` | 修复状态数量 |
| `seckill.bucket.reconcile.survivor.updated` | survivor 更新次数 |
| `seckill.bucket.auto.transfer.total` | 自动调拨任务耗时 |
| `seckill.bucket.auto.transfer.attempt` | 调拨尝试次数 |
| `seckill.bucket.auto.transfer.success` | 调拨成功次数 |
| `seckill.bucket.auto.transfer.lock.miss` | 调拨锁未获取次数 |
| `seckill.bucket.auto.transfer.source.miss` | 找不到来源桶次数 |
| `seckill.bucket.auto.transfer.target.miss` | 找不到目标桶次数 |
| `seckill.bucket.auto.transfer.failure` | 调拨异常次数 |

## 测试计划

### 单元测试

`SeckillBucketReconcileJob` / `SeckillBucketReconcileService`：

- `EMPTY + saleable_quantity > 0` 被恢复为 `ACTIVE`。
- `ACTIVE + saleable_quantity <= 0` 被置为 `EMPTY`。
- survivor 列表按真实可用桶重建。
- 重复执行不改变结果。

`SeckillBucketAutoTransferService`：

- 成功从富余桶搬库存到低水位桶，写成对日志。
- source 不足时跳过。
- target 更新失败时回滚。
- 日志写入失败时回滚。
- source 扣到 0 时条件标空并移除 survivor。
- target 加库存后加入 survivor。

`SeckillBucketAutoTransferJob`：

- 未开启配置时不执行。
- 获取不到锁时跳过。
- 每个 SKU 不超过 `maxPairsPerSku`。

### 集成与压测

1. 人工构造状态不一致：
   - `EMPTY + saleable_quantity > 0`
   - `ACTIVE + saleable_quantity = 0`
   - survivor 列表缺失或多余

   开启 reconcile 后应全部修正。

2. 人工构造库存不均：
   - 一个或多个低水位桶
   - 一个富余桶

   开启 auto-transfer 后应产生 `TRANSFER_OUT / TRANSFER_IN` 成对日志。

3. 低库存打空压测：
   - `transfer=false/true`
   - `auto-transfer=false/true`
   - 验收 `accepted=stock`、`aggregateStock=0`、`centerSaleable=0`、`emptyPositiveBuckets=0`、`TRANSFER_OUT=TRANSFER_IN`、`NEW/PROCESSING=0`、RabbitMQ 无积压。

4. 稳定库存压测：
   - 大库存不打空，固定 2 分钟。
   - 比较自动调拨开启前后的 QPS、P95/P99、数据库计时器和调拨写入量。

## 实现顺序

1. 增加 `SeckillProperties.Bucket.Reconcile` 和 `AutoTransfer` 配置。
2. 在 mapper 中补齐状态整理、目标桶查询、来源桶查询和按 config 扫描方法。
3. 新增 `SeckillBucketReconcileService`。
4. 新增 `SeckillBucketReconcileJob`。
5. 新增 `SeckillBucketAutoTransferService`。
6. 新增 `SeckillBucketAutoTransferJob`。
7. 增加单元测试。
8. 扩展压测脚本，记录自动调拨相关日志数量和验收项。
9. 打包重启，运行低库存和稳定库存压测。

## 后续 Stage 3C/3D

Stage 3B 仍然是单库语义完整复刻。真实阶段三性能复刻需要继续做：

- `BucketRouteService` 路由抽象。
- `bucket_no -> shard_key -> datasource/table` 映射。
- 多数据源或 ShardingSphere 接入。
- 分片维度压测指标。
- 跨分片调拨 saga 和补偿状态机。

在真实分片前，自动调拨只依赖单库事务。进入跨分片后，不能继续假设 source/target 可以同事务提交。

## 验收标准

Stage 3B 完成后必须满足：

- 状态整理任务可开关，默认关闭。
- 自动调拨任务可开关，默认关闭。
- 状态整理不会改变总库存，不写变更日志。
- 自动调拨只改变业务桶库存分布，不改变业务桶聚合库存。
- 自动调拨日志成对出现，净变化为 0。
- 中心桶异步消费后总账不漂移。
- survivor 列表与真实可用桶一致。
- 重复运行后台任务不会造成库存增加或减少。
- `mall-seckill` 单元测试通过。
- 低库存打空售罄压测通过。
