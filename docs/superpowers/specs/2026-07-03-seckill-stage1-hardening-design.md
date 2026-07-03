# Seckill Stage 1 Hardening Design

## 背景

当前 `mall-seckill` 已将阶段一主链路切到数据库同步扣减：`seckill_sku` 是库存事实源，`seckill_stock_snapshot` 记录扣减快照，TairString 只承担读缓存和售罄快速失败。但阶段一还缺少三类生产化兜底：

- 数据库层一人一单唯一约束，避免并发穿透 Redisson 或应用先查再插竞态。
- TairString 刷新失败后的缓存修复闭环，避免缓存长期停留在旧版本。
- 一键式真实阶段一压测验收脚本，固定重置、压测、等待异步结果、账本校验和摘要输出。

本设计仅覆盖阶段一正确性和验收增强，不引入阶段二热点隔离，也不实现阶段三分桶。

## 业务口径

秒杀一人一单按活动维度约束：同一 `activity_id + user_id` 只能最终成功一次。该口径与现有 `seckill_order` 唯一键 `uk_seckill_user(activity_id, user_id)` 保持一致。

释放语义：

- `DEDUCTED`：已扣库存但订单未最终确认，属于活跃资格。
- `CONFIRMED`：订单创建成功，仍属于活跃资格，并永久占住本活动资格。
- `RELEASED`：订单创建失败、消息发布失败或补偿释放成功，不再占住资格，允许用户再次重试。

## 方案

采用 `active` 标记加唯一键：

- `seckill_stock_snapshot` 新增 `active TINYINT NOT NULL DEFAULT 1`。
- 新增唯一键 `uk_snapshot_active_user(activity_id, user_id, active)`。
- 插入扣减快照时写 `active=1`。
- 确认快照时保持 `active=1`。
- 释放快照时更新为 `status='RELEASED', active=0`，并回补 `seckill_sku.stock/version`。

选择该方案是因为 MySQL/OceanBase MySQL 模式不能稳定依赖部分唯一索引；`active` 可以表达“活跃资格唯一”，同时释放后不阻断重试。

## 组件设计

### 唯一约束与幂等兜底

数据库变更：

- 更新 `sql/schema.sql` 中 `seckill_stock_snapshot` 表结构。
- 新增迁移脚本，将历史 `DEDUCTED/CONFIRMED` 设置为 `active=1`，其他状态设置为 `active=0`，再创建唯一键。

Repository 变更：

- `hasActiveDeduction(activityId, userId)` 改为按 `active=1` 查询，不再把 `skuId` 放入口径。
- `recordDeduction()` 保留前置查询作为快速失败，但不能依赖它保证正确性。
- `snapshotMapper.insert()` 捕获 `DuplicateKeyException`，遇到唯一键冲突返回 `StockDeductionResult.duplicate()`。
- `releaseDeduction()` 更新快照时同时设置 `active=0`。
- `confirmDeduction()` 保持 `active=1`。

### 缓存修复闭环

新增 `SeckillStockCacheRepairJob`：

- 通过配置 `mall.seckill.stock-cache.repair.enabled=true` 启用。
- 定时扫描 `seckill_sku`，读取 `activity_id, sku_id, stock, version`。
- 按版本调用现有 `SeckillStockCache.refresh()`，利用 TairString 版本比较避免旧值覆盖新值。
- 支持 `fixed-delay` 与 `limit` 配置，默认关闭，避免本地普通 Redis profile 误执行 TairString 命令。
- 单条修复失败只记录日志，下一轮继续；数据库仍是事实源。

### 阶段一压测验收脚本

新增 PowerShell 脚本 `docs/scripts/verify-seckill-stage1.ps1`：

- 参数化 `JMeterPath`、`MysqlCliPath`、`DbHost`、`DbPort`、`RedisHost`、`RedisPort`、`ActivityId`、`SkuId`、`Stock`、`Threads`、`Loops`、`Ramp`、`WaitSeconds`。
- 重置 `seckill_sku`、清理 `seckill_stock_snapshot`、`seckill_result`、`seckill_order`、`mq_message`、`consume_record`。
- 删除 TairString/Redis 缓存 key `seckill:stock-cache:{activityId}:{skuId}`。
- 调用 `docs/jmeter/seckill-submit.jmx`。
- 等待异步订单结果追平。
- 校验数据库账本、缓存值和版本、JMeter 失败数。
- 输出 `target/loadtest/stage1-verify-<timestamp>.json` 摘要。

验收判定：

- `seckill_sku.stock = 初始库存 - SUCCESS 数`。
- `seckill_sku.version = SUCCESS 数`。
- `seckill_stock_snapshot.CONFIRMED = SUCCESS 数`。
- `seckill_stock_snapshot.DEDUCTED = 0`。
- `seckill_result.SUCCESS = SUCCESS 数`。
- `seckill_order = SUCCESS 数`。
- 缓存值等于数据库 `stock/version`。
- JMeter 失败数为 `0`，除非脚本参数显式允许阈值。

## 错误处理

- 唯一键冲突视为重复购买，返回业务失败，不扣库存、不发 MQ。
- 库存不足抛出原有 `SeckillStockNotEnoughException`，事务回滚，不留下快照。
- 缓存修复失败不影响数据库账本，下轮重试。
- 压测脚本遇到任一账本不一致时以非零退出码失败。

## 测试计划

- Repository 单测：
  - 唯一键冲突返回 duplicate。
  - 活跃重复检查按 `activity_id + user_id + active=1`。
  - 释放快照时设置 `active=0` 并回补库存。
  - 确认快照不释放 active。
- 缓存修复任务单测：
  - 启用后扫描库存并刷新缓存。
  - 修复单条异常不终止整轮。
- 回归：
  - 执行 `mvn -pl mall-seckill -am test`。
  - 在真实 OceanBase + TairString 环境执行 `docs/scripts/verify-seckill-stage1.ps1`。

## 非目标

- 不实现 `LOGIC_UPDATE + RETURNING` 快路径。
- 不实现分桶、调拨、中心桶。
- 不调整订单服务的一人一单唯一键口径。
- 不修改默认 MySQL + 普通 Redis profile 的行为；阶段一缓存修复默认关闭。
