# armada 租户地基 + 极简登录 设计 (spec)

- 日期:2026-06-24
- 范围:**B —— 后端地基 + 前端对接层 + 登录页**
- 阶段:**先测功能**(JWT 后置;部署/nginx 由另起的部署 agent 负责,不在本 spec)
- 关联:`docs/business/对接.md`(总对接说明)、`docs/business/0-foundation.md`、wheel `TenantContextInterceptor`/`TenantCodeResolver`(参照对象)

---

## 1. 目标与背景

armada 后端三块(IP/导入链接/营销)接口已就绪,但 `TenantContext`(ThreadLocal)全代码无人调 `set()`,MyBatis 租户行隔离拦截器对每条 SQL 回退哨兵 `tenant_id=-1`(fail-closed),导致所有列表恒空、写入落到 -1。前端业务页是空壳,登录走 mock。

本地基要让前端能**登录、按租户隔离地拿到真数据**,作为 IP/导入链接/营销三页对接的共同前提。鉴权(JWT)后置——本阶段登录是临时垫片,`X-Tenant-Code` 头可被伪造、无真安全,接上 JWT 才安全。

## 2. 关键决策(已与用户确认)

- 租户模型:**建轻量 `tenant` 表 + 配置统一测试密码**;不建用户表(account/IAM 已明确推迟,见 `对接.md` TODO #2)。
- 范围 B:后端地基 + 前端对接层 + 登录页;**不含** nginx/compose/部署。
- 登录页字段:**租户码 + 密码**(无用户名概念)。
- 登录密码:**全局统一一个测试密码**,放配置(可环境变量覆盖)。
- 租户注册相关代码放 `com.armada.platform.tenant` 包(平台级,现为空壳)。
- 升级路径:接 JWT 时,拦截器把"租户码来自 `X-Tenant-Code` 头"换成"来自 token",`TenantCodeResolver`/`TenantContext`/MyBatis 隔离骨架不动。

## 3. 后端设计(armada-api)

### 3.1 Flyway V004 —— `tenant` 表
列:
- `id BIGINT PK AUTO_INCREMENT` —— **即业务表里用的 tenant_id**
- `tenant_code VARCHAR(64) NOT NULL UNIQUE` —— 前端 `X-Tenant-Code` 传的码
- `name VARCHAR(128) NOT NULL` —— 租户名
- `status TINYINT NOT NULL DEFAULT 1` —— 1 启用 / 0 停用
- `created_at DATETIME NOT NULL`、`updated_at DATETIME NOT NULL`

seed:`(1,'demo','演示租户A',1)`、`(2,'demo2','演示租户B',1)`。
注意:`tenant` 表**自身无 tenant_id 列**。

### 3.2 MyBatisConfig
- `IGNORED_TABLES` 加入 `"tenant"`(否则解析租户时该查询被注入 `AND tenant_id=?` → Unknown column)。仅此一行改动。

### 3.3 Tenant 实体 + Mapper(`com.armada.platform.tenant`)
- `Tenant` 实体(对应 tenant 表)。
- `TenantMapper.selectByCode(String code)` —— 返回启用的租户(`status=1`)。

### 3.4 TenantCodeResolver(`com.armada.platform.tenant`)
- 接口:`Optional<Long> resolveTenantId(String tenantCode)`。
- 实现:查 `tenant` 表(经 `TenantMapper`),命中启用租户返回其 `id`,否则空。
- 命名/职责镜像 wheel `TenantCodeResolver`,JWT 升级时复用。

### 3.5 TenantContextInterceptor(`com.armada.shared.tenant`,挨着 TenantContext,镜像 wheel)
`HandlerInterceptor`:
- `preHandle`:
  - 读请求头 `X-Tenant-Code`;为空/空白 → 抛 `BusinessException(TENANT_MISSING)`(code≠0,HTTP 200)。
  - `TenantCodeResolver.resolveTenantId(code)`;解析不到 → 抛 `BusinessException(TENANT_NOT_FOUND)`。
  - `TenantContext.set(tenantId)`。
- `afterCompletion`:**必 `TenantContext.clear()`**(防 ThreadLocal 串号)。
- 新增错误码:`TENANT_MISSING`、`TENANT_NOT_FOUND`(armada 错误码体系内)。

### 3.6 登录接口 TenantAuthController(`com.armada.platform.tenant`)
- `POST /api/public/auth/login`(**公开,免拦截**)。
- 入参:`{ tenantCode, password }`。
- 校验:`password` 等于配置 `armada.dev-login.password`,且 `tenant_code` 在 tenant 表存在且启用。任一不满足 → `ApiResponse.error(...)`(如 `LOGIN_FAILED`,统一提示不暴露是码错还是密码错)。
- 返回:`{ tenantCode, tenantName, token }`。`token` 是占位串(如 `"dev-"+tenantCode`),**仅为满足前端路由守卫,非真鉴权凭据**。

### 3.7 WebMvcConfig(新增 `WebMvcConfigurer`,`boot/config`)
- `addInterceptors`:`TenantContextInterceptor` 注册到 `/api/**`,`excludePathPatterns("/api/public/**")`。

### 3.8 配置 application.yml
- `armada.dev-login.password`(默认值 + 支持环境变量覆盖)。

## 4. 前端设计(wheel-saas-pure-web)

### 4.1 vite.config.ts
- 加 dev 代理:`server.proxy['/api'] = { target: 'http://127.0.0.1:8080', changeOrigin: true }`。**仅本地 `npm run dev` 用**;测试环境靠 nginx。

### 4.2 utils/http
- 请求拦截器:从 localStorage 取租户码,注入 `X-Tenant-Code` 头(无 JWT,暂不注入 Authorization)。
- `baseURL` 走相对 `/`(默认即相对,确保不写绝对 host)。
- 响应:新增**薄 helper**(armada api 文件统一用),按 `code===0` 取 `data`、非 0 用 `message` 抛错;全局响应拦截器维持原状(降低对 pure-admin 脚手架扰动)。

### 4.3 登录与守卫
- `api/auth.ts`:`login({tenantCode,password})` → `POST /api/public/auth/login`。
- 登录页:字段改 **租户码 + 密码**(复用现有输入框当租户码)。
- `store/modules/user`:登录成功存 `tenantCode`(供 X-Tenant-Code 头)+ `token`(占位,过 `getToken()` 路由守卫);登出清除两者。
- 路由守卫沿用 pure-admin(有 token 即放行)。异步路由 mock 可暂留或改静态路由(不在本地基重点)。

## 5. 数据流

1. 登录:浏览器 `POST /api/public/auth/login {tenantCode,password}`(不过拦截器)→ 校验配置密码 + tenant 表启用 → 返回 `{tenantCode,tenantName,token}` → 前端存 localStorage。
2. 业务:浏览器 `GET /api/ip-proxies`(带 `X-Tenant-Code: demo`)→ 拦截器读头 → resolver 查 tenant 表 `demo→id=1` → `TenantContext.set(1)` → MyBatis 自动 `AND tenant_id=1` → 返回租户 1 数据 → `afterCompletion` clear。
3. 缺头:`GET /api/...` 无 `X-Tenant-Code` → 拦截器抛 `TENANT_MISSING`(code≠0,HTTP 200)→ 前端提示重新登录。

## 6. 错误处理

- 统一 `ApiResponse{code,message,data}`,业务错误 HTTP 200 + 非 0 code(沿用 armada `GlobalExceptionHandler`)。
- 新错误码:`TENANT_MISSING`(缺租户头)、`TENANT_NOT_FOUND`(租户码无效/停用)、`LOGIN_FAILED`(登录校验失败)。
- 前端:`code≠0` 时弹 `message`;`TENANT_MISSING`/未授权时清本地态并跳登录页。

## 7. 测试

- **后端 DbTest**(本机 MySQL armada 库,沿用 `DbTestBase`):
  - `TenantCodeResolver`:有效码→正确 id;不存在/停用码→空。
  - 登录:密码对+租户存在→成功返回;密码错→失败;租户不存在/停用→失败。
  - 拦截器:带 `X-Tenant-Code` → 解析正确 tenantId 并设上下文;无头 → 抛 `TENANT_MISSING`;`afterCompletion` 清空。
  - **租户隔离**(端到端):租户 1 登录后查 IP 列表,看不到租户 2 的数据。
- **前端**:`tsc` 通过 + 本地 `npm run dev` 浏览器手验「登录 → 进系统」。

## 8. 不在本范围(承接 TODO / 其他 agent)

- nginx 同源反代 + docker-compose + 部署脚本 → 另起的部署 agent。
- JWT/真鉴权、用户表、account/IAM → 后置(`对接.md` TODO #2)。
- IP / 导入链接 / 营销三页的具体对接 → 地基之后按顺序做(IP 先)。

## 9. 实施顺序(粗)

1. 后端:V004 tenant 表 + seed → IGNORED_TABLES → Tenant 实体/Mapper → TenantCodeResolver → 错误码 → TenantContextInterceptor → WebMvcConfig 注册 → 登录 controller → 配置。(每步 TDD,DbTest 验隔离)
2. 前端:vite dev 代理 → http 层(X-Tenant-Code + 拆包 helper)→ auth api + 登录页 + store + 守卫。
3. 本地浏览器端到端手验。
