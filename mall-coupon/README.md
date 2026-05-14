# mall-coupon 优惠券服务

`mall-coupon` 负责按 SKU 查询商品可领取优惠券。它当前服务于商品详情页，由 `mall-product` 通过 OpenFeign 调用，并作为商品详情非核心展示信息的一部分异步聚合。

更多整体背景见 [../README.md](../README.md) 和 [../docs/architecture.md](../docs/architecture.md)。

## 模块定位

- 服务名：`mall-coupon`
- 默认端口：`8107`
- 注册中心：Nacos `localhost:8848`
- 数据库：MySQL `product_coupon`
- 主要依赖：`mall-common`、Spring Web、MyBatis-Plus、MySQL、Nacos Discovery
- 主要调用方：`mall-product`

## 核心功能

- 查询指定 SKU 当前可用、可领取的优惠券。
- 返回优惠券标题、门槛金额、优惠金额、过期时间等展示信息。
- 接收可选的 `X-User-Id` 请求头，为后续按用户过滤优惠券预留入口。

当前实现主要用于本地演示商品详情页聚合能力，`userId` 参数暂未参与过滤。

## 接口

内部接口：

| 方法 | 路径 | 调用方 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/internal/coupon/sku/{skuId}/available` | `mall-product` | 查询 SKU 可领取优惠券 |

请求头：

```http
X-User-Id: 1
```

## 数据

- MySQL 表：`product_coupon`
- 常用索引：`idx_product_coupon_sku`、`idx_product_coupon_expire`
- 查询入口：`CouponRepository.available(skuId)`

## 关键代码

- `CouponController`：优惠券内部接口。
- `CouponServiceImpl`：优惠券查询业务。
- `CouponRepository`：优惠券持久化查询。
- `ProductCouponMapper`、`ProductCouponEntity`：MyBatis-Plus 表映射。

## 启动

先启动 Nacos、MySQL，再运行：

```bash
java -jar target/mall-coupon-0.0.1-SNAPSHOT.jar
```

也可以在根目录编译指定模块：

```bash
mvn -pl mall-coupon -am package -DskipTests
```
