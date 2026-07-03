# Seckill Stage 2 Completion Design

## 背景

`inventory_hotspot_seckill_architecture.md` 的阶段二目标是在不改变“单行库存”模型的前提下，通过流程拆分、连接池优化、热点预热和冷热流量隔离，挖掘单点库存扣减能力。当前项目已经完成阶段一主链路的大部分工程化工作：OceanBase 作为库存事实源，TairString 承担读缓存和售罄快速失败，秒杀订单通过 RabbitMQ 异步创建，并已沉淀多轮 JMeter、Prometheus 和 Grafana 观测记录。

但当前还不能称为阶段二完成：

- 阶段一真实验收脚本暴露出 `mq_message` 状态回退问题：RabbitMQ publisher confirm 的 `markSent` 可能覆盖消费者已经写入的 `CONSUMED`。
- 阶段二已有连接池预热、`application-perf.yml`、stock-only 对照压测和 Micrometer 指标，但缺少面向阶段二的统一入口、热点隔离策略和一键验收脚本。
- 本地 Docker OceanBase 环境无法代表文档中的生产 NDB/DB Proxy 集群，因此阶段二不能承诺 `6k QPS`，应以架构闭环、相对提升和可复现证据为目标。

## 目标

阶段二作为个人项目的完成口径是“简历展示型工程闭环”：

- 修复可靠消息状态单调前进，保证阶段一账本闭合后再继续阶段二压测。
- 保持阶段一正确主链路：仍由数据库同步扣减库存，TairString 只作为版本化读缓存。
- 增加热点 SKU 治理能力：显式配置热点活动/SKU，热点请求走独立 Sentinel resource，普通请求走默认 resource。
- 支持热点 QPS 限流和热点并发限流，用于模拟文档中的热点 VIP 通道和实例级保护。
- 完善阶段二预热：连接池预热、热点元数据预热、TairString 库存缓存预热。
- 沉淀阶段二一键验收脚本，固定重置、启动口径、压测、账本校验、限流结果统计和 JSON 摘要。
- 更新性能文档，明确当前环境的真实结果、瓶颈和不承诺 `6k QPS` 的原因。

## 非目标

- 不实现阶段三分桶、中心桶、库存调拨、碎片整理。
- 不强行把快照表和库存表拆到两个物理库；当前阶段仍保留本地事务保证快照与库存扣减一致。
- 不实现真实 DB Proxy 二级连接池，也不模拟多 OceanBase 实例路由。
- 不以本地 Docker 环境压到 `6k QPS` 作为验收条件。
- 不把 stock-only 或 update-only 快路径替代正式秒杀主链路。

## 方案选择

### 方案 A：只补文档和压测记录

成本最低，但阶段二仍缺少代码层热点隔离和预热入口，简历展示价值偏弱。

### 方案 B：阶段二工程闭环

修可靠消息状态，补热点配置、独立 Sentinel resource、预热、阶段二验收脚本和文档。该方案不改变库存模型，风险可控，也最贴近阶段二原文。

### 方案 C：强做物理库拆分和 DB Proxy 模拟

看起来更像原文，但会引入跨库一致性、补偿状态机和基础设施复杂度；个人项目收益不成比例。

选择方案 B。

## 架构设计

### 可靠消息状态单调前进

`ReliableMessageRepository` 的状态更新要从“无条件覆盖”改为“状态前进”：

- `markSent(messageId)` 只允许 `NEW/FAILED -> SENT`，不允许覆盖 `CONSUMED`。
- `markFailed(messageId, error)` 只允许非 `CONSUMED` 消息进入 `FAILED`，不允许消费成功后被 return/nack 回调改回失败。
- `markConsumed(messageId)` 允许幂等设置为 `CONSUMED`。

这样可以处理高并发下消费者先完成、publisher confirm 后到达的乱序情况，阶段一和阶段二验收都不再因消息表状态回退失败。

### 热点 SKU 配置与识别

个人项目阶段二采用显式配置识别热点，不做自动动态识别：

- 新增 `mall.seckill.hotspot.enabled`。
- 新增 `mall.seckill.hotspot.items`，元素格式为 `activityId:skuId`。
- 新增热点限流参数，例如 `qps`、`max-concurrent`、`warmup-enabled`。

显式配置的好处是行为可预测，方便压测复现；动态识别可以作为阶段三之后的增强，而不是阶段二必需项。

### 冷热流量隔离

`SeckillServiceImpl.submit()` 在执行 Sentinel 检查前判断当前 `activityId:skuId` 是否为热点：

- 普通请求进入默认资源 `seckill-submit`。
- 热点请求进入独立资源 `seckill-submit-hot`。

`SentinelSeckillGuard` 负责注册并检查两个资源：

- 默认资源继续使用现有 QPS 限流。
- 热点资源支持单独 QPS 限流。
- 热点并发度限流使用本地 `Semaphore`，按 `activityId:skuId` 维度保护热点请求进入数据库事务的并发数。QPS 由 Sentinel 负责，并发由 `Semaphore` 负责，两者职责固定，避免压测结果难以解释。

阶段二文档中的“中心化并发限流、中心化 QPS 限流、实例限流”在个人项目中映射为：

- 中心化 QPS 限流：热点 resource 的 QPS 规则。
- 中心化并发限流：热点 `activityId:skuId` 的本地 `Semaphore`。
- 实例限流：当前单实例应用内规则和指标观测。

### 预热闭环

阶段二预热分三类：

- 连接池预热：沿用 `application-perf.yml` 中已有 Hikari 最小空闲连接和启动时 `SELECT 1` 预热。
- 元数据预热：启动时加载配置中的热点活动和 SKU，填充本地 metadata cache。
- TairString 预热：从数据库读取热点 SKU 的 `stock/version`，写入 TairString，避免秒杀开始时首次请求触发冷读。

预热必须是只读或缓存写入，不允许做真实扣减。

### 阶段二验收脚本

新增 `docs/scripts/verify-seckill-stage2.ps1`，不替代阶段一脚本，而是编排阶段二特有场景：

- 检查 OceanBase、TairString、RabbitMQ、JMeter 可用。
- 重置目标 SKU、清理快照、结果、订单、可靠消息和消费记录。
- 运行正式秒杀主链路压测，校验库存、快照、结果、订单、消息状态和 TairString。
- 运行热点限流场景，验证不会出现 HTTP 500、不会超卖，且限流结果可被统计。
- 可选运行 stock-only 对照，用于生成阶段二性能诊断摘要。
- 输出 `target/loadtest/stage2-verify-<timestamp>.json`。

验收通过条件：

- 正式主链路账本完全一致。
- `mq_message` 中秒杀创建和结果消息没有 `NEW/SENT/FAILED` 残留。
- TairString `stock/version` 与数据库一致。
- 热点限流场景只出现预期业务失败或限流失败，不出现 HTTP 500。
- JSON 摘要包含 QPS、P95、P99、成功数、限流数、账本结果和产物路径。

## 数据流

正式秒杀主链路保持阶段一语义：

1. 请求进入 `SeckillServiceImpl.submit()`。
2. 根据 `activityId:skuId` 选择默认或热点 Sentinel resource。
3. 读取本地预热后的活动/SKU 元数据。
4. TairString 判断售罄快速失败。
5. 数据库事务写入扣减快照、扣减 `seckill_sku` 单行库存、保存 PROCESSING 结果。
6. 刷新 TairString `stock/version`。
7. 发送秒杀下单消息。
8. 订单服务创建订单并发布结果消息。
9. 秒杀服务确认快照，更新结果，消息状态进入 `CONSUMED`。

## 错误处理

- 热点限流返回业务码 `429`，不进入库存扣减事务。
- 可靠消息状态更新必须不覆盖 `CONSUMED`。
- 预热失败只记录日志，不阻止服务启动；数据库仍是事实源。
- TairString 预热或刷新失败后依赖阶段一缓存修复任务兜底。
- 压测脚本遇到账本不一致、消息残留、缓存版本不一致或非预期 HTTP 500 时失败退出。

## 测试计划

单元测试：

- `ReliableMessageRepository` 状态转移：`CONSUMED` 不被 `markSent` 和 `markFailed` 覆盖。
- 热点配置匹配：命中与未命中 `activityId:skuId`。
- Sentinel/限流 guard：热点与普通资源分流，热点限流时返回 `429`。
- 预热组件：加载热点 SKU 并刷新 TairString，不执行库存扣减。

回归测试：

- `mvn -pl mall-message,mall-seckill -am test`。
- 执行阶段一验收脚本，确认消息状态修复后账本闭合。
- 执行阶段二验收脚本，生成 JSON 摘要和 JMeter 报告。

文档验证：

- 更新 `docs/performance-testing.md` 的阶段二小节。
- 记录本地 Docker 环境实际 QPS、P95/P99、限流数和账本结果。
- 明确该阶段是个人项目工程闭环，不声称达到生产环境 `6k QPS`。

## 实施顺序

1. 修复可靠消息状态单调前进，并补测试。
2. 增加热点配置模型和热点匹配器。
3. 改造 Sentinel guard，使默认资源和热点资源分流。
4. 增加阶段二预热组件。
5. 新增阶段二验收脚本。
6. 跑单元测试、阶段一验收、阶段二验收。
7. 更新性能文档和阶段二结论。

## 完成定义

- 阶段一真实验收脚本通过。
- 阶段二脚本可以一键运行并输出 JSON 摘要。
- 文档中能清楚展示：阶段二做了哪些架构增强、哪些是当前环境瓶颈、为什么不承诺 `6k QPS`。
- 主链路仍保持数据库事实源、TairString 版本化缓存和异步订单结果闭环。
