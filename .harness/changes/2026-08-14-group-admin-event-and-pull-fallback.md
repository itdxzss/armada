# 变更记录：群管理员事件事实与拉群缺失兜底

- 日期 / 分支 / worktree：2026-08-14 / `1.0.3-snapshot` / `armada` 主工作树
- 需求来源：用户确认按“角色事件主链、任务结果不双写、本地缺失时异步点查”实施；设计见 `docs/superpowers/specs/2026-08-14-group-admin-event-and-pull-fallback-design.md`
- 状态：实现与本地验证完成，待授权部署 test1 验收

## 目标（一句话）

用 WhatsApp promote/demote 事件实时维护受控管理员事实，在普通拉群本地无管理员时异步查询一次当前成员角色后重新选号，并停止成功群的固定周期 metadata 轮询。

## 缺口拆解 / 任务清单

- [x] 核对 test1 任务 #122、群成员快照、账号群关系和管理员选号证据。
- [x] 核对 Web `group.participant_changed`、metadata 同步请求和后端未消费现状。
- [x] 核对 Android WGP2 角色通知过滤点及现有成员定点查询能力。
- [x] 核对现有异步 `group.members.query.requested/result_reported` 复用边界。
- [x] 用户确认书面设计。
- [x] 编写实施计划并按 TDD 实现协议事件载荷与 metadata 触发收窄。
- [x] 按 TDD 实现 Android WGP2 `promote/demote` 解析和统一角色事件发布。
- [x] 按 TDD 删除 `SUCCEEDED` 群的周期候选，保留首次快照、事件、重试和手动刷新调度。
- [x] 按 TDD 实现后端角色事实消费、成员状态与账号群关系对齐。
- [x] 按 TDD 实现 `MANAGER_ADMIN_DISCOVERY` 异步兜底和历史等待行唤醒。
- [x] 完成聚焦回归、构建、XML/Flyway 校验和 test1 验收准备。

## 关键设计决策

- Web/Android 统一以 `group.participant_changed` 作为实时管理员事实入口；任务动作回执不双写全局关系。
- 只取消 promote/demote 引发的完整 metadata 请求；add/remove 和 groups.update 保持原行为。
- 删除成功群默认 60 秒再次到期的后台查询；保留同步 Job 处理首次建档、事件、重试和手动刷新。
- 复用 `whatsapp_group_member_state` 和 `account_group_membership.is_admin`，不新增管理员镜像列。
- 拉群兜底复用现有异步成员查询框架，正常派发线程不等待网络。
- 不静态回填旧成员快照；Flyway 只唤醒符合条件的活动等待执行行。
- Android 放行 WGP2 `promote/demote` 并发布同契约增量事件；既有定点查询继续作为任务缺失兜底。

## 验证（evidence-before-done）

### 后端 `armada/armada-api`

- 以下相关回归测试退出码 `0`：metadata 停止周期调度、角色事实、Web/Android 事件消费、成员查询 adapter、管理员 discovery 状态机、拉群端到端、资源恢复、V114/Flyway 合同。

```bash
mvn -q -DargLine='-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading' \
  -Dtest=GroupMetadataSyncTaskServiceImplTest,GroupMetadataSyncJobTest,GroupParticipantObservationServiceImplTest,WhatsappGroupMemberCacheMapperH2Test,AccountGroupMembershipMapperSqlTest,ProtocolGroupEventConsumerTest,ProtocolGroupParticipantChangedSinkAdapterTest,ProtocolGroupMembersResultAdapterTest,PullTaskExecutionEndToEndIntegrationTest,PullTaskResourceRecoveryTransactionIntegrationTest,PullTaskManagerAdminTransactionIntegrationTest,PullTaskManagerAdminProcessorTest,PullTaskMemberQueryAwaitServiceTest,PullTaskMemberQueryServiceTest,PullTaskMemberQueryResultServiceImplTest,PullTaskMemberQueryCommandServiceTest,GroupAdminEventAndPullFallbackMigrationSqlTest,PullTaskManagerAdminStageMigrationSqlTest,FlywayMigrationVersionContractTest \
  test
mvn -q -DskipTests package
```

- `mvn test` 会进入真实数据库型 `*DbTest` 并持续尝试 Hikari 连接；为避免未经授权连接真实环境已中止。`GroupExecutionAccountSelectorDbTest` 和 MySQL Testcontainers 没有在本机执行；对应 SQL 合同、H2、service/integration 测试已通过。

### Web 协议 `armada-protocol/protocol-layer`

```bash
npm test -- --runInBand
npm run lint
```

- Jest：`67 passed / 67 suites`，`638 passed / 638 tests`。
- TypeScript `tsc --noEmit`：退出码 `0`。

### Android 协议 `whatsapp-server-feature-android-zhuan`

```bash
env GOCACHE=/private/tmp/armada-admin-fix-go-cache go vet ./...
env GOCACHE=/private/tmp/armada-admin-fix-go-cache go build ./...
env GOCACHE=/private/tmp/armada-admin-fix-go-cache go test -count=1 ./internal/service/node/processor ./internal/armada
env GOCACHE=/private/tmp/armada-admin-fix-go-cache go test ./...
```

- `go vet ./...`、`go build ./...`：退出码 `0`。
- 本次改动包：processor `0.090s`、armada `6.221s`，退出码 `0`。
- 全量测试中其余业务包通过；仅仓库既有 `pkg/noise` 失败（`vectors.txt` 缺失，以及 7 个 Noise 历史向量/回滚断言不一致），共 8 个失败用例，与本次群事件改动无关。

### 提交边界

- 后端提交：`b2235541`、`55b70a95`、`f649bb5d`、`f6ad164f`、`04b3bbd3`。
- Web 协议提交：`81485da`。
- Android 协议提交：`8c6480d`。
- 三个仓库 `git diff --check` 均通过；未把 `armada` 的 `.claude/worktrees`、批处理脚本或 `armada-protocol/.codegraph` 纳入本任务提交。

## 部署

- 业务代码已在三个本地仓库分别提交。
- 未部署、未连接 test1 数据库、未修改任何远程数据。
- V114 仅在后端部署时一次性唤醒精确命中的活动等待行，不做账号关系历史回填。

## 遗留 / 跟进

- 获得明确部署授权后，按后端 → Web/Android 协议顺序部署 test1。
- 验收任务 #122：V114 唤醒执行行 169；只创建一条稳定业务键的 `MANAGER_ADMIN_DISCOVERY`；回调先在同一事务写入管理员事实再唤醒；重选管理员并继续 `PROMOTE_MANAGER`。
- 观察成功群不再生成固定 60 秒 metadata 命令；首次建档、add/remove/groups.update、失败重试和手动刷新仍可触发。
- 如需修复 Android `pkg/noise` 历史测试，另立任务处理，避免扩大本次群管理员修复范围。

## 回滚点

- 后端从新到旧回退：`04b3bbd3`（一次性唤醒）、`f6ad164f`（定点发现）、`f649bb5d`（事件消费）、`55b70a95`（统一事实）、`b2235541`（停止周期轮询）。V114 已执行后的状态唤醒属于数据状态变化，代码回滚不会自动恢复等待原因。
- Web 协议回退：`81485da`。
- Android 协议回退：`8c6480d`。
