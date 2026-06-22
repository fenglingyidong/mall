# Mall Frontend

这是 `mallbackend` 的本地演示前端，使用 Vue 3、Vite、Element Plus 和 Axios 构建。当前页面已经从单页接口控制台升级为带导航的商城工作台，覆盖导购 Agent、商品、购物车、订单和秒杀链路。

## 运行方式

先在仓库根目录启动后端依赖和业务服务。当前推荐 API 入口是 Docker Nginx：

```text
浏览器 -> Vite dev server -> /api -> http://localhost:8080 -> Nginx -> Gateway 8100 -> 各微服务
```

安装依赖并启动前端：

```powershell
cd frontend
npm.cmd install
npm.cmd run dev
```

默认访问地址：

```text
http://localhost:5173
```

构建：

```powershell
npm.cmd run build
```

## 页面索引

- `#/agent`：导购 Agent 工作台，参考 `D:\mycodes\RAGAgent\frontend` demo 的主要功能，提供导购聊天、图片输入、模型选择、联网搜索开关、候选商品、商品对比和加购入口。
- `#/products`：商品搜索和 SKU 详情，支持关键词、类目、品牌、价格区间检索，也可以把商品带回导购页追问。
- `#/cart`：购物车维护，支持刷新、改数量、勾选、删除，并可跳转订单确认或让导购检查购物车。
- `#/orders`：普通订单链路，支持确认单、创建订单、查询、支付和取消。
- `#/seckill`：秒杀链路，支持活动刷新、提交秒杀请求、按 `requestId` 查询异步结果，并可跳转订单页。

## 导购 Agent 现状

导购页优先对接独立的 RAGAgent 服务：

```text
默认地址: http://localhost:8081
聊天接口: POST /api/shopping/chat
模型接口: GET  /api/models/chat
候选接口: GET  /api/shopping/cart/products
```

前端会同时带上：

- `Authorization: Basic <商城账号:商城密码>`，供 RAGAgent 通过商城适配层登录。
- `X-Mall-Authorization: Bearer <商城 token>`，供 RAGAgent 复用当前商城登录态。

如果 RAGAgent 没启动，导购候选商品会降级使用当前商城的 `GET /api/product/search`。聊天能力不会伪造本地回复，会显示真实错误，便于联调定位。

## 当前约束

- 前端没有新增后端接口，商城业务仍走现有 `/api/auth`、`/api/product`、`/api/cart`、`/api/order`、`/api/seckill`。
- 导购聊天依赖 `D:\mycodes\RAGAgent` 项目提供的接口；只启动商城后端时，商品、购物车、订单和秒杀页面仍可用。
- 页面跳转使用 hash 路由实现，没有引入 `vue-router`，保持依赖简单。
- 构建产物输出到 `frontend/dist/`。当前后端 Nginx 只代理 `/api/**`，还没有托管前端静态资源；如需 Docker 一键部署，可把 `dist/` 挂载到 Nginx 并补充静态资源路由。

## 本次更新记录

- 新增导购 Agent 页面，移植 RAGAgent demo 的核心交互：连接设置、会话 ID、模型选择、联网搜索、图文聊天、候选商品、对比视图和购物车联动。
- 新增页面导航和 hash 路由，实现导购、商品、购物车、订单、秒杀之间的跳转。
- 商品页新增 `GET /api/product/search` 检索入口，并把搜索结果接入导购候选和追问动作。
- 购物车、订单、秒杀页面从原单页控制台拆出，保留原有业务操作并增加跨页面动作入口。
- 运行 `npm.cmd run build` 验证通过；仅保留 Vite 对打包体积的常规提醒。
