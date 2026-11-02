# 账号导出时让协议删除数据（不登出）

- 日期 / 分支：2026-11-01 / `1.0.3-snapshot`
- 需求来源：WB customer1 Worker 内存问题第④个原因（重连后钥匙回退）的配套改动，用户 11-01 指定 Claude 负责。
  完整方案见 `wbpro-v2/docs/切片/52-重连沿用钥匙.md`，协议侧记录见 `armada-protocol/.harness/changes/reconnect-reuse-auth-storage/summary.md`。
- 状态：已实现、本地测试通过；未提交、未部署。

## 变更概述

协议（`armada-protocol` `1.0.3-snapshot`）改为**下线不再删除凭据和钥匙**，同一账号 ID 重新上线时以协议里已存的为准，
不再被 `account_credential` 里的导入快照覆盖（Armada 以前每次"下线→上线"同样在回退钥匙）。

"导出账号"把号交给别人，以前靠下线时协议顺手删数据；改后需要显式让协议删。`AccountExportService.complete`
（客户端确认收到 ZIP、移除账号的那一步）在同一事务里为每个 Web 协议账号写一条 `account.offline.requested`，
payload 带 `purgeCredentials: true`、`source=account_export`。协议 Worker 先下线（若在线）再删 creds / keys，不发 WhatsApp 登出；
无 owner 时由协议 Master 直接删。创建导出、取消导出、摘要不符被拒都不发。Android 协议账号不发。

## 影响

- `ProtocolOfflineCommandRequest` 增加 `purgeCredentials`（三参构造保持 false）；批量下线显式传 false。
- Outbox 下线 payload 仅在 `purgeCredentials=true` 时出现该字段，普通下线 payload 不变。
- `AccountExportCandidate` 与 `AccountExportMapper.candidates` 多读 `protocol_account_id`、`protocol_id`。
- DB 变更：无。API 变更：无（Kafka 命令 payload 增加可选字段）。

## 约束

- 删数据命令与移除账号同事务写 outbox，回滚则不发；Kafka key 为协议账号 ID，与之前的上线命令保序。
- 协议删除失败时不 ack / 保留为路由失败，不会静默丢掉。

## 验证

- `AccountExportServiceH2Test`（H2 + 真实 Mapper）15 项通过：新增"完成交付只发一次、只发 Web 账号"和"取消或摘要不符不发"。
- `ProtocolCommandOutboxServiceImplTest` 50 项通过：新增导出删数据 payload 带 `purgeCredentials`，普通下线不带。
- `AccountOnlineCommandServiceImplTest` 38 项、`AccountRestrictionServiceImplTest` 2 项通过。
- 全量 `mvn test`：6,147 项、15 失败、448 错误；同机在 HEAD `721f281b` 干净工作树上跑全量为 6,144 项、15 失败、448 错误，
  失败类相同（真库 DbTest 无库、群组/营销/拉群任务/权限模块既有失败），本次新增 3 项全部通过。

## 回滚

回滚本仓库提交即可；旧协议不认识 `purgeCredentials`，会当作普通下线（旧协议下线本来就删数据）。

## 以后再处理

Armada 删除账号只软删，不清协议数据。
