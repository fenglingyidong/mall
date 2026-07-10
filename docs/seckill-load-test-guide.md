# 秒杀入口异步化压测指南

本文用于验证 `stage3c-sharding` 下的秒杀入口异步化实现。压测结论必须同时看提交入口指标和异步闭合状态，不能只看 QPS 或 P95。

当前仓库不再依赖 `target/loadtest/*.ps1`。`target/` 会被 `mvn clean` 清理，因此压测脚本或 JMX 若要长期使用，应放到可跟踪目录，例如 `scripts/loadtest/stage3c/`。

## 1. 安全边界

- 只允许在本地或专用压测环境执行 reset。
- 禁止在生产库执行本文的清表、重建库存或清 MQ 命令。
- 每轮记录代码版本、工作区改动、库存参数、并发参数、JTL 路径、数据库闭合结果。
- 压测只是运行态验证，不能替代单元测试、集成测试和代码审查。

## 2. 当前正确性口径

入口链路当前行为：

1. `POST /api/seckill/{activityId}/{skuId}` 返回 `PROCESSING`、`FAILED` 或已有结果。
2. HTTP submit 不再同步写 `seckill_reservation_guard`。
3. HTTP submit 不再同步创建 `seckill.order.create` outbox。
4. submit 只登记 `seckill_stock_snapshot.REGISTERED`，扣业务桶库存，并写 `seckill_stock_change_log.DEDUCT/NEW`。
5. `SeckillOrderOutboxFromChangeLogJob` 将 `change_log.NEW` 推进到 `OUTBOXING`、`OUTBOXED`，并生成 `seckill.order.create`。
6. `mall-order` 异步建单并回传 `seckill.order.result`。
7. `SeckillResultMessageListener` 将 snapshot/result 闭合到 `CONFIRMED`、`RELEASED`、`FAILED` 等终态。
8. `SeckillSnapshotFactRepairJob` 兜底修复没有扣减事实的 stale `REGISTERED`。

压测正确性必须满足：

- HTTP 500 为 0。
- 非售罄场景下，`Stock not enough` 不应大量出现。
- 非重复购买场景下，`Duplicate purchase` 不应大量出现。
- 等待 drain 后，`seckill_result.PROCESSING` 应接近 0。
- 等待 drain 后，`seckill_stock_snapshot.REGISTERED` 应接近 0。
- `seckill_stock_change_log.NEW`、`OUTBOXING`、`LEDGER_PROCESSING` 不应长期堆积。
- `mq_message.NEW`、`DISPATCHING`、`FAILED` 不应长期堆积。
- `seckill_order` 数量应等于最终 `seckill_result.SUCCESS` 数量。
- 最终成功数不能超过初始库存。

`DEDUCTED` 是旧同步链路的重要中间状态。异步入口验证不能再只盯 `DEDUCTED = 0`，必须检查 `REGISTERED` 和 `seckill_stock_change_log`。

## 3. 请求头要求

直接压 `mall-seckill` 即可：

```text
POST http://localhost:8105/api/seckill/{activityId}/{skuId}
GET  http://localhost:8105/api/seckill/result/{requestId}
```

必须在压测请求里设置：

```text
X-Request-Id: 每个提交唯一
X-User-Id: 每个预期成功用户唯一
```

原因：

- `X-Request-Id` 用于请求幂等。重复 requestId 会被当成同一请求。
- `X-User-Id` 来自 `UserContextFilter`。不传时默认用户是 `1`，大量请求会被判定为同一用户重复购买。
- 做售罄尾段压测时也建议使用唯一用户，避免把重复购买误判为库存耗尽。

JMeter 可用 CSV Data Set Config 预生成 `requestId,userId`，或使用全局 counter / UUID 函数生成。关键是同一轮中 `requestId` 唯一，且非重复购买场景下 `userId` 唯一。

## 4. 环境准备

确认当前分支和工作区：

```powershell
rtk git status --short --branch
rtk git log --oneline -5
```

启动依赖容器：

```powershell
rtk docker compose --profile stage3c-sharding up -d mysql tairstring rabbitmq oceanbase oceanbase-shard1 nacos sentinel seata
```

确认容器：

```powershell
rtk docker ps
```

至少应看到：

- `mall-mysql`
- `mall-tairstring`
- `mall-rabbitmq`
- `mall-oceanbase-ce`
- `mall-oceanbase-ce-shard1`
- `mall-nacos`
- `mall-sentinel`
- `mall-seata`

确认 JMeter：

```powershell
rtk proxy powershell -NoProfile -Command "Test-Path 'C:\Java\apache-jmeter-5.6.3\bin\jmeter.bat'"
```

确认 RabbitMQ 宿主机端口。当前 compose 可能把容器 `5672` 映射到非默认宿主机端口，例如本地曾是 `35672`：

```powershell
rtk proxy powershell -NoProfile -Command "docker inspect mall-rabbitmq --format '{{json .NetworkSettings.Ports}}'"
rtk proxy powershell -NoProfile -Command "Test-NetConnection localhost -Port 5672 | Select-Object TcpTestSucceeded,RemotePort; Test-NetConnection localhost -Port 35672 | Select-Object TcpTestSucceeded,RemotePort"
```

如果 `5672` 不通、`35672` 通，启动 `mall-order` 和 `mall-seckill` 时必须加：

```text
--spring.rabbitmq.port=35672
```

不要假设应用默认的 `localhost:5672` 一定可用。

## 5. 数据库迁移

新库优先使用当前 `sql/schema.sql`。旧库必须按当前分支补齐迁移，尤其是：

- `sql/migration-v10-seckill-stage3c-sharded-outbox.sql`
- `sql/migration-v11-seckill-reservation-order-source.sql`
- `sql/migration-v12-seckill-asset-risk-stopgap.sql`
- `sql/migration-v13-seckill-entry-async.sql`

`migration-v13-seckill-entry-async.sql` 是本分支必须项，补了异步入口需要的索引。

在 OceanBase 主分片执行 v13：

```powershell
rtk proxy powershell -NoProfile -Command "Get-Content -Raw -Encoding UTF8 'sql/migration-v13-seckill-entry-async.sql' | docker exec -i mall-oceanbase-ce obclient -h 127.0.0.1 -P 2881 -u root@test -D mall"
```

在 OceanBase shard1 执行 v13：

```powershell
rtk proxy powershell -NoProfile -Command "Get-Content -Raw -Encoding UTF8 'sql/migration-v13-seckill-entry-async.sql' | docker exec -i mall-oceanbase-ce-shard1 obclient -h 127.0.0.1 -P 2881 -u root@test -D test"
```

订单侧 MySQL 至少确认 `mq_message.error_type` 存在：

```powershell
rtk proxy powershell -NoProfile -Command "docker exec -e MYSQL_PWD=root mall-mysql mysql --default-character-set=utf8mb4 -uroot mall -N -e 'SHOW COLUMNS FROM mq_message LIKE ''error_type'''"
```

如果不存在，先补 `migration-v12-seckill-asset-risk-stopgap.sql` 到 MySQL。

## 6. 构建和启动

打包当前工作区：

```powershell
rtk mvn -pl mall-order,mall-seckill -am -DskipTests package
```

准备日志目录：

```powershell
rtk proxy powershell -NoProfile -Command "New-Item -ItemType Directory -Force target\loadtest\stage3c-current | Out-Null"
```

启动 `mall-order`：

```powershell
rtk proxy powershell -NoProfile -Command "Start-Process java -WindowStyle Hidden -PassThru -RedirectStandardOutput target\loadtest\stage3c-current\mall-order.log -RedirectStandardError target\loadtest\stage3c-current\mall-order.err -ArgumentList '-jar','mall-order\target\mall-order-0.0.1-SNAPSHOT.jar' | Select-Object Id,ProcessName"
```

启动 `mall-seckill`，必须启用 `stage3c-sharding`：

```powershell
rtk proxy powershell -NoProfile -Command "Start-Process java -WindowStyle Hidden -PassThru -RedirectStandardOutput target\loadtest\stage3c-current\mall-seckill.log -RedirectStandardError target\loadtest\stage3c-current\mall-seckill.err -ArgumentList '-jar','mall-seckill\target\mall-seckill-0.0.1-SNAPSHOT.jar','--spring.profiles.active=stage3c-sharding' | Select-Object Id,ProcessName"
```

如果 RabbitMQ 宿主机端口不是 `5672`，把上面两条启动命令的 `-ArgumentList` 都加上实际端口，例如：

```text
'--spring.rabbitmq.port=35672'
```

本地压测可以额外关闭注册中心和 Sentinel 依赖，避免非核心依赖影响压测启动：

```text
'--spring.cloud.nacos.discovery.enabled=false','--spring.cloud.nacos.config.enabled=false','--spring.cloud.sentinel.enabled=false'
```

`Start-Process` 在某些 Windows/工具环境里可能返回超时，但 Java 进程已经启动。遇到命令超时时，不要立刻重复启动；先检查端口和进程：

```powershell
rtk proxy powershell -NoProfile -Command "Get-NetTCPConnection -LocalPort 8104,8105 -State Listen -ErrorAction SilentlyContinue | Select-Object LocalAddress,LocalPort,OwningProcess"
rtk proxy powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { `$_.ProcessName -like 'java*' -and (`$_.CommandLine -like '*mall-seckill*target*jar*' -or `$_.CommandLine -like '*mall-order*target*jar*') } | Select-Object ProcessId,CommandLine"
```

如果重复启动了多组 jar，先停止再重启，否则端口、消费者数量和压测结果都会失真。

确认端口：

```powershell
rtk proxy powershell -NoProfile -Command "Get-NetTCPConnection -LocalPort 8104,8105 -State Listen -ErrorAction SilentlyContinue | Select-Object LocalAddress,LocalPort,OwningProcess"
```

确认健康：

```powershell
rtk proxy powershell -NoProfile -Command "Invoke-WebRequest -UseBasicParsing 'http://localhost:8105/actuator/health' -TimeoutSec 5 | Select-Object StatusCode,Content"
```

若启动失败：

```powershell
rtk proxy rg -n "Started |APPLICATION FAILED|Exception|Unknown column|No default constructor|Port .* in use|Tomcat started" target/loadtest/stage3c-current
```

## 7. Reset 口径

推荐把 reset 固化为脚本并提交到 `scripts/loadtest/stage3c/`。在脚本补齐前，可以选择两种方式。

### 7.1 本地全量重建

只适合本地演示环境：

```powershell
rtk docker compose --profile stage3c-sharding down -v
rtk docker compose --profile stage3c-sharding up -d mysql tairstring rabbitmq oceanbase oceanbase-shard1 nacos sentinel seata
```

该命令会删除 compose volume。随后导入 schema、seed 和迁移。该方式成本高，但最干净。

### 7.2 手工 reset 检查项

如果不重建库，reset 必须至少清理：

- OceanBase 两个物理库：`seckill_result`、`seckill_reservation_guard`、`seckill_result_retry`、`seckill_stock_snapshot`、`seckill_stock_change_log`、`seckill_stock_bucket`、`seckill_bucket_config`、秒杀侧 `mq_message`。
- MySQL：`seckill_order`、订单相关 `order_info` / `order_item`、订单侧 `mq_message`、`consume_record`。
- RabbitMQ：秒杀建单、结果、关单相关队列。
- TairString / Redis：秒杀库存缓存和 entry guard key。

reset 后必须重建：

- `seckill_sku.stock`
- `seckill_bucket_config`
- `seckill_stock_bucket` 中一个 `CENTER` 桶和多个 `BUCKET` 业务桶
- `survivor_buckets`

标准参数：

```text
ActivityId=1
SkuId=1001
BucketCount=16
Stock=按场景设置
```

`BucketCount=16` 时，业务桶 `bucket_no` 建议为 `1..16`，`shard_key` 与 `bucket_no` 对齐，`survivor_buckets` 为 `1,2,...,16`。库存均分到业务桶；尾数可以放到低号桶。

`CENTER` 桶不能初始化为 `0`。中心账本会对 `DEDUCT` 事实应用负向 delta，如果 `CENTER.saleable_quantity=0`，会持续报：

```text
Seckill center bucket ledger apply failed
```

正确做法是让 `CENTER.saleable_quantity` 和 `CENTER.setting_quantity` 等于本轮初始 `Stock`，业务桶也按同一个 `Stock` 均分。这样 `DEDUCT/OUTBOXED` 才能继续推进到 `APPLIED`。

reset 还必须清 TairString / Redis 的压测 key。只清数据库不清缓存会残留 `seckill:entry:*` request/buyer key 或库存缓存，下一轮会出现大量业务 `FAILED`、`Duplicate purchase` 或误判售罄：

```powershell
rtk proxy docker exec mall-tairstring redis-cli -p 6379 --scan --pattern '*seckill*'
rtk proxy docker exec mall-tairstring redis-cli -p 6379 FLUSHDB
```

`FLUSHDB` 只允许在本地或专用压测库执行。共享环境应改成按前缀精确删除 `seckill:*` key。

reset 完成后检查基线：

```powershell
rtk proxy powershell -NoProfile -Command "docker exec mall-oceanbase-ce obclient -h 127.0.0.1 -P 2881 -u root@test -D mall -e 'SELECT status, COUNT(*) FROM seckill_result GROUP BY status; SELECT status, COUNT(*) FROM seckill_stock_snapshot GROUP BY status; SELECT status, COUNT(*) FROM seckill_stock_change_log GROUP BY status; SELECT status, COUNT(*) FROM mq_message GROUP BY status;'"
```

```powershell
rtk proxy powershell -NoProfile -Command "docker exec mall-oceanbase-ce-shard1 obclient -h 127.0.0.1 -P 2881 -u root@test -D test -e 'SELECT status, COUNT(*) FROM seckill_stock_snapshot GROUP BY status; SELECT status, COUNT(*) FROM seckill_stock_change_log GROUP BY status; SELECT status, COUNT(*) FROM mq_message GROUP BY status;'"
```

```powershell
rtk proxy powershell -NoProfile -Command "docker exec -e MYSQL_PWD=root mall-mysql mysql --default-character-set=utf8mb4 -uroot mall -e 'SELECT COUNT(*) AS seckill_order_count FROM seckill_order; SELECT status, COUNT(*) FROM mq_message GROUP BY status; SELECT COUNT(*) AS consume_record_count FROM consume_record;'"
```

预期：结果、快照、change_log、mq_message、订单基线为 0；bucket 表已按本轮库存重建。

## 8. Smoke

先用单请求确认链路：

```powershell
rtk proxy powershell -NoProfile -Command "`$rid='smoke-'+[guid]::NewGuid().ToString(); `$headers=@{'X-Request-Id'=`$rid;'X-User-Id'='9000001'}; Invoke-RestMethod -Method Post -Uri 'http://localhost:8105/api/seckill/1/1001' -Headers `$headers; Start-Sleep -Seconds 2; Invoke-RestMethod -Uri ('http://localhost:8105/api/seckill/result/' + `$rid)"
```

预期：

- submit 返回 `PROCESSING` 或最终态。
- result 最终变为 `SUCCESS` 或 `FAILED`。
- 日志没有 `Exception`、`Unknown column`、`multi data nodes`、`UPDATE ... LIMIT` 分片错误。

查日志：

```powershell
rtk proxy rg -n "Exception|ERROR|Unknown column|multi data nodes|UPDATE .* LIMIT|Lock wait timeout|APPLICATION FAILED" target/loadtest/stage3c-current
```

## 9. JMeter 计划要求

当前仓库没有可复用的 stage3c JMX。新建 JMX 时必须满足：

- Thread Group 参数化：`threads`、`ramp`、`duration`。
- HTTP Request：`POST ${baseUrl}/api/seckill/${activityId}/${skuId}`。
- Header Manager：
  - `X-Request-Id=${requestId}`
  - `X-User-Id=${userId}`
- CSV Data Set Config 或函数生成唯一 `requestId`、`userId`。
- Response Assertion：
  - HTTP code 不是 `500`。
  - JSON `code` 为成功业务码。
  - 非售罄场景下，`data.status` 不应大量为 `FAILED`。

提交入口 JMX 只衡量 submit 受理吞吐和延迟，不代表全链路 P95。

全链路 JMX 需要额外做：

1. 提交。
2. 提取 `data.requestId`。
3. 轮询 `GET /api/seckill/result/{requestId}`。
4. 等待 `SUCCESS`、`FAILED` 或超时。
5. 用 Transaction Controller 包住提交和轮询。

## 10. 推荐压测场景

### 10.1 Smoke

```text
Stock=10000
BucketCount=16
Threads=20
Ramp=5
Duration=30
```

目标：确认服务、数据库、MQ、异步闭合可用。

### 10.2 提交入口 QPS/P95

```text
Stock=100000 或 200000
BucketCount=16
Threads=80
Ramp=10
Duration=60 或 120
```

目标：避免售罄，观察 submit 吞吐和延迟。若 `Stock not enough` 明显出现，说明库存不够或 reset 不干净。

### 10.3 售罄尾段

```text
Stock=20000
BucketCount=16
Threads=80
Ramp=10
Duration=120
```

目标：观察库存耗尽后是否稳定。大量 `Stock not enough` 是预期业务结果，但 HTTP 500、锁等待和 MQ 长期堆积不是预期。

### 10.4 异步闭合

```text
Stock=20000 起步
BucketCount=16
Threads=50 到 80
Ramp=10
Duration=60
```

目标：观察订单创建、结果回传、snapshot 闭合、change_log 推进和 MQ drain。

## 11. JTL 分析

如果 JTL 路径为 `target/loadtest/current/submit.jtl`：

```powershell
rtk proxy powershell -NoProfile -Command "Import-Csv 'target/loadtest/current/submit.jtl' | Group-Object responseCode,success | Sort-Object Count -Descending | Select-Object Count,Name"
```

失败样例：

```powershell
rtk proxy powershell -NoProfile -Command "Import-Csv 'target/loadtest/current/submit.jtl' | Where-Object success -ne 'true' | Select-Object -First 20 timeStamp,elapsed,responseCode,success,failureMessage | ConvertTo-Json -Depth 4"
```

关键错误计数：

```powershell
rtk proxy powershell -NoProfile -Command "Select-String -LiteralPath 'target/loadtest/current/submit.jtl' -Pattern 'Lock wait timeout','Stock not enough','Duplicate purchase','ExecutorService in active state did not accept task','Seckill bucket snapshot update failed' | Group-Object Pattern | Select-Object Count,Name"
```

判读：

- `Stock not enough`：售罄场景可接受；入口 QPS/P95 场景不可接受。
- `Duplicate purchase`：若不是重复购买测试，通常是 `X-User-Id` 生成错误或 reset 不干净。
- `Lock wait timeout`：数据库竞争，需要查热点 SQL 和 bucket 状态。
- `ExecutorService in active state did not accept task`：可靠消息派发背压问题，不能冒泡成 HTTP 500。
- `Seckill bucket snapshot update failed`：snapshot 状态机竞争，需要看是否影响最终闭合。

## 12. 异步闭合检查

RabbitMQ：

```powershell
rtk proxy docker exec mall-rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers
```

OceanBase 主分片：

```powershell
rtk proxy powershell -NoProfile -Command "docker exec mall-oceanbase-ce obclient -h 127.0.0.1 -P 2881 -u root@test -D mall -e 'SELECT status, COUNT(*) FROM seckill_result GROUP BY status; SELECT status, COUNT(*) FROM seckill_stock_snapshot GROUP BY status; SELECT status, COUNT(*) FROM seckill_stock_change_log GROUP BY status; SELECT status, COUNT(*) FROM mq_message GROUP BY status;'"
```

OceanBase shard1：

```powershell
rtk proxy powershell -NoProfile -Command "docker exec mall-oceanbase-ce-shard1 obclient -h 127.0.0.1 -P 2881 -u root@test -D test -e 'SELECT status, COUNT(*) FROM seckill_stock_snapshot GROUP BY status; SELECT status, COUNT(*) FROM seckill_stock_change_log GROUP BY status; SELECT status, COUNT(*) FROM mq_message GROUP BY status;'"
```

MySQL 订单侧：

```powershell
rtk proxy powershell -NoProfile -Command "docker exec -e MYSQL_PWD=root mall-mysql mysql --default-character-set=utf8mb4 -uroot mall -e 'SELECT status, COUNT(*) FROM order_info GROUP BY status; SELECT COUNT(*) AS seckill_order_count FROM seckill_order; SELECT status, COUNT(*) FROM mq_message GROUP BY status;'"
```

数量一致性：

```powershell
rtk proxy powershell -NoProfile -Command "docker exec mall-oceanbase-ce obclient -h 127.0.0.1 -P 2881 -u root@test -D mall -e 'SELECT status, COUNT(*) FROM seckill_result GROUP BY status; SELECT status, COUNT(*) FROM seckill_stock_snapshot GROUP BY status; SELECT status, COUNT(*) FROM seckill_stock_change_log GROUP BY status;'"
```

正确结果：

- `seckill_result.SUCCESS` 等于 MySQL `seckill_order` 数量。
- `seckill_result.SUCCESS` 小于等于本轮初始库存。
- `seckill_stock_snapshot.REGISTERED` drain 后接近 0。
- `seckill_stock_change_log.NEW`、`OUTBOXING`、`LEDGER_PROCESSING` drain 后接近 0。
- `mq_message.NEW`、`DISPATCHING`、`FAILED` drain 后接近 0。
- RabbitMQ ready/unacked 能回落。

## 13. 运行态故障排查

服务日志：

```powershell
rtk proxy rg -n "ERROR|Exception|Unknown column|multi data nodes|UPDATE .* LIMIT|Lock wait timeout|APPLICATION FAILED" target/loadtest/stage3c-current
```

端口占用：

```powershell
rtk proxy powershell -NoProfile -Command "Get-NetTCPConnection -LocalPort 8104,8105 -State Listen -ErrorAction SilentlyContinue | Select-Object LocalAddress,LocalPort,OwningProcess"
```

Java 进程：

```powershell
rtk proxy powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { `$_.ProcessName -like 'java*' -and (`$_.CommandLine -like '*mall-seckill*target*jar*' -or `$_.CommandLine -like '*mall-order*target*jar*') } | Select-Object ProcessId,CommandLine"
```

常见问题：

- 大量 `Duplicate purchase`：检查 `X-User-Id` 是否唯一，或 reset 是否清理了 snapshot / result / order。
- 大量 `Stock not enough`：如果不是售罄场景，增大库存或检查 bucket 初始化。
- `Unknown column`：迁移未执行到当前分支。
- `UPDATE ... LIMIT can not support route to multiple data nodes`：分片路由缺失，重点查 reliable-message compensation 和 `bucket_shard_key`。
- RabbitMQ 队列不下降：订单消费者、结果消费者或 reliable-message compensation 未正常工作。
- `Seckill center bucket ledger apply failed`：优先检查 reset 是否把 `CENTER` 桶库存初始化为本轮 `Stock`。`CENTER=0` 会导致 `OUTBOXED` 无法推进到 `APPLIED`。
- 提交返回大量 `FAILED` 但 HTTP 500 为 0：先查 TairString 是否残留 `seckill:entry:*` 或库存缓存 key；数据库 reset 干净不代表缓存 reset 干净。
- `Start-Process` 命令超时：先查 `8104/8105` 端口和 Java 进程。超时不等于启动失败，重复启动可能制造多组消费者。
- `mall-order` / `mall-seckill` 连不上 RabbitMQ：检查 Docker 实际端口映射。当前本地曾是宿主机 `35672 -> container 5672`，需要用 `--spring.rabbitmq.port=35672` 覆盖应用默认值。
- 使用 PowerShell Job 或临时脚本压测：结果只能作为轻量 smoke/功能验证，不要和 JMeter 的 QPS/P95 直接比较。正式性能结论仍以 JMeter JTL 为准。

## 14. 本次压测踩坑清单

本轮实际遇到并修正过的问题：

- RabbitMQ 容器对外端口不是默认 `5672`，而是宿主机 `35672`。未覆盖端口会导致服务连接错误或消息链路不可用。
- `Start-Process` 在工具调用里超时，但端口已经监听。重复执行启动命令会拉起多组 Java 进程，压测前必须查进程并清理。
- 历史 `seckill_stock_bucket` 脏数据导致 `selectOne()` 返回 2 行，日志出现 `TooManyResultsException`。压测前必须清 bucket 表并重建唯一基线。
- 手工 reset 时把 `CENTER` 桶库存设为 `0`，导致 `Seckill center bucket ledger apply failed`，`DEDUCT/OUTBOXED` 无法推进到 `APPLIED`。正确值是 `CENTER.saleable_quantity=Stock`。
- 只清数据库没有清 TairString，残留 `seckill:entry:req:*` 和 `seckill:entry:buyer:*`，下一轮出现大量业务 `FAILED`。reset 必须同时清缓存。
- 业务闭合需要等待异步 drain。刚压完时看到 `REGISTERED`、`NEW` 积压不一定是失败，要持续观察是否下降；长期不下降才判失败。
- `mall.order.close.delay.queue` 有延迟关单消息是预期现象，不应把 delay queue 的 ready 数直接当作秒杀入口未闭合。
- 临时 PowerShell 压测脚本有 Job 启停开销，`wallSeconds` 可能大于设定 `DurationSeconds`。该方式适合 smoke，不适合作为正式吞吐基准。

## 15. 停止服务

优先按 Java 进程命令行过滤停止本轮 jar：

```powershell
rtk proxy powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { `$_.ProcessName -like 'java*' -and (`$_.CommandLine -like '*mall-seckill*target*jar*' -or `$_.CommandLine -like '*mall-order*target*jar*') } | ForEach-Object { Stop-Process -Id `$_.ProcessId -Force }"
```

确认端口释放：

```powershell
rtk proxy powershell -NoProfile -Command "Get-NetTCPConnection -LocalPort 8104,8105 -State Listen -ErrorAction SilentlyContinue"
```

## 16. 结果记录模板

每轮至少记录：

```text
branch:
head:
dirty files:
activityId:
skuId:
stock:
bucketCount:
threads:
ramp:
duration:
jtl:
samples:
successQps:
p95:
p99:
http500:
stockNotEnough:
duplicatePurchase:
rabbitmq before drain:
rabbitmq after drain:
seckill_result status:
seckill_stock_snapshot status:
seckill_stock_change_log status:
seckill mq_message status:
order mq_message status:
seckill_order_count:
conclusion:
```

结论只能写：

- `通过`：提交无系统错误，异步状态可 drain，数量一致。
- `不通过`：存在 HTTP 500、状态长期不闭合、数量不一致、超卖、队列不可 drain。
- `仅入口通过`：submit 指标正常，但未完成全链路闭合验证。
