# 商城 MCP 工具服务接入执行计划

## 概览

本阶段做最小实现：在当前商城仓库中新增独立 `mall-mcp` 服务，先把商品、购物车、普通订单链路包装成 MCP Tools，并验证服务可编译、可启动、工具规则可单测覆盖。

RAGAgent 代码本阶段不修改。后续接入时只使用现有 `POST /api/react`，不新增 `/api/shopping/chat` 兼容入口；商城前端如果要联调 RAGAgent，需要把导购聊天请求调整到 `/api/react`。

## 本阶段实现

- 新增 Maven 模块 `mall-mcp`，默认端口 `8120`。
- MCP 传输使用 Spring AI MCP Server WebFlux Starter 的 Streamable HTTP。
- MCP endpoint 使用 `/mcp`。
- MCP Server 使用 `type=SYNC`。
- 商城业务接口默认走 Gateway：`http://localhost:8100`。
- 新增内部上下文接口：`POST /internal/mcp/mall/context`。
- 上下文按 `sessionId` 缓存 30 分钟，订单确认缓存 5 分钟。

## MCP Tools

统一返回结构：

```json
{ "ok": true, "code": "OK", "message": "success", "data": {} }
```

已实现工具：

| Tool | 说明 |
| --- | --- |
| `mall_search_products` | 调用 `GET /api/product/search`，无需登录 |
| `mall_get_product_detail` | 调用 `GET /api/product/{skuId}`，无需登录 |
| `mall_add_to_cart` | 需要上下文；先查商品详情，再用真实 `skuName/price` 加购 |
| `mall_view_cart` | 需要上下文；查看当前用户购物车 |
| `mall_prepare_order` | 需要上下文；确认订单并返回 `confirmationId` |
| `mall_create_order` | 需要上下文；校验 `confirmationId` 和 `userConfirmed=true` 后创建订单 |

固定错误码：

```text
OK
AUTH_REQUIRED
NOT_FOUND
STOCK_NOT_ENOUGH
CONFIRMATION_REQUIRED
CONFIRMATION_EXPIRED
MALL_ERROR
```

## 验证步骤

单模块测试：

```powershell
mvn -pl mall-mcp test
```

全工程测试：

```powershell
mvn test
```

启动 MCP 服务：

```powershell
mvn -pl mall-mcp spring-boot:run
```

默认地址：

```text
MCP endpoint: http://localhost:8120/mcp
Context API:  http://localhost:8120/internal/mcp/mall/context
```

## 后续 RAGAgent 接入

- 在 RAGAgent 增加 `mall` MCP Client streamable-http connection。
- 每次进入 `POST /api/react` 前，把当前 `sessionId`、商城 token 或 Basic 登录信息注册到 `mall-mcp` 上下文接口。
- 包装 `mall_*` ToolCallback，强制覆盖注入当前请求 `sessionId`。
- `mall_*` 工具默认可用；WebSearch MCP 仍只受 `webSearchEnabled` 控制。
- BuiltInTools 关于商城的内容去除，只使用 MCP 接入商城。

## 当前约束

- 本阶段不修改 RAGAgent。
- 本阶段不修改商城前端导购页接口。
- `mall-mcp` 第一版不包含秒杀链路。
- 创建订单必须二阶段：先 `mall_prepare_order`，再由用户明确确认后调用 `mall_create_order`。
- 工具方法均为同步返回值。
