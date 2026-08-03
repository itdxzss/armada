# 变更记录：营销任务 WhatsApp 群成员导出优化

- 日期 / 分支 / worktree: 2026-08-03 / `codex/simple-whatsapp-group-member-export` / 主工作树
- 需求来源: `docs/business/marketing-task-whatsapp-group-member-export-design.md`
- 状态: 已完成，待部署验证

## 目标（一句话）

营销任务全量导出和按国家导出统一使用任务实际涉及群的 WhatsApp 实时成员，并合并 Android 协议明确下发的历史/实时退群事实。

## 缺口拆解 / 任务清单

- [x] 按任务群 JID 查询全部目标群，不以 Armada 受控账号作为成员范围。
- [x] 每群选择最多两个实际发送 Android 账号，主账号失败时仅回退一次。
- [x] Android 群成员接口返回实时 `Announce`，Armada 映射为发言权限。
- [x] HistorySync `PastParticipants` 与 WGP2 `remove/leave` 事件复用群同步 Topic 上报。
- [x] MySQL 保存每个群成员最近一次退群事实，当前成员不落快照。
- [x] 全量和按国家模式共享同一个 WhatsApp 成员数据集。
- [x] 增加协议事件、提供器、消费解析、SQL 查询和作业编排测试。

## 关键设计决策

- 当前成员只在导出时实时查询，不建立群成员快照表，避免陈旧数据成为导出主来源。
- 退群成员无法从当前成员接口恢复，只保存 WhatsApp 主动下发的 `PastParticipants` 和实时退群通知；未被 WhatsApp 下发过的更早历史无法凭空补齐。
- 观察账号只用于通过协议鉴权查询对应群，不参与导出成员过滤。
- 每账号串行、最多四个账号并行；单群最多主账号加一个回退，防止循环重试。
- 每批最多四个群，当前成员和该批退群事实合并后直接推送到 SXSSF 两个工作表，不在 JVM 中累计完整成员结果集。
- `phone` 为空时只允许从 PN/device-PN JID 派生号码；LID 没有可信映射时保持未知，不参与国家归属。
- 任一任务群无法完成实时查询时整单失败，不生成看似成功的残缺文件。
- 退群事实采用 MySQL 8.4 row alias 原子 upsert；H2 负责租户查询，MySQL 专有更新语义由 Testcontainers 真库测试覆盖。

## 验证（evidence-before-done）

- Armada `clean` 定向测试：68 个测试中 67 个通过、0 失败、0 错误、1 个跳过（工作簿流式写入、导出服务、实时成员提供器、Kafka 消费、Android metadata/成员映射、退群事实 Mapper/Service）。
- 最终只读事务修复后补跑受影响测试：22 个测试全部通过，0 失败、0 错误、0 跳过。
- MySQL 8.4 Testcontainers 测试已覆盖 row alias upsert、来源优先级、事件 ID 二进制 tie-break、手机号保留和重复重放；本机没有 Docker，因此该真库测试自动跳过，须在具备 Docker 的 CI/机器补跑至 `Skipped: 0`。
- Android 相关包：`api/service`、`internal/armada`、`internal/service/app`、`internal/service/node/processor` 全部通过。
- Android 全仓 `go vet ./...`、`go build ./...` 通过；`go test ./...` 被仓库既有部署脚本 CRLF、promise 异步日志和 noise fixture/向量测试失败阻断，与本次改动包无关。

## 部署

- 未提交、未部署；按用户要求仅放入两个仓库的 Git 暂存区。

## 遗留 / 跟进

- 部署到目标环境后，用真实 Android 在线账号验证 WhatsApp 是否为目标群返回所需时间范围内的 `PastParticipants`。
- 已核对相关 `1.0.2-snapshot*` 分支：`V090` 已被群成员快照迁移占用，本功能使用 `V091`，部署前仍需由 Flyway 校验目标库历史。
