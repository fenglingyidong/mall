# mall-cart 购物车服务

`mall-cart` 负责购物车增删改查、选中状态维护和按用户隔离购物车数据。购物车以 MySQL `cart_item` 为准，Redis Hash 只作为读缓存和写后同步缓存。

更多整体背景见 [../README.md](../README.md) 和 [../docs/architecture.md](../docs/architecture.md)。

## 模块定位

- 服务名：`mall-cart`
- 默认端口：`8103`
- 注册中心：Nacos `localhost:8848`
- 数据库：MySQL `cart_item`
- 缓存：Redis Hash `cart:{userId}`
- 主要依赖：`mall-common`、Spring Web、Validation、MyBatis-Plus、Redis、Nacos Discovery

## 核心功能

- 查询当前用户购物车列表。
- 添加 SKU 到购物车；已存在时累加数量。
- 更新购物车项数量和勾选状态。
- 删除购物车项。
- 为订单服务提供内部接口：查询当前用户已勾选商品、创建订单后清空已勾选商品。

用户身份来自 `mall-common` 的 `UserContext`。通过网关访问时，`mall-gateway` 会把 Token 解析后的 `X-User-Id`、`X-Username` 透传给本服务。

## 接口

对外接口：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/cart` | 查询购物车列表 |
| `POST` | `/api/cart/items` | 添加购物车商品 |
| `PUT` | `/api/cart/items/{skuId}` | 更新数量或勾选状态 |
| `DELETE` | `/api/cart/items/{skuId}` | 删除购物车商品 |

内部接口：

| 方法 | 路径 | 调用方 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/internal/cart/selected` | `mall-order` | 查询用户已勾选商品 |
| `POST` | `/internal/cart/selected/clear` | `mall-order` | 清空用户已勾选商品 |

## 数据与缓存

- MySQL 表：`cart_item`
- 唯一约束：`uk_cart_user_sku(user_id, sku_id)`
- Redis Key：`cart:{userId}`
- Redis Field：`skuId`
- Redis 缓存只做加速，Redis 不可用时仍回源 MySQL。

## 关键代码

- `CartController`：购物车 HTTP 接口。
- `CartServiceImpl`：购物车业务编排。
- `CartMapper`：MySQL 与 Redis 缓存组合访问。
- `CartItemMapper`：MyBatis-Plus Mapper，包含 `ON DUPLICATE KEY UPDATE` 写入逻辑。

## 启动

先启动 Nacos、MySQL、Redis，再运行：

```bash
java -jar target/mall-cart-0.0.1-SNAPSHOT.jar
```

也可以在根目录编译指定模块：

```bash
mvn -pl mall-cart -am package -DskipTests
```
