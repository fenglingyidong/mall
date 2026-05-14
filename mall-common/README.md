# mall-common 公共模块

`mall-common` 是各业务服务共享的基础能力模块，不是独立启动的微服务。它提供统一响应、统一异常处理、用户上下文、通用模型和工具类，减少业务模块里的重复代码。

更多整体背景见 [../README.md](../README.md) 和 [../docs/architecture.md](../docs/architecture.md)。

## 模块定位

- Maven artifact：`mall-common`
- 是否独立启动：否
- 主要依赖：Spring Web、Validation、TransmittableThreadLocal
- 被依赖方：`mall-auth`、`mall-product`、`mall-review`、`mall-coupon`、`mall-cart`、`mall-order`、`mall-seckill`、`mall-gateway`、`mall-message`

## 核心能力

- `ApiResponse<T>`：统一响应结构，成功时 `code=0`。
- `BusinessException`：业务异常，携带业务错误码。
- `GlobalExceptionHandler`：统一处理业务异常、参数校验异常和未知异常。
- `UserContext`：基于 `TransmittableThreadLocal` 保存当前请求用户。
- `UserContextFilter`：从 `X-User-Id`、`X-Username` 请求头写入用户上下文，并在请求结束后清理。
- `OrderStatus`：订单状态枚举，供订单状态机复用。
- `SkuSnapshot`：商品快照模型。
- `BloomFilter`：简单布隆过滤器，商品详情查询用来过滤明显不存在的 SKU。
- `JsonUtils`、`OrderNoGenerator`：JSON 读写和订单号生成工具。

## 用户上下文链路

```text
mall-gateway
  -> 校验 Authorization Token
  -> 写入 X-User-Id / X-Username
  -> 下游 Servlet 服务的 UserContextFilter 写入 UserContext
  -> 业务代码通过 UserContext.currentUserIdOrDefault(...) 读取用户
  -> 请求结束后清理 ThreadLocal
```

`mall-product` 的异步商品详情聚合线程池使用 TTL 包装，因此 `UserContext` 可以跨线程池传递。

## 使用约定

- Controller 返回值优先使用 `ApiResponse.success(...)`。
- 业务失败优先抛 `BusinessException`，不要在每个 Controller 里重复拼响应。
- 读取当前用户优先走 `UserContext`，不要在业务代码里重复解析请求头。
- 新增公共工具时保持轻量，只有多个模块复用时再放入本模块。

## 编译

在根目录编译依赖它的模块时会自动编译 `mall-common`：

```bash
mvn -pl mall-order -am package -DskipTests
```

也可以单独编译：

```bash
mvn -pl mall-common package -DskipTests
```
