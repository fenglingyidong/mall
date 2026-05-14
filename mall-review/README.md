# mall-review 评价服务

`mall-review` 负责查询商品评价摘要。它当前服务于商品详情页，由 `mall-product` 通过 OpenFeign 调用，并作为商品详情非核心展示信息的一部分异步聚合。

更多整体背景见 [../README.md](../README.md) 和 [../docs/architecture.md](../docs/architecture.md)。

## 模块定位

- 服务名：`mall-review`
- 默认端口：`8106`
- 注册中心：Nacos `localhost:8848`
- 数据库：MySQL `review_summary`
- 主要依赖：`mall-common`、Spring Web、MyBatis-Plus、MySQL、Nacos Discovery
- 主要调用方：`mall-product`

## 核心功能

- 根据 `skuId` 查询评价摘要。
- 返回平均评分、评价数、好评率、最近一条评价等展示信息。
- 当商品详情服务聚合评价失败或超时时，由 `mall-product` 使用空评价摘要降级。

## 接口

内部接口：

| 方法 | 路径 | 调用方 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/internal/review/summary/{skuId}` | `mall-product` | 查询 SKU 评价摘要 |

## 数据

- MySQL 表：`review_summary`
- 主键：`sku_id`
- 查询入口：`ReviewRepository.summary(skuId)`

## 关键代码

- `ReviewController`：评价摘要内部接口。
- `ReviewServiceImpl`：评价查询业务。
- `ReviewRepository`：评价持久化查询。
- `ReviewSummaryMapper`、`ReviewSummaryEntity`：MyBatis-Plus 表映射。

## 启动

先启动 Nacos、MySQL，再运行：

```bash
java -jar target/mall-review-0.0.1-SNAPSHOT.jar
```

也可以在根目录编译指定模块：

```bash
mvn -pl mall-review -am package -DskipTests
```
