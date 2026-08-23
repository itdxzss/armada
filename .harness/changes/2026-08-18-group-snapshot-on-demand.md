# 变更记录：群快照按需查询与邀请码同步

- 日期 / 分支 / worktree: 2026-08-18 / 1.0.3-snapshot / 主工作区
- 需求来源: `docs/superpowers/specs/2026-08-18-group-snapshot-on-demand-design.md`
- 状态: 进行中

## 目标（一句话）

把人工群资料与邀请码刷新改为可幂等结算的 Kafka 单群命令，并让 Web、Android 两端复用现有单群查询能力回传既有事实事件。

## 缺口拆解 / 任务清单

- [x] 固定命令、事实事件和结算事件契约 fixture
- [x] V129 为自动快照和批量任务补命令关联、scope、候选游标与结果期限字段
- [x] Armada 以任务状态和 Outbox 同事务派发 `group.snapshot.requested`
- [x] Armada 同一轮按 `tenant_id + group_jid` 内存去重，重复任务留待下一轮
- [x] Armada 消费 `group.snapshot_result_reported`，处理 CAS、候选轮换和超时恢复
- [x] 人工批量资料刷新从同步 HTTP 切换到 Outbox + 结果结算
- [x] Web 增加单群快照 executor、Redis 幂等状态与 `commandId` 透传
- [x] Android 发布逐群 `group.profile_reported` 并接入单群快照命令
- [x] 增加业务开关、指标和失败路径验证
- [x] Android HTTP 与 Kafka 邀请码读取统一切换到只读 MEX
- [x] `400/410` 归一为 `GROUP_INVITE_LINK_UNAVAILABLE`，不再误判群不可用
- [x] 控端 Kafka consumer 接受新错误码并透传到结算层
- [x] 批量刷新链接对邀请码不可用返回“当前群没有可用邀请链接”
- [ ] 在 test1 完成真实 fixture 消息大小与实际错误码验证

## 关键设计决策

- 首次建档继续复用 `account.groups_sync.requested`，不按群拆命令。
- 群资料与邀请码仍分别只由 `group.profile_reported`、`group.invite_link_changed` 写事实；新事件只做命令结算。
- 任务表保持按 `group_link_id` 唯一；只在单轮调度内按 `group_jid` 去重，不新增 peer 列、等待状态或广播结算。
- Web 命令走 master topic，Android 命令走 group-action topic；Kafka key 均为 `protocolAccountId`。
- 所有开关默认关闭，滚动发布先上 consumer，再上两端 executor，最后灰度派发。
- 邀请链接刷新只读取当前 code；MEX 返回空 code 或 `400/410` 时保留群资料与群健康状态，
  不创建、不撤销、不重置链接，也不再把该结果结算为 `GROUP_UNAVAILABLE`。

## 验证（evidence-before-done）

- Armada：相关 55 tests 与调整后的相关 47 tests 均通过；`mvn -DskipTests compile` 通过；Mapper XML 经 `xmllint` 校验通过。
- Web：快照/命令路由/配置相关 5 suites、87 tests 全部通过；`npm run build` 通过。
- Android：`go test ./internal/armada` 通过；其中完整群资料映射覆盖 `owner` / `role`。
- 三个仓库均执行 `git diff --check`，退出码 0。
- 2026-08-23 回归：Android `go vet ./...`、`go build ./...` 通过，相关包测试通过；全仓
  `go test ./...` 仅被既有 Promise 异步日志与 Noise 向量测试阻断。Armada consumer + 结算层
  相关测试共 41 个通过；全仓测试因本机无集成测试数据库而在连接重试阶段停止。

## 部署

- commit / 环境 / 部署后验证结果: 未部署；本次不操作远程或真实数据库。

## 遗留 / 跟进

- Android 单群 IQ 字段完整度与真实 payload 大小需在 test1 用脱敏 fixture 验证。
- 人工批量任务取消后的事实落库策略按设计建议处理：允许落事实，不累计已取消任务进度。
- 当前所有业务开关保持关闭；test1 真实错误结构和最大群 payload 仍需上线前校准。
