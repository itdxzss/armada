# 账号手动指定代理上线设计

- 日期: 2026-06-26
- 状态: 已确认,待实现
- 范围: armada 后端单账号上线发起闭环

## Goal

把现有底层 `AccountOnlineService.online(AccountOnlinePlan)` 接成一个可被业务调用的后端入口:运营手动指定账号和代理,后端查凭据后调用协议层 `/v1/accounts/{id}/online`,返回"已受理"回执。

## 做什么

新增 `POST /api/accounts/{id}/online`,请求体只包含 `proxyId`。后端按当前租户隔离查活跃账号、活跃凭据、活跃代理,把代理字段转成 `ProxyEndpoint`,再复用现有上线服务调用协议层。

返回 VO 只表达协议层 HTTP 已受理结果,包含 `accountId`、`protocolAccountId`、`accepted`、`stateSource`、`syncedAt`、owner 路由字段。`syncedAt` 转 epoch 毫秒,保持 armada API 时间口径一致。

## 不做什么

- 不做批量上线。
- 不做自动代理分配、锁代理、绑定、释放、回收。
- 不修改 `ip_proxy.status`。
- 不写 `account_state.login_state=ONLINE`,避免把"协议已受理"伪装成"已经在线"。
- 不接 Kafka `account.state_changed` 真终态回写。
- 不改 armada-protocol。

## 业务规则

1. 账号 ID 必须存在且未软删,否则返回 `NOT_FOUND`。
2. `proxyId` 必填,否则返回 `VALIDATION`。
3. 凭据必须存在且未软删,否则返回 `VALIDATION`。
4. 凭据格式按 `account_credential.cred_format`:1 -> `SIX_SEGMENT`,2 -> `BAILEYS_JSON`,3 -> `PARAMS`。
5. 代理必须存在且未软删,否则返回 `NOT_FOUND`。本刀不要求状态必须为空闲,因为不做绑定状态管理。
6. 可恢复业务错误抛 `BusinessException`;协议层错误继续由 `ProtocolException` 向上交给全局处理。

## 组件边界

- `account/controller/AccountController`: 增加单账号上线端点,只收参并包装 `ApiResponse`。
- `account/service/AccountOnlineCommandService`: 新增应用服务,按账号 ID 和 proxyId 编排查库 + 调协议端口。
- `resource/service/IpProxyService`: 新增跨域服务方法,给 account 域返回 `ProxyEndpoint`,避免 account 域直接依赖 resource mapper/entity。
- `AccountMapper` / `IpProxyMapper`: 各补一个按 ID 查活跃行的方法。

## 测试

- Service 单测:账号 + 凭据 + 代理存在时,组装 `AccountOnlinePlan` 并返回 VO。
- Service 单测:缺 `proxyId`、缺账号、缺凭据分别抛业务异常。
- Resource Service 单测:按 proxyId 返回 `ProxyEndpoint`,代理不存在抛 `NOT_FOUND`。
- Controller MockMvc:请求 `/api/accounts/{id}/online` 能走到 service 并返回 `ApiResponse.code=0`。
- Mapper XML 改动后跑相关单测;真库 DbTest 视本地 DB 环境执行。
