# Seckill Asset Risk Stopgap Implementation Plan

## 来源

- 设计文档：`docs/superpowers/specs/2026-07-07-seckill-asset-risk-stopgap-design.md`
- 设计提交：`231ea36 docs: add seckill asset risk stopgap design`
- 本计划只进入实现拆解，不修改业务代码。

## 实施原则

1. 先止血，再优化吞吐。单库单表 guard 是本轮资损 invariant 的事实源。
2. 每一步都保持可回滚：新增表、新增状态、新增组件先兼容旧链路，再切换调用方。
3. 所有状态推进使用 CAS。失败、确认、回补、repair 不能覆盖彼此已经完成的结果。
4. 不能证明“没有扣库存”时，guard 保留 `PROCESSING`，交给 repair。
5. 测试先覆盖资损边界，再补普通成功路径。

## 阶段 0：基线和开关

### 改动

- 记录当前工作树状态，确认后续只编辑本计划覆盖的文件。
- 增加配置模型：
  - `mall.seckill.reservation-guard.enabled`
  - `mall.seckill.reservation-guard.processing-probe-after-seconds`
  - `mall.seckill.reservation-guard.safe-release-after-seconds`
  - `mall.seckill.reservation-guard.repair-batch-size`
  - `mall.seckill.result-retry.enabled`
  - `mall.seckill.result-retry.max-attempts`
  - `mall.seckill.result-retry.delays`
  - `mall.message.compensation.dispatching-timeout-seconds`
- 先让默认配置保持旧行为，`stage3c-sharding` 再开启 guard 和 retry。

### 文件

- `mall-seckill/src/main/java/com/mall/seckill/config/SeckillProperties.java`
- `mall-seckill/src/main/resources/application.yml`
- `mall-seckill/src/main/resources/application-stage3c-sharding.yml`
- `mall-message` 的补偿超时配置先从使用方 `application*.yml` 读取，不新增模块级资源文件。

### 验证

- 配置绑定单元测试或现有 Spring context 测试能启动。

## 阶段 1：数据库和 ShardingSphere 路由

### 改动

- 新增迁移 `sql/migration-v12-seckill-asset-risk-stopgap.sql`。
- 新增 `seckill_reservation_guard`，止血版落在单库单表。
- 新增 `seckill_result_retry`，止血版也落单表，记录结果消费延迟重试事实。
- `mq_message` 新增 `error_type` 字段，状态允许 `DISPATCHING`。
- `sql/schema.sql` 同步最终建表结构。
- `shardingsphere-stage3c.yaml` 将以下表声明为 `!SINGLE`：
  - `seckill_reservation_guard`
  - `seckill_result_retry`

### Guard 关键约束

```sql
UNIQUE KEY uk_guard_reservation (reservation_id),
UNIQUE KEY uk_guard_request (request_id),
UNIQUE KEY uk_guard_activity_active (activity_id, active_key)
```

### Retry 关键字段

```text
message_id, reservation_id, result_type, payload, bucket_shard_key,
retry_count, first_failed_at, last_failed_at, last_error, next_retry_at, status
```

### 验证

- 本地执行 schema 兼容检查。
- `stage3c-sharding` 下插入同一 `(activity_id, active_key)` 必须只能成功一次。

## 阶段 2：可靠消息状态机 CAS

### 改动

- 扩展消息状态：
  - `NEW`
  - `DISPATCHING`
  - `SENT`
  - `FAILED`
  - `CONSUMED`
- 增加错误类型：
  - `RETURNED`
  - `CONFIRM_NACK`
  - `SEND_EXCEPTION`
  - `TIMEOUT`
- `ReliableMessageRepository` 新增 CAS 方法：
  - `markDispatching(messageId, bucketShardKey)`
  - `markSentIfDispatching(messageId, bucketShardKey)`
  - `markFailedIfDispatching(messageId, bucketShardKey, errorType, errorMessage)`
  - `markDispatchingTimedOut(timeoutBefore, limit)`
- `ReliableMessagePublisher.send` 在真正 `convertAndSend` 前先 `NEW/FAILED -> DISPATCHING`。
- confirm ack 只允许 `DISPATCHING -> SENT`。
- return、confirm nack、send exception 只允许 `DISPATCHING -> FAILED`。
- 补偿任务只扫描 `NEW/FAILED`；另加 `DISPATCHING` 超时修复为 `FAILED`。
- 所有 `error_message` 入库前截断到字段长度。

### 文件

- `mall-message/src/main/java/com/mall/message/ReliableMessageRepository.java`
- `mall-message/src/main/java/com/mall/message/ReliableMessagePublisher.java`
- `mall-message/src/main/java/com/mall/message/MessageCompensationJob.java`
- `mall-message/src/main/java/com/mall/message/MqMessageEntity.java`
- `mall-message/src/test/java/com/mall/message/ReliableMessageRepositoryTest.java`
- `mall-message/src/test/java/com/mall/message/ReliableMessagePublisherTest.java`

### 验证

- return 先到、confirm ack 后到时，最终仍为 `FAILED`。
- confirm nack 写 `CONFIRM_NACK`。
- send exception 写 `SEND_EXCEPTION`。
- `DISPATCHING` 超时能回到 `FAILED`，随后被补偿任务重发。

## 阶段 3：Reservation Guard 模块

### 改动

- 新增实体和 mapper：
  - `SeckillReservationGuardEntity`
  - `SeckillReservationGuardMapper`
- 新增仓储：
  - `ReservationGuardRepository`
- 建议返回类型：
  - `CreateGuardResult`
  - `GuardCreateOutcome.CREATED`
  - `GuardCreateOutcome.REQUEST_DUPLICATE`
  - `GuardCreateOutcome.ACTIVE_DUPLICATE`
- `ReservationGuardRepository` 提供小接口：
  - `createOrLoad(requestId, draft)`
  - `attachBucket(reservationId, bucketRef)`
  - `markDeducted(reservationId)`
  - `markFailedIfProcessing(reservationId, reason)`
  - `markConfirmed(reservationId)`
  - `markReleasedFromDeducted(reservationId, reason)`
  - `markReleasedFromConfirmed(reservationId, reason)`
  - `findStaleProcessing(before, limit)`
  - `findByReservationId(reservationId)`
  - `findByRequestId(requestId)`

### CAS 规则

- `PROCESSING -> DEDUCTED`
- `PROCESSING -> FAILED`
- `PROCESSING/DEDUCTED -> CONFIRMED`
- `DEDUCTED -> RELEASED`
- `CONFIRMED -> RELEASED` 只给订单关闭/取消路径使用
- `CONFIRMED` 不清空 `active_key`
- `FAILED/RELEASED` 清空 `active_key`

### 验证

- 同一 `requestId` 并发提交只创建一个 guard。
- 同一 `(activity_id, active_key)` 并发提交只创建一个有效 guard。
- `CONFIRMED` 后再次提交返回已购买，不释放占用。

## 阶段 4：提交链路接入 Guard

### 改动

- 新增 `SeckillDeductionCoordinator`，负责串联：
  1. 创建或加载 guard。
  2. 处理 requestId 幂等返回。
  3. 处理 active_key 冲突。
  4. 选择 bucket。
  5. 扣减前持久化 `bucket_id/bucket_no/bucket_shard_key` 到 guard。
  6. 执行 bucket 分片事务。
  7. 提交成功后推进 guard 到 `DEDUCTED`。
- `SeckillServiceImpl` 保留入口校验、限流、热点 permit、结果包装，把扣减编排交给 coordinator。
- `SeckillRepository` 增加接受已选 bucket 的扣减入口，避免在 guard attach 后又重新选桶。
- guard 开关关闭时保留旧链路，便于回滚。
- 明确异常分类：
  - 明确没扣：guard `PROCESSING -> FAILED`，清空 `active_key`。
  - 状态未知：guard 保持 `PROCESSING`。
- 现阶段 `requestId` 继续等于服务端生成的 `reservationId`；后续再加 `X-Request-Id`。

### 文件

- `mall-seckill/src/main/java/com/mall/seckill/service/impl/SeckillServiceImpl.java`
- `mall-seckill/src/main/java/com/mall/seckill/service/impl/SeckillDeductionCoordinator.java`
- `mall-seckill/src/main/java/com/mall/seckill/mapper/SeckillRepository.java`
- `mall-seckill/src/main/java/com/mall/seckill/service/impl/SeckillBucketService.java`
- `mall-seckill/src/main/java/com/mall/seckill/pojo/dto/SeckillOrderRequest.java`

### 验证

- bucket 模式下 guard 的 `bucket_shard_key` 早于库存扣减持久化。
- 桶事务 commit 边界异常时不释放 guard。
- guard active 冲突时不再依赖分片 snapshot 唯一键。

## 阶段 5：结果消费、延迟重试和幂等释放

### 改动

- 新增结果处理服务 `SeckillResultProcessor`，让 Rabbit listener 只做反序列化、ack/nack、重试调度。
- 当前订单侧 `FAILED` 结果按 `CREATE_FAILED` 语义处理，后续可把消息枚举正式改名。
- `SUCCESS`：
  - snapshot `DEDUCTED -> CONFIRMED`
  - guard `PROCESSING/DEDUCTED -> CONFIRMED`
  - `active_key` 保留
  - 保存 result `SUCCESS`
- `CREATE_FAILED`：
  - 只允许 snapshot `DEDUCTED -> RELEASING -> RELEASED`
  - 只允许 guard `DEDUCTED -> RELEASED`
  - 不能释放 `CONFIRMED`
- `ORDER_CLOSED/ORDER_CANCELED`：
  - 使用独立路径处理 `CONFIRMED -> RELEASED`
  - 后续接订单状态校验
- `releaseDeduction` 拆成两段式 CAS：
  1. snapshot `DEDUCTED -> RELEASING`
  2. 成功者回补 bucket 或普通 sku 库存
  3. snapshot `RELEASING -> RELEASED`
- 新增 `SeckillResultRetryRepository` 和 retry job：
  - transient 异常：写 retry，ack 原消息。
  - 延迟后重投到主结果队列。
  - 超过次数：投 DLQ 或标记 `DLQ`，同时告警日志。
- `RabbitMessageConfig` 复用现有 `mall.delay.exchange` 的 TTL + DLX 思路，新增结果 retry delay queue 和 binding。
- `OrderMessageListener.onDeadLetter` 移除 `SECKILL_ORDER_RESULT_DLQ`，避免订单服务吞库存侧失败结果。
- 所有结果消息文本截断到字段长度。

### 文件

- `mall-seckill/src/main/java/com/mall/seckill/service/impl/SeckillResultMessageListener.java`
- `mall-seckill/src/main/java/com/mall/seckill/service/impl/SeckillResultProcessor.java`
- `mall-seckill/src/main/java/com/mall/seckill/mapper/SeckillRepository.java`
- `mall-seckill/src/main/java/com/mall/seckill/mapper/SeckillStockSnapshotMapper.java`
- `mall-message/src/main/java/com/mall/message/RabbitMessageConfig.java`
- `mall-message/src/main/java/com/mall/message/MessageNames.java`
- `mall-order/src/main/java/com/mall/order/service/impl/OrderMessageListener.java`

### 验证

- result consumer transient DB 异常写 retry，不 `requeue=true` 无限重试。
- retry 次数和 `next_retry_at` 按 `5s,30s,2m,10m` 推进。
- 超限进入 DLQ/告警。
- SUCCESS 和 FAILED 乱序到达时，成功确认不会被后到失败释放。
- 重复 FAILED 只回补一次库存。

## 阶段 6：Repair Job

### 改动

- 新增 `SeckillReservationRepairJob`。
- 扫描 `PROCESSING` 且超过探测窗口的 guard。
- 如果 `bucket_shard_key` 已存在，用 `reservationId + bucket_shard_key` 查询：
  - snapshot
  - outbox `mq_message`
  - change_log
- 查询失败或分片不可达：保留 `PROCESSING`，打告警日志。
- snapshot + outbox 存在：guard CAS 到 `DEDUCTED`，确保 outbox 可被补偿发送。
- snapshot 存在但 outbox 不存在：不释放，补 outbox 或告警。
- 全部事实确认不存在且超过安全窗口：guard `PROCESSING -> FAILED`，释放 `active_key`。
- `bucket_shard_key` 为空且超过安全窗口：只在能确认没有进入扣减事务时释放。

### 文件

- `mall-seckill/src/main/java/com/mall/seckill/service/impl/SeckillReservationRepairJob.java`
- `mall-seckill/src/main/java/com/mall/seckill/mapper/ReservationGuardRepository.java`
- `mall-seckill/src/main/java/com/mall/seckill/mapper/SeckillRepository.java`
- `mall-message/src/main/java/com/mall/message/ReliableMessageRepository.java`

### 验证

- `PROCESSING + bucket_shard_key + snapshot/outbox` 存在时推进为 `DEDUCTED`。
- `snapshot` 存在但 `outbox` 不存在时不释放。
- 分片查询异常时不释放。
- 超过安全窗口且所有事实确认不存在时才释放。

## 阶段 7：测试矩阵

### 单元测试

- `ReservationGuardRepositoryTest`
  - requestId 幂等。
  - active_key 全局唯一。
  - CAS 状态推进。
  - CONFIRMED 后继续占用。
- `SeckillDeductionCoordinatorTest`
  - 扣减前 attach bucket。
  - 已有 requestId 返回原结果。
  - active_key 冲突返回重复购买。
  - 未知事务状态保留 PROCESSING。
- `ReliableMessageRepositoryTest`
  - `NEW/FAILED -> DISPATCHING -> SENT`
  - return 和 confirm ack 竞态。
  - nack 和 send exception 写不同 error_type。
  - DISPATCHING 超时回 FAILED。
- `SeckillResultMessageListenerTest`
  - transient 写 retry 并 ack。
  - poison 进 DLQ。
  - 乱序 SUCCESS/FAILED。
- `SeckillRepositoryTest`
  - release 两段式 CAS。
  - 重复 release 不重复回补。
  - CREATE_FAILED 不释放 CONFIRMED。
- `SeckillReservationRepairJobTest`
  - snapshot/outbox/change_log 各种组合。
  - 查询异常不释放。

### 集成测试

- `stage3c-sharding` 下同一用户并发提交，强制路由到不同 bucket shard，最终只成功一个 guard。
- result consumer 与 repair 并发执行，guard/snapshot/bucket 库存不重复推进或回补。
- Rabbit return 先于 confirm ack，最终不变成 `SENT`。
- 主结果队列消费失败后不无限 requeue，retry 表和 retry queue 可观察。

### 回归命令

```powershell
rtk proxy mvn -pl mall-message -am test
rtk proxy mvn -pl mall-seckill -am test
rtk proxy mvn -pl mall-order -am test
rtk proxy mvn -pl mall-seckill,mall-order,mall-message -am test
```

## 推荐提交拆分

1. `sql: add seckill reservation guard and retry tables`
2. `feat(message): guard reliable dispatch with cas`
3. `feat(seckill): add reservation guard repository`
4. `feat(seckill): attach guard before bucket deduction`
5. `feat(seckill): make result release retryable and idempotent`
6. `feat(seckill): repair unknown reservation states`
7. `test(seckill): cover asset risk stopgap invariants`

## 完成标准

- 同一用户跨 bucket shard 并发不会重复扣减。
- 事务未知不会释放购买资格。
- `CONFIRMED` 后同一活动同一用户不能再次购买。
- Rabbit return/confirm 竞态不会把失败消息写成成功。
- 结果消费失败进入延迟重试和最大次数控制。
- 库存回补只发生一次。
- 长异常消息不会阻断状态推进。
- `rtk proxy mvn -pl mall-seckill,mall-order,mall-message -am test` 通过。
