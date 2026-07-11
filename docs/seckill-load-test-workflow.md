# 秒杀入口当前可执行压测工作流

本文只保留当前本地开发环境已经实际验证过的压测步骤，目标是让 `stage3c-sharding` 下的秒杀入口压测可以直接执行。

补充背景、完整口径和历史问题排查见 [seckill-load-test-guide.md](./seckill-load-test-guide.md)。

## 1. 适用范围

- 只适用于本地或专用压测环境。
- 压测目标是 `POST /api/seckill/{activityId}/{skuId}` 提交入口。
- 当前入口返回 `PROCESSING`、`FAILED` 或已有结果，例如 `SUCCESS`。
- 入口压测结论只能说明 submit 侧表现；是否真正通过，还要看异步闭合是否 drain。

## 2. 固定前提

当前本地环境按下面口径执行：

- 服务：
  - `mall-order` 监听 `8104`
  - `mall-seckill` 监听 `8105`
- RabbitMQ 宿主机端口：
  - `35672`
- JMeter：
  - `C:\Java\apache-jmeter-5.6.3\bin\jmeter.bat`
- 当前默认压测活动：
  - `activityId=1`
  - `skuId=1001`
- 当前 reset 脚本：
  - `scripts/loadtest/stage3c/reset-seckill-loadtest.ps1`

JMeter 计划文件已经按当前入口口径调整完成：

- `docs/jmeter/seckill-submit-duration.jmx`
- `docs/jmeter/seckill-submit.jmx`

这两个 JMX 已固定：

- `HTTPSampler.use_keepalive=true`
- `HTTPSampler.implementation=HttpClient4`
- `ThreadGroup.same_user_on_next_iteration=true`
- 断言接受 `PROCESSING`、`FAILED`、`SUCCESS`

## 3. 一轮压测的完整流程

### 3.1 启动依赖容器

```powershell
rtk docker compose --profile stage3c-sharding up -d mysql tairstring rabbitmq oceanbase oceanbase-shard1 nacos sentinel seata
```

确认 RabbitMQ 端口映射：

```powershell
rtk proxy powershell -NoProfile -Command "docker inspect mall-rabbitmq --format '{{json .NetworkSettings.Ports}}'"
```

预期本地能看到宿主机 `35672 -> container 5672`。

### 3.2 执行 reset

先预演：

```powershell
rtk proxy powershell -NoProfile -ExecutionPolicy Bypass -File 'scripts/loadtest/stage3c/reset-seckill-loadtest.ps1' -DryRun -Stock 100000 -BucketCount 16
```

确认无误后执行真实 reset：

```powershell
rtk proxy powershell -NoProfile -ExecutionPolicy Bypass -File 'scripts/loadtest/stage3c/reset-seckill-loadtest.ps1' -ConfirmDestructive -Stock 100000 -BucketCount 16
```

脚本默认是安全模式：

- Redis 只删 `*seckill*` 相关 key，不做 `FLUSHDB`
- RabbitMQ 只 purge 秒杀和关单相关白名单队列
- MySQL 只清 `source='SECKILL'` 的订单及相关消息

### 3.3 构建服务

```powershell
rtk mvn -pl mall-order,mall-seckill -am -DskipTests package
```

准备日志目录：

```powershell
rtk proxy powershell -NoProfile -Command "New-Item -ItemType Directory -Force target\loadtest\stage3c-current | Out-Null"
```

### 3.4 启动 `mall-order` 和 `mall-seckill`

启动 `mall-order`：

```powershell
rtk proxy powershell -NoProfile -Command "Start-Process java -WindowStyle Hidden -PassThru -RedirectStandardOutput 'target\loadtest\stage3c-current\mall-order.log' -RedirectStandardError 'target\loadtest\stage3c-current\mall-order.err' -ArgumentList '-jar','mall-order\target\mall-order-0.0.1-SNAPSHOT.jar','--spring.rabbitmq.port=35672','--spring.cloud.nacos.discovery.enabled=false','--spring.cloud.nacos.config.enabled=false','--spring.cloud.sentinel.enabled=false' | Select-Object Id,ProcessName"
```

启动 `mall-seckill`：

```powershell
rtk proxy powershell -NoProfile -Command "Start-Process java -WindowStyle Hidden -PassThru -RedirectStandardOutput 'target\loadtest\stage3c-current\mall-seckill.log' -RedirectStandardError 'target\loadtest\stage3c-current\mall-seckill.err' -ArgumentList '-jar','mall-seckill\target\mall-seckill-0.0.1-SNAPSHOT.jar','--spring.profiles.active=stage3c-sharding','--spring.rabbitmq.port=35672','--spring.cloud.nacos.discovery.enabled=false','--spring.cloud.nacos.config.enabled=false','--spring.cloud.sentinel.enabled=false' | Select-Object Id,ProcessName"
```

说明：

- `Start-Process` 在 Windows 下可能超时，但 Java 进程已经启动。
- 不要因为超时就重复启动；先查端口和进程。

检查端口和进程：

```powershell
rtk proxy powershell -NoProfile -Command "Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { `$_.LocalPort -in 8104,8105 } | Select-Object LocalAddress,LocalPort,OwningProcess"
```

```powershell
rtk proxy powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { `$_.ProcessName -like 'java*' -and (`$_.CommandLine -like '*mall-seckill*target*jar*' -or `$_.CommandLine -like '*mall-order*target*jar*') } | Select-Object ProcessId,CommandLine"
```

如果重复启动出多组进程，只保留真正监听 `8104` / `8105` 的一组。

### 3.5 健康检查和 smoke

检查健康：

```powershell
rtk proxy powershell -NoProfile -Command "Invoke-WebRequest -UseBasicParsing 'http://localhost:8104/actuator/health' -TimeoutSec 5 | Select-Object StatusCode,Content"
```

```powershell
rtk proxy powershell -NoProfile -Command "Invoke-WebRequest -UseBasicParsing 'http://localhost:8105/actuator/health' -TimeoutSec 5 | Select-Object StatusCode,Content"
```

做一次提交 smoke：

```powershell
rtk proxy powershell -NoProfile -Command "`$rid='smoke-'+[guid]::NewGuid().ToString(); `$headers=@{'X-Request-Id'=`$rid;'X-User-Id'='9000001'}; Invoke-RestMethod -Method Post -Uri 'http://localhost:8105/api/seckill/1/1001' -Headers `$headers | ConvertTo-Json -Depth 6"
```

预期：

- `code=0`
- `data.status` 为 `PROCESSING`、`FAILED` 或 `SUCCESS`
- 不能出现 HTTP 500

### 3.6 采集 Actuator before 快照

先准备本轮指标产物目录：

```powershell
@'
$ts = Get-Date -Format 'yyyyMMdd-HHmmss'
$metrics = "target\loadtest\stage3c-current\metrics-$ts"
New-Item -ItemType Directory -Force $metrics | Out-Null
Set-Content -Encoding UTF8 -Path 'target\loadtest\stage3c-current\last-metrics-dir.txt' -Value $metrics
Write-Output "METRICS=$metrics"
'@ | rtk proxy powershell -NoProfile -
```

抓压测前快照：

```powershell
rtk proxy powershell -NoProfile -ExecutionPolicy Bypass -File "scripts/loadtest/stage3c/collect-seckill-metrics.ps1" -Mode Snapshot -BaseUrl "http://localhost:8105" -OutputDir (Get-Content -Raw -Encoding UTF8 "target\loadtest\stage3c-current\last-metrics-dir.txt").Trim() -Label before
```

预期：

- `target\loadtest\stage3c-current\metrics-<timestamp>\before\summary.json` 成功生成
- 默认 7 个入口指标都存在

### 3.7 执行 JMeter 入口压测并轮询指标

当前推荐先跑 `40` 线程、`60s`，确认客户端和入口稳定：

先启动指标轮询任务：

```powershell
@'
$scriptPath = (Resolve-Path 'scripts/loadtest/stage3c/collect-seckill-metrics.ps1').Path
$metrics = (Get-Content -Raw -Encoding UTF8 'target\loadtest\stage3c-current\last-metrics-dir.txt').Trim()
$job = Start-Job -ScriptBlock {
    param($path, $dir)
    powershell -NoProfile -ExecutionPolicy Bypass -File $path -Mode Watch -BaseUrl 'http://localhost:8105' -OutputDir $dir -IntervalSeconds 2 -DurationSeconds 300
} -ArgumentList $scriptPath, $metrics
Set-Content -Encoding UTF8 -Path 'target\loadtest\stage3c-current\last-metrics-job.txt' -Value $job.Id
Write-Output "METRICS_JOB=$($job.Id)"
'@ | rtk proxy powershell -NoProfile -
```

再执行 JMeter：

```powershell
@'
$ts = Get-Date -Format 'yyyyMMdd-HHmmss'
$jtl = "target\loadtest\stage3c-current\submit-$ts.jtl"
$report = "target\loadtest\stage3c-current\report-$ts"
& 'C:\Java\apache-jmeter-5.6.3\bin\jmeter.bat' -n -t 'docs\jmeter\seckill-submit-duration.jmx' -l $jtl -e -o $report '-Jhost=127.0.0.1' '-Jport=8105' '-JactivityId=1' '-JskuId=1001' '-Jthreads=40' '-Jramp=10' '-JdurationSeconds=60' '-JuserIdStart=4000000'
Write-Output "JTL=$jtl"
Write-Output "REPORT=$report"
'@ | rtk proxy powershell -NoProfile -
```

JMeter 结束后，先停轮询任务，再抓 after 快照：

```powershell
@'
$jobId = [int](Get-Content -Raw -Encoding UTF8 'target\loadtest\stage3c-current\last-metrics-job.txt')
Stop-Job -Id $jobId -ErrorAction SilentlyContinue
Receive-Job -Id $jobId -Keep | Out-String | Set-Content -Encoding UTF8 'target\loadtest\stage3c-current\last-metrics-job-output.txt'
Remove-Job -Id $jobId -Force -ErrorAction SilentlyContinue
'@ | rtk proxy powershell -NoProfile -
```

```powershell
rtk proxy powershell -NoProfile -ExecutionPolicy Bypass -File "scripts/loadtest/stage3c/collect-seckill-metrics.ps1" -Mode Snapshot -BaseUrl "http://localhost:8105" -OutputDir (Get-Content -Raw -Encoding UTF8 "target\loadtest\stage3c-current\last-metrics-dir.txt").Trim() -Label after
```

说明：

- `host` 建议显式用 `127.0.0.1`，不要用裸 `localhost`
- `X-Request-Id` 和 `X-User-Id` 由 JMX 内部自动生成唯一值
- 如果未来要冲更高并发，再逐步调到 `80` 线程
- `Watch` 默认按 `2` 秒间隔轮询，`DurationSeconds=300` 覆盖当前 `60` 秒压测和收尾，长压测时要同步调大
- `after` 快照会自动生成整轮 `summary.json`

### 3.8 轻量回归压测 `50 x 20`

当目标是快速确认当前代码改动有没有把秒杀入口或异步闭合打坏，而不是追高吞吐时，推荐先跑一轮固定请求数的轻量回归：

- `50` 线程
- 每线程 `20` 次
- 总请求数 `1000`

这轮压测建议直接复用 `3.2` 到 `3.5` 的 reset、重启和 smoke 流程，然后执行下面这组命令。

先做一次 smoke：

```powershell
@'
$rid = 'smoke-' + [guid]::NewGuid().ToString()
$headers = @{ 'X-Request-Id' = $rid; 'X-User-Id' = '9000001' }
Invoke-RestMethod -Method Post -Uri 'http://localhost:8105/api/seckill/1/1001' -Headers $headers | ConvertTo-Json -Depth 6
'@ | rtk proxy powershell -NoProfile -
```

预期：

- `code=0`
- `data.status` 为 `PROCESSING`、`FAILED` 或 `SUCCESS`
- 不能出现 HTTP 500

再执行 JMeter 循环压测：

```powershell
@'
$ts = Get-Date -Format 'yyyyMMdd-HHmmss'
$jtl = "target\loadtest\stage3c-current\submit-loops-$ts.jtl"
$report = "target\loadtest\stage3c-current\report-loops-$ts"
& 'C:\Java\apache-jmeter-5.6.3\bin\jmeter.bat' -n -t 'docs\jmeter\seckill-submit.jmx' -l $jtl -e -o $report '-Jhost=127.0.0.1' '-Jport=8105' '-JactivityId=1' '-JskuId=1001' '-Jthreads=50' '-Jloops=20' '-Jramp=10' '-JuserIdStart=5000000'
Write-Output "JTL=$jtl"
Write-Output "REPORT=$report"
'@ | rtk proxy powershell -NoProfile -
```

这轮不强制要求开启 Actuator 轮询；它的目标是用最少操作先判断：

- 入口是否还能稳定返回 `200`
- JMeter 客户端是否还有连接复用问题
- RabbitMQ 和订单闭合是否还能快速 drain

JMeter 结束后，建议立刻检查：

```powershell
@'
$jtl = 'target\loadtest\stage3c-current\submit-loops-<timestamp>.jtl'
Import-Csv $jtl | Group-Object responseCode,success | Sort-Object Count -Descending | Select-Object Count,Name | Format-Table -AutoSize
'@ | rtk proxy powershell -NoProfile -
```

```powershell
rtk proxy powershell -NoProfile -Command "docker exec mall-rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers"
```

```powershell
rtk proxy powershell -NoProfile -Command "docker exec mall-oceanbase-ce obclient -h 127.0.0.1 -P 2881 -u root@test -D mall -e 'SELECT status, COUNT(*) FROM seckill_result GROUP BY status; SELECT status, COUNT(*) FROM seckill_stock_snapshot GROUP BY status; SELECT status, COUNT(*) FROM seckill_stock_change_log GROUP BY status;'"
```

```powershell
rtk proxy powershell -NoProfile -Command "docker exec mall-oceanbase-ce-shard1 obclient -h 127.0.0.1 -P 2881 -u root@test -D test -e 'SELECT status, COUNT(*) FROM seckill_stock_snapshot GROUP BY status; SELECT status, COUNT(*) FROM seckill_stock_change_log GROUP BY status;'"
```

```powershell
rtk proxy powershell -NoProfile -Command "docker exec -e MYSQL_PWD=root mall-mysql mysql --default-character-set=utf8mb4 -uroot mall -e 'SELECT status, COUNT(*) FROM order_info GROUP BY status; SELECT COUNT(*) AS seckill_order_count FROM seckill_order; SELECT status, COUNT(*) FROM mq_message GROUP BY status;'"
```

正确口径：

- `1000` 个压测请求应全部落到 `200`
- RabbitMQ 相关队列应回落到 `ready=0`、`unacked=0`
- `seckill_result.SUCCESS`、两个分片合计 `snapshot.CONFIRMED`、两个分片合计 `change_log.APPLIED`、`seckill_order_count` 应基本对齐
- 如果压测前做过 smoke，请把 smoke 的 `1` 条请求计入总量

## 4. 结果判读

### 4.1 先看 JTL

统计响应码和成功率：

```powershell
rtk proxy powershell -NoProfile -Command "Import-Csv 'target\loadtest\stage3c-current\submit-<timestamp>.jtl' | Group-Object responseCode,success | Sort-Object Count -Descending | Select-Object Count,Name"
```

看失败样例：

```powershell
rtk proxy powershell -NoProfile -Command "Import-Csv 'target\loadtest\stage3c-current\submit-<timestamp>.jtl' | Where-Object success -ne 'true' | Select-Object -First 20 timeStamp,elapsed,responseCode,success,failureMessage,responseMessage | ConvertTo-Json -Depth 4"
```

当前入口压测最低要求：

- HTTP 500 为 `0`
- 不应再出现 `java.net.BindException: Address already in use: connect`
- 非售罄场景下，不应大量出现 `Stock not enough`
- 非重复购买场景下，不应大量出现 `Duplicate purchase`

查看 Actuator 分段指标汇总：

```powershell
@'
$metrics = (Get-Content -Raw -Encoding UTF8 'target\loadtest\stage3c-current\last-metrics-dir.txt').Trim()
$summary = Get-Content -Raw -Encoding UTF8 (Join-Path $metrics 'summary.json') | ConvertFrom-Json
$summary.metrics | Select-Object metric,countDelta,totalTimeDelta,avgMs,afterMaxMs | Format-Table -AutoSize
'@ | rtk proxy powershell -NoProfile -
```

下一轮重点先看：

- `seckill.submit.record.bucket.db.deduct`
- `seckill.submit.record.snapshot.insert`
- `seckill.submit.record.bucket.change-log.insert`
- `seckill.submit.record.bucket.route`

轻量回归压测 `50 x 20` 的最低要求：

- `1000` 请求全部为 `200, true`
- JMeter 错误率 `0%`
- 不出现 `Stock not enough`、`Duplicate purchase` 的批量异常
- 队列和库表能在短时间内自行回落，不出现 `REGISTERED`、`NEW` 长时间堆积

### 4.2 再看异步闭合

RabbitMQ：

```powershell
rtk proxy powershell -NoProfile -Command "docker exec mall-rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers"
```

OceanBase 主分片：

```powershell
rtk proxy powershell -NoProfile -Command "docker exec mall-oceanbase-ce obclient -h 127.0.0.1 -P 2881 -u root@test -D mall -e 'SELECT status, COUNT(*) FROM seckill_result GROUP BY status; SELECT status, COUNT(*) FROM seckill_stock_snapshot GROUP BY status; SELECT status, COUNT(*) FROM seckill_stock_change_log GROUP BY status;'"
```

OceanBase shard1：

```powershell
rtk proxy powershell -NoProfile -Command "docker exec mall-oceanbase-ce-shard1 obclient -h 127.0.0.1 -P 2881 -u root@test -D test -e 'SELECT status, COUNT(*) FROM seckill_stock_snapshot GROUP BY status; SELECT status, COUNT(*) FROM seckill_stock_change_log GROUP BY status;'"
```

MySQL 订单侧：

```powershell
rtk proxy powershell -NoProfile -Command "docker exec -e MYSQL_PWD=root mall-mysql mysql --default-character-set=utf8mb4 -uroot mall -e 'SELECT status, COUNT(*) FROM order_info GROUP BY status; SELECT COUNT(*) AS seckill_order_count FROM seckill_order; SELECT status, COUNT(*) FROM mq_message GROUP BY status;'"
```

正确口径：

- `seckill_result.SUCCESS` 最终应接近或等于 `seckill_order_count`
- `seckill_stock_snapshot.REGISTERED` 最终应接近 `0`
- `seckill_stock_change_log.NEW`、`OUTBOXING`、`LEDGER_PROCESSING` 不应长期堆积
- 队列 `ready` / `unacked` 应能回落

如果入口指标正常，但闭合没有 drain，只能记为 `仅入口通过`。

## 5. 当前已验证的结论

当前本地已经验证过：

- 旧 JMX 在 Windows 下可能因为连接复用配置不完整导致大量 `java.net.BindException`
- 补齐 `HttpClient4 + KeepAlive + same_user_on_next_iteration=true` 后，`40` 线程、`60s` 可以跑到 `0%` JMeter 客户端错误
- 一轮实际可用结果示例：
  - JTL：`target/loadtest/stage3c-current/submit-20260710-115513.jtl`
  - 报表：`target/loadtest/stage3c-current/report-20260710-115513`
  - submit 统计：`23018` 请求，约 `383 req/s`，平均 `95ms`，JMeter 采样错误 `0%`
- 一轮轻量回归结果示例：
  - JTL：`target/loadtest/stage3c-current/submit-loops-20260711-112235.jtl`
  - 报表：`target/loadtest/stage3c-current/report-loops-20260711-112235`
  - submit 统计：`1000` 请求，约 `86.3 req/s`，平均 `145ms`，JMeter 采样错误 `0%`
  - 全链路闭合：本轮压测前做过 `1` 次 smoke，因此最终 `seckill_result.SUCCESS=1001`、`seckill_order_count=1001`、两个分片合计 `snapshot.CONFIRMED=1001`、两个分片合计 `change_log.APPLIED=1001`

但当前服务端仍存在闭合问题，压测时已经观察到：

- `mall-seckill` 日志里有 `TooManyResultsException`
- `mall-order` 日志里有 MySQL deadlock
- `REGISTERED` / `NEW` 仍可能大量堆积

所以当前文档的定位是：

- 可以稳定跑入口压测
- 不能把当前服务端状态直接判定为全链路通过

## 6. 常见坑

- RabbitMQ 端口不是默认 `5672`，当前本地是 `35672`
- `Start-Process` 超时不等于启动失败，先查端口和 Java 进程
- 如果看到 `java.net.BindException: Address already in use: connect`，优先检查 JMX 是否仍保留连接复用配置
- reset 后如果 `CENTER.saleable_quantity=0`，会导致中心账本推进失败
- 如果重复启动出多组 jar，会制造额外消费者，压测结果直接失真
