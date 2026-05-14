# mall-gateway 网关服务

`mall-gateway` 是系统统一 HTTP 入口，负责路由转发、Token 校验、用户上下文透传和 Sentinel 网关限流接入。外部请求推荐先经过 Docker Nginx 的 `8080` 端口，再转发到本服务 `8100`。

更多整体背景见 [../README.md](../README.md) 和 [../docs/architecture.md](../docs/architecture.md)。

## 模块定位

- 服务名：`mall-gateway`
- 默认端口：`8100`
- Web 类型：Spring WebFlux / Spring Cloud Gateway
- 注册中心：Nacos `localhost:8848`
- Sentinel Dashboard：`localhost:8858`
- 主要依赖：`mall-common`、Spring Cloud Gateway、LoadBalancer、Nacos Discovery、Sentinel

## 核心功能

- 基于 `lb://mall-*` 路由到下游微服务实例。
- 校验 `Authorization: Bearer <token>`，Token 签名逻辑与 `mall-auth` 保持一致。
- 校验成功后向下游写入 `X-User-Id`、`X-Username`。
- 放行登录、商品查询、秒杀活动查询等公开路径。
- Sentinel 网关回退响应统一为业务码 `429`。

当前公开路径由 `GatewayAuthFilter.PUBLIC_PATHS` 控制：

- `/api/auth/login`
- `/api/product`
- `/api/seckill/activities`

其中 `/api/product` 使用前缀匹配，因此商品详情和分类树当前都不要求登录。

## 路由

| 路径 | 目标服务 |
| --- | --- |
| `/api/auth/**` | `lb://mall-auth` |
| `/api/product/**` | `lb://mall-product` |
| `/api/cart/**` | `lb://mall-cart` |
| `/api/order/**` | `lb://mall-order` |
| `/api/seckill/**` | `lb://mall-seckill` |

## 用户上下文

```text
Client
  -> Authorization: Bearer <token>
  -> mall-gateway 校验 Token
  -> 写入 X-User-Id / X-Username
  -> 下游服务 UserContextFilter 写入 UserContext
```

`mall-auth` 和 `mall-gateway` 都依赖 `mall.auth.secret`，两边配置必须一致，否则网关无法校验认证服务签发的 Token。

## 关键代码

- `GatewayApplication`：网关启动入口。
- `GatewayAuthFilter`：全局认证过滤器和用户上下文透传。
- `GatewayTokenServiceImpl`：Token 解析、签名校验和过期校验。
- `application.yml`：路由、Nacos、Sentinel 配置。

## 启动

先启动 Nacos、Sentinel，再运行：

```bash
java -jar target/mall-gateway-0.0.1-SNAPSHOT.jar
```

也可以在根目录编译指定模块：

```bash
mvn -pl mall-gateway -am package -DskipTests
```
