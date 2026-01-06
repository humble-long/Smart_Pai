# PaiSmart 登录流程学习文档（按代码走读）

这份文档用于你在本仓库内“顺着代码”理解登录、鉴权、Token 刷新与登出流程。文档以当前代码实现为准，并尽量给出可直接跳转的文件入口。

## 1. 你要先记住的主线

- **登录成功**：前端把 `token`/`refreshToken` 写入 `localStorage`，后续请求自动带 `Authorization: Bearer <token>`。
- **后端鉴权**：Spring Security + JWT，主要入口是 `SecurityConfig` + `JwtAuthenticationFilter`。
- **JWT 不是“只验签”**：本项目还会把 `tokenId` 写入 JWT，并在 Redis 里维护 token 状态（有效/黑名单/refresh token），校验时会先查 Redis。
- **两套刷新机制**：
  - 无感刷新：后端在响应头返回 `New-Token`，前端 axios 封装读取 `new-token` 并更新本地 token。
  - 显式刷新：`/api/v1/auth/refreshToken`（前端有后备逻辑与封装）。

## 2. 快速走读路径（建议顺序）

前端（从 UI 到请求层）：

- 登录页容器：`frontend/src/views/_builtin/login/index.vue`
- 密码登录模块：`frontend/src/views/_builtin/login/modules/pwd-login.vue`
- Auth Store（登录、保存 token、拉取用户信息）：`frontend/src/store/modules/auth/index.ts`
- API 封装（login/me/logout/refresh）：`frontend/src/service/api/auth.ts`
- 请求封装（自动加 Authorization + 处理 New-Token）：`frontend/src/service/request/index.ts`
- 路由守卫（没 token 跳转登录、角色无权限跳 403）：`frontend/src/router/guard/route.ts`

后端（从接口到 Security 过滤链）：

- 登录/注册/用户信息/登出：`src/main/java/com/yizhaoqi/smartpai/controller/UserController.java`
- 刷新 token：`src/main/java/com/yizhaoqi/smartpai/controller/AuthController.java`
- Spring Security 配置：`src/main/java/com/yizhaoqi/smartpai/config/SecurityConfig.java`
- JWT 认证过滤器：`src/main/java/com/yizhaoqi/smartpai/config/JwtAuthenticationFilter.java`
- JWT 工具类（生成/校验/刷新/失效）：`src/main/java/com/yizhaoqi/smartpai/utils/JwtUtils.java`
- Token Redis 状态管理：`src/main/java/com/yizhaoqi/smartpai/service/TokenCacheService.java`

## 3. 端到端流程：从点击“登录”开始

### 3.1 前端发起登录

1. 用户在 `PwdLogin` 页面点击登录按钮 → 调用 `authStore.login(userName, password)`  
   - `frontend/src/views/_builtin/login/modules/pwd-login.vue`
   - `frontend/src/store/modules/auth/index.ts`
2. `authStore.login()` 内部调用 `fetchLogin(userName, password)` 发请求  
   - `frontend/src/service/api/auth.ts`
3. 登录成功后执行 `loginByToken()`：
   - `localStorage.setItem('token', ...)`
   - `localStorage.setItem('refreshToken', ...)`
   - 再调用 `fetchGetUserInfo()` 拉取 `/users/me`，把返回的用户信息写入 `authStore.userInfo`
   - `frontend/src/store/modules/auth/index.ts`

### 3.2 登录接口与返回

登录接口：

- `POST /api/v1/users/login`
- 请求体：`{ "username": "...", "password": "..." }`
- 返回：`{ code: 200, data: { token, refreshToken } }`
- 代码：`src/main/java/com/yizhaoqi/smartpai/controller/UserController.java`

登录校验的核心逻辑：

- `UserService.authenticateUser(username, password)`：从 DB 查询用户并用 BCrypt 校验密码  
  - `src/main/java/com/yizhaoqi/smartpai/service/UserService.java`
  - `src/main/java/com/yizhaoqi/smartpai/utils/PasswordUtil.java`
- `JwtUtils.generateToken(username)`：生成 JWT（并把 token 状态写入 Redis）  
  - `src/main/java/com/yizhaoqi/smartpai/utils/JwtUtils.java`
- `JwtUtils.generateRefreshToken(username)`：生成 refresh token（也写入 Redis）  
  - `src/main/java/com/yizhaoqi/smartpai/utils/JwtUtils.java`

## 4. 后端鉴权链路：请求为什么会“自动变成已登录”

### 4.1 Security 配置（放行/拦截规则）

在 `SecurityConfig` 中：

- 放行：静态资源、WebSocket、`/api/v1/users/register`、`/api/v1/users/login` 等
- 其他请求默认 `.authenticated()`
- 将 `JwtAuthenticationFilter` 加到过滤链中

入口文件：

- `src/main/java/com/yizhaoqi/smartpai/config/SecurityConfig.java`

### 4.2 JWT 过滤器（JwtAuthenticationFilter）

每个请求都会经过 `JwtAuthenticationFilter`：

1. 从请求头提取 `Authorization: Bearer <token>`（如果没有就直接放行到下游）
2. `jwtUtils.validateToken(token)`：校验 token
3. 校验通过后：
   - `jwtUtils.extractUsernameFromToken(token)` 提取用户名
   - `CustomUserDetailsService.loadUserByUsername(username)` 从 DB 加载用户与角色权限
   - 写入 `SecurityContextHolder.getContext().setAuthentication(...)`

入口文件：

- `src/main/java/com/yizhaoqi/smartpai/config/JwtAuthenticationFilter.java`
- `src/main/java/com/yizhaoqi/smartpai/service/CustomUserDetailsService.java`

## 5. JWT + Redis：为什么 token 能被“强制失效”

### 5.1 JWT 中的关键 claims

在 `JwtUtils.generateToken()` 里，JWT 会放入：

- `tokenId`：JWT 唯一标识（用于 Redis 状态校验/黑名单）
- `role`、`userId`
- `orgTags`、`primaryOrg`（组织标签授权相关）

入口文件：

- `src/main/java/com/yizhaoqi/smartpai/utils/JwtUtils.java`

### 5.2 validateToken 的校验顺序

`validateToken(token)` 的核心顺序是：

1. 解析 `tokenId`
2. 去 Redis 判断 token 是否仍有效（并检查黑名单）
3. Redis 通过后再做 JWT 签名校验

入口文件：

- `src/main/java/com/yizhaoqi/smartpai/utils/JwtUtils.java`
- `src/main/java/com/yizhaoqi/smartpai/service/TokenCacheService.java`

## 6. Token 刷新机制（两条路）

### 6.1 无感刷新：响应头 `New-Token` → 前端自动更新

后端侧（过滤器内）：

- token 剩余时间 < 5 分钟：`jwtUtils.shouldRefreshToken(token)` 为 true
- 或 token 已过期但在 10 分钟宽限期内：`jwtUtils.canRefreshExpiredToken(token)` 为 true
- 刷新后通过响应头返回：`response.setHeader("New-Token", newToken)`

入口文件：

- `src/main/java/com/yizhaoqi/smartpai/config/JwtAuthenticationFilter.java`
- `src/main/java/com/yizhaoqi/smartpai/utils/JwtUtils.java`

前端侧（axios 封装）：

- axios response interceptor 读取响应头 `new-token`
- 如果存在则回调 `onTokenRefresh(newToken)`，并更新本地 token（`authStore.setToken`）

入口文件：

- `frontend/packages/axios/src/index.ts`
- `frontend/src/service/request/index.ts`
- `frontend/src/store/modules/auth/index.ts`

### 6.2 显式刷新：`/api/v1/auth/refreshToken`

当你希望“主动刷新 token”或作为无感刷新失败的后备方案时：

- `POST /api/v1/auth/refreshToken`，请求体：`{ "refreshToken": "..." }`
- 后端校验 refresh token（Redis + 验签）后返回新的 `token + refreshToken`

入口文件：

- `src/main/java/com/yizhaoqi/smartpai/controller/AuthController.java`
- `frontend/src/service/api/auth.ts`

注意：

- 前端项目里还有一套“按业务 code 判断 token 过期并自动 refresh + 重试”的逻辑（`frontend/src/service/request/index.ts`），但当前后端更多使用 HTTP `401/403` + `{ code: 401 }` 这种方式返回；是否能触发该逻辑取决于你们是否把后端业务 code 与前端 `.env` 里的 `VITE_SERVICE_EXPIRED_TOKEN_CODES` 对齐。

## 7. 登出：token 如何失效

### 7.1 单设备登出

- `POST /api/v1/users/logout`
- 后端解析 token → `jwtUtils.invalidateToken(jwtToken)`：
  - 加入 Redis 黑名单
  - 从 Redis 有效 token 中移除

入口文件：

- `src/main/java/com/yizhaoqi/smartpai/controller/UserController.java`
- `src/main/java/com/yizhaoqi/smartpai/utils/JwtUtils.java`

### 7.2 全设备登出

- `POST /api/v1/users/logout-all`
- 后端提取 `userId` → `jwtUtils.invalidateAllUserTokens(userId)` 批量移除

入口文件：

- `src/main/java/com/yizhaoqi/smartpai/controller/UserController.java`

## 8. 默认账号从哪来（为什么前端示例能直接填 admin/admin123）

应用启动时会自动创建管理员账号（如果不存在）：

- `src/main/java/com/yizhaoqi/smartpai/config/AdminUserInitializer.java`
- 默认账号密码来自配置：`src/main/resources/application.yml` 的 `admin.username`/`admin.password`

## 9. 走读建议：怎么自己验证理解是否正确

- **前端验证**：登录后打开浏览器 DevTools → Application/Storage，确认 `token`/`refreshToken` 已写入；再看 Network 请求头是否带 `Authorization`。
- **后端验证**：
  - 开启 `org.springframework.security` DEBUG（仓库 `application.yml` 已配置），观察过滤器日志。
  - 观察响应头是否出现 `New-Token`（当 token 接近过期时）。

---

如果你想继续深入“权限控制/组织标签授权”，下一步建议读：`src/main/java/com/yizhaoqi/smartpai/config/OrgTagAuthorizationFilter.java`（它会对部分接口注入 `@RequestAttribute("userId")` 并做资源级授权判断）。
