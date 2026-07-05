# 压测方案

## 目标

先压最容易暴露瓶颈的链路，再补业务写接口和完整下单链路。压测入口优先走网关或 Nginx，模拟真实访问。

## 优先接口

| 优先级 | 接口 | 目的 |
| --- | --- | --- |
| P0 | `GET /api/product/search` | 高频商品搜索，观察 MySQL 查询、索引和网关吞吐 |
| P0 | `GET /api/product/{skuId}` | 高频商品详情，观察缓存、聚合调用和库存读取 |
| P0 | `POST /api/seckill/{activityId}/{skuId}` | 秒杀热点提交，观察限流、Redis Lua、锁和 MQ |
| P0 | `GET /api/seckill/result/{requestId}` | 秒杀结果轮询，观察异步结果查询压力 |
| P1 | `POST /api/cart/items` | 加购写接口，观察登录态、购物车落库和缓存同步 |
| P1 | `GET /api/cart` | 购物车读取，观察用户隔离和读缓存 |
| P1 | `POST /api/order/confirm` | 下单确认，观察购物车和商品快照读取 |
| P1 | `POST /api/order/create` | 普通下单核心链路，观察 Seata、库存和订单写入 |
| P2 | `POST /api/auth/login` | 登录接口，适合单独测稳定性和吞吐 |
| P2 | `POST /api/order/{orderSn}/pay` | 支付状态流转，适合幂等和状态机测试 |
| P2 | `POST /api/order/{orderSn}/cancel` | 取消订单，观察库存释放和关单 |

RAGAgent 的 `POST /api/shopping/chat` 不建议直接做大并发压测，因为它依赖外部 DashScope，波动和成本都高。若要测，只建议先把大模型调用隔离掉，压自己的商品检索和工具调用层。

## 推荐顺序

1. 先压单接口：商品搜索、商品详情、购物车读取。
2. 再压业务链路：登录 -> 搜索 -> 详情 -> 加购 -> 确认订单 -> 创建订单。
3. 最后压秒杀：提交 -> 轮询结果。
4. 出现瓶颈后，再绕过网关直连服务，区分是网关、MySQL、Redis、MQ 还是 Seata。

## JMeter 基础配置

建议从网关入口压：

```text
协议：http
Host：localhost
Port：8080
```

全局加 HTTP Header Manager：

```text
Content-Type: application/json
Authorization: Bearer ${token}
```

登录请求：

```http
POST /api/auth/login
```

Body：

```json
{
  "username": "${username}",
  "password": "demo123"
}
```

JSON Extractor：

```text
变量名：token
JSONPath：$.data.token
```

## 典型脚本

### 商品搜索

```http
GET /api/product/search?keyword=${keyword}&limit=12
```

建议 CSV：

```csv
keyword
耳机
积木
键盘
鼠标
保温杯
跑步鞋
```

### 商品详情

```http
GET /api/product/${skuId}
```

建议 CSV：

```csv
skuId
1001
1002
3001
3004
3020
3021
3026
```

这类接口建议分两轮：冷启动压测和预热后压测。

### 购物车链路

加购：

```http
POST /api/cart/items
```

Body：

```json
{
  "skuId": ${skuId},
  "skuName": "${skuName}",
  "price": ${price},
  "quantity": 1
}
```

查看购物车：

```http
GET /api/cart
```

购物车压测尽量使用多个用户，不要所有线程都打同一个用户。

### 普通下单链路

建议串联：

```text
登录
商品搜索
商品详情
加购
确认订单
创建订单
查询订单
```

确认订单：

```http
POST /api/order/confirm
```

创建订单：

```http
POST /api/order/create
```

查询订单：

```http
GET /api/order/${orderSn}
```

### 秒杀链路

提交秒杀：

```http
POST /api/seckill/${activityId}/${skuId}
```

查询结果：

```http
GET /api/seckill/result/${requestId}
```

建议先做阶梯并发，不要一上来就顶满。

## 建议并发

| 场景 | 并发 | 持续时间 | 目标 |
| --- | ---: | ---: | --- |
| 商品搜索 | 100 | 10 分钟 | 看 MySQL 查询和网关吞吐 |
| 商品详情 | 100 | 10 分钟 | 看缓存和聚合调用 |
| 加购 + 查购物车 | 50 | 5 分钟 | 看购物车稳定性 |
| 普通下单链路 | 30 | 5 分钟 | 看 Seata、库存和订单一致性 |
| 秒杀提交 | 300 | 3 分钟 | 看限流、Redis、MQ 和超卖风险 |
| 秒杀结果轮询 | 300 | 5 分钟 | 看异步结果延迟和查询压力 |

## 断言

建议至少加这些断言：

```text
响应 code = 0
响应不包含 Exception
响应不包含 500
```

商品搜索可以断言有返回结果，商品详情可以断言 `price` 和 `stock` 存在。秒杀场景里，成功、排队中、库存不足、重复提交、限流都属于可接受业务结果，HTTP 500 不可接受。

## 命令行

正式压测优先用非 GUI 模式：

```powershell
jmeter -n -t mall-pressure.jmx -l result.jtl -e -o report
```

当前仓库已提供秒杀提交压测脚本：

```powershell
& "C:\Program Files\apache-jmeter-5.6.3\bin\jmeter.bat" `
  -n `
  -t "docs\jmeter\seckill-submit.jmx" `
  -l "target\loadtest\jmeter-seckill-submit.jtl" `
  -e `
  -o "target\loadtest\jmeter-report" `
  "-Jthreads=80" `
  "-Jloops=1" `
  "-Jramp=1" `
  "-JactivityId=1" `
  "-JskuId=1001" `
  "-Jhost=localhost" `
  "-Jport=8105"
```

脚本会为每次请求生成独立 `X-User-Id`，避免把持续压测误测成同一用户重复提交。脚本内置业务断言：响应 JSON 的 `code != 0` 会被 JMeter 标记为失败，因此 Sentinel `429` 不会再混入 HTTP 200 成功样本。

### 阶段一一键验收

阶段一完整验收优先使用仓库脚本：

```powershell
powershell -ExecutionPolicy Bypass -File docs/scripts/verify-seckill-stage1.ps1 `
  -DbPort 2881 `
  -RedisPort 6381 `
  -ServicePort 8105 `
  -Stock 1000 `
  -Threads 200 `
  -Loops 5
```

前置条件：

- Docker 环境先启动 OceanBase CE 与 TairString：

```powershell
docker compose up -d oceanbase tairstring
```

如果本机已经有手工启动的 `mall-oceanbase-ce` 同名容器，先复用现有容器；需要迁移为 compose 管理时，先确认容器内数据可丢弃或已备份，再删除旧容器后执行上面的命令。

- `mall-seckill` 使用 `oceanbase` profile 启动，并连接真实 OceanBase CE 与 TairString。
- `mall-order` 指向同一个 OceanBase 库，并提高 Rabbit listener 并发，例如 `concurrency=8`、`max-concurrency=16`、`prefetch=50`。
- JMeter、`mysql` 客户端、`redis-cli` 可以在当前 PowerShell 中直接执行；路径不一致时通过 `-JMeterPath`、`-MysqlCliPath`、`-RedisCliPath` 参数覆盖。
- 目标环境必须是压测环境。脚本会清理 `seckill_stock_snapshot`、`seckill_result`、当前活动的 `seckill_order`、`mq_message`、`consume_record`，并重置目标 `seckill_sku.stock/version`。

脚本执行内容：

- 删除 TairString 缓存 key `seckill:stock-cache:{activityId}:{skuId}`。
- 调用 `docs/jmeter/seckill-submit.jmx` 发起秒杀提交压测。
- 等待异步建单与结果回传追平，默认等待 `60s`。
- 校验 `seckill_sku`、`seckill_stock_snapshot`、`seckill_result`、`seckill_order`、秒杀 MQ 消息状态和 TairString `stock/version`。
- 输出 `target/loadtest/stage1-verify-<timestamp>.json`，同时生成 JMeter `.jtl` 与 HTML 报告。

通过标准：

- JMeter 失败数不超过 `-MaxJMeterFailures`，默认必须为 `0`。
- `seckill_sku.stock = 初始库存 - 预期成功数`，`version = 预期成功数`。
- `seckill_stock_snapshot.CONFIRMED = 预期成功数`，`DEDUCTED = 0`。
- `seckill_result.SUCCESS = 预期成功数`，`FAILED = 0`。
- `seckill_order = 预期成功数`。
- 秒杀创建和结果消息没有未消费残留。
- TairString 缓存值和版本与数据库一致。

### 阶段二一键验收

阶段二仍保持“单行库存”模型，不追本地 Docker 环境的 `6k QPS`，验收重点是：阶段一账本闭合、热点 SKU 独立限流、热点并发保护、连接池/元数据/TairString 预热和可复现压测摘要。

推荐启动 `mall-seckill` 时使用 `oceanbase,perf` profile，并显式打开热点配置：

```powershell
java -jar mall-seckill/target/mall-seckill-0.0.1-SNAPSHOT.jar `
  --spring.profiles.active=oceanbase,perf `
  --spring.cloud.nacos.discovery.server-addr=localhost:18848 `
  --spring.cloud.sentinel.transport.dashboard=localhost:18858 `
  --mall.seckill.hotspot.enabled=true `
  --mall.seckill.hotspot.items[0]=1:1001 `
  --mall.seckill.hotspot.permits-per-second=10000 `
  --mall.seckill.hotspot.max-concurrent=300 `
  --mall.seckill.hotspot.warmup-enabled=true
```

`mall-order` 继续指向同一个 OceanBase 库，并提高 Rabbit listener 并发：

```powershell
java -jar mall-order/target/mall-order-0.0.1-SNAPSHOT.jar `
  --spring.cloud.nacos.discovery.server-addr=localhost:18848 `
  --spring.datasource.url="jdbc:mysql://localhost:2881/mall?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true" `
  --spring.datasource.username=root@test `
  --spring.rabbitmq.listener.simple.concurrency=8 `
  --spring.rabbitmq.listener.simple.max-concurrency=16 `
  --spring.rabbitmq.listener.simple.prefetch=50
```

执行阶段二脚本：

```powershell
powershell -ExecutionPolicy Bypass -File docs/scripts/verify-seckill-stage2.ps1 `
  -JMeterPath "C:\Java\apache-jmeter-5.6.3\bin\jmeter.bat" `
  -DbPort 2881 `
  -RedisPort 6381 `
  -ServicePort 8105 `
  -Stock 1000 `
  -Threads 200 `
  -Loops 5 `
  -HotspotThreads 200 `
  -HotspotLoops 2
```

如果要专门证明热点限流生效，可以临时把 `mall.seckill.hotspot.permits-per-second` 或 `mall.seckill.hotspot.max-concurrent` 调低，并给脚本传入 `-MinHotspotFailures 1`。脚本把 JMeter 业务失败和 HTTP 500 分开统计：热点限流导致的业务失败可以接受，HTTP 500 不可接受。

脚本输出：

- `target/loadtest/stage2-verify-<timestamp>.json`
- `target/loadtest/jmeter-seckill-stage2-main-<timestamp>.jtl`
- `target/loadtest/jmeter-report-seckill-stage2-main-<timestamp>`
- `target/loadtest/jmeter-seckill-stage2-hotspot-<timestamp>.jtl`
- `target/loadtest/jmeter-report-seckill-stage2-hotspot-<timestamp>`

通过标准：

- 主链路场景 JMeter 失败数为 `0`。
- 主链路库存、版本、快照、结果、订单、MQ 消息状态和 TairString `stock/version` 全部对齐。
- 热点场景没有 HTTP 500。
- 热点场景不超卖，`stock = 初始库存 - CONFIRMED`，`DEDUCTED = 0`，秒杀 MQ 消息没有未消费残留。
- 设置 `-MinHotspotFailures` 时，热点场景失败数必须达到该阈值，用于证明热点 QPS/并发保护确实生效。

### 2026-07-03 阶段二正式秒杀接口阶梯压测

本轮压测对象为正式秒杀提交接口，不使用 stock-only 或 update-only 快路径：

- 接口：`POST /api/seckill/1/1001`。
- JMeter 脚本：`docs/jmeter/seckill-submit.jmx`。
- 服务口径：`mall-seckill` 使用 `oceanbase,perf` profile，`mall-order` 指向同一个 OceanBase；热点 SKU 为 `1:1001`。
- 热点保护：`mall.seckill.hotspot.max-concurrent=300`，`mall.seckill.hotspot.permits-per-second=10000`。
- 阶梯档位：`50/100/150/200/300/400/500` 并发。
- 每档独立运行：`loops=5`、`ramp=2`，每档开始前重置库存为本档请求数、清理快照/结果/订单/可靠消息/TairString 缓存。
- QPS 口径：`请求 QPS = JMeter 总样本数 / 样本窗口耗时`；`成功受理 QPS = JMeter success=true 样本数 / 样本窗口耗时`。`400/500` 并发档包含热点保护返回的业务失败，因此不能只看总请求 QPS。
- 摘要文件：`target/loadtest/seckill-stage2-ladder-20260703-231445.json`。

阶梯结果：

| 并发 | 请求数 | 请求 QPS | 成功受理 QPS | JMeter 失败 | 失败率 | HTTP 5xx | P95 | P99 | CONFIRMED | DEDUCTED 残留 | TairString 对齐 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 50 | 250 | 153.28 | 153.28 | 0 | 0.00% | 0 | 95ms | 109ms | 250 | 0 | 是 |
| 100 | 500 | 221.63 | 221.63 | 0 | 0.00% | 0 | 417ms | 427ms | 500 | 0 | 是 |
| 150 | 750 | 239.69 | 239.69 | 0 | 0.00% | 0 | 593ms | 629ms | 750 | 0 | 是 |
| 200 | 1000 | 233.97 | 233.97 | 0 | 0.00% | 0 | 993ms | 1254ms | 1000 | 0 | 是 |
| 300 | 1500 | 257.69 | 257.69 | 0 | 0.00% | 0 | 1514ms | 1806ms | 1500 | 0 | 是 |
| 400 | 2000 | 318.93 | 223.89 | 596 | 29.80% | 0 | 1931ms | 2192ms | 1404 | 0 | 是 |
| 500 | 2500 | 448.91 | 244.39 | 1139 | 45.56% | 0 | 1792ms | 1958ms | 1361 | 0 | 是 |

本轮结论：

- 在当前本地 Docker OceanBase + TairString + RabbitMQ 环境下，正式秒杀主链路无业务失败的最高阶梯是 `300` 并发，成功受理 QPS 约 `257.69`。
- `400/500` 并发档总请求 QPS 更高，是因为大量请求被热点保护快速拒绝；失败样本 HTTP 状态仍为 `200`，响应业务码为 `429`，典型样本为 `{"code":429,"message":"Hotspot seckill busy","data":null}`。
- 全部阶梯均没有 HTTP 5xx，没有 `DEDUCTED` 残留，没有未消费秒杀 MQ 消息；数据库库存、确认订单数和 TairString `stock/version` 均对齐。
- 阶段二当前瓶颈已经从默认入口限流转为热点并发保护与单行库存事务链路；简历展示口径建议写“正式主链路本地可复现约 `250+` 成功受理 QPS，超过 `300` 并发后热点保护开始介入”，不要把 `400/500` 档的总请求 QPS 当作真实下单能力。

### 2026-07-04 热点保护拉高后的主链路极限与 UPDATE 基准

本轮目的：把热点快速拒绝阈值拉高，避免 `max-concurrent=300` 提前挡住请求，继续摸正式秒杀主链路和库存 `UPDATE` 的真实上限。

服务启动差异：

- `mall-seckill` 使用 `oceanbase,perf` profile。
- 运行时覆盖 `--mall.seckill.hotspot.max-concurrent=2000`。
- 保持 `--mall.seckill.hotspot.permits-per-second=10000`、`--mall.seckill.permits-per-second=10000`。
- `mall-order` 继续使用 OceanBase 和 RabbitMQ 高消费并发。

正式秒杀主链路阶梯：

- 接口：`POST /api/seckill/1/1001`。
- JMeter 脚本：`docs/jmeter/seckill-submit.jmx`。
- 口径：每档独立重置库存；`loops=5`、`ramp=3`。
- 摘要文件：`target/loadtest/seckill-stage2-hotspot2000-ladder-20260703-234605.json`。

| 并发 | 请求数 | 成功受理 QPS | JMeter 失败 | HTTP 5xx | P95 | P99 | 账本闭合情况 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 300 | 1500 | 147.43 | 0 | 0 | 2451ms | 2997ms | 脚本窗口内闭合 |
| 400 | 2000 | 161.66 | 0 | 0 | 3189ms | 3983ms | 脚本窗口内闭合 |
| 500 | 2500 | 148.15 | 0 | 0 | 4553ms | 5468ms | 脚本窗口内闭合 |
| 700 | 3500 | 153.54 | 0 | 0 | 7338ms | 8946ms | 脚本窗口内闭合 |
| 1000 | 5000 | 216.40 | 0 | 0 | 5707ms | 6893ms | JMeter 全成功；结果消息约额外 `30s` 后闭合 |

主链路结论：

- 拉高热点保护后，`400/500/700` 档不再出现 `Hotspot seckill busy`，说明上一轮 `400/500` 的大量失败确实来自热点并发保护，而不是服务端崩溃。
- 当前正式主链路入口在本机单 JMeter 下看到的最高成功受理 QPS 是 `216.40`，出现在 `1000` 并发档。
- 但 `1000` 档已经出现异步结果链路滞后：JMeter 结束时 5000 个订单已创建，`seckill_result` 和快照确认还没完全追平；额外等待约 `30s` 后 `CONFIRMED=5000`、`DEDUCTED=0`、未消费秒杀 MQ 为 `0`。
- 因此更稳妥的展示口径是：正式主链路“入口受理峰值约 `216 QPS`，稳定闭合区间约 `150-160 QPS`；继续加并发主要拉高异步结果滞后和尾延迟”。

MyBatis update-only HTTP 阶梯：

- 接口：`POST /internal/seckill/loadtest/stock-deduct-update-only/1/1001`。
- 执行内容：应用内 MyBatis 调用 `UPDATE seckill_sku SET stock = stock - 1, version = version + 1 WHERE id = ? AND stock >= ?`，无 `@Transactional`，无后续 `SELECT`。
- 临时 JMeter 脚本：`target/loadtest/seckill-update-only-temp.jmx`。
- 口径：每档重置 `seckill_sku.stock=1000000, version=0`；`loops=10`、`ramp=3`。
- 摘要文件：`target/loadtest/seckill-update-only-ladder-20260704-000039.json`。

| 并发 | 请求数 | 成功数 | 失败原因 | 成功 QPS | P95 | P99 |
| ---: | ---: | ---: | --- | ---: | ---: | ---: |
| 100 | 1000 | 1000 | 无 | 334.56 | 60ms | 199ms |
| 200 | 2000 | 2000 | 无 | 569.48 | 372ms | 734ms |
| 300 | 3000 | 3000 | 无 | 608.52 | 597ms | 934ms |
| 500 | 5000 | 5000 | 无 | 663.31 | 940ms | 1322ms |
| 700 | 7000 | 1834 | JMeter 客户端 `Address already in use: connect` | 323.34 | 1300ms | 2124ms |
| 1000 | 10000 | 12 | JMeter 客户端 `Address already in use: connect` | 2.38 | 347ms | 470ms |

update-only 结论：

- 在不触发本机 JMeter 端口耗尽的档位里，MyBatis update-only HTTP 入口最高成功 QPS 为 `663.31`。
- `700/1000` 档不能作为服务端上限，因为失败样本都是压测端 `java.net.BindException: Address already in use: connect`，不是服务端 5xx，也不是库存扣减失败。
- 相比正式秒杀主链路，update-only 少了快照、结果、可靠消息、RabbitMQ 和订单结果回传，所以能到 `600+ QPS`；这说明正式主链路的主要开销不只是库存行 `UPDATE`，还包括事务内账本写入和异步消息闭环。

OceanBase 裸热点行 `UPDATE` 基准：

- 方式：临时 Java/JDBC 压测器，每个线程一条 JDBC 连接，autocommit 单语句更新。
- 测试表：`hot_row_bench(id BIGINT PRIMARY KEY, k BIGINT NOT NULL)`。
- SQL：`UPDATE hot_row_bench SET k = k + 1 WHERE id = 1`。
- 结果文件：`target/loadtest/hot-row-update-bench-20260704-0000.txt`。

| 并发 | 总更新数 | 成功数 | 错误数 | TPS | 平均耗时 | P50 | P90 | P95 | P99 | Max |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 50 | 50000 | 50000 | 0 | 750.51 | 63.97ms | 27ms | 167ms | 278ms | 529ms | 1595ms |
| 100 | 100000 | 100000 | 0 | 1452.35 | 64.32ms | 12ms | 224ms | 336ms | 597ms | 1924ms |
| 200 | 100000 | 100000 | 0 | 1339.80 | 135.36ms | 20ms | 397ms | 592ms | 1103ms | 3300ms |

裸 SQL 结论：

- 当前环境里，单热点行裸 `UPDATE` 最好档为 `100` 并发，约 `1452 TPS`。
- 这个数与前一轮 `1.3k-1.6k TPS` 判断一致，说明 OceanBase 热点行本体能力仍是千级。
- 从能力层级看：裸 SQL `~1452 TPS` > MyBatis update-only HTTP `~663 QPS` > 正式秒杀主链路稳定闭合 `~150-160 QPS` / 入口峰值 `~216 QPS`。这条差距链路就是后续优化要拆的部分。

### 2026-07-04 ELR 开关对热点行 UPDATE 的影响

官方热点行体验文档给出的基准之一是：

- Parallel Degree：`50`。
- Total Updates：`100000`。
- 未开启 ELR：`54.5s`，`1834.86 TPS`。
- 开启 ELR 且设置 `_max_elr_dependent_trx_count=1000`：`12.16s`，`8223.68 TPS`。

本地复测说明：

- OceanBase CE：`5.7.25-OceanBase_CE-v4.4.2.1`。
- 本地 CE 镜像只暴露 `enable_early_lock_release`，未暴露 `_max_elr_dependent_trx_count`。
- 压测方式：临时 Java/JDBC 压测器，每线程一条连接，autocommit 单语句更新。
- 同样使用 `50` 并发、`100000` 次总更新。
- SQL：`UPDATE hot_row_bench SET k = k + 1 WHERE id = 1`。
- 结果文件：
  - `target/loadtest/hot-row-update-elr-off-20260704-clean.txt`
  - `target/loadtest/hot-row-update-elr-on-20260704-clean.txt`

| ELR | 总更新数 | 成功数 | 错误数 | 耗时 | TPS | 平均耗时 | P50 | P90 | P95 | P99 | Max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 关闭 | 100000 | 100000 | 0 | 589.739s | 169.57 | 290.88ms | 430ms | 583ms | 650ms | 813ms | 1111ms |
| 开启 | 100000 | 100000 | 0 | 117.071s | 854.18 | 57.22ms | 15ms | 166ms | 283ms | 545ms | 1716ms |

补充高线程数实验：

- ELR：开启。
- Parallel Degree：`5000`。
- 每线程更新：`20` 次。
- Total Updates：`100000`。
- 结果文件：`target/loadtest/hot-row-update-elr-on-5000x20-20260704.txt`。

| 并发 | 每线程更新 | 目标总更新 | 成功数 | 错误数 | TPS | 耗时 | 平均耗时 | P50 | P95 | P99 | Max |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 5000 | 20 | 100000 | 6786 | 93214 | 33.53 | 202.369s | 5326.39ms | 5163ms | 9488ms | 9855ms | 9929ms |

本地结论：

- 仅切换 `enable_early_lock_release` 后，本地热点行 UPDATE TPS 从 `169.57` 提升到 `854.18`，约 `5.04x`。
- `5000` 线程组不是有效的数据库热点行吞吐上限：只有 `6786/100000` 次成功，大量错误发生在极端线程/连接压力下；该组主要说明本地 Docker + 单 JVM 压测器无法承载 5000 条并发 JDBC 连接。
- 本地绝对值低于官方示例，主要差异是：当前 Docker CE 环境资源更弱，且 `_max_elr_dependent_trx_count=1000` 在当前镜像中不可见/不可配置；本地压测器也不是官方 Python 脚本的完全同环境复刻。
- 但趋势与官方一致：开启 ELR 后，热点行更新的等待和吞吐有数量级改善。后续项目压测应保持 `enable_early_lock_release=true`，并继续把 `_max_elr_dependent_trx_count` 记录为“当前 CE 镜像不可配的官方体验参数”。

秒杀瓶颈诊断建议至少跑三组同口径对比：

| 组别 | `mall-seckill` 参数 | `mall-order` 参数 | 目的 |
| --- | --- | --- | --- |
| 默认 | 默认 `mall.seckill.permits-per-second=100` | 默认 Rabbit listener | 观察真实默认表现 |
| 高 Sentinel | `--mall.seckill.permits-per-second=500` | 默认 Rabbit listener | 排除入口限流影响 |
| 高消费并发 | `--mall.seckill.permits-per-second=500` | `--spring.rabbitmq.listener.simple.concurrency=8 --spring.rabbitmq.listener.simple.max-concurrency=16 --spring.rabbitmq.listener.simple.prefetch=50` | 观察订单消费链路上限 |

每组之间都要重置 `seckill_sku`、Redis 秒杀 key、`seckill_stock_snapshot`、`seckill_result`、秒杀订单、可靠消息表和 RabbitMQ 队列。

2026-07-02 本地对比压测结果，口径为 `200` 线程、每线程 `5` 次请求、JMeter 完成后等待 `60s` 读取账本：

| 组别 | 请求 QPS | 业务受理 QPS | JMeter 失败 | 429 | 60s 确认订单 | 60s 确认 QPS | DEDUCTED 残留 | P95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 默认 | 35.86 | 34.82 | 29 | 29 | 971 | 11.05 | 0 | 7648ms |
| 高 Sentinel | 36.34 | 36.34 | 0 | 0 | 990 | 11.31 | 10 | 7499ms |
| 高 Sentinel + 高消费并发 | 28.25 | 28.25 | 0 | 0 | 1000 | 10.48 | 0 | 9490ms |

本轮结论：

- 提高 Sentinel 后 `429` 消失，说明默认入口限流会污染压测结果。
- 提高 Rabbit listener 并发后 `60s` 内账本能完全确认，但入口响应变慢，说明订单消费与 HTTP 提交会争抢本地 MySQL/CPU。
- 当前主要瓶颈不是 RabbitMQ 本身，而是同步提交链路和订单消费链路中的 MySQL 写入。

2026-07-02 低风险瘦身已落地：

- 秒杀提交入口为活动和 SKU 元数据增加 `mall.seckill.metadata-cache-ttl-millis` 短 TTL 本地缓存，默认 `1000ms`。
- 成功路径删除外层重复的 `hasActiveDeduction` 查询，只保留 `deductAndRecord` 事务内重复购买检查。
- 成功路径删除 `PROCESSING` 结果写入；查询结果缺失时仍按已有逻辑返回 `PROCESSING`，最终成功/失败由异步结果消息写入。
- 下一轮复测沿用上表同口径，重点对比 `业务受理 QPS`、`P95`、`60s 确认订单` 和 MySQL 资源占用。

2026-07-02 低风险瘦身后复测，仍使用高 Sentinel 口径：`200` 线程、每线程 `5` 次请求、JMeter 完成后等待 `60s` 读取账本。

| 组别 | 请求 QPS | 业务受理 QPS | JMeter 失败 | 429 | 60s 确认订单 | 60s 确认 QPS | DEDUCTED 残留 | P95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 高 Sentinel（瘦身前） | 36.34 | 36.34 | 0 | 0 | 990 | 11.31 | 10 | 7499ms |
| 高 Sentinel（瘦身后） | 37.34 | 37.34 | 0 | 0 | 1000 | 11.52 | 0 | 7457ms |

复测结论：

- 低风险瘦身后入口 QPS 从 `36.34` 提升到 `37.34`，P95 从 `7499ms` 降到 `7457ms`，提升有限。
- `60s` 窗口内确认订单从 `990` 提升到 `1000`，`DEDUCTED` 残留从 `10` 降为 `0`，说明减少同步写入后异步结果追平更稳定。
- 这一步没有改变主要瓶颈判断：入口链路仍被 Redis 后的 MySQL 热库存行更新和同步可靠消息写入限制。下一阶段要明显提升 QPS，需要把 MySQL 扣库存从 HTTP 提交链路移出，改为 Redis 扣减 + 异步落账。

2026-07-02 已将 MySQL 秒杀库存扣减迁移到异步落账链路：

- HTTP 提交链路以 Redis Lua 作为必需准入账本，Redis 不可用时返回 `503`，不再回退到 MySQL 扣库存。
- HTTP 提交链路只写 `seckill_stock_snapshot=DEDUCTED` 和可靠消息，不再同步执行 `UPDATE seckill_sku SET stock = stock - 1`。
- 秒杀结果消息消费成功时，在 `confirmDeduction` 事务内把快照确认成 `CONFIRMED` 并扣减 `seckill_sku.stock`。
- 订单创建失败或消息发布失败时，只释放 Redis 资格并把快照改为 `RELEASED/FAILED`，不回补未扣过的 MySQL 秒杀库存。
- Redis 启动预热时使用 `seckill_sku.stock - DEDUCTED 预约量` 作为可售库存，避免服务重启后重复放大未确认资格。

2026-07-02 秒杀入口 Tomcat 线程扩容后复测：

- `mall-seckill` 显式配置内嵌 Tomcat：`server.tomcat.threads.max=500`、`min-spare=50`、`accept-count=1000`、`max-connections=10000`。
- 未配置前走 Spring Boot 内嵌 Tomcat 默认线程口径，最大工作线程通常为 `200`；本地 JMeter 也是 `200` 并发，入口线程池贴近上限。
- 本轮仍使用高 Sentinel 口径：`200` 线程、每线程 `5` 次请求，库存 `1000`，JMeter 完成后等待 `60s` 读取账本。

| 组别 | 请求 QPS | 业务受理 QPS | JMeter 失败 | 429 | 60s 确认订单 | DEDUCTED 残留 | P95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 低风险瘦身后 | 37.34 | 37.34 | 0 | 0 | 1000 | 0 | 7457ms |
| 异步落账 + Tomcat 500 | 53.01 | 53.01 | 0 | 0 | 1000 | 0 | 5902ms |

本轮结论：

- 入口 QPS 从 `37.34` 提升到 `53.01`，P95 从 `7457ms` 降到 `5902ms`。
- 因为上一轮没有单独压测“异步落账但 Tomcat 默认线程”的组合，这个提升不能完全归因于 Tomcat；更准确地说，是“移除 HTTP 同步 MySQL 热库存扣减 + 放大入口线程”后的综合效果。
- 订单创建和结果确认在 `60s` 内全部追平，说明当前 `200 * 5` 口径下 RabbitMQ 和订单消费者还能跟上入口受理。

2026-07-02 阶段一代码合并到 `main` 后复测：

- 本轮使用默认 MySQL profile，未启用 `application-oceanbase.yml`，`mall.seckill.stock-cache.enabled=false`；因此该结果不是 OceanBase CE + TairString 真机结果。
- `mall-seckill` 启动参数：`--mall.seckill.permits-per-second=1000`。
- `mall-order` 启动参数：`--spring.rabbitmq.listener.simple.concurrency=8 --spring.rabbitmq.listener.simple.max-concurrency=16 --spring.rabbitmq.listener.simple.prefetch=50`。
- JMeter 口径：`200` 线程、每线程 `5` 次请求，库存 `1000`，JMeter 完成后等待 `60s` 读取账本。
- JMeter 结果文件：`target/loadtest/jmeter-seckill-stage1-20260702-213759.jtl`。
- JMeter HTML 报告：`target/loadtest/jmeter-report-stage1-20260702-213759`。

| 组别 | 请求 QPS | JMeter 失败 | 60s 确认订单 | DEDUCTED 残留 | P95 | P99 | 平均耗时 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 阶段一代码，MySQL profile | 28.56 | 0 | 1000 | 0 | 9295ms | 10911ms | 6494ms |

账本结果：

- `seckill_sku.stock=0`，`version=1000`。
- `seckill_stock_snapshot.CONFIRMED=1000`。
- `seckill_result.SUCCESS=1000`。
- `seckill_order=1000`。
- RabbitMQ 秒杀创建队列、结果队列和对应死信队列均为 `0`。

本轮结论：

- 阶段一把库存扣减重新放回 HTTP 提交事务，入口 QPS 从上一轮“异步落账 + Tomcat 500”的 `53.01` 回落到 `28.56`，符合热点库存行同步更新会拉低入口吞吐的预期。
- 异步结果链路可以在 JMeter 结束后 `60s` 内追平，最终没有 `DEDUCTED` 残留，也没有 JMeter 业务失败。
- 下一轮若要验证文档阶段一目标，必须切到真实 OceanBase CE + TairString 环境，再用同一 JMeter 口径复测；当前 MySQL profile 只能作为回归基线。

2026-07-02 OceanBase CE + TairString 真机复测：

- 容器：`oceanbase/oceanbase-ce:latest` 暴露 `2881`，`tairmodule/tairstring:latest` 暴露 `6381`。
- `mall-seckill` 启动参数：`--spring.profiles.active=oceanbase --spring.data.redis.port=6381 --mall.seckill.permits-per-second=1000`。
- `mall-order` 启动参数：`--spring.datasource.url=jdbc:mysql://localhost:2881/mall?... --spring.datasource.username=root@test --spring.rabbitmq.listener.simple.concurrency=8 --spring.rabbitmq.listener.simple.max-concurrency=16 --spring.rabbitmq.listener.simple.prefetch=50`。
- JMeter 口径：`200` 线程、每线程 `5` 次请求，库存 `1000`，JMeter 完成后等待 `60s` 读取账本。
- JMeter 结果文件：`target/loadtest/jmeter-seckill-oceanbase-tairstring-lua-20260702-221040.jtl`。
- JMeter HTML 报告：`target/loadtest/jmeter-report-oceanbase-tairstring-lua-20260702-221040`。

| 组别 | 请求 QPS | JMeter 失败 | 60s 确认订单 | DEDUCTED 残留 | P95 | P99 | 平均耗时 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| OceanBase CE + TairString | 24.21 | 0 | 1000 | 0 | 13848ms | 13895ms | 6591ms |

账本结果：

- OceanBase `seckill_sku.stock=0`，`version=1000`。
- TairString `seckill:stock-cache:1:1001=0@1000`。
- `seckill_stock_snapshot.CONFIRMED=1000`。
- `seckill_result.SUCCESS=1000`。
- `seckill_order=1000`。
- RabbitMQ 秒杀创建队列、结果队列和对应死信队列均为 `0`。

本轮踩坑和修复：

- 初次启用 TairString 后，`EXGET` 返回值包含整数版本，Spring Data Redis/Lettuce 的 raw `connection.execute("EXGET", ...)` 使用 `ByteArrayOutput` 解码时抛 `UnsupportedOperationException: ByteArrayOutput does not support set(long)`，导致大量 `Unknown redis exception`。
- 修复方式：`RedisTairStringCommands` 改为 Lua 包装 `EXGET/EXSET`，`get` 返回普通字符串，`set` 在 Lua 内做版本比较后 `EXSET ... ABS version`，避免 Lettuce 数组解码问题，也避免并发低版本覆盖高版本。

本轮结论：

- 在当前 Docker 单机 OceanBase CE 环境下，阶段一入口 QPS 为 `24.21`，低于 MySQL profile 的 `28.56`。这不能证明 OceanBase 热点行优化无效，因为本轮是本地单机容器，OceanBase 日志也提示宿主资源低于推荐值。
- 一致性链路是闭合的：OceanBase、TairString、快照、结果、订单、RabbitMQ 队列最终全部对齐。
- 下一步要提升 QPS，需要继续做数据库侧参数/资源核查，或者回到文档阶段二的聚合/异步化模型；单纯切本地 OceanBase CE 容器没有带来入口吞吐提升。

2026-07-03 00:03（Asia/Shanghai）库存扣减主链路单独压测：

- 目的：只测 `HTTP + MyBatis + OceanBase seckill_sku` 热点库存行扣减，不走 Redisson 用户锁、快照账本、结果表、TairString 刷新和 RabbitMQ。
- OceanBase：`OceanBase_CE-v4.4.2.1`，`enable_early_lock_release=True`。
- `mall-seckill` 启动参数：`--spring.profiles.active=oceanbase --spring.data.redis.port=6381 --mall.seckill.stock-cache.enabled=false --mall.seckill.load-test.stock-deduct-enabled=true --mall.seckill.permits-per-second=10000`。
- JMeter 口径：`100` 线程、每线程 `10` 次请求，库存从 `10000` 重置到 `10000`，共 `1000` 次扣减。
- JMeter 结果文件：`target/loadtest/jmeter-seckill-stock-deduct-only-20260703-000314.jtl`。
- JMeter HTML 报告：`target/loadtest/jmeter-report-stock-deduct-only-20260703-000314`。

| 组别 | 请求 QPS | JMeter 失败 | 成功扣减 | P50 | P90 | P95 | P99 | 平均耗时 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| OceanBase CE stock-only | 59.94 | 0 | 1000 | 1335ms | 1755ms | 1922ms | 2284ms | 1323ms |

账本结果：

- OceanBase `seckill_sku.stock=9000`，`version=1000`。
- 本轮没有写入 `seckill_stock_snapshot`、`seckill_result`，也没有发布 RabbitMQ 消息。

本轮结论：

- 去掉锁、快照、结果表、TairString 和 MQ 后，入口从完整 OceanBase 链路的 `24.21 QPS` 提升到 `59.94 QPS`，说明外围链路确实有额外开销。
- 但 stock-only 仍远低于文档阶段一的 `1.5k QPS`，说明当前本地 OceanBase CE Docker 环境的热点行扣减本身就是主要瓶颈之一。
- 当前环境支持 ELR，但没有验证到文档里的 `LOGIC_UPDATE + RETURNING stock_num, version` 完整能力；现实现仍是 `UPDATE + SELECT`。

生成报告后重点看：

```text
Throughput
Average
P90 / P95 / P99
Error %
Active Threads
Response Time Over Time
Transactions per Second
```

2026-07-03 00:37（Asia/Shanghai）接入 Prometheus/Grafana 后 stock-only 同口径压测：

- `mall-seckill` 已接入 `spring-boot-starter-actuator` 和 `micrometer-registry-prometheus`，暴露 `/actuator/prometheus`。
- stock-only 入口已加 3 个 Micrometer Timer：`seckill.stock.deduct.total`、`seckill.stock.deduct.update`、`seckill.stock.deduct.select`。
- Docker 已启动 Prometheus 和 Grafana；Prometheus：`http://localhost:9090`，Grafana：`http://localhost:3000`，默认账号 `admin/admin`。
- Grafana 面板：`http://localhost:3000/d/mall-seckill-stock-only/mall-seckill-stock-only-latency`。
- 本轮 JMeter 口径：`100` 线程、每线程 `10` 次请求，共 `1000` 次 stock-only 扣减。

JMeter 结果：

| 组别 | 请求数 | 成功数 | 请求 QPS | P50 | P90 | P95 | P99 | 平均耗时 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 默认 Hikari | 1000 | 1000 | 69.22 | 1110ms | 1419ms | 1847ms | 2544ms | 1155ms |
| Hikari 100 | 1000 | 1000 | 74.57 | 1097ms | 1464ms | 1618ms | 2057ms | 1048ms |

Prometheus/Grafana P95/P99 分段：

| 组别 | HTTP P95 | HTTP P99 | total P95 | total P99 | update P95 | update P99 | select P95 | select P99 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 默认 Hikari | 1412ms | 2126ms | 156ms | 239ms | 152ms | 229ms | 13ms | 25ms |
| Hikari 100 | 1688ms | 1992ms | 1335ms | 1414ms | 1322ms | 1412ms | 13ms | 23ms |

本轮结论：

- 默认 Hikari 下，`HTTP P95/P99` 远高于 `total/update/select`，说明大量等待发生在 Repository 方法之前，主要是数据库连接池排队。
- Hikari 放大到 `100` 后，`total` 与 `update` 的 P95/P99 同步升高，说明更多并发真正打到 OceanBase 热点库存行，等待从连接池转移到了热点行 `UPDATE`。
- `select` 始终在十几毫秒量级，不是当前主瓶颈。
- 单纯放大连接池只能把 stock-only QPS 从约 `69.22` 提到 `74.57`，收益有限；当前主瓶颈仍是单热点行同步扣减。

2026-07-03 08:09（Asia/Shanghai）按 OceanBase 官方热点行参数复测：

- 参考官方热点行更新文档，尝试设置 `enable_early_lock_release=true` 与 `_max_elr_dependent_trx_count=1000`。
- 本地业务租户为 `test`，OceanBase 版本为 `4.4.2.1`。
- `alter system set enable_early_lock_release = true tenant='test'` 执行成功，复查值为 `True`。
- `alter system set _max_elr_dependent_trx_count = 1000 tenant='test'` 返回 `ERROR 5099 (42000): System config unknown`，当前 CE 镜像不识别该隐藏参数，因此本轮只确认 ELR 生效，不能视为完整复现官方参数组合。
- JMeter 口径沿用 stock-only、`100` 线程、每线程 `10` 次、Hikari `100`，共 `1000` 次库存扣减。
- JMeter 结果文件：`target/loadtest/jmeter-seckill-stock-deduct-only-official-elr-hikari100-20260703-080914.jtl`。
- JMeter HTML 报告：`target/loadtest/jmeter-report-stock-deduct-only-official-elr-hikari100-20260703-080914`。

JMeter 结果：

| 组别 | 请求数 | 成功数 | 请求 QPS | P50 | P90 | P95 | P99 | 平均耗时 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 官方 ELR 可用参数 + Hikari 100 | 1000 | 1000 | 75.72 | 838ms | 1974ms | 2139ms | 2655ms | 1021ms |

Prometheus/Grafana P95/P99 分段：

| 组别 | HTTP P95 | HTTP P99 | total P95 | total P99 | update P95 | update P99 | select P95 | select P99 | Hikari active max | Hikari pending max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 官方 ELR 可用参数 + Hikari 100 | 2133ms | 2635ms | 1310ms | 1411ms | 1309ms | 1411ms | 13ms | 25ms | 98 | 37 |

账本结果：

- OceanBase `seckill_sku.stock=9000`，`version=1000`。
- 本轮没有写入 `seckill_stock_snapshot`、`seckill_result`，也没有发布 RabbitMQ 消息。

本轮结论：

- 相比上一轮 Hikari `100` 的 `74.57 QPS`，本轮为 `75.72 QPS`，基本持平。
- `update P95/P99` 仍约 `1309ms/1411ms`，说明热点行 `UPDATE` 排队仍是主要瓶颈。
- 当前环境无法设置官方文档中的 `_max_elr_dependent_trx_count=1000`，因此只能验证 `enable_early_lock_release=true` 下的本地表现，不能等同于官方完整热点行基准环境。

2026-07-03 10:24（Asia/Shanghai）OceanBase 纯热点行基准：

- 目的：去掉 HTTP、Spring MVC、MyBatis、Hikari、业务事务和 JMeter 影响，只测 OceanBase 单热点行 `UPDATE`。
- 测试表：`hot_row_bench(id BIGINT PRIMARY KEY, k BIGINT)`，单分区，单行 `id=1`。
- SQL：`UPDATE hot_row_bench SET k = k + 1 WHERE id = 1`。
- 压测方式：临时 Java/JDBC 压测器，每个线程 1 条 JDBC 连接，连接全部建立后同时开始，autocommit 单语句更新。
- OceanBase：`enable_early_lock_release=True`；`_max_elr_dependent_trx_count=1000` 在当前 CE 镜像仍不可设置。

| 组别 | 总更新数 | 成功数 | 错误数 | TPS | 平均耗时 | P50 | P90 | P95 | P99 | Max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 50 并发短跑 | 5000 | 5000 | 0 | 1350.20 | 35ms | 12ms | 75ms | 157ms | 404ms | 996ms |
| 50 并发长跑 | 100000 | 100000 | 0 | 1314.17 | 38ms | 12ms | 83ms | 167ms | 427ms | 1526ms |
| 100 并发 | 50000 | 50000 | 0 | 1610.91 | 61ms | 15ms | 157ms | 321ms | 659ms | 2285ms |

资源观测：

- 50 并发长跑过程中，`mall-oceanbase-ce` 容器 CPU 采样曾达到 `794.80%`，说明纯 DB 基准已经把 OceanBase 打到高 CPU 状态。

本轮结论：

- 本地 OceanBase CE 纯热点行 `UPDATE` 能达到 `1.3k-1.6k TPS`，已经接近文档阶段一图里的 `1.5k` 量级。
- 这说明上一轮 stock-only HTTP 压测的 `70+ QPS` 不是数据库裸热点行上限，而是应用压测链路、连接池预热/排队、MyBatis/Spring 调用、短压测窗口等因素叠加后的结果。
- 下一步如果继续追 1k+ HTTP QPS，应先做 stock-only 应用链路瘦身和预热：连接池预热、关闭 DEBUG 日志、拉长压测时长、去掉 `/actuator/prometheus` 高频抓取干扰，并用同一条 SQL 的专用 Repository/JDBC 路径对比 MyBatis 开销。

2026-07-03 11:07（Asia/Shanghai）stock-only MyBatis 链路低风险压测优化：

- Prometheus `scrape_interval` 与 `evaluation_interval` 从 `2s` 调整为 `15s`，并已重启 `mall-prometheus`。
- 新增 `application-perf.yml`：Hikari `maximum-pool-size=100`、`minimum-idle=100`，开启 stock-only load-test，关闭 DEBUG 日志，保留 MyBatis 执行 SQL。
- 新增启动连接池预热：`mall.seckill.load-test.connection-warmup-enabled=true` 时，并发获取 `connection-warmup-size` 个连接并执行 `SELECT 1`。
- 本轮启动 profile：`oceanbase,perf`，启动日志确认 `Prewarmed 100 datasource connections in 5897 ms`。
- 当前 Windows shell 存在环境变量 `DEBUG=release`，会导致 Spring Boot 输出 DEBUG 日志；压测启动前需要在当前 shell 执行 `Remove-Item Env:DEBUG -ErrorAction SilentlyContinue`，或在启动参数中显式覆盖 `--debug=false`。
- OceanBase 压测 SKU 已重置为大库存：`seckill_sku.stock=1000000, version=0`。

JMeter 短验结果：

- JMeter 结果文件：`target/loadtest/jmeter-seckill-stock-deduct-only-perf-mybatis-20260703-110530.jtl`。
- JMeter HTML 报告：`target/loadtest/jmeter-report-stock-deduct-only-perf-mybatis-20260703-110530`。
- 口径：stock-only、`100` 线程、每线程 `10` 次、共 `1000` 次扣减。

| 组别 | 请求数 | 成功数 | 请求 QPS | P50 | P90 | P95 | P99 | 平均耗时 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| perf + MyBatis 短验 | 1000 | 1000 | 80.65 | 775ms | 1208ms | 1881ms | 2506ms | 807ms |

Prometheus/Grafana 5 分钟窗口分段：

| 组别 | HTTP P95 | HTTP P99 | total P95 | total P99 | update P95 | update P99 | select P95 | select P99 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| perf + MyBatis 短验 | 1563ms | 2145ms | 1393ms | 1872ms | 1378ms | 1775ms | 8ms | 19ms |

本轮结论：

- 短验 QPS 从上一轮约 `75.72` 提升到 `80.65`，平均耗时从约 `1021ms` 降到 `807ms`，但仍没有接近纯 OceanBase 热点行 `1.3k-1.6k TPS`。
- `update` 仍占据主要耗时，说明在保留 Spring MVC + MyBatis + 事务代理 + `UPDATE + SELECT` 的链路下，热点行等待仍会被放大。
- Prometheus 改成 `15s` 后，`1000` 次短压测太短，容易错过 Hikari active/pending 这类瞬时指标；正式对比应跑 `3-5min`，再看稳定窗口。

2026-07-03 12:14（Asia/Shanghai）stock-only 主键扣减路径改造：

- 采用方案 A：外部接口仍使用 `activityId + skuId`，内部在 `requireSku()` 阶段解析并缓存 `seckill_sku.id`。
- `SeckillSku` 领域对象新增 `id` 字段。
- 主链路 `recordDeduction()` 改为使用 `stockId` 执行库存扣减和 `stock/version` 查询。
- stock-only 压测入口首次按 `activityId + skuId` 解析 `stockId`，之后在本地缓存中复用，避免每次压测请求都走二级唯一索引。
- 保留 MyBatis 执行 SQL，不切换 JDBC 快路径。

新旧 SQL 执行计划对比：

| SQL 口径 | 访问对象 | `is_index_back` | 说明 |
| --- | --- | --- | --- |
| `WHERE id = 1` | `TABLE GET seckill_sku` | `false` | 主键直接命中热点库存行 |
| `WHERE activity_id = 1 AND sku_id = 1001` | `TABLE GET seckill_sku(uk_activity_sku)` | `true` | 走二级唯一索引后回表 |

本轮只完成代码和执行计划验证，尚未重新跑 JMeter。下一轮应沿用 `oceanbase,perf` profile 与 `1000000` 大库存基线，复测 stock-only MyBatis QPS 和 `update P95/P99`。

2026-07-03 12:21（Asia/Shanghai）stock-only 主键扣减路径复测：

- 本轮启动 profile：`oceanbase,perf`，启动前清理当前 shell 的 `DEBUG` 环境变量，启动日志确认 `Prewarmed 100 datasource connections in 5584 ms`。
- OceanBase 压测 SKU 在压测前重置为 `seckill_sku.stock=1000000, version=0`，TairString 缓存 key 已删除。
- JMeter 口径：stock-only、`100` 线程、每线程 `10` 次，共 `1000` 次库存扣减。
- JMeter 结果文件：`target/loadtest/jmeter-seckill-stock-deduct-only-pk-mybatis-20260703-122111.jtl`。
- JMeter HTML 报告：`target/loadtest/jmeter-report-stock-deduct-only-pk-mybatis-20260703-122111`。

JMeter 结果：

| 组别 | 请求数 | 成功数 | 请求 QPS | Min | Max | P50 | P90 | P95 | P99 | 平均耗时 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| perf + MyBatis + 主键扣减 | 1000 | 1000 | 82.07 | 19ms | 2768ms | 778ms | 1273ms | 1696ms | 2366ms | 831ms |

Prometheus/Grafana 分段：

| 组别 | HTTP P50 | HTTP P95 | HTTP P99 | total P95 | total P99 | update P95 | update P99 | select P95 | select P99 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| perf + MyBatis + 主键扣减 | 785ms | 1695ms | 2423ms | 1334ms | 1425ms | 1328ms | 1422ms | 8ms | 15ms |

指标说明：

- HTTP 分位来自本轮累计 histogram bucket；由于本轮只有约 `12s`，Prometheus `15s` scrape 下 `rate(...[5m])` 对 HTTP Server Timer 返回 `NaN`，但累计 bucket 的请求数为 `1000`，与 JMeter 对齐。
- `total/update/select` 主动埋点均记录到 `1000` 次；`update` 的 P95/P99 基本等于 `total`，`select` 仍在十几毫秒以内。
- 压测结束时账本校验为 `seckill_sku.stock=999000, version=1000`；记录完成后已恢复为 `stock=1000000, version=0`，并删除 TairString 缓存 key。

本轮结论：

- 相比上一轮 `perf + MyBatis 短验`，QPS 从 `80.65` 小幅提升到 `82.07`，P95 从 `1881ms` 降到 `1696ms`，P99 从 `2506ms` 降到 `2366ms`。
- 主键路径确认消除了二级索引回表，但吞吐没有数量级提升；当前主要耗时仍集中在单热点行 `UPDATE` 的排队与提交路径上。
- 这说明方案 A 是正确的低风险收敛：先把访问路径对齐到文档阶段一的“按库存行主键更新”，但要接近纯 DB `1.3k-1.6k TPS`，还需要继续剥离 Spring MVC/MyBatis/事务代理/JMeter 短窗口等应用链路开销。

2026-07-03 12:38（Asia/Shanghai）stock-only 主键扣减阶梯压测：

- 本轮启动 profile：`oceanbase,perf`，启动前清理当前 shell 的 `DEBUG` 环境变量，启动日志确认 `Prewarmed 100 datasource connections in 5553 ms`。
- JMeter 脚本：`docs/jmeter/seckill-stock-deduct-only.jmx`。
- JMeter 口径：每档独立运行，`loops=10`，`ramp=2`；补充的 `700/1000` 档使用 `ramp=3`。
- 阶梯档位：`25/50/75/100/150/200/300/500/700/1000` 并发。
- 汇总文件：`target/loadtest/jmeter-seckill-stock-deduct-only-step-pk-mybatis-20260703-123800-summary-all.csv`。

JMeter 阶梯结果：

| 并发 | 总请求 | 成功 | 失败率 | 成功 QPS | P50 | P95 | P99 | 相邻 QPS 变化 | 相邻 P95 变化 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 25 | 250 | 250 | 0% | 43.07 | 270ms | 581ms | 649ms | - | - |
| 50 | 500 | 500 | 0% | 71.56 | 416ms | 616ms | 666ms | +28.49 | +35ms |
| 75 | 750 | 750 | 0% | 85.77 | 637ms | 929ms | 989ms | +14.21 | +313ms |
| 100 | 1000 | 1000 | 0% | 95.58 | 725ms | 997ms | 1039ms | +9.81 | +68ms |
| 150 | 1500 | 1500 | 0% | 98.19 | 1100ms | 1920ms | 2015ms | +2.61 | +923ms |
| 200 | 2000 | 2000 | 0% | 110.60 | 1471ms | 1843ms | 1966ms | +12.41 | -77ms |
| 300 | 3000 | 3000 | 0% | 119.43 | 2138ms | 2618ms | 2757ms | +8.83 | +775ms |
| 500 | 5000 | 5000 | 0% | 133.43 | 3411ms | 3987ms | 5474ms | +14.00 | +1369ms |
| 700 | 7000 | 7000 | 0% | 139.68 | 4626ms | 5356ms | 5961ms | +6.25 | +1369ms |
| 1000 | 10000 | 8912 | 10.88% | 131.44 | 6711ms | 8609ms | 9966ms | -8.24 | +3253ms |

1000 并发失败原因：

- 失败样本全部为 JMeter 客户端连接异常：`java.net.BindException: Address already in use: connect`。
- 因此 `1000` 档不能直接视为服务端真实 QPS 上限；它说明当前单机 JMeter 压测端在连续阶梯后已经出现本地连接/端口资源瓶颈。

账本校验：

- 压测结束时 OceanBase `seckill_sku.stock=970088, version=29912`，与 JMeter 成功数 `29912` 对齐。
- 记录完成后已恢复为 `stock=1000000, version=0`，并删除 TairString 缓存 key。

本轮结论：

- 如果只看无错误档位，`25 -> 700` 并发内成功 QPS 仍从 `43.07` 增至 `139.68`，没有出现服务端成功 QPS 绝对下降。
- 第一个明显收益骤降点在 `100 -> 150` 并发：成功 QPS 只增加 `2.61`，但 P95 从 `997ms` 跳到 `1920ms`。如果目标是 P95 约 `1s`，建议把当前链路的有效并发上限定在 `100` 左右。
- 吞吐优先但允许高延迟时，`500 -> 700` 已进入高成本区间：成功 QPS 只增加 `6.25`，P95 却继续增加 `1369ms` 到 `5356ms`。
- 当前单机压测下，`1000` 并发开始出现成功 QPS 下降和 `10.88%` 失败，但直接原因是 JMeter 客户端 `Address already in use`，需要分布式 JMeter 或调整 Windows 端口/TIME_WAIT 参数后再验证服务端是否真的在该档下降。

2026-07-03 13:22（Asia/Shanghai）MyBatis 对照实验：update-only 无事务 vs 事务 UPDATE+SELECT：

- 目的：解释为什么 OceanBase 裸热点行 SQL 能到 `1.3k-1.6k TPS`，但应用 stock-only MyBatis 链路只有百级 QPS。
- 本轮只使用 MyBatis，不切换 JDBC 快路径。
- 新增内部压测入口：
  - `POST /internal/seckill/loadtest/stock-deduct-update-only/{activityId}/{skuId}`：MyBatis `UPDATE` only，无 `@Transactional`，无 `SELECT`。
  - `POST /internal/seckill/loadtest/stock-deduct-tx-update-select/{activityId}/{skuId}`：MyBatis `@Transactional + UPDATE + SELECT`，等价当前 stock-only 事务形态。
- JMeter 脚本：`docs/jmeter/seckill-stock-deduct-only.jmx`，已支持通过 `-Jpath=...` 切换压测路径。
- JMeter 口径：`100` 线程、每线程 `10` 次、`ramp=2`，每组压测前重置 `seckill_sku.stock=1000000, version=0`。
- 汇总文件：`target/loadtest/jmeter-seckill-mybatis-compare-20260703-132207-summary.csv`。

JMeter 结果：

| 组别 | 请求数 | 成功数 | 失败数 | 成功 QPS | 平均耗时 | P50 | P90 | P95 | P99 | Max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| MyBatis update-only 无事务 | 1000 | 1000 | 0 | 130.34 | 272ms | 200ms | 474ms | 993ms | 1318ms | 1343ms |
| MyBatis 事务 UPDATE+SELECT | 1000 | 1000 | 0 | 90.53 | 711ms | 817ms | 1139ms | 1204ms | 1267ms | 1298ms |

Prometheus/Micrometer 结果：

| 组别 | total avg | update avg | total P95 | total P99 | update P95 | update P99 | select P95 | select P99 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| MyBatis update-only 无事务 | 180ms | 179ms | 本轮未出 bucket | 本轮未出 bucket | 本轮未出 bucket | 本轮未出 bucket | 无 SELECT | 无 SELECT |
| MyBatis 事务 UPDATE+SELECT | - | - | 1287ms | 1403ms | 1284ms | 1402ms | 8ms | 13ms |

指标说明：

- update-only 新 Timer 本轮暴露为 summary，能看到 count/sum/max，但没有 histogram bucket；已把 `seckill.stock.deduct.update-only.total` 和 `seckill.stock.deduct.update-only.update` 加入 `management.metrics.distribution.percentiles-histogram`，下一轮启动后 Grafana 可以计算 P95/P99。
- 两组账本都对齐：每组成功扣减 `1000` 次，压测后 `version=1000`，记录完成后均已恢复为 `stock=1000000, version=0`。
- `mall-seckill` 由 `Stop-Process` 停止，因此 Maven `spring-boot:run` 最后显示 `Process terminated with exit code: -1`，这是清理进程导致，不是压测失败。

本轮结论：

- 在同样使用 MyBatis 的前提下，去掉 `@Transactional + SELECT` 后，QPS 从 `90.53` 提升到 `130.34`，提升约 `44%`。
- update-only 的服务端 Timer 平均约 `180ms`，事务 UPDATE+SELECT 的 `update P95/P99` 约 `1284ms/1402ms`，说明事务形态会明显放大热点行等待。
- 这进一步说明：慢点不是 OceanBase 裸 SQL 执行能力，而是应用链路中的事务边界、`UPDATE` 后续 `SELECT`、MyBatis/Spring 调用和短压测并发波峰共同放大了热点行排队。
