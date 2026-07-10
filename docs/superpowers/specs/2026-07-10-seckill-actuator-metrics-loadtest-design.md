# 秒杀入口 Actuator 分段指标压测采集设计

日期：2026-07-10

## 目标

在 stage3c 秒杀入口压测中补齐 `mall-seckill` Actuator 指标采集。下一轮压测不只看 JMeter 总耗时，还要同时看到入口同步链路各阶段的 `count`、`total_time`、`max`、可用百分位和压测中波动。

采集方式采用“前后快照 + 压测期间轮询”：

- 压测前抓一次完整 metrics 快照。
- 压测期间按固定间隔抓取时间序列。
- 压测后再抓一次完整 metrics 快照。
- 输出 summary，方便快速判断入口同步成本主要落在哪些阶段。

## 边界

- 只新增压测观测能力，不改业务代码。
- 只抓 `mall-seckill` 的 Actuator 指标，不把 `mall-order` 合并进本设计。
- 不引入 Prometheus、Grafana 或额外容器。
- `seckill.submit.record.bucket.db.deduct` 继续作为必要同步路径观察，不在本设计里绕开或弱化。OceanBase 热点行优化是当前库存扣减路径的前提选择。
- 采集脚本失败不能影响业务服务状态；失败时产物中要保留错误信息，便于复盘。

## 采集指标

默认采集这些指标：

- `seckill.submit.total`
- `seckill.submit.stock-cache.sold-out`
- `seckill.submit.record.snapshot.insert`
- `seckill.submit.record.bucket.route`
- `seckill.submit.record.bucket.db.deduct`
- `seckill.submit.record.bucket.change-log.insert`
- `seckill.submit.record.stock.update`

保留扩展参数，允许临时追加指标，例如 `seckill.submit.record.total`、`seckill.submit.record.duplicate`、`hikaricp.connections.*`。

## 脚本设计

新增独立脚本：

`scripts/loadtest/stage3c/collect-seckill-metrics.ps1`

核心参数：

- `-BaseUrl`：默认 `http://localhost:8105`。
- `-OutputDir`：指标产物目录，调用方传入。
- `-MetricNames`：指标名数组，默认使用本设计的入口指标列表。
- `-Mode`：`Snapshot` 或 `Watch`。
- `-Label`：快照标签，例如 `before`、`after`。
- `-IntervalSeconds`：轮询间隔，默认 `2`。
- `-DurationSeconds`：轮询持续时间；未传时可由调用方用后台进程控制停止。

行为：

- `Snapshot` 模式：逐个请求 `/actuator/metrics/{metricName}`，保存原始 JSON，并写一份聚合 summary。
- `Watch` 模式：按间隔轮询所有指标，将每个采样点写入 JSON Lines，字段包含时间戳、指标名、measurements、availableTags 和错误信息。
- HTTP 超时使用短超时，单个指标失败不终止整轮采集。
- 输出统一使用 UTF-8。

## 产物设计

一轮压测指标目录：

`target/loadtest/stage3c-current/metrics-<timestamp>/`

目录结构：

- `before/*.json`：压测前每个指标的 Actuator 原始响应。
- `before/summary.json`：压测前聚合摘要。
- `during/metrics.jsonl`：压测期间时间序列。
- `during/errors.jsonl`：压测期间采集错误。
- `after/*.json`：压测后每个指标的 Actuator 原始响应。
- `after/summary.json`：压测后聚合摘要。
- `summary.json`：本轮总摘要，包含 before/after 差值和 during 峰值。

`summary.json` 至少包含：

- metric 名称。
- before/after 的 `COUNT`、`TOTAL_TIME`、`MAX`。
- before/after 的 `COUNT` 差值和 `TOTAL_TIME` 差值。
- 基于差值计算出的本轮平均耗时。
- during 期间观察到的最大 `MAX`。
- Actuator 返回的可用百分位 measurements，如存在则原样保留。

## 工作流接入

更新 `docs/seckill-load-test-workflow.md`，把指标采集加入标准压测步骤：

1. reset、构建、启动服务、smoke 保持不变。
2. JMeter 前运行 `Snapshot -Label before`。
3. JMeter 启动前启动 `Watch`。
4. JMeter 结束后停止 `Watch`。
5. 运行 `Snapshot -Label after`。
6. 生成 `summary.json`，和 JTL、JMeter report 一起作为本轮压测产物。

后续如需一键化，再新增 `run-stage3c-loadtest-with-metrics.ps1` 串联 reset、smoke、JMeter 和指标采集。本设计先优先落独立采集脚本与文档流程。

## 判读口径

`bucket.db.deduct` 是库存事实扣减，OceanBase 热点行优化后仍可能是主要耗时项。判读时不把它简单归因为“SQL 未优化”，而是和下面阶段一起看：

- `snapshot.insert` 高：请求事实登记、唯一约束和分片写入成本偏高。
- `bucket.route` 高：选桶查询或分片路由成本偏高。
- `bucket.db.deduct` 高：热点行条件更新、行锁等待或事务提交成本偏高。
- `change-log.insert` 高：扣减事实日志写入、唯一索引或分片写入成本偏高。
- `stock.update` 高但 `bucket.db.deduct` 不高：需要确认外层 Timer 是否覆盖了额外逻辑。

最终判断以 JMeter 入口 P95/P99、Actuator 阶段差值、压测期间峰值三者一起看。

## 验证

最低验证：

- 在服务未启动时运行脚本，应生成错误产物并退出非零或给出清晰错误。
- 在 `mall-seckill` 启动后运行 `Snapshot`，应生成每个默认指标的 JSON 文件和 summary。
- 短时间运行 `Watch`，应生成 `during/metrics.jsonl`。
- 更新后的文档命令能被直接复制执行。

集成验证：

- 下一轮 stage3c 压测执行前后快照和轮询。
- 产物目录中同时存在 JTL、JMeter report、metrics before/during/after/summary。
- summary 能看出本轮 `bucket.db.deduct`、`snapshot.insert`、`change-log.insert` 的 count 差值和耗时差值。
