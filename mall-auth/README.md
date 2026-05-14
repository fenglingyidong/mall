# mall-auth 认证服务

`mall-auth` 是项目的认证服务，负责演示环境下的登录、Token 签发和 Token 校验。它不依赖数据库，当前用户名会直接映射成稳定的 `userId`，适合本地联调普通下单、购物车和秒杀链路。

更多整体背景见 [../README.md](../README.md) 和 [../docs/architecture.md](../docs/architecture.md)。

## 模块定位

- 服务名：`mall-auth`
- 默认端口：`8101`
- 注册中心：Nacos `localhost:8848`
- 主要依赖：`mall-common`、Spring Web、Validation、Nacos Discovery
- 对外入口：经 `mall-gateway` 暴露 `/api/auth/**`

## 核心功能

- 登录后签发演示 Token，Token 内容包含 `userId`、`username`、过期时间和 HMAC-SHA256 签名。
- Token 默认有效期为 24 小时。
- `/api/auth/me` 从 `mall-common` 的 `UserContext` 读取当前用户信息，用户上下文由网关透传的请求头写入。
- `/api/auth/verify` 可直接校验 `Authorization: Bearer <token>`。

当前登录实现是演示版：不查询 `user` 表，也不校验密码，只根据用户名生成用户身份。生产化时应替换为真实用户中心、密码校验和刷新令牌机制。

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/auth/login` | 登录并签发 Token |
| `GET` | `/api/auth/me` | 查询当前登录用户 |
| `GET` | `/api/auth/verify` | 校验 Token 并返回载荷 |

登录请求示例：

```json
{
  "username": "alice",
  "password": "123456"
}
```

## 关键代码

- `AuthController`：认证接口入口。
- `SimpleTokenServiceImpl`：Token 生成、签名和校验。
- `LoginRequest`、`LoginResponse`、`TokenPayload`：认证请求和响应对象。

## 启动

先启动 Nacos，再运行：

```bash
java -jar target/mall-auth-0.0.1-SNAPSHOT.jar
```

也可以在根目录编译指定模块：

```bash
mvn -pl mall-auth -am package -DskipTests
```
