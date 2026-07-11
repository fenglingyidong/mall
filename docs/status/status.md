这些 `status` 不是同一条状态机的重复字段；它们分别描述“库存事实、消息投递、用户结果、正式订单”。当前链路里最关键的是下面四张表。

## 1. `seckill_stock_change_log.status`：库存流水的后台推进状态

```text
NEW → OUTBOXING → OUTBOXED → LEDGER_PROCESSING → APPLIED
                     │
                     └→ OUTBOX_FAILED
```

| 状态 | 含义 | 何时进入 |
|---|---|---|
| `NEW` | 库存变更事实已落库，尚未被后台消费 | 分桶扣减写 `DEDUCT`，或后续释放/调拨写库存变更时 |
| `OUTBOXING` | 秒杀 outbox worker 已 claim，正在为该流水处理建单消息 | `NEW` 被 CAS 抢占 |
| `OUTBOXED` | 已通过订单 outbox 阶段 | `DEDUCT` 已存在对应 `mq_message`；非 `DEDUCT` 流水不建订单消息，也会直接通过此阶段 |
| `OUTBOX_FAILED` | 确定性坏数据，无法构造订单消息 | 当前主要是 `requestId` 为空；不会自动重试 |
| `LEDGER_PROCESSING` | 中心库存账本正在汇总并应用该流水 | 中心账本消费者 claim `OUTBOXED` |
| `APPLIED` | 中心账本已应用完成 | 中心账本批量更新成功 |

当前运行态启用 order-outbox 时，正常路径必须先到 `OUTBOXED`，中心账本才处理。若 outbox 关闭，中心账本兼容旧路径，可直接消费 `NEW`。

注意：`OUTBOXING` 遇到可重试异常不会立即变失败，而是保持该状态；当前超过 60 秒的陈旧记录会被重置为 `NEW` 再试。

## 2. `seckill_stock_snapshot.status`：请求级库存占位状态

```text
REGISTERED ──订单成功──→ CONFIRMED
     │                       │
     ├─无 DEDUCT 事实──→ FAILED
     │
     └─建单失败──→ RELEASING → RELEASED
                             ↑
                  订单关闭/取消 ─┘
```

| 状态 | 含义 |
|---|---|
| `REGISTERED` | 请求登记完成，持有一人一单占位 |
| `CONFIRMED` | 正式订单创建成功，库存扣减被确认 |
| `RELEASING` | 正在执行库存回补的短暂事务中间态 |
| `RELEASED` | 已回补库存，并释放买家占位，可重新抢购 |
| `FAILED` | 未形成扣减事实或入口失败，释放买家占位 |
| `DEDUCTED` | 旧同步链路兼容状态；当前异步入口通常不写它 |

这里有一个容易混淆的点：当前异步入口即使已经产生 `DEDUCT change_log`，snapshot 仍可能是 `REGISTERED`。代码通过“`REGISTERED + 存在 DEDUCT change_log`”判断它可确认或可释放。也就是说，`change_log` 才是“已扣库存”的权威事实。

## 3. `mq_message.status`：可靠消息投递状态

```text
NEW → DISPATCHING → SENT
 │         │
 └─────────┴→ FAILED → 重试后回到 DISPATCHING
```

| 状态 | 含义 |
|---|---|
| `NEW` | 消息已可靠落库，尚未被发送线程领取 |
| `DISPATCHING` | 发送线程已领取，等待 RabbitMQ 发送/confirm |
| `SENT` | RabbitMQ producer confirm 已 ACK；只表示 broker 接收，不表示订单已创建 |
| `FAILED` | 发送异常、confirm nack、路由 returned，或 `DISPATCHING` 超时 |
| `CONSUMED` | 消费者显式标记已消费 |

补偿任务扫描 `NEW` 和 `FAILED` 重投。当前秒杀建单消费者成功 ack 后没有显式将该消息改为 `CONSUMED`，因此建单消息通常停在 `SENT`；这不影响建单幂等，`SENT` 也不应被理解为“用户已成功”。

## 4. `seckill_result.status`：用户查询的最终投影

| 状态 | 用户含义 |
|---|---|
| `PROCESSING` | 已受理，正在等待后台闭环 |
| `SUCCESS` | 正式秒杀订单已创建，携带 `orderSn`；不代表已支付 |
| `FAILED` | 未抢到、库存不足、建单失败或重试耗尽 |
| `CANCELED` / `ORDER_CANCELED` | 已创建的订单后续被取消，库存已回补 |
| `ORDER_CLOSED` | 超时未支付而关闭，库存已回补 |

当前异步入口成功返回 `PROCESSING` 时，`seckill_result` 不一定已有一条 `PROCESSING` 记录；查询不存在的 `requestId` 时，代码默认返回 `PROCESSING`。真正的终态由订单结果消息回写。

补充：`order_info.status` 是正式订单自身状态，独立于秒杀结果：

```text
CREATED → PAID
CREATED → CLOSED / CANCELED
```

秒杀的 `SUCCESS` 对应的是 `order_info.CREATED`，而不是 `PAID`。



这是现有的字段吗？这么多字段是否有必要？


是的，前面列出的 `status` 大多都是现有字段和值；不是我新设计出来的一套字段。

当前已有的状态列包括：

- `seckill_stock_change_log.status`
- `seckill_stock_snapshot.status`
- `mq_message.status`
- `seckill_result.status`
- `order_info.status`

我前面建议的 `outbox_claim_token`、`outbox_claimed_at` 以及两个索引/唯一约束，才是尚未存在的改造项。

这些状态不能简单合并成一个，因为它们分别回答不同问题：

```text
snapshot：这次用户库存占位最终怎样？
change_log：这笔库存变更的后台动作推进到哪一步？
mq_message：消息有没有可靠送达 RabbitMQ？
seckill_result：用户查询时应该看到什么？
order_info：正式订单的支付生命周期是什么？
```

所以“五张表都有状态”是有必要的；它们不是同一份状态的重复拷贝。

但确实有两处可以收敛：

- `snapshot` 的 `DEDUCTED` 是旧同步链路遗留兼容状态。当前异步链路以 `REGISTERED + DEDUCT change_log` 表示已扣库存，后续可以在旧数据和旧路径完全下线后删除该兼容分支。
- `mq_message.CONSUMED` 对发送可靠性并非必要，`SENT` 已表示 RabbitMQ 已确认接收。当前秒杀建单消费者也没有一致地写 `CONSUMED`，它更适合做消费审计，而不应参与发送重试判断。

`change_log` 的六个状态看起来多，是因为它串了两个后台阶段：

```text
NEW → OUTBOXING → OUTBOXED
                         ↓
              LEDGER_PROCESSING → APPLIED
```

在当前“必须先建订单消息、再更新中心账本”的严格顺序下，用一个状态列是合理的，6 个值并不算过度。若未来这两个阶段需要独立重试、独立扩容，再拆成 `outbox_status` 与 `ledger_status` 才有价值；现在拆会增加复杂度。

结论：保留多张表的独立状态，但清理旧的 `DEDUCTED` 兼容语义，并明确 `mq_message.CONSUMED` 的定位即可。
## 2026-07-10 seckill outbox direct drain

- 模块测试通过：`mvn -pl mall-message,mall-seckill -am test`
- 打包通过：`mvn -pl mall-message,mall-seckill -am -DskipTests package`
- 入口压测通过：`target/loadtest/stage3c-current/submit-20260710-184930.jtl`，`18105` 请求，约 `301 req/s`，JMeter 错误 `0%`
- 当前全链路结论：`仅入口通过`
- 首次闭合失败根因：运行库未执行 `sql/migration-v14-seckill-outbox-direct-drain.sql`，日志报 `Unknown column 'outbox_claim_token'`
- 二次复验阻塞：`scripts/loadtest/stage3c/reset-seckill-loadtest.ps1` Redis 批量删除报 `filename extension too long`
- 详细记录：`docs/status/2026-07-10-seckill-outbox-direct-drain-verification.md`
- 后续补充：`scripts/loadtest/stage3c/reset-seckill-loadtest.ps1` 的 Redis 批量删除已修复，真实 reset 已验证通过
- 第二轮 clean rerun：`submit-20260710-193215.jtl`，`14998` 请求，约 `249.5 req/s`，JMeter 错误 `0%`
- 第二轮压测结论：`不通过`；80 秒后仍有 `mall.seckill.order.create.queue ready=629 / unacked=800`，两个分片仍有大量 `REGISTERED`，且 `seckill_result.SUCCESS` 落后于 `seckill_order_count`
