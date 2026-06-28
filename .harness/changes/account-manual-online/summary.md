# 变更记录：账号上线自动分配代理

- 日期 / 分支 / worktree: 2026-06-27 / main / `/Users/daishuaishuai/IdeaProjects/armada`
- 需求来源: 用户对齐上线接口设计，要求后端自动拿空闲 IP，不由页面传 proxyId
- 状态: 已完成

## 目标（一句话）

`POST /api/accounts/{id}/online` 发起上线时，后端先释放该账号旧代理绑定，再短事务锁定一条空闲代理并置为使用中，随后只把协议层 HTTP 返回视为“命令已受理”。

## 缺口拆解 / 任务清单

- [x] 删除上线请求体里的 `proxyId` 入口，Controller/Service 改为只接收账号 ID。
- [x] `ip_proxy` 增加 `bound_account_id`、`bound_at`，记录当前代理绑定账号。
- [x] `IpProxyMapper` 增加 `selectOneIdleForUpdate`、`markUsingAndBind`、`releaseByAccount`。
- [x] `IpProxyService.allocateOnlineEndpoint(accountId)` 封装释放旧绑定、锁空闲代理、绑定账号和端点转换。
- [x] `AccountOnlineCommandServiceImpl` 接入自动代理分配，补上线核心流程注释和安全日志。
- [x] 补普通单测和真库 mapper DbTest。

## 关键设计决策

- 页面不再选择 IP；账号上线由后台自动分配空闲代理。
- 用户点击上线不保留旧 IP：先释放该账号旧绑定，再从空闲池选择一条代理。
- 代理分配是本地 DB 短事务：只保护“不要两个账号抢同一条代理”。
- HTTP 调协议层 `/online` 只代表命令投递/已受理，不代表账号已在线；最终状态仍等 Kafka 异步回填。
- 本刀不做代理不可用后的自动换 IP、失败回收、retain/release 复杂生命周期。
- 已应用过的历史 Flyway 迁移不修改；新增字段走 V009。

## 验证（evidence-before-done）

- 红测:
  - `mvn -Dtest=AccountControllerTest,AccountOnlineCommandServiceImplTest,IpProxyServiceImplTest test`
  - 结果: testCompile 失败，缺少新设计的 `IpProxyAllocation`。
- 目标单测:
  - `mvn -Dtest=AccountControllerTest,AccountOnlineCommandServiceImplTest,IpProxyServiceImplTest test`
  - 结果: `Tests run: 18, Failures: 0, Errors: 0, Skipped: 0`
- 真库 DbTest:
  - `./dbtest.sh IpProxyMapperDbTest`
  - 结果: Flyway 成功应用 `V009__ip_proxy_binding`，测试进程退出码 0。

## 部署

- commit / 环境 / 部署后验证结果: 未部署

## 遗留 / 跟进

- Kafka 回填失败、离线、proxy_failed 后的代理释放/回收/换 IP 作为后续切片。
- 下线/删除场景复用 `releaseByAccount` 的接口接入作为后续切片。
