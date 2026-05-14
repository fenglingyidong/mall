# mall-product 商品服务

`mall-product` 是商品详情、分类树和库存能力的核心服务。它通过 MySQL 构建商品主体信息，通过本地缓存 + Redis 缓存加速商品详情，并异步聚合评价和优惠券；订单创建时，它还承担库存扣减、库存释放和 Seata TCC 库存预留。

更多整体背景见 [../README.md](../README.md) 和 [../docs/architecture.md](../docs/architecture.md)。

## 模块定位

- 服务名：`mall-product`
- 默认端口：`8102`
- 注册中心：Nacos `localhost:8848`
- Seata TC：`localhost:8091`
- 数据库：`sku`、`spu`、`brand`、`category`、`sku_stock`
- 缓存：本地缓存、Redis `product:detail:{skuId}`
- Canal：订阅 `sku`、`sku_stock`、`spu`、`brand`、`category` 表变更
- 主要依赖：`mall-common`、Spring Web、OpenFeign、MyBatis-Plus、Redis、Nacos、Seata、Canal、TransmittableThreadLocal

## 核心功能

- 查询商品详情：商品主体、同 SPU SKU 选项、评价摘要、可领取优惠券。
- 查询分类树。
- 提供内部商品详情接口给订单服务。
- 提供库存扣减和库存释放接口。
- 在 Seata 全局事务内执行库存 TCC Try/Confirm/Cancel。
- 使用布隆过滤器过滤明显不存在的 SKU。
- 使用 JVM 互斥锁减少热点 SKU 缓存击穿。
- 监听 Canal Binlog 变更，自动失效本地缓存和 Redis 缓存。

## 接口

对外接口：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/product/{skuId}` | 查询商品详情 |
| `GET` | `/api/product/category/tree` | 查询分类树 |

内部接口：

| 方法 | 路径 | 调用方 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/internal/product/sku/{skuId}` | `mall-order` | 查询商品详情 |
| `POST` | `/internal/product/stock/deduct` | `mall-order` | 扣减库存或进入 TCC Try |
| `POST` | `/internal/product/stock/release` | `mall-order` | 释放库存 |
| `POST` | `/internal/product/cache/invalidate/{skuId}` | 内部联调/Canal 链路 | 手动失效商品缓存 |

## 服务调用

- 调用 `mall-review` 的 `/internal/review/summary/{skuId}` 查询评价摘要。
- 调用 `mall-coupon` 的 `/internal/coupon/sku/{skuId}/available` 查询可领取优惠券。

商品详情中的评价和优惠券通过 `CompletableFuture` 并行获取，超时或异常时降级为空结果，避免非核心展示信息拖慢商品主链路。异步线程池由 `TtlExecutors` 包装，可传递 `UserContext`。

## 缓存与一致性

```text
查询商品详情
  -> 布隆过滤器判断 SKU 是否可能存在
  -> 本地缓存
  -> Redis product:detail:{skuId}
  -> MySQL JOIN 回源
  -> 写入本地缓存和 Redis
```

商品表变更后：

```text
MySQL ROW Binlog
  -> Canal Server
  -> mall-product Canal Client
  -> 定位受影响 skuId
  -> ProductService.invalidate(skuId)
  -> 删除本地缓存和 Redis 缓存
```

## 库存事务

- 非 Seata 全局事务：直接条件扣减 `sku_stock.stock`。
- Seata 全局事务内：
  - Try：`stock` 转入 `locked_stock`。
  - Confirm：扣减 `locked_stock`。
  - Cancel：释放 `locked_stock` 回 `stock`。
- TCC Fence 依赖 `tcc_fence_log` 处理空回滚、悬挂和重复二阶段调用。

## 关键代码

- `ProductController`：商品和库存接口。
- `ProductServiceImpl`：商品详情查询、异步聚合、库存入口。
- `ProductCache`：本地缓存 + Redis 缓存。
- `StockTccActionImpl`：Seata TCC 库存动作。
- `CanalProductCacheInvalidationListener`：Canal Binlog 监听和缓存失效。
- `ProductRepository`：商品详情回源、库存 SQL 编排。

## 启动

先启动 Nacos、MySQL、Redis、Seata、Canal，并确保 `mall-review`、`mall-coupon` 可用，再运行：

```bash
java -jar target/mall-product-0.0.1-SNAPSHOT.jar
```

也可以在根目录编译指定模块：

```bash
mvn -pl mall-product -am package -DskipTests
```
