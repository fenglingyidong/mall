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
