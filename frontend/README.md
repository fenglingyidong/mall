# Mall Frontend

这是 `mallbackend` 的简单演示前端，使用 Vue 3、Vite、Element Plus 和 Axios 构建。它面向本地联调和面试演示，覆盖登录、商品查询、购物车、普通下单和秒杀链路。

## 技术栈

- Vue 3
- Vite
- Element Plus
- Axios

## 运行方式

先在仓库根目录启动后端依赖和业务服务。当前推荐的 API 入口是 Docker Nginx：

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

## 后端入口

开发环境的代理配置在 `vite.config.js`：

```js
proxy: {
  '/api': {
    target: 'http://localhost:8080',
    changeOrigin: true
  }
}
```

如果不启动 Nginx，也可以临时把代理目标改成网关直连地址 `http://localhost:8100`。

## 页面功能

- 登录：默认账号 `alice / demo123`，登录后保存 token 到 `localStorage`。
- 商品：默认查询 SKU `1001`，展示商品详情，并可加入购物车。
- 购物车：支持刷新、修改数量、勾选和删除。
- 订单：支持确认订单、创建订单、查询订单、支付和取消。
- 秒杀：展示活动和商品，支持提交秒杀并通过 `requestId` 查询异步结果。

## 联调记录

- 页面刷新时，如果本地已有 token，会自动请求商品、秒杀活动和购物车。
- 曾遇到刷新后弹出 `System error`，定位为 `GET /api/cart` 返回 500：Redis 购物车分支返回不可变列表，服务层排序时抛异常。已在后端 `mall-cart` 中修正为返回可排序列表。

## 构建

```powershell
npm.cmd run build
```

构建产物输出到 `frontend/dist/`。当前后端 Nginx 只代理 `/api/**`，还没有托管前端静态资源；如果后续要一键 Docker 部署前后端，可以把 `dist/` 挂载到 Nginx 并补充静态资源路由。
