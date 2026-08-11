# 变更记录：群邀请链接实时刷新与拉手 UNKNOWN 收敛

- 日期 / 分支 / worktree: 2026-08-10 / 1.0.3-snapshot / 主工作区
- 需求来源: `docs/superpowers/specs/2026-08-10-group-invite-link-refresh-and-puller-unknown-design.md`
- 状态: 已实现并完成聚焦验证，待部署（2026-08-11 补充 task #57 失效链接恢复）

## 目标（一句话）

让 Web/Android 观察到的群邀请链接变更进入 Armada 当前邀请码事实，并让普通拉群的 UNKNOWN
拉手邀请通过在线账号实时成员事实收敛。

## 缺口拆解 / 任务清单

- [x] Web 发布 `group.invite_link_changed`
- [x] Android 解析并发布 `w:gp2/invite`
- [x] Armada 消费事件并按观察时间保存当前邀请码
- [x] 普通拉群进群 Outbox 在发送前使用当前邀请码
- [x] UNKNOWN 成员查询优先在线在群账号、过滤离线账号并支持候选故障切换
- [x] 成功成员快照确认目标缺席时收敛邀请失败
- [x] 三仓聚焦验证、编译与静态检查

## 关键设计决策

- `pull_task_group_execution.invite_code` 保留为输入审计快照，不被异步事件改写；发送前 hydration
  读取 `group_link_preview.invite_code`。
- 当前邀请码使用独立 `invite_code_observed_at` 防止乱序覆盖，不复用会被其它 metadata 字段更新的
  `updated_at`。
- UNKNOWN 成员复核不再使用只保证“未删除”的账号引用；必须以当前 ONLINE 事实筛选。
- 成员快照成功且目标缺席是明确否定事实，可以结束 UNKNOWN；查询失败或无可用账号仍保持 UNKNOWN。

## 验证（evidence-before-done）

- Web `armada-protocol/protocol-layer`
  - `npm test -- --runInBand`: 退出码 0，65 suites / 596 tests 全部通过。
  - `npm run lint`: 退出码 0。
  - `npm run build`: 退出码 0。
- Android `whatsapp-server-feature-android-zhuan`
  - `gofmt` 已执行；`go vet ./...`、`go build ./...`: 退出码 0。
  - `go test -count=1 ./internal/armada ./internal/service/node/processor`: 退出码 0。
  - 沙箱外 `go test ./...` 中本次涉及包和其余主工程包通过，仅既有 `pkg/noise` 失败：
    缺少 `vectors.txt`，且 7 个既有 Noise 固定向量/回滚断言与当前改动无关。
- Armada `armada-api`
  - 12 个相关测试类聚合执行：退出码 0，60 tests 全部通过。
  - `mvn -DskipTests package`: 退出码 0，Spring Boot jar 构建成功。
  - `mvn test` 会进入现有真实数据库集成测试并持续等待本机不可达的数据源，已终止该环境等待；
    聚焦测试中的 H2 Mapper、Flyway 版本/SQL 契约均已通过。

## 部署

- 未部署；本次先完成代码、测试、提交与推送，部署前另行确认 test1。

## 遗留 / 跟进

- #49 现有错误状态需在新版本部署后由协调器自动收敛；部署前不直接修改 test1 数据。

## 2026-08-11：test1 拉群任务 #57 补充

### 本轮实现

- WhatsApp 被动 `group.invite_link_changed` 与管理员主动查询统一调用
  `GroupInviteLinkService.applyCurrentInvite`，不再各写一套 Mapper SQL。
- 当前邀请码只维护在 `group_link_preview.invite_code`；`group_link.link_url` 保留首次入口身份，
  `pull_task_group_execution.invite_code/normalized_link` 保留任务冻结审计快照，
  `group_link_health` 维护可用/失效/封禁状态。
- 管理员进群成功后先绑定原 `group_link_id` 与 `group_jid`，随后 WhatsApp 链接变更事件可命中
  原群入口，不会按 JID 另建一条群记录。
- `INVITE_REVOKED` / `INVITE_INVALID` 回调不再立即结束执行行：退避后先使用本地已收到的不同
  invite code；没有替代值时选择一个已在群管理员主动查询，查询结果通过公共方法落库，再用新链接
  重试一次；仍无新码或重试仍明确失效时结束执行行，避免无限循环。
- Web 重试参数为 `https://chat.whatsapp.com/<current-code>`，Android 重试参数为大小写敏感的纯 code。
- metadata 同步、历史群详情和历史群手工刷新均改走公共当前链接写入；旧的
  `updateInviteCodeByGroupJid` 写入路径已删除。
- 运营分组选群时优先返回 `group_link_preview` 中的当前邀请码；任务登记按当前邀请码反查原
  `group_link_id`，避免链接轮换后生成重复群入口。
- 邀请码观测恢复健康状态增加观察时间保护，旧事件不能覆盖更新的 `INVITE_REVOKED`；已有封禁
  状态不会被邀请码事件解除。

### 本轮验证

- TDD 红灯覆盖：旧链接事件覆盖新失效状态、回调立即失败、缺少主动恢复执行器、metadata 绕过
  公共写入、原群入口未绑定、分组继续返回旧链接、当前 code 生成重复群入口。
- 17 个相关测试类聚合执行：退出码 0，79 tests 全部通过，包含真实 H2 MySQL 模式 Mapper、
  状态机和普通拉群端到端测试。
- `xmllint --noout` 校验 3 个变更 Mapper XML：退出码 0。
- `mvn -DskipTests package`：退出码 0，Spring Boot jar 构建成功。
- `mvn test` 进入既有 `PromotionCapiEventOutboxSchemaDbTest` 后持续连接本机不可达真实数据源，
  约 60 秒后人工终止（退出码 130）；该环境限制与本轮聚焦测试分开记录，不声明全量通过。

### 部署与存量任务

- 本轮没有新增表或列，但代码依赖既有 Flyway
  `V109__group_invite_code_observed_at.sql`；test1 必须先确认该迁移已执行，再部署应用。
- 未部署、未修改 test1 数据。task #57 若已错过旧版本的失败回调，不保证仅靠部署自动重放；
  部署后需先只读核对 execution/action/group preview 状态，再决定安全重排或重建该任务。
