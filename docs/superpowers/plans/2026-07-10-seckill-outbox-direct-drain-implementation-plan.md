# 秒杀订单 Outbox 直推与恢复实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将秒杀 `change_log.NEW` 到订单创建可靠消息的推进改为提交后分片直推、批量处理和一秒恢复兜底，使 10,000 请求突发后的用户终态 P99 小于 10 秒。

**架构：** 库存事务提交后发布分片事件，监听器只向有界 outbox 执行器发送合并信号。协调器按 `bucketShardKey` 串行化同分片 drain，并以批量 claim、批量读取和批量消息写入推进 `NEW -> OUTBOXED`；数据库 claim token、唯一消息键和恢复扫描保证信号丢失、进程崩溃与并发恢复时不丢单、不重单。

**技术栈：** Java 21、Spring Boot 3.4、Spring 事务事件与调度、MyBatis-Plus、Micrometer、OceanBase/MySQL、RabbitMQ。

---

## 文件结构

| 文件 | 职责 |
| --- | --- |
| `sql/migration-v14-seckill-outbox-direct-drain.sql` | 新增 claim 字段、分片扫描索引和秒杀消息去重约束，并在发现历史重复时终止迁移。 |
| `sql/schema.sql` | 新装环境的最终表定义，与 v14 一致。 |
| `mall-seckill/.../SeckillStockChangeLogEntity.java` | 映射 claim token 与 claim 时间。 |
| `mall-seckill/.../SeckillStockChangeLogMapper.java` | 分片批量 claim、按 token 回读、批量状态推进和超时释放。 |
| `mall-message/.../MqMessageMapper.java`、`ReliableMessageRepository.java`、`ReliableMessagePublisher.java` | 批量幂等保存秒杀建单消息并在提交后调度首次发送。 |
| `mall-seckill/.../event/SeckillDeductCommittedEvent.java` | 扣减事实提交后的轻量事件。 |
| `mall-seckill/.../SeckillBucketService.java` | 写入 DEDUCT change log 后发布事件。 |
| `mall-seckill/.../SeckillOrderOutboxCoordinator.java` | 分片 signal 合并、worker 生命周期及任务拒绝指标。 |
| `mall-seckill/.../SeckillOrderOutboxFromChangeLogService.java` | 单分片批量 drain 的事务实现与指标。 |
| `mall-seckill/.../SeckillOrderOutboxFromChangeLogJob.java` | 一秒恢复扫描，只 signal 不直接处理业务。 |
| `mall-seckill/.../SeckillOrderOutboxTaskConfiguration.java` | 专用有界执行器与恢复 scheduler。 |
| `mall-seckill/.../SeckillOrderCreateMessageCompensationJob.java` | 仅补偿 `seckill.order.create` 的 `mq_message.NEW/FAILED`。 |
| `mall-seckill/.../SeckillProperties.java`、`application*.yml` | Outbox 并发、批次、超时、恢复和消息重试配置。 |
| 对应 `src/test` 文件 | 覆盖提交后触发、信号合并、批量状态机、超时恢复、消息去重和配置绑定。 |

### 任务 1：先落地数据库契约与实体映射

**文件：**

- 创建：`sql/migration-v14-seckill-outbox-direct-drain.sql`
- 修改：`sql/schema.sql`
- 修改：`mall-seckill/src/main/java/com/mall/seckill/pojo/entity/SeckillStockChangeLogEntity.java`
- 创建：`mall-seckill/src/test/java/com/mall/seckill/mapper/SeckillOutboxDirectDrainMigrationSqlTest.java`

- [ ] **步骤 1：编写迁移脚本测试**

在 `SeckillOutboxDirectDrainMigrationSqlTest` 读取 `migration-v14-seckill-outbox-direct-drain.sql`，断言脚本包含以下不可缺少的 SQL 片段：

```java
assertThat(sql).contains("outbox_claim_token VARCHAR(36)");
assertThat(sql).contains("outbox_claimed_at DATETIME(3)");
assertThat(sql).contains("idx_change_log_shard_status_id");
assertThat(sql).contains("(bucket_shard_key, status, id)");
assertThat(sql).contains("uk_mq_message_bucket_route_business");
assertThat(sql).contains("SIGNAL SQLSTATE '45000'");
```

- [ ] **步骤 2：运行迁移脚本测试并确认失败**

运行：

```powershell
rtk ./mvnw -pl mall-seckill -Dtest=SeckillOutboxDirectDrainMigrationSqlTest test
```

预期：FAIL，原因是迁移脚本尚不存在。

- [ ] **步骤 3：实现可重复执行的 v14 迁移**

创建迁移脚本。先在 `mq_message` 中按 `bucket_shard_key, routing_key, business_key` 分组统计 `COUNT(*) > 1` 的 `seckill.order.create` 行；若存在则 `SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'duplicate seckill order create outbox messages must be resolved before migration'`，不得自动删除消息。

随后通过已有的 `add_column_if_missing` / `add_index_if_missing` 模式执行：

```sql
ALTER TABLE seckill_stock_change_log
  ADD COLUMN outbox_claim_token VARCHAR(36) NULL AFTER status;
ALTER TABLE seckill_stock_change_log
  ADD COLUMN outbox_claimed_at DATETIME(3) NULL AFTER outbox_claim_token;
ALTER TABLE seckill_stock_change_log
  ADD KEY idx_change_log_shard_status_id (bucket_shard_key, status, id);
ALTER TABLE mq_message
  ADD UNIQUE KEY uk_mq_message_bucket_route_business
    (bucket_shard_key, routing_key, business_key);
```

迁移过程必须在每个实际数据源执行；唯一键只保护 `bucket_shard_key` 非空的秒杀消息，保持其他 legacy 消息的既有 nullable 行为。

- [ ] **步骤 4：同步新装 schema 与实体**

在 `schema.sql` 的 `seckill_stock_change_log` 中加入：

```sql
outbox_claim_token VARCHAR(36),
outbox_claimed_at DATETIME(3),
KEY idx_change_log_shard_status_id (bucket_shard_key, status, id)
```

在 `mq_message` 中加入：

```sql
UNIQUE KEY uk_mq_message_bucket_route_business
  (bucket_shard_key, routing_key, business_key)
```

并在 `SeckillStockChangeLogEntity` 新增：

```java
private String outboxClaimToken;
private LocalDateTime outboxClaimedAt;
```

`MqMessageEntity` 不新增业务字段；唯一键由数据库维护，实体字段保持不变。

- [ ] **步骤 5：运行迁移测试并检查 diff**

运行：

```powershell
rtk ./mvnw -pl mall-seckill -Dtest=SeckillOutboxDirectDrainMigrationSqlTest test
rtk git diff --check
```

预期：PASS；无空白错误。

- [ ] **步骤 6：提交本任务**

```powershell
rtk git add sql/migration-v14-seckill-outbox-direct-drain.sql sql/schema.sql mall-seckill/src/main/java/com/mall/seckill/pojo/entity/SeckillStockChangeLogEntity.java mall-seckill/src/test/java/com/mall/seckill/mapper/SeckillOutboxDirectDrainMigrationSqlTest.java
rtk git commit -m "feat: add seckill outbox claim schema"
```

### 任务 2：实现批量分片 mapper 与批量可靠消息保存

**文件：**

- 修改：`mall-seckill/src/main/java/com/mall/seckill/mapper/SeckillStockChangeLogMapper.java`
- 修改：`mall-message/src/main/java/com/mall/message/MqMessageMapper.java`
- 修改：`mall-message/src/main/java/com/mall/message/ReliableMessageRepository.java`
- 修改：`mall-message/src/main/java/com/mall/message/ReliableMessagePublisher.java`
- 创建：`mall-message/src/test/java/com/mall/message/ReliableMessageBatchRepositoryTest.java`

- [ ] **步骤 1：编写批量消息 repository 的失败测试**

测试 `saveSeckillOrderCreateBatch` 将多条 `ReliableMessage` 交给 mapper 的批量幂等插入，并验证不调用逐条 `save`：

```java
repository.saveIgnoreDuplicates(List.of(message("r1", 1L), message("r2", 1L)));

verify(messageMapper).insertIgnoreBatch(captor.capture());
assertThat(captor.getValue()).hasSize(2);
assertThat(captor.getValue()).allSatisfy(entity -> {
    assertThat(entity.getStatus()).isEqualTo("NEW");
    assertThat(entity.getBucketShardKey()).isEqualTo(1L);
});
```

- [ ] **步骤 2：运行消息批量测试并确认失败**

```powershell
rtk ./mvnw -pl mall-message -Dtest=ReliableMessageBatchRepositoryTest test
```

预期：FAIL，`saveIgnoreDuplicates` 与 `insertIgnoreBatch` 不存在。

- [ ] **步骤 3：扩展 mapper 与 repository**

在 `MqMessageMapper` 加入 MyBatis `<script>` 批量插入，使用 MySQL/OceanBase 的 `INSERT IGNORE`，并写入与单条 `save` 相同的字段和 `NEW` 状态：

```java
int insertIgnoreBatch(@Param("entities") List<MqMessageEntity> entities);
```

在 `ReliableMessageRepository` 新增：

```java
public void saveIgnoreDuplicates(List<ReliableMessage> messages) {
    if (messages == null || messages.isEmpty()) {
        return;
    }
    messageMapper.insertIgnoreBatch(messages.stream().map(this::newEntity).toList());
}
```

将现有 `save` 提取为 `newEntity(ReliableMessage)`，保证单条与批量字段、时间和 `NEW` 语义一致。

- [ ] **步骤 4：提供批量 enqueue API**

在 `ReliableMessagePublisher` 新增只面向秒杀建单的 DTO 和方法：

```java
public record SeckillOrderCreateOutbox(String requestId, String payload, Long bucketShardKey) {}

public List<ReliableMessage> enqueueSeckillOrderCreateBatch(
        List<SeckillOrderCreateOutbox> outboxes) {
    List<ReliableMessage> messages = outboxes.stream()
            .map(item -> ReliableMessage.of(MessageNames.MALL_EXCHANGE,
                    MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY,
                    item.requestId(), item.payload(), item.bucketShardKey(), null))
            .toList();
    repository.saveIgnoreDuplicates(messages);
    messages.forEach(this::dispatchAfterCommit);
    return messages;
}
```

重复唯一键被忽略后仍调度发送是安全的：`markDispatching` 只接受 `NEW/FAILED`，已发送消息不会重复投递；遗留 `NEW/FAILED` 消息会被及时唤醒。

- [ ] **步骤 5：增加 change log mapper 的批量契约**

新增以下方法，所有更新都必须同时带 `bucket_shard_key`：

```java
int claimStatusByIdsAndShard(List<Long> ids, Long bucketShardKey,
        String claimToken, LocalDateTime claimedAt);

List<SeckillStockChangeLogEntity> selectClaimedByTokenAndShard(
        Long bucketShardKey, String claimToken);

int updateStatusByIdsAndClaimToken(List<Long> ids, Long bucketShardKey,
        String claimToken, String nextStatus);

int resetStaleOutboxingByShard(Long bucketShardKey, LocalDateTime before, int limit);
```

claim SQL 必须是 `status = 'NEW'` 的 CAS；`OUTBOXED` 更新必须是 `status = 'OUTBOXING' AND outbox_claim_token = ?`；reset 必须将状态设回 `NEW` 并清空 token 与 claim 时间。

- [ ] **步骤 6：运行模块测试**

```powershell
rtk ./mvnw -pl mall-message,mall-seckill -am -Dtest=ReliableMessageBatchRepositoryTest,ReliableMessagePublisherTest test
```

预期：PASS。

- [ ] **步骤 7：提交本任务**

```powershell
rtk git add mall-message/src/main/java/com/mall/message/MqMessageMapper.java mall-message/src/main/java/com/mall/message/ReliableMessageRepository.java mall-message/src/main/java/com/mall/message/ReliableMessagePublisher.java mall-message/src/test/java/com/mall/message/ReliableMessageBatchRepositoryTest.java mall-seckill/src/main/java/com/mall/seckill/mapper/SeckillStockChangeLogMapper.java
rtk git commit -m "feat: batch seckill outbox persistence"
```

### 任务 3：发布扣减提交事件并提供专用执行资源

**文件：**

- 创建：`mall-seckill/src/main/java/com/mall/seckill/service/event/SeckillDeductCommittedEvent.java`
- 创建：`mall-seckill/src/main/java/com/mall/seckill/service/impl/SeckillDeductCommittedListener.java`
- 创建：`mall-seckill/src/main/java/com/mall/seckill/config/SeckillOrderOutboxTaskConfiguration.java`
- 修改：`mall-seckill/src/main/java/com/mall/seckill/service/impl/SeckillBucketService.java`
- 修改：`mall-seckill/src/main/java/com/mall/seckill/config/SeckillProperties.java`
- 修改：`mall-seckill/src/test/java/com/mall/seckill/service/impl/SeckillBucketServiceTest.java`
- 创建：`mall-seckill/src/test/java/com/mall/seckill/service/impl/SeckillDeductCommittedListenerTest.java`

- [ ] **步骤 1：编写事件与 after-commit 监听器失败测试**

在 bucket service 测试中，扣减成功后验证事件已发布：

```java
verify(eventPublisher).publishEvent(new SeckillDeductCommittedEvent("r1", 3L));
```

在 listener 测试中直接调用监听器并验证它只 signal、不访问 mapper：

```java
listener.onDeductCommitted(new SeckillDeductCommittedEvent("r1", 3L));

verify(coordinator).signal(3L);
verifyNoMoreInteractions(coordinator);
```

- [ ] **步骤 2：运行事件测试并确认失败**

```powershell
rtk ./mvnw -pl mall-seckill -Dtest=SeckillBucketServiceTest,SeckillDeductCommittedListenerTest test
```

预期：FAIL，事件、listener 和构造器依赖不存在。

- [ ] **步骤 3：实现事件发布**

创建不可变事件：

```java
public record SeckillDeductCommittedEvent(String requestId, Long bucketShardKey) {}
```

将 `ApplicationEventPublisher` 作为 `SeckillBucketService` 的可选生产依赖注入；现有包可见测试构造器传 `null`。在 `afterDeducted(...)` 成功插入 change log 后调用：

```java
if (eventPublisher != null) {
    eventPublisher.publishEvent(new SeckillDeductCommittedEvent(requestId, selectedBucket.bucketShardKey()));
}
```

不得在库存扣减失败、change log 插入失败或 RELEASE 流水时发布该事件。

- [ ] **步骤 4：实现 after-commit listener 与任务配置**

listener 使用：

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onDeductCommitted(SeckillDeductCommittedEvent event) {
    coordinator.signal(event.bucketShardKey());
}
```

在 `SeckillOrderOutboxTaskConfiguration` 创建两个命名 bean：

```java
@Bean("seckillOrderOutboxExecutor")
ThreadPoolTaskExecutor seckillOrderOutboxExecutor(SeckillProperties properties)

@Bean("seckillOutboxRecoveryScheduler")
ThreadPoolTaskScheduler seckillOutboxRecoveryScheduler()
```

执行器从 `orderOutbox` 配置读取 core=4、max=8、queue=64，显式 `new ThreadPoolExecutor.AbortPolicy()`，线程名前缀 `seckill-outbox-`。scheduler 使用独立 2 线程池；不能修改全局默认 scheduler。

- [ ] **步骤 5：扩展配置对象与 YAML**

在 `OrderOutbox` 增加并提供完整 getter/setter：

```java
private int workerCorePoolSize = 4;
private int workerMaxPoolSize = 8;
private int workerQueueCapacity = 64;
private int maxBatchesPerRun = 5;
private long claimTimeoutSeconds = 5;
private long recoveryFixedDelay = 1000;
private long messageRetryFixedDelay = 1000;
private long messageDispatchTimeoutSeconds = 5;
```

在 `application.yml` 保留保守默认，在 `application-stage3c-sharding.yml` 显式写入上述运行态值和 `batch-size: 200`。

- [ ] **步骤 6：运行事件与配置测试**

```powershell
rtk ./mvnw -pl mall-seckill -Dtest=SeckillBucketServiceTest,SeckillDeductCommittedListenerTest,SeckillEntryAsyncPropertiesTest test
```

预期：PASS；现有入口行为不变。

- [ ] **步骤 7：提交本任务**

```powershell
rtk git add mall-seckill/src/main/java/com/mall/seckill/service/event/SeckillDeductCommittedEvent.java mall-seckill/src/main/java/com/mall/seckill/service/impl/SeckillDeductCommittedListener.java mall-seckill/src/main/java/com/mall/seckill/config/SeckillOrderOutboxTaskConfiguration.java mall-seckill/src/main/java/com/mall/seckill/service/impl/SeckillBucketService.java mall-seckill/src/main/java/com/mall/seckill/config/SeckillProperties.java mall-seckill/src/main/resources/application.yml mall-seckill/src/main/resources/application-stage3c-sharding.yml mall-seckill/src/test/java/com/mall/seckill/service/impl/SeckillBucketServiceTest.java mall-seckill/src/test/java/com/mall/seckill/service/impl/SeckillDeductCommittedListenerTest.java
rtk git commit -m "feat: signal outbox after seckill deduction commit"
```

### 任务 4：实现分片信号合并协调器与恢复 job

**文件：**

- 创建：`mall-seckill/src/main/java/com/mall/seckill/service/impl/SeckillOrderOutboxCoordinator.java`
- 修改：`mall-seckill/src/main/java/com/mall/seckill/service/impl/SeckillOrderOutboxFromChangeLogJob.java`
- 创建：`mall-seckill/src/test/java/com/mall/seckill/service/impl/SeckillOrderOutboxCoordinatorTest.java`
- 修改：`mall-seckill/src/test/java/com/mall/seckill/service/impl/SeckillOrderOutboxFromChangeLogServiceTest.java`

- [ ] **步骤 1：编写协调器失败测试**

使用可控 `TaskExecutor` 捕获任务，验证同一分片的两个 signal 仅提交一个 runnable：

```java
coordinator.signal(3L);
coordinator.signal(3L);

assertThat(executor.submitted()).hasSize(1);
executor.runNext();
verify(service).drainShard(3L, 5);
```

再加入“worker 执行期间 signal”的测试：首次 drain 返回 0，执行中再次 signal 后 worker 退出前必须再执行一次 drain，且不会遗失该 signal。加入拒绝 executor 的测试，断言不抛异常且 `seckill.outbox.signal.rejected` 计数器加一。

- [ ] **步骤 2：运行协调器测试并确认失败**

```powershell
rtk ./mvnw -pl mall-seckill -Dtest=SeckillOrderOutboxCoordinatorTest test
```

预期：FAIL，协调器不存在。

- [ ] **步骤 3：实现 `SeckillOrderOutboxCoordinator`**

实现 `ConcurrentHashMap<Long, ShardSlot>`，其中 `ShardSlot` 包含 `AtomicBoolean dirty` 与 `AtomicBoolean running`。核心逻辑为：

```java
public void signal(Long bucketShardKey) {
    if (!enabled() || bucketShardKey == null) return;
    ShardSlot slot = slots.computeIfAbsent(bucketShardKey, ignored -> new ShardSlot());
    slot.dirty.set(true);
    submitIfIdle(bucketShardKey, slot);
}

private void run(Long bucketShardKey, ShardSlot slot) {
    try {
        do {
            slot.dirty.set(false);
            service.drainShard(bucketShardKey, properties.getOrderOutbox().getMaxBatchesPerRun());
        } while (slot.dirty.get());
    } finally {
        slot.running.set(false);
        if (slot.dirty.get()) submitIfIdle(bucketShardKey, slot);
    }
}
```

`submitIfIdle` 必须以 CAS 设置 `running`，捕获 `TaskRejectedException` 后恢复 `running=false`、保留 `dirty=true` 并递增拒绝计数。不得用调用线程执行 drain。

- [ ] **步骤 4：将旧定时 job 改为恢复 signal**

保留类名和开关，替换 `service.drainOnce()`：

```java
@Scheduled(
    fixedDelayString = "${mall.seckill.order-outbox.recovery-fixed-delay:1000}",
    scheduler = "seckillOutboxRecoveryScheduler")
public void drain() {
    coordinator.recoverConfiguredShards();
}
```

`recoverConfiguredShards()` 必须先按每个 configured shard 重置超时 `OUTBOXING`，再调用 `signal(shard)`；它不允许直接执行 SQL drain。没有配置 shard key 时记录一次 WARN 并跳过，避免回退为全局跨库扫描。

- [ ] **步骤 5：运行协调器与旧 outbox 测试**

```powershell
rtk ./mvnw -pl mall-seckill -Dtest=SeckillOrderOutboxCoordinatorTest,SeckillOrderOutboxFromChangeLogServiceTest test
```

预期：PASS；旧测试中依赖 `drainOnce` 的断言将在下一任务替换为 `drainShard` 的分片断言。

- [ ] **步骤 6：提交本任务**

```powershell
rtk git add mall-seckill/src/main/java/com/mall/seckill/service/impl/SeckillOrderOutboxCoordinator.java mall-seckill/src/main/java/com/mall/seckill/service/impl/SeckillOrderOutboxFromChangeLogJob.java mall-seckill/src/test/java/com/mall/seckill/service/impl/SeckillOrderOutboxCoordinatorTest.java mall-seckill/src/test/java/com/mall/seckill/service/impl/SeckillOrderOutboxFromChangeLogServiceTest.java
rtk git commit -m "feat: coordinate direct seckill outbox drains"
```

### 任务 5：将 outbox service 改为分片批量状态机

**文件：**

- 修改：`mall-seckill/src/main/java/com/mall/seckill/service/impl/SeckillOrderOutboxFromChangeLogService.java`
- 修改：`mall-seckill/src/main/java/com/mall/seckill/mapper/SeckillRepository.java`
- 修改：`mall-seckill/src/main/java/com/mall/seckill/mapper/SeckillStockSnapshotMapper.java`
- 修改：`mall-seckill/src/main/java/com/mall/seckill/mapper/SeckillSkuMapper.java`
- 修改：`mall-seckill/src/test/java/com/mall/seckill/service/impl/SeckillOrderOutboxFromChangeLogServiceTest.java`

- [ ] **步骤 1：替换逐条行为测试为单分片批量测试**

在 outbox service 测试覆盖以下三个案例：

```java
@Test
void shouldClaimOnlySelectedShardAndBatchEnqueueDeducts() {
    when(changeLogMapper.selectByStatusForConsumeByShard(3L, "NEW", 200))
            .thenReturn(List.of(log(11L, "r1", "DEDUCT"), log(12L, "r2", "DEDUCT")));
    when(changeLogMapper.claimStatusByIdsAndShard(anyList(), eq(3L), anyString(), any()))
            .thenReturn(2);
    when(changeLogMapper.selectClaimedByTokenAndShard(eq(3L), anyString()))
            .thenReturn(List.of(log(11L, "r1", "DEDUCT"), log(12L, "r2", "DEDUCT")));

    assertThat(service.drainShard(3L, 1)).isEqualTo(2);

    verify(messagePublisher).enqueueSeckillOrderCreateBatch(anyList());
    verify(changeLogMapper).updateStatusByIdsAndClaimToken(
            List.of(11L, 12L), 3L, anyString(), "OUTBOXED");
}
```

另加：RELEASE 记录不进入 publisher 但转 `OUTBOXED`；snapshot 缺失导致事务回滚并保持可恢复 `OUTBOXING`；批量中同一 SKU 仅查询一次；旧 token 的更新影响 0 行时抛出并回滚。

- [ ] **步骤 2：运行 service 测试并确认失败**

```powershell
rtk ./mvnw -pl mall-seckill -Dtest=SeckillOrderOutboxFromChangeLogServiceTest test
```

预期：FAIL，`drainShard` 与批量依赖不存在。

- [ ] **步骤 3：提供批量读取接口**

在 `SeckillRepository` 新增按 request id 和 shard 返回 map 的方法：

```java
public Map<String, StockSnapshot> findStockSnapshots(Collection<String> requestIds,
                                                       Long bucketShardKey)
```

在 `SeckillStockSnapshotMapper` 使用 `request_id IN (...) AND bucket_shard_key = ?` 一次读取。按 activity 分组后，在 `SeckillSkuMapper` 增加 `selectByActivityIdAndSkuIds(activityId, skuIds)`，由 repository 返回 `Map<ActivitySkuKey, SeckillSku>`。遇到 snapshot 或 SKU 缺失视为可重试异常，不能把库存扣减直接标成失败。

- [ ] **步骤 4：实现 `drainShard`**

删除全局 `drainOnce()` 主路径，提供：

```java
public int drainShard(Long bucketShardKey, int maxBatches) {
    int total = 0;
    for (int index = 0; index < Math.max(1, maxBatches); index++) {
        int drained = drainOneBatch(bucketShardKey, batchSize());
        total += drained;
        if (drained < batchSize()) break;
    }
    return total;
}
```

`drainOneBatch` 依次选 `NEW`、生成 UUID token 批量 claim、按 token 回读，并在一个 `TransactionTemplate` 内：构建 `SeckillOrderCreateOutbox` 列表、调用 `enqueueSeckillOrderCreateBatch`、以 token 批量更新状态。DEDUCT 以外的记录不创建消息但仍按 token 进入 `OUTBOXED`。所有数据库读写都带当前 shard；不得调用 `selectByStatusForConsume`。

- [ ] **步骤 5：实现 claim 超时恢复**

新增：

```java
public int resetStaleOutboxing(Long bucketShardKey) {
    LocalDateTime before = LocalDateTime.now()
            .minusSeconds(properties.getOrderOutbox().getClaimTimeoutSeconds());
    return changeLogMapper.resetStaleOutboxingByShard(bucketShardKey, before, batchSize());
}
```

该方法只重置 `OUTBOXING` 且 `outbox_claimed_at < before` 的记录，并同时清空 token、claim 时间。恢复 job 在每个 shard signal 前调用它。

- [ ] **步骤 6：运行聚焦测试**

```powershell
rtk ./mvnw -pl mall-message,mall-seckill -am -Dtest=SeckillOrderOutboxFromChangeLogServiceTest,ReliableMessageBatchRepositoryTest,ReliableMessagePublisherTest test
```

预期：PASS；Mockito 验证中不再出现逐条 `existsByBusinessKeyAndRoutingKey` 或全局 `selectByStatusForConsume`。

- [ ] **步骤 7：提交本任务**

```powershell
rtk git add mall-seckill/src/main/java/com/mall/seckill/service/impl/SeckillOrderOutboxFromChangeLogService.java mall-seckill/src/main/java/com/mall/seckill/mapper/SeckillRepository.java mall-seckill/src/main/java/com/mall/seckill/mapper/SeckillStockSnapshotMapper.java mall-seckill/src/main/java/com/mall/seckill/mapper/SeckillSkuMapper.java mall-seckill/src/test/java/com/mall/seckill/service/impl/SeckillOrderOutboxFromChangeLogServiceTest.java
rtk git commit -m "feat: batch drain seckill outbox by shard"
```

### 任务 6：为秒杀建单消息提供一秒补偿

**文件：**

- 修改：`mall-message/src/main/java/com/mall/message/ReliableMessageRepository.java`
- 创建：`mall-seckill/src/main/java/com/mall/seckill/service/impl/SeckillOrderCreateMessageCompensationJob.java`
- 修改：`mall-seckill/src/main/java/com/mall/seckill/config/SeckillProperties.java`
- 修改：`mall-seckill/src/main/resources/application.yml`
- 修改：`mall-seckill/src/main/resources/application-stage3c-sharding.yml`
- 创建：`mall-seckill/src/test/java/com/mall/seckill/service/impl/SeckillOrderCreateMessageCompensationJobTest.java`

- [ ] **步骤 1：编写建单消息补偿失败测试**

配置 `enabled=true`、分片 `[1, 3]`、batch=40，断言每个 shard 先标记过期 `DISPATCHING`，再只查询 `seckill.order.create` 的 `NEW/FAILED`，并交给 publisher：

```java
verify(repository).markDispatchingTimedOut(
        any(Instant.class), eq(1L), eq(MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY), eq(40));
verify(repository).findNeedCompensation(
        eq(3L), eq(MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY), eq(40));
verify(publisher).resend(message);
```

- [ ] **步骤 2：运行补偿测试并确认失败**

```powershell
rtk ./mvnw -pl mall-seckill -Dtest=SeckillOrderCreateMessageCompensationJobTest test
```

预期：FAIL，路由键过滤 repository API 与 job 不存在。

- [ ] **步骤 3：增加路由键过滤 repository API**

新增以下重载，查询/更新条件必须包含 `routing_key`：

```java
List<ReliableMessage> findNeedCompensation(Long bucketShardKey, String routingKey, int limit);
int markDispatchingTimedOut(Instant timeoutBefore, Long bucketShardKey,
                             String routingKey, int limit);
```

保留现有通用 60 秒 `MessageCompensationJob`，不得改变其查询范围或频率。

- [ ] **步骤 4：实现专用补偿 job**

job 用独立 scheduler：

```java
@Scheduled(
    fixedDelayString = "${mall.seckill.order-outbox.message-retry-fixed-delay:1000}",
    scheduler = "seckillOutboxRecoveryScheduler")
public void compensate() {
    for (Long shardKey : properties.getBucket().getRouting().getBucketShardKeys()) {
        repository.markDispatchingTimedOut(before(), shardKey,
                MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY, batchSize());
        repository.findNeedCompensation(shardKey,
                MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY, batchSize())
                .forEach(publisher::resend);
    }
}
```

timeout 使用 `orderOutbox.messageDispatchTimeoutSeconds`，默认 5 秒；空 shard 配置只 WARN 并跳过，禁止全局跨库查询。

- [ ] **步骤 5：运行补偿和消息模块测试**

```powershell
rtk ./mvnw -pl mall-message,mall-seckill -am -Dtest=SeckillOrderCreateMessageCompensationJobTest,MessageCompensationJobTest,ReliableMessageRepositoryTest test
```

预期：PASS；通用补偿测试仍保持 60 秒默认语义。

- [ ] **步骤 6：提交本任务**

```powershell
rtk git add mall-message/src/main/java/com/mall/message/ReliableMessageRepository.java mall-seckill/src/main/java/com/mall/seckill/service/impl/SeckillOrderCreateMessageCompensationJob.java mall-seckill/src/main/java/com/mall/seckill/config/SeckillProperties.java mall-seckill/src/main/resources/application.yml mall-seckill/src/main/resources/application-stage3c-sharding.yml mall-seckill/src/test/java/com/mall/seckill/service/impl/SeckillOrderCreateMessageCompensationJobTest.java
rtk git commit -m "feat: retry seckill order create messages quickly"
```

### 任务 7：加入延迟、backlog 与拒绝观测

**文件：**

- 修改：`mall-seckill/src/main/java/com/mall/seckill/service/impl/SeckillOrderOutboxCoordinator.java`
- 修改：`mall-seckill/src/main/java/com/mall/seckill/service/impl/SeckillOrderOutboxFromChangeLogService.java`
- 修改：`mall-seckill/src/main/java/com/mall/seckill/mapper/SeckillStockChangeLogMapper.java`
- 创建：`mall-seckill/src/test/java/com/mall/seckill/service/impl/SeckillOrderOutboxMetricsTest.java`

- [ ] **步骤 1：编写指标失败测试**

使用 `SimpleMeterRegistry`，验证任务拒绝后：

```java
assertThat(registry.counter("seckill.outbox.signal.rejected").count()).isEqualTo(1.0);
```

并在 service drain 成功后验证：

```java
assertThat(registry.find("seckill.outbox.batch.duration").timer()).isNotNull();
assertThat(registry.find("seckill.outbox.batch.records").summary()).isNotNull();
```

- [ ] **步骤 2：运行指标测试并确认失败**

```powershell
rtk ./mvnw -pl mall-seckill -Dtest=SeckillOrderOutboxMetricsTest test
```

预期：FAIL，指标未注册。

- [ ] **步骤 3：实现指标与 backlog 查询**

在 coordinator 增加 counters：`seckill.outbox.signal.accepted`、`seckill.outbox.signal.coalesced`、`seckill.outbox.signal.rejected`。在 service 增加 `seckill.outbox.batch.duration` timer、`seckill.outbox.batch.records` distribution summary、`seckill.outbox.batch.failure` counter。

在 mapper 增加按 shard、status 聚合 count 和最早 `created_at` 的查询；恢复 job 每轮更新 gauges：

```text
seckill.outbox.backlog{shard,status}
seckill.outbox.oldest.age.seconds{shard,status}
```

记录 `change_log.created_at -> mq_message.created_at`：创建消息时以 change log 的 `createdAt` 计算并写入 `seckill.outbox.create.lag` timer。不得在 HTTP 提交链路查询额外数据。

- [ ] **步骤 4：运行指标和回归测试**

```powershell
rtk ./mvnw -pl mall-seckill -Dtest=SeckillOrderOutboxMetricsTest,SeckillOrderOutboxCoordinatorTest,SeckillOrderOutboxFromChangeLogServiceTest test
```

预期：PASS。

- [ ] **步骤 5：提交本任务**

```powershell
rtk git add mall-seckill/src/main/java/com/mall/seckill/service/impl/SeckillOrderOutboxCoordinator.java mall-seckill/src/main/java/com/mall/seckill/service/impl/SeckillOrderOutboxFromChangeLogService.java mall-seckill/src/main/java/com/mall/seckill/mapper/SeckillStockChangeLogMapper.java mall-seckill/src/test/java/com/mall/seckill/service/impl/SeckillOrderOutboxMetricsTest.java
rtk git commit -m "feat: observe seckill outbox lag and backlog"
```

### 任务 8：集成回归、压测与文档闭环

**文件：**

- 修改：`docs/seckill-current-architecture-flow.md`
- 修改：`docs/seckill-load-test-guide.md`
- 创建：`docs/status/2026-07-10-seckill-outbox-direct-drain-verification.md`

- [ ] **步骤 1：运行完整模块测试**

```powershell
rtk ./mvnw -pl mall-message,mall-order,mall-seckill -am test
```

预期：PASS。若因本工作区已有用户改动失败，先记录与本计划无关的失败文件，不修改或回滚用户改动。

- [ ] **步骤 2：构建可运行包**

```powershell
rtk ./mvnw -pl mall-message,mall-order,mall-seckill -am -DskipTests package
```

预期：PASS。

- [ ] **步骤 3：运行 stage3c 10,000 请求压测**

使用仓库当前可用的 stage3c reset/submit 脚本；若脚本路径已由用户改动，使用 `docs/seckill-load-test-guide.md` 指定的命令。记录入口开始/结束、`DEDUCT` 落库、`mq_message(seckill.order.create)` 创建、`order_info` 创建、`seckill_result` 闭合时间。

验收断言：

```text
change_log -> mq_message：P99 <= 2 秒
mq_message -> order_info：P99 <= 2 秒
order_info -> seckill_result：P99 <= 2 秒
用户终态：P99 < 10 秒
RabbitMQ：ready=0，unacked=0
change_log：无遗留 NEW / OUTBOXING / OUTBOX_FAILED
重复订单数：0
```

- [ ] **步骤 4：更新架构与验证记录**

在 `docs/seckill-current-architecture-flow.md` 替换“全局定时 outbox worker”表述，明确：`AFTER_COMMIT -> coordinator.signal -> shard worker -> mq_message -> after-commit RabbitMQ`，一秒恢复扫描只做兜底。将配置、指标名、压测实际结果和未达标项写入验证记录。

- [ ] **步骤 5：提交文档与验证记录**

```powershell
rtk git add docs/seckill-current-architecture-flow.md docs/seckill-load-test-guide.md docs/status/2026-07-10-seckill-outbox-direct-drain-verification.md
rtk git commit -m "docs: verify direct seckill outbox drain"
```

---

## 自检

**规格覆盖度：** 任务 1 覆盖 schema、索引、唯一键与 claim 持久化；任务 2 覆盖批量 SQL 与可靠消息批量写入；任务 3–4 覆盖 after-commit 信号、普通有界线程池和一秒恢复；任务 5 覆盖分片批量状态机；任务 6 覆盖 RabbitMQ 首次发送失败的快速补偿；任务 7 覆盖 lag/backlog 指标；任务 8 覆盖端到端压测验收。

**占位符扫描：** 每个任务包含精确文件、方法、测试命令、预期结果和提交范围；不存在未定义占位符或泛化处理指令。

**类型一致性：** 全程统一使用 `SeckillDeductCommittedEvent`、`SeckillOrderOutboxCoordinator`、`drainShard`、`outboxClaimToken`、`outboxClaimedAt`、`enqueueSeckillOrderCreateBatch` 与 `SeckillOrderCreateMessageCompensationJob`。
