# 变更记录：首次上线群全量同步收口

- 日期 / 分支 / worktree: 2026-08-22 / `1.0.3-snapshot` / 主工作目录
- 需求来源: 用户确认“首次上线获取全量 metadata；后续重连不查 metadata、不做轻量群组查询；页面手动刷新保留”
- 状态: 实现完成，待部署验证

## 目标（一句话）

由 Armada 持久化 baseline 状态决定首次全量群同步，协议层不再根据进程内记忆在 ONLINE 后自行查群。

## 缺口拆解 / 任务清单

- [x] PENDING 且从未请求过的账号 ONLINE 后由 Armada 显式下发一次账号全量群同步命令
- [x] 已请求或 CAPTURED 的账号后续 ONLINE 不下发账号群同步命令
- [x] 协议层 ONLINE 只登记在线上下文，不安排轻量群列表查询
- [x] 不完整账号群快照保持 PENDING，完整快照才进入 CAPTURED
- [x] ONLINE 只恢复邀请码单项任务，不恢复 metadata 查询
- [x] 页面 METADATA / INVITE_CODE 手动刷新始终可执行，不再受协议灰度开关阻断
- [x] Java 与 Go 受影响范围回归和构建通过

## 关键设计决策

- 复用现有 `account.groups_sync.requested`，不新增协议命令类型。
- 复用 `account_group_sync_state.baseline_state` 与 `last_sync_requested_at`；不新增表或字段。
- `last_sync_requested_at` 一旦存在即视为首次全量已经请求，后续重连不再借 ONLINE 重试。
- `group.snapshot.requested` 继续只负责页面单群手动刷新；协议层移除运行时禁用分支。
- ONLINE 恢复任务仅允许 `completed_scope_mask=METADATA` 的邀请码待办，避免重连带出 metadata 查询。

## 验证（evidence-before-done）

- Armada 聚焦测试：70 个通过，0 失败（首次同步、baseline 完整性、邀请码恢复、Mapper、Kafka publisher）。
- Armada 构建：`mvn -DskipTests package` 通过。
- Android 协议层：`go test ./internal/armada -count=1` 通过；`go build ./...` 通过；
  `go vet ./internal/armada/...` 通过。
- Android 全仓 `go test ./...` 的本次相关包全部通过；最终仅已有 `pkg/noise` 测试失败，包含
  缺少 `vectors.txt` 和既有密码学向量不一致，与本变更文件无交集。
- `GroupListCurrentMapperMySqlTest` 需要 Docker；本机无 Docker 环境，未执行成功。其余聚焦测试通过。

## 部署

- commit / 环境 / 部署后验证结果: 未部署；建议先控端、后协议。

## 遗留 / 跟进

- 两套测试环境部署后需处理此前因禁用而积压的手动刷新命令；新版本不再被该兼容配置阻断。
