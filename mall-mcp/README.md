# mall-mcp

`mall-mcp` 是商城 MCP 工具服务，负责把商品搜索、商品详情、加购物车、查购物车、订单确认和普通下单包装成 MCP Tools，供后续 RAGAgent 调用。

## 当前状态

- 服务端口：`8120`
- MCP endpoint：`/mcp`
- 默认商城网关：`http://localhost:8100`
- MCP 传输：Streamable HTTP
- MCP Server 类型：`SYNC`
- 上下文与订单确认缓存：Caffeine 本地 TTL 缓存
- RAGAgent 暂未接入；后续只接现有 `POST /api/react`

## 配置

```yaml
server:
  port: ${MALL_MCP_PORT:8120}

mall:
  mcp:
    mall-base-url: ${MALL_BASE_URL:http://localhost:8100}
    context-secret: ${MCP_CONTEXT_SECRET:mall-mcp-dev-secret}
    context-ttl: 30m
    confirmation-ttl: 5m
    request-timeout: 10s
```

## 内部上下文接口

```http
POST /internal/mcp/mall/context
X-Mcp-Context-Secret: <secret>
```

Body：

```json
{
  "sessionId": "shopping-demo",
  "userId": "10001",
  "mallToken": "Bearer xxx",
  "mallUsername": "alice",
  "mallPassword": "demo123"
}
```

购物车和订单工具必须能通过 `sessionId` 找到有效上下文。若 token 缺失但用户名密码存在，服务会登录商城并缓存 token。

上下文缓存由 Caffeine 按 `context-ttl` 自动过期，避免长期运行时保留失效会话。

## 工具列表

| Tool | 说明 |
| --- | --- |
| `mall_search_products` | 搜索商品 |
| `mall_get_product_detail` | 查询 SKU 详情 |
| `mall_add_to_cart` | 加购物车，服务端补真实商品名和价格 |
| `mall_view_cart` | 查询购物车 |
| `mall_prepare_order` | 确认订单并返回 `confirmationId` |
| `mall_create_order` | 二次确认后创建普通订单 |

## 验证

```powershell
cd d:\mycodes\mallbackend
mvn -pl mall-mcp test
mvn -pl mall-mcp spring-boot:run
```

成功启动后，MCP 服务监听：

```text
http://localhost:8120/mcp
```

## 现存约束

- 不向模型暴露 token 或密码。
- `mall_add_to_cart` 不信任模型传入价格，始终以商品详情接口返回为准。
- `mall_create_order` 必须先有未过期的 `confirmationId`，并且 `userConfirmed=true`。
- 创建订单前会重新确认订单，商品项、数量、单价、金额、总金额变化时要求重新确认。
- 订单确认按 `confirmation-ttl` 判定是否有效；过期后保留短暂窗口用于返回 `CONFIRMATION_EXPIRED`，随后由 Caffeine 自动清理。
- 商城 Gateway 调用集中在 `WebClientMallGatewayClient`，通过统一的 `get/post/authorizedGet/authorizedPost` 方法处理 WebClient 请求。
- 不包含秒杀链路，秒杀必须通过用户自己去秒杀页面执行。
