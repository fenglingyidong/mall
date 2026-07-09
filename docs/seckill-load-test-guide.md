# 秒杀压测指南

本文是秒杀压测 runbook，目标是让后续压测可重复、可对比、可解释。当前 stage3c 脚本覆盖“秒杀提交入口”，真正“全链路”需要额外补 JMeter 提交后轮询结果。

## 安全边界

- 只在本地或压测环境执行。
- 不要在生产环境执行 reset 脚本。
- reset 脚本会清理订单、消息、秒杀结果、预约 guard、结果重试、快照、分桶库存、分桶配置、缓存和 RabbitMQ 队列。
- 每轮压测前记录代码版本、本地变更、reset 参数、JMeter 参数、JTL 路径。

## 指标口径

### 提交入口口径

现有脚本：

```text
target/loadtest/run-stage3c-submit-async.ps1
```

测这个接口：

```http
POST /api/seckill/{activityId}/{skuId}
```

它只衡量“秒杀服务受理请求”的吞吐和延迟，不等待订单创建完成，也不等待结果回传。

常看指标：

- `QPS`：JMeter 总样本数除以样本窗口耗时。
- `successQps`：JMeter `success=true` 样本数除以样本窗口耗时。
- `P95/P99`：提交接口响应延迟。
- `HTTP 500`：系统错误，不能忽略。
- `200,false`：业务断言失败。若是 `Stock not enough`，在售罄场景可视为预期。

### 全链路口径

真正全链路应包含：

1. 提交：`POST /api/seckill/{activityId}/{skuId}`
2. 提取响应里的 `data.requestId`
3. 轮询：`GET /api/seckill/result/{requestId}`
4. 等待结果变为 `SUCCESS` 或 `FAILED`
5. 用 JMeter `Transaction Controller` 包住提交和轮询

当前仓库 stage3c JMX 还不是全链路 JMX。不要把提交入口 P95 当成全链路 P95。

在全链路 JMX 完成前，先用“提交入口 QPS/P95 + 异步闭合状态”观察：

- `seckill_result.PROCESSING` 是否下降到 0。
- `seckill_stock_snapshot.DEDUCTED` 是否下降到 0。
- 秒杀库 `mq_message.NEW/DISPATCHING/FAILED` 是否清空。
- 订单库 `mq_message.NEW/DISPATCHING/FAILED` 是否清空。
- RabbitMQ 秒杀建单队列、结果队列是否清空。

## 环境准备

确认依赖容器：

```powershell
rtk docker ps
```

至少应有：

- `mall-oceanbase-ce`
- `mall-oceanbase-ce-shard1`
- `mall-mysql`
- `mall-tairstring`
- `mall-rabbitmq`
- `mall-nacos`
- `mall-sentinel`
- `mall-seata`

确认 JMeter：

```powershell
rtk proxy powershell -NoProfile -Command "Test-Path 'C:\Java\apache-jmeter-5.6.3\bin\jmeter.bat'"
```

确认 MySQL `mq_message` 有 `error_type` 列：

```powershell
rtk proxy powershell -NoProfile -Command "docker exec -e MYSQL_PWD=root mall-mysql mysql --default-character-set=utf8mb4 -uroot mall -N -e 'SHOW COLUMNS FROM mq_message LIKE ''error_type'''"
```

若没有，先应用迁移：

```powershell
rtk proxy powershell -NoProfile -Command "Get-Content -Raw -Encoding UTF8 'sql/migration-v12-seckill-asset-risk-stopgap.sql' | rtk proxy docker exec -i -e MYSQL_PWD=root mall-mysql mysql --default-character-set=utf8mb4 -uroot mall"
```

## 构建和启动

打包当前工作区：

```powershell
rtk mvn -pl mall-order,mall-seckill -am -DskipTests package
```

启动 stage3c 服务：

```powershell
rtk proxy powershell -NoProfile -ExecutionPolicy Bypass -File target\loadtest\start-stage3c-current.ps1
```

确认端口：

```powershell
rtk proxy powershell -NoProfile -Command "Get-NetTCPConnection -LocalPort 8104,8105 -State Listen -ErrorAction SilentlyContinue | Select-Object LocalAddress,LocalPort,OwningProcess"
```

确认 `mall-seckill` 健康：

```powershell
rtk proxy powershell -NoProfile -Command "Invoke-WebRequest -UseBasicParsing 'http://localhost:8105/actuator/health' -TimeoutSec 5 | Select-Object StatusCode,Content"
```

若启动失败，先看日志：

```powershell
rtk proxy rg -n "Started |APPLICATION FAILED|Exception|No default constructor|Unknown column|Tomcat started|Port .* in use" target/loadtest/stage3c-current
```

## 重置库存

标准 reset：

```powershell
rtk proxy powershell -NoProfile -ExecutionPolicy Bypass -File target\loadtest\reset-stage3c-async.ps1 `
  -ActivityId 1 `
  -SkuId 1001 `
  -Stock 100000 `
  -BucketCount 16
```

reset 会做：

- 清 RabbitMQ 秒杀、结果、关单相关队列。
- 清 MySQL 订单、订单可靠消息、消费记录。
- 清 OceanBase 秒杀结果、预约 guard、结果重试、快照、分桶库存、分桶配置、秒杀可靠消息。
- 重建 `seckill_bucket_config`。
- 按 `BucketCount` 均分库存到 bucket。
- 删除 TairString 库存缓存 key。
- reset 后确认 `seckill_result`、`seckill_reservation_guard`、`seckill_result_retry`、`seckill_stock_snapshot`、`mq_message` 和订单表基线为 0。

## 库存设置

库存取决于本轮目标。

### 测提交 QPS/P95

不要让库存耗尽污染指标：

```text
Stock >= 预估QPS * Duration秒数 * 1.2
```

示例：

- 80 threads，60s，预估 500 QPS：设 `Stock=50000`。
- 80 threads，120s，预估 500 QPS：设 `Stock=100000`。
- 探索上限且不想售罄：设 `Stock=200000`。

### 测售罄尾段

让库存小于请求量：

```text
Stock < 预估QPS * Duration秒数
```

示例：

- 80 threads，120s：设 `Stock=20000`。

这时大量 `Stock not enough` 是预期业务结果。重点看 HTTP 500、锁等待、队列积压和尾延迟。

### 测全链路闭合

库存不要过大，否则订单和关单队列会堆太多：

- 起步：`Stock=20000`
- 稳定后：`Stock=50000`
- 若 `mall.order.close.queue` 明显堆积，先不要继续加库存或加并发。

## 跑提交入口压测

标准压测：

```powershell
rtk proxy powershell -NoProfile -ExecutionPolicy Bypass -File target\loadtest\run-stage3c-submit-async.ps1 `
  -Threads 80 `
  -Ramp 10 `
  -Duration 120 `
  -JMeterPath "C:\Java\apache-jmeter-5.6.3\bin\jmeter.bat"
```

快速 smoke：

```powershell
rtk proxy powershell -NoProfile -ExecutionPolicy Bypass -File target\loadtest\reset-stage3c-async.ps1 -ActivityId 1 -SkuId 1001 -Stock 10000 -BucketCount 16

rtk proxy powershell -NoProfile -ExecutionPolicy Bypass -File target\loadtest\run-stage3c-submit-async.ps1 `
  -Threads 20 `
  -Ramp 5 `
  -Duration 30 `
  -JMeterPath "C:\Java\apache-jmeter-5.6.3\bin\jmeter.bat"
```

脚本会输出最新 JTL 路径，并写入：

```text
target/loadtest/stage3c-submit-async-opt-latest-jtl.txt
```

## 计算 QPS 和 P95

优先用已有 Python 汇总脚本：

```powershell
rtk proxy powershell -NoProfile -Command "python target/loadtest/summarize_jmeter_jtl.py (Get-Content target\loadtest\stage3c-submit-async-opt-latest-jtl.txt)"
```

输出包含：

- `samples`
- `success`
- `failure`
- `responseCodes`
- `durationSeconds`
- `qps`
- `successQps`
- `failureCategories`
- `avgMs`
- `p50Ms`
- `p95Ms`
- `p99Ms`

`failureCategories` 用于区分系统错误和业务失败：

- `executorRejected`：可靠消息 after-commit 派发线程池拒绝任务，不能冒泡成 HTTP 500。
- `stockNotEnough`：售罄场景预期业务失败；入口 QPS/P95 场景说明库存太低。
- `duplicatePurchase`：同一用户重复购买；若 reset 后仍大量出现，需要检查 JMeter 用户生成。
- `lockWaitTimeout`、`snapshotUpdateFailed`：数据库竞争或状态机竞争，不能忽略。

看 10 秒窗口波动：

```powershell
rtk proxy powershell -NoProfile -ExecutionPolicy Bypass -File target\loadtest\analyze-stage3c-jtl.ps1 -Path (Get-Content target\loadtest\stage3c-submit-async-opt-latest-jtl.txt)
```

窗口脚本会输出每个时间窗的 count、errors、avg、P95、P99、max。

## 错误分析

看 HTTP 和 JMeter 断言分布：

```powershell
rtk proxy powershell -NoProfile -Command "Import-Csv (Get-Content target\loadtest\stage3c-submit-async-opt-latest-jtl.txt) | Group-Object responseCode,success | Sort-Object Count -Descending | Select-Object Count,Name"
```

看失败样例：

```powershell
rtk proxy powershell -NoProfile -Command "Import-Csv (Get-Content target\loadtest\stage3c-submit-async-opt-latest-jtl.txt) | Where-Object success -ne 'true' | Select-Object -First 10 timeStamp,elapsed,responseCode,success,failureMessage | ConvertTo-Json -Depth 4"
```

检查关键错误：

```powershell
rtk proxy powershell -NoProfile -Command "Select-String -LiteralPath (Get-Content target\loadtest\stage3c-submit-async-opt-latest-jtl.txt) -Pattern 'Lock wait timeout' | Measure-Object | Select-Object Count"

rtk proxy powershell -NoProfile -Command "Select-String -LiteralPath (Get-Content target\loadtest\stage3c-submit-async-opt-latest-jtl.txt) -Pattern 'removeSurvivorBucket' | Measure-Object | Select-Object Count"

rtk proxy powershell -NoProfile -Command "Select-String -LiteralPath (Get-Content target\loadtest\stage3c-submit-async-opt-latest-jtl.txt) -Pattern 'markEmptyIfNoSaleableByShard' | Measure-Object | Select-Object Count"

rtk proxy powershell -NoProfile -Command "Select-String -LiteralPath (Get-Content target\loadtest\stage3c-submit-async-opt-latest-jtl.txt) -Pattern 'Seckill bucket snapshot update failed' | Measure-Object | Select-Object Count"

rtk proxy powershell -NoProfile -Command "Select-String -LiteralPath (Get-Content target\loadtest\stage3c-submit-async-opt-latest-jtl.txt) -Pattern 'ExecutorService in active state did not accept task' | Measure-Object | Select-Object Count"
```

判读：

- `Lock wait timeout > 0`：数据库锁等待，查热点 SQL。
- `removeSurvivorBucket > 0`：仍有 survivor 直接写进入热路径。
- `markEmptyIfNoSaleableByShard > 0`：桶标空仍在压测窗口造成锁等待。
- `Seckill bucket snapshot update failed > 0`：快照状态更新存在竞争。
- `ExecutorService in active state did not accept task > 0`：可靠消息 after-commit 派发线程池饱和，不能让它冒泡成 HTTP 500。
- `Stock not enough`：若本轮目标是售罄尾段，属于预期；若目标是提交 QPS/P95，说明库存太低。

## 异步闭合检查

RabbitMQ：

```powershell
rtk proxy docker exec mall-rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers
```

秒杀主库：

```powershell
rtk proxy powershell -NoProfile -Command "docker exec mall-oceanbase-ce obclient -h 127.0.0.1 -P 2881 -u root@test -D mall -e 'SELECT status, COUNT(*) FROM seckill_result GROUP BY status; SELECT status, COUNT(*) FROM seckill_stock_snapshot GROUP BY status; SELECT status, COUNT(*) FROM mq_message GROUP BY status;'"
```

秒杀分片库：

```powershell
rtk proxy powershell -NoProfile -Command "docker exec mall-oceanbase-ce-shard1 obclient -h 127.0.0.1 -P 2881 -u root@test -D test -e 'SELECT status, COUNT(*) FROM seckill_stock_snapshot GROUP BY status; SELECT status, COUNT(*) FROM mq_message GROUP BY status;'"
```

订单库：

```powershell
rtk proxy powershell -NoProfile -Command "docker exec -e MYSQL_PWD=root mall-mysql mysql --default-character-set=utf8mb4 -uroot mall -e 'SELECT status, COUNT(*) FROM order_info GROUP BY status; SELECT COUNT(*) AS seckill_order_count FROM seckill_order; SELECT status, COUNT(*) FROM mq_message GROUP BY status;'"
```

闭合标准：

- 提交入口压测：允许异步短暂滞后，但不能长期堆积。
- 全链路压测：等待 drain 后，`PROCESSING`、`DEDUCTED`、`mq_message.NEW/DISPATCHING/FAILED` 应接近 0。
- 如果 `mall.order.close.queue` 持续堆积，不要把尾段 P95 直接归因到秒杀提交入口。

## 推荐场景

### smoke

- `Stock=10000`
- `Threads=20`
- `Ramp=5`
- `Duration=30`
- 目标：确认服务、脚本、数据库、MQ 可用。

### 提交入口 QPS/P95

- `Stock=100000` 或 `200000`
- `Threads=80`
- `Ramp=10`
- `Duration=60` 或 `120`
- 目标：避免售罄，观察入口吞吐和延迟。

### 售罄尾段

- `Stock=20000`
- `Threads=80`
- `Ramp=10`
- `Duration=120`
- 目标：观察库存耗尽后的业务失败、HTTP 500、锁等待、队列积压。

### 全链路闭合

- `Stock=20000` 起步
- `Threads=50` 到 `80`
- `Ramp=10`
- `Duration=60`
- 目标：观察订单创建、结果回传、快照确认、MQ drain。

## 结果记录

每轮至少记录：

- 代码版本或本地变更摘要。
- reset 参数：`ActivityId`、`SkuId`、`Stock`、`BucketCount`。
- JMeter 参数：`Threads`、`Ramp`、`Duration`。
- JTL 路径。
- 总样本、成功样本、失败样本。
- QPS、successQps、P95、P99。
- HTTP 500 数量和 top error。
- RabbitMQ 队列快照。
- 秒杀库、订单库状态快照。
- 是否售罄，售罄是否是本轮预期。

可写入：

```text
target/loadtest/stage3c-current/stage3c-run-summary.md
```

## 清理服务

如果服务由 `target/loadtest/start-stage3c-current.ps1` 启动，优先用 pid 文件停止：

```powershell
rtk proxy powershell -NoProfile -Command "Get-Content target\loadtest\stage3c-current\mall-order.pid,target\loadtest\stage3c-current\mall-seckill.pid | ForEach-Object { Stop-Process -Id ([int]$_) -Force -ErrorAction SilentlyContinue }"
```

确认端口释放：

```powershell
rtk proxy powershell -NoProfile -Command "Get-NetTCPConnection -LocalPort 8104,8105 -State Listen -ErrorAction SilentlyContinue"
```

## 常见问题

### 库存耗尽导致大量失败

现象：

- `200,false` 很多。
- 失败消息包含 `Stock not enough`。

处理：

- 若目标是入口 QPS/P95，提高 `Stock`。
- 若目标是售罄尾段，保留结果，但不要把这些业务失败算作系统错误。

### HTTP 500 来自可靠消息派发线程池

现象：

```text
ExecutorService in active state did not accept task: com.mall.message.ReliableMessagePublisher...
```

处理方向：

- 不让 after-commit dispatch executor 拒绝任务冒泡成提交接口 500。
- 补可靠消息派发背压策略，或调大线程池和队列容量。
- 同时观察 `mq_message.NEW` 是否长期堆积。

### 关单队列堆积

现象：

- `mall.order.close.queue` ready 很高。
- `order_info.CREATED` 长时间不下降。

处理方向：

- 增加关单消费者能力，或压测提交链路时隔离关单影响。
- 全链路压测时必须记录该队列，否则尾延迟判断会失真。

### stage3c 启动失败

先看日志：

```powershell
rtk proxy rg -n "APPLICATION FAILED|Exception|Unknown column|No default constructor|Port .* in use" target/loadtest/stage3c-current
```

常见原因：

- 端口 `8104/8105` 被旧进程占用。
- 数据库迁移未应用。
- 当前 jar 不是最新工作区代码。
