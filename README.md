# B2C 电商后端项目

这是一个用于后端简历展示和面试讲解的 B2C 电商后端项目，核心目标是把“普通下单链路”和“秒杀链路”做完整、做可解释。

## 技术栈

- Java 17
- Spring Boot 3.4.1
- Spring Cloud 2024.0.2
- Spring Cloud Alibaba 2023.0.3.4
- Maven 多模块
- MySQL/OceanBase MySQL 模式 + MyBatis-Plus：商品、评价、优惠券、购物车、订单、秒杀活动、秒杀库存快照、秒杀结果、可靠消息记录与消费幂等
- Redis/TairString：商品缓存、购物车缓存、秒杀版本化库存缓存
- RabbitMQ：Confirm、Return、手动 ACK、延迟关单、死信队列
- Seata TCC：普通订单创建链路库存预留、确认和回滚
- Redisson：秒杀同用户重复提交分布式锁
- Spring Cloud Gateway：统一入口、路由转发、Token 校验和用户上下文透传
- TransmittableThreadLocal：业务服务内用户上下文跨线程池传播
- Nacos Discovery：服务注册与发现
- OpenFeign：订单服务调用商品、购物车内部接口，商品服务调用评价、优惠券内部接口；商品服务额外暴露商品名模糊搜索契约，供其他微服务复用
- Sentinel：网关接入 Sentinel 组件，秒杀入口使用 Sentinel QPS 流控
- Canal Server + Canal Client：监听商品相关表 Binlog，自动失效商品详情缓存
- Nginx：Docker 部署反向代理，对外统一暴露 HTTP 入口
- Docker Compose：Nginx、MySQL、Redis、RabbitMQ、Nacos、Sentinel、Seata、Canal 环境模板

## 模块说明

- `mall-gateway`：Spring Cloud Gateway 统一入口、基于 Nacos 的 `lb://` 路由、Token 校验、用户上下文透传
- `mall-common`：统一响应、异常处理、基于 TransmittableThreadLocal 的用户上下文、布隆过滤器、工具类
- `mall-auth`：登录、Token 签发和校验
- `mall-product`：商品详情、商品名模糊搜索、分类树、库存扣减、Seata TCC 库存预留、CompletableFuture 异步聚合评价和优惠券、TTL 线程池上下文传播、Canal 监听 Binlog 后自动缓存失效
- `mall-review`：商品评价摘要查询
- `mall-coupon`：商品可领取优惠券查询
- `mall-cart`：购物车增删改查、勾选状态、用户隔离，MySQL 持久化并使用 Redis 缓存
- `mall-order`：订单确认、创建、支付、取消、Seata 全局事务、OpenFeign 调用商品/购物车、RabbitMQ 延迟关单、普通订单库存释放、秒杀订单异步创建
- `mall-seckill`：秒杀活动、Sentinel 限流、Redisson 防重复提交锁、交易型数据库秒杀库存扣减、TairString 版本化库存缓存、扣减快照、一人一单、RabbitMQ 异步创建订单
- `mall-message`：RabbitMQ 交换机/队列声明、Confirm/Return 发布器、`mq_message` 可靠消息表、`consume_record` 消费幂等表
- `frontend`：Vue 3 + Vite 演示前端，当前已提供导购 Agent、商品、购物车、订单和秒杀五个可跳转页面；导购页参考 `D:\mycodes\RAGAgent\frontend` demo，支持图文聊天、模型选择、候选商品、对比和加购联动，详细状态见 [frontend/README.md](frontend/README.md)

## 代码分层

各业务微服务统一采用以下包结构：

```text
controller
service
service/impl
mapper
pojo/entity
pojo/dto
pojo/vo
config
```

- `controller`：HTTP 接口入口
- `service`：业务接口
- `service/impl`：业务接口实现
- `mapper`：MyBatis-Plus Mapper、Redis 访问或内部服务客户端
- `pojo/entity`：领域实体或持久化实体
- `pojo/dto`：请求参数对象
- `pojo/vo`：响应视图对象
- `config`：配置类和配置属性

## 快速启动

先启动中间件：

```bash
docker compose up -d nginx mysql redis rabbitmq nacos sentinel seata canal
```

Nginx 容器默认监听本机 `8080`，并把 `/api/**` 反向代理到本机 `mall-gateway` 的 `8100` 端口：

```text
Nginx 入口: http://localhost:8080
Gateway 直连: http://localhost:8100
```

因此启动业务服务后，推荐通过 Nginx 调接口：

```text
http://localhost:8080/api/product/1001
```

当前 `docker-compose.yml` 将 MySQL 容器端口映射到本机 `3307`：

```text
JDBC URL: jdbc:mysql://localhost:3307/mall
username: root
password: root
```

当前商城 Redis 容器端口映射到本机 `6380`，避免与另一个项目占用的 `6379` 冲突：

```text
Redis URL: localhost:6380
```

MySQL 已开启 ROW Binlog，初始化脚本会创建 `canal/canal` 账号并授予 Binlog 订阅权限；Canal Server 默认连接 Docker Compose 内的 `mysql:3306`，容器内端口是 `11111`，映射到本机 `12111`，避免 Windows 环境下 `11111` 附近端口被系统保留。已有旧 MySQL 容器时，需要重建本地演示容器让 Binlog 参数生效：

```powershell
docker compose up -d --force-recreate mysql canal
```

重建本地 MySQL 容器会清空容器内数据，演示库可以重新执行下方 `schema.sql` 导入。

如果容器已经初始化过，需要手动重新导入表结构和种子数据：

```powershell
docker compose exec -T -e MYSQL_PWD=root mysql mysql --default-character-set=utf8mb4 -uroot mall -e "source /docker-entrypoint-initdb.d/schema.sql"
```

如果是从旧版本库表升级到当前 MyBatis-Plus + 可靠消息表结构，执行迁移脚本：

```powershell
docker compose exec -T -e MYSQL_PWD=root mysql mysql --default-character-set=utf8mb4 -uroot mall -e "source /docker-entrypoint-initdb.d/migration-v2-mybatis-message.sql"
```

如果旧库的 `cart_item` 表缺少 `sku_name`、`price` 字段，再执行购物车 MySQL 化迁移脚本：
```powershell
docker compose exec -T -e MYSQL_PWD=root mysql mysql --default-character-set=utf8mb4 -uroot mall -e "source /docker-entrypoint-initdb.d/migration-v3-cart-mysql.sql"
```

如果旧库缺少商品详情回源和缓存失效反查所需索引，再执行商品索引迁移脚本：
```powershell
docker compose exec -T -e MYSQL_PWD=root mysql mysql --default-character-set=utf8mb4 -uroot mall -e "source /docker-entrypoint-initdb.d/migration-v4-product-indexes.sql"
```

如果旧库缺少评价摘要和商品优惠券表，再执行评价/优惠券迁移脚本：
```powershell
docker compose exec -T -e MYSQL_PWD=root mysql mysql --default-character-set=utf8mb4 -uroot mall -e "source /docker-entrypoint-initdb.d/migration-v5-review-coupon.sql"
```

如果旧库缺少 Seata TCC 防悬挂/幂等所需表，再执行 Seata 迁移脚本：
```powershell
docker compose exec -T -e MYSQL_PWD=root mysql mysql --default-character-set=utf8mb4 -uroot mall -e "source /docker-entrypoint-initdb.d/migration-v6-seata-tcc.sql"
```

注意：迁移脚本会重建 `mq_message`、`consume_record`、`seckill_activity`、`seckill_sku`、`seckill_result`，用于本地演示库升级。导入中文 SQL 时不要用 `Get-Content ... | docker compose exec ...` 管道方式，避免 PowerShell 编码把中文写坏。商品与类目种子也要用 `utf8mb4` 重新导入；如果库里已经出现 `????`，说明旧数据在错误会话里写坏了，需要清表后重灌。

RabbitMQ 管理后台：

```text
http://localhost:15672
username: mall
password: mall
```

Nacos 控制台：

```text
http://localhost:8848/nacos
username: nacos
password: nacos
```

Nacos 2.x 除了 HTTP 控制台端口 `8848`，服务注册发现还会使用 gRPC 端口 `9848`；`9849` 预留给服务端 gRPC 通信。Docker Compose 已同时暴露这两个端口，如果修改过 Nacos 端口映射，需要重建容器：

```powershell
docker compose up -d --force-recreate nacos
```

Sentinel 控制台：

```text
http://localhost:8858
username: sentinel
password: sentinel
```

Seata Server 端口：

```text
TC 通信端口: localhost:8091
控制台端口: http://localhost:7091
```

编译：

```bash
mvn clean package -DskipTests
```

启动真正需要运行的服务：
java -jar mall-auth/target/mall-auth-0.0.1-SNAPSHOT.jar
java -jar mall-review/target/mall-review-0.0.1-SNAPSHOT.jar
java -jar mall-coupon/target/mall-coupon-0.0.1-SNAPSHOT.jar
java -jar mall-product/target/mall-product-0.0.1-SNAPSHOT.jar
java -jar mall-cart/target/mall-cart-0.0.1-SNAPSHOT.jar
java -jar mall-order/target/mall-order-0.0.1-SNAPSHOT.jar
java -jar mall-seckill/target/mall-seckill-0.0.1-SNAPSHOT.jar
java -jar mall-gateway/target/mall-gateway-0.0.1-SNAPSHOT.jar

建议每个服务开一个 PowerShell 窗口。mall-common 不启动，它只是公共依赖包；mall-message 当前也是消息公共模块/依赖包，不是独立业务服务入口。mall-mcp 是可选 MCP 服务，需要时再启动：
java -jar mall-mcp/target/mall-mcp-0.0.1-SNAPSHOT.jar
默认端口：
gateway  8100
auth     8101
product  8102
cart     8103
order    8104
seckill  8105
review   8106
coupon   8107
mall-mcp 8120

## 核心接口

登录：

```http
POST /api/auth/login
```

商品：

```http
GET /api/product/search
GET /api/product/{skuId}
GET /api/product/category/tree
```

购物车：

```http
GET    /api/cart
POST   /api/cart/items
PUT    /api/cart/items/{skuId}
DELETE /api/cart/items/{skuId}
```

订单：

```http
POST /api/order/confirm
POST /api/order/create
GET  /api/order/{orderSn}
POST /api/order/{orderSn}/pay
POST /api/order/{orderSn}/cancel
```

秒杀：

```http
GET  /api/seckill/activities
POST /api/seckill/{activityId}/{skuId}
GET  /api/seckill/result/{requestId}
```

其中商品搜索支持：

```text
keyword, categoryId, brand, minPrice, maxPrice, limit
```

另有商品名模糊搜索内网接口 `GET /internal/product/search/name?name=xxx&limit=10`，由 `mall-common` 中的 `ProductSearchClient` 对外提供给其他微服务复用。用于给导购页、订单侧、购物车侧等只读调用场景召回 SKU 级商品候选。除登录、商品查询、秒杀活动查询外，通过网关访问时需要带：

```http
Authorization: Bearer <token>
```

Windows PowerShell 下建议使用 `Invoke-RestMethod` 和 `ConvertTo-Json` 调接口，不要直接把 JSON 写在 `curl.exe -d '{"skuId":1001}'` 里，否则双引号可能被 PowerShell/curl 参数解析处理掉，服务端会收到非法 JSON。完整示例见 [docs/api-examples.md](docs/api-examples.md)。

## RabbitMQ 设计

`mall-message` 当前已经接入 RabbitMQ，不再是本地消息模拟。

交换机：

| 名称 | 类型 | 说明 |
| --- | --- | --- |
| `mall.exchange` | direct | 普通业务交换机 |
| `mall.delay.exchange` | direct | 延迟消息入口 |
| `mall.dlx` | direct | 死信交换机 |

队列：

| 队列 | Routing Key | 说明 |
| --- | --- | --- |
| `mall.order.close.delay.queue` | `order.close.delay` | 延迟关单入口队列，消息过期后转发到关单队列 |
| `mall.order.close.queue` | `order.close` | 关单消费队列，失败后进入死信队列 |
| `mall.order.close.dlq` | `order.close.dlq` | 关单死信队列 |
| `mall.seckill.order.create.queue` | `seckill.order.create` | 秒杀异步下单队列 |
| `mall.seckill.order.create.dlq` | `seckill.order.create.dlq` | 秒杀下单死信队列 |
| `mall.seckill.order.result.queue` | `seckill.order.result` | 秒杀订单创建结果队列 |
| `mall.seckill.order.result.dlq` | `seckill.order.result.dlq` | 秒杀结果死信队列 |

可靠投递：

- `ReliableMessagePublisher` 发送消息前写入 MySQL `mq_message` 表。
- 开启 `publisher-confirm-type: correlated`，Broker ACK 后标记 `SENT`，NACK 后标记 `FAILED`。
- 开启 `publisher-returns: true` 和 `mandatory`，路由失败时通过 ReturnCallback 标记失败。
- 消息设置持久化投递模式。
- `MessageCompensationJob` 定时扫描 `NEW/FAILED` 消息并保留原路由、原延迟时间重新投递，支持服务重启后的补偿。

可靠消费：

- `mall-order` 使用 `@RabbitListener` 消费关单消息和秒杀下单消息。
- `mall-seckill` 使用 `@RabbitListener` 消费秒杀结果消息，确认或释放扣减快照，并把 `requestId` 更新为 `SUCCESS/FAILED`。
- 消费成功后手动 `basicAck`。
- 消费异常时 `basicNack(requeue=false)`，消息进入对应死信队列。
- 秒杀下单消费基于 `consume_record(message_id)` 唯一索引做消费幂等。
- 秒杀订单表通过 `seckill_order(activity_id, user_id)` 唯一索引保证一人一单。
- 延迟关单基于订单状态机保证幂等，只有 `CREATED` 状态允许关闭。

## 当前完成进度

- 已完成 MyBatis-Plus + MySQL 接入：`mall-product` 查询商品和库存，`mall-review` 持久化评价摘要，`mall-coupon` 持久化商品优惠券，`mall-cart` 持久化购物车，`mall-order` 持久化订单、订单明细和秒杀订单，`mall-seckill` 持久化秒杀活动、秒杀库存、扣减快照和查询结果，`mall-message` 持久化可靠消息和消费记录。
- 已启用 `sql/schema.sql`：Docker Compose 将本地 `sql` 目录挂载到 MySQL 初始化目录，首次初始化可自动建表和导入种子数据，旧库可手动执行迁移脚本。
- 已将 `mall-message` 的内存消息记录替换为数据库 `mq_message` 表，并通过 `consume_record` 表、Confirm/Return、手动 ACK、死信队列和补偿任务串起可靠消息闭环。
- 已接入 Spring Cloud Gateway、Nacos Discovery、OpenFeign、Sentinel 和 Seata：服务启动后注册到 Nacos，网关通过 `lb://mall-*` 转发请求，订单服务通过 OpenFeign 调用商品和购物车，秒杀提交使用 Sentinel 资源规则限流，普通订单创建通过 Seata TCC 协调商品库存预留。
- 已接入 Canal Server：MySQL 开启 ROW Binlog，`mall-product` 内置 Canal Client 订阅 `sku`、`sku_stock`、`spu`、`brand`、`category` 表变更，解析受影响的 SKU 后自动清理本地缓存和 Redis 缓存。
- 已补充商品模糊搜索契约：`mall-product` 暴露 `/internal/product/search/name`，`mall-common` 提供 `ProductSearchClient` 和响应 DTO，其他微服务可直接注入调用。
- 已接入 Redisson：秒杀提交按 `activityId + skuId + userId` 加分布式锁，默认使用 watchdog 自动续约，控制同一用户重复提交；秒杀库存扣减已前移到 `seckill_sku.stock/version` 事务，`seckill_stock_snapshot` 负责扣减账本，TairString 仅作为版本化库存缓存。

## 设计重点

网关服务使用 Spring Cloud Gateway 官方组件，不再维护手写代理 Controller。`GatewayAuthFilter` 负责校验 Token，并向下游透传 `X-User-Id`、`X-Username`；路由通过 Nacos 服务名和 `lb://` 完成负载均衡转发。下游业务服务由 `UserContextFilter` 将请求头写入 `UserContext`，请求结束后清理，避免 Tomcat 线程复用造成用户上下文串号。

各业务服务接入 Nacos Discovery。启动 `mall-auth`、`mall-product`、`mall-review`、`mall-coupon`、`mall-cart`、`mall-order`、`mall-seckill` 后，可以在 Nacos 服务列表中看到对应实例。

订单服务使用 OpenFeign 声明式调用 `mall-product` 和 `mall-cart` 的内部接口。商品详情查询保留 fallback 便于本地演示；库存扣减/释放 fallback 会返回失败，避免商品服务不可用时误创建订单。

普通订单创建使用 Seata TCC：`mall-order` 以 `@GlobalTransactional` 开启全局事务，调用 `mall-product` 库存接口时透传 XID；商品服务在全局事务内执行 TCC Try，把 `sku_stock.stock` 转入 `locked_stock`，全局提交时 Confirm 扣减锁定库存，全局回滚时 Cancel 释放锁定库存。TCC Fence 依赖 `tcc_fence_log` 防止空回滚、业务悬挂和重复二阶段调用；普通订单取消或超时关单仍通过库存释放接口把已售库存加回。

商品详情回源使用规范化商品表，不维护冗余详情宽表；当前通过 `sku`、`spu`、`brand`、`category`、`sku_stock` 一次 JOIN 查询商品核心主体，再单独查询同 SPU 的 SKU 选项。评价摘要和可领取优惠券来自独立服务，`mall-product` 使用 `CompletableFuture` 并行调用 `mall-review`、`mall-coupon`，并设置短超时和默认降级结果，避免展示页被非核心信息拖慢。商品详情异步线程池通过 `TtlExecutors` 包装，配合 `UserContext` 中的 `TransmittableThreadLocal` 将请求用户上下文传递到异步任务。

商品服务通过 MyBatis-Plus 查询 MySQL。商品核心信息采用本地缓存 + Redis 缓存；评价和优惠券每次详情请求异步聚合，不写入商品 SKU 级缓存。布隆过滤器用于过滤明显不存在的 SKU，缓存未命中时通过互斥锁避免热点 Key 击穿。

购物车服务通过 MyBatis-Plus 持久化 `cart_item` 表，Redis Hash `cart:{userId}` 作为读缓存和写后同步缓存；Redis 不可用时仍以 MySQL 为准，不再使用 JVM 内存存储购物车。

Canal 缓存一致性链路：MySQL ROW Binlog -> Canal Server -> `mall-product` Canal Client -> 解析表变更影响的 `skuId` -> 调用商品缓存失效逻辑。`sku` 和 `sku_stock` 变更直接失效对应 SKU；`spu`、`brand`、`category` 变更会反查关联 SKU 后批量失效。`/internal/product/cache/invalidate/{skuId}` 仍保留为手动失效接口。

订单服务通过 MyBatis-Plus 持久化 `order_info`、`order_item`、`seckill_order`。订单创建后发送 RabbitMQ 延迟关单消息，消息先写入 `mq_message`，再进入 `mall.order.close.delay.queue`，到期后通过死信转发到 `mall.order.close.queue`，订单服务消费后只关闭 `CREATED` 状态订单；普通订单释放商品库存，秒杀订单不释放商品库存。

秒杀链路使用 Sentinel 对 `seckill-submit` 资源做 QPS 限流，Redisson 对同一用户同一活动 SKU 的重复提交加锁并默认使用 watchdog 自动续约；提交事务内条件扣减 `seckill_sku.stock` 并递增 `version`，同时写入 `seckill_stock_snapshot(DEDUCTED)` 和 `seckill_result(PROCESSING)`。TairString 只保存 `stock/version` 读缓存，用于已售罄快速失败和低版本覆盖保护；扣减成功后发送 RabbitMQ 下单消息，由订单服务异步创建秒杀订单。订单创建成功后发送秒杀结果消息，秒杀服务消费后确认快照并把查询结果从 `PROCESSING` 更新为 `SUCCESS`，订单创建失败时按快照回补秒杀库存、递增版本、刷新 TairString 并更新为 `FAILED`。

## 测试

运行测试：

```bash
mvn test
```

已覆盖：

- 布隆过滤器基础命中与拦截
- 订单状态机流转
- 秒杀活动时间窗口判断

压测方案见 [docs/performance-testing.md](docs/performance-testing.md)。

## 后续增强

当前版本优先保证业务链路可跑、代码可读、面试可讲。后续可以按以下方向增强：

- 使用 JMeter 或 wrk 输出真实压测报告

## mall-mcp MCP 工具服务

当前新增 `mall-mcp` 独立模块，用于把商城商品、购物车和普通订单链路包装成 MCP Tools，供后续导购 Agent 调用。详细执行计划见 [docs/mall-mcp-implementation-plan.md](docs/mall-mcp-implementation-plan.md)，模块状态见 [mall-mcp/README.md](mall-mcp/README.md)。

默认配置：

```text
服务端口: 8120
MCP endpoint: http://localhost:8120/mcp
商城网关: http://localhost:8100
```

启动与验证：

```powershell
mvn -pl mall-mcp test
mvn -pl mall-mcp spring-boot:run
```

当前实现的 MCP Tools：

```text
mall_search_products
mall_get_product_detail
mall_add_to_cart
mall_view_cart
mall_prepare_order
mall_create_order
```

当前约束：

- RAGAgent 本阶段未改动。
- 后续 RAGAgent 接入只使用现有 `POST /api/react`，不新增 `/api/shopping/chat`。
- 本阶段不修改商城前端导购页接口；后续联调时再把前端导购请求调整到 `/api/react`。
- 创建普通订单必须先确认订单，再由用户明确确认后创建。
