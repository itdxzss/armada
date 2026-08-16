# WhatsApp 群快照与邀请码 Kafka 同步 Implementation Plan

**Goal:** 把首次建档、人工刷新和异常修复中的群详情/邀请码主动查询，从 Armada 批量同步 HTTP 改为现有
Outbox + Kafka 命令/结果闭环；100 个账号发现 500 个唯一群时只创建约 500 个群任务，并由协议端完整解析后
回写控端数据库。

**Architecture:** 复用现有 Web master command topic、Android group-action command topic、group event topic 和
`protocol_command_outbox`。新增 `group.snapshot_sync.requested` / `group.snapshot_sync_result_reported` 契约；
自动任务按租户与群唯一，metadata 与邀请码按 scope 独立结算，邀请码权限失败时管理员优先、普通成员兜底轮换。

**Design:** `docs/superpowers/specs/2026-08-16-group-snapshot-kafka-sync-design.md`

**Related design:** `docs/superpowers/specs/2026-08-16-group-event-direct-projection-design.md`

**Repositories:**

- Backend: `/Users/daishuaishuai/IdeaProjects/armada`
- Web protocol: `/Users/daishuaishuai/IdeaProjects/armada-protocol`
- Android protocol: `/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan`

**Constraints:**

- 不新增 Kafka topic，不新增 Outbox 表；
- 一群一条命令/结果，不按账号群关系派发，不把 500 群合并成单条消息；
- 不在普通群成员/资料变更事件后重新查询 metadata；
- 不执行邀请链接 revoke/reset；
- 协议端必须解析结构化结果，ACK、日志和原始 node 均不能代表业务成功；
- metadata 成功、invite 失败时必须保存 metadata，后续只重试 invite；
- 每个任务控制在 4 小时以内并独立验证；
- 三个仓库的用户在途修改必须保留；
- Flyway 使用实施时确认未占用的下一版本，当前候选为 V123，不得覆盖在途 V121/V122。

---

## 实施依赖与关键路径

```text
Task 1 契约/环境门禁
  -> Task 2 Armada 数据状态
  -> Task 3 Armada 结果 consumer
  -> Task 4 Armada 纯落库 reducer
  -> Task 5 Armada Outbox 命令
       -> Task 6 Web executor
       -> Task 7 Android executor
  -> Task 8 自动任务 Kafka 派发
  -> Task 9 候选轮换与超时恢复
  -> Task 10 人工批量刷新
  -> Task 11 历史群/主动兜底收口
  -> Task 12 监控与开关
  -> Task 13 跨仓验证
  -> Task 14 test1/500 群验收与发布
```

Task 6、Task 7 可在 Task 1/5 契约固定后并行实现。环境发布必须先让 Armada 具备安全消费结果能力，再部署
Web/Android executor，最后打开 Armada 派发开关。

---

## Task 1：冻结契约、Topic 映射和协议实证门禁（≤4h）

**Files:**

- Modify: 设计文档（仅在实证与当前设计不一致时）
- Create: 三仓就近的 command/result contract tests 或 fixtures
- Verify: Armada/Web/Android 当前 Kafka 配置与部署模板

- [ ] 固定 v1 名称：`group.snapshot_sync.requested`、`group.snapshot_sync_result_reported`、
  `METADATA/INVITE_CODE`、`SUCCESS/FAILED`。
- [ ] fixture 覆盖：双 scope 成功、metadata 成功 + invite 权限失败、invite-only 重试、账号不在群、超时、非法字段。
- [ ] 只读核对 Web master topic、Android group-action/node suffix、两端群结果实际 topic 和 Armada 订阅范围。
- [ ] Android 群结果默认值与部署值若未对齐，先修现有配置；不得新增 topic 规避。
- [ ] test1 同一普通成员做邀请权限关闭/打开 A/B，保存脱敏成功/错误 fixture。
- [ ] 用实际最大成员群测量 Web/Android 结果 JSON；小于 800 KiB 才继续 v1 单消息，超过则先补分片设计。

**Exit gate:** 三仓 fixture 字段一致；Topic 无新增；权限和 payload 有真实证据。

---

## Task 2：扩展 Armada 耐久任务状态（≤4h）

**Files:**

- Create: `armada-api/src/main/resources/db/migration/V123__group_snapshot_kafka_sync.sql`（版本实施时复核）
- Modify: `GroupMetadataSyncTask.java`、`GroupBatchTaskItem.java`、`GroupBatchTaskItemStatus.java`
- Modify: `GroupMetadataSyncTaskMapper.java/xml` 与 batch item Mapper/XML
- Create/Modify: H2、SQL shape、Flyway 测试

- [ ] 先写失败测试：读写 commandId、scope、候选游标、结果截止时间；batch item 支持 `WAITING_RESULT`。
- [ ] 租约恢复只接管已经超过结果 deadline 的等待项；所有更新必须含 tenant 和预期 commandId/status。
- [ ] 自动任务增加 `current_command_id/requested_scope_mask/completed_scope_mask/candidate_cursor/result_deadline_at`。
- [ ] batch item 增加等价关联字段和等待状态；保留现有任务/群唯一约束。
- [ ] 提供绑定 commandId、结果 CAS、超时恢复、取消不结算计数的 Mapper 方法。

```bash
cd armada-api
mvn -Dtest='*GroupMetadataSyncTask*Test,*GroupBatchTask*Test,*GroupSnapshot*Migration*Test' test
```

**Exit gate:** 旧数据无损；旧/重复 commandId 无法推进当前 attempt。

---

## Task 3：Armada 接入查询结果契约与严格 consumer（≤4h）

**Files:**

- Create: `ProtocolGroupSnapshotSyncResultEvent.java` 及 metadata/invite/participant records
- Create: `ProtocolGroupSnapshotSyncResultSink.java`
- Modify: `ProtocolGroupEventConsumer.java`
- Modify: `ProtocolGroupEventConsumerTest.java`

- [ ] 先写合法 WEB/ANDROID、部分成功、缺 scope、未知 outcome/errorCode、非法 JID/成员/时间/关联测试。
- [ ] envelope `accountId` 必须等于 `data.protocolAccountId`；backend、tenant、account、task correlation 必填。
- [ ] consumer 只做 JSON、白名单、数量、JID 和关联校验，不查协议、不落业务状态。
- [ ] 日志不得包含 inviteCode、participants 原文或错误 body。

```bash
cd armada-api
mvn -Dtest=ProtocolGroupEventConsumerTest test
```

**Exit gate:** Armada 在不开派发时可安全接受滚动升级期间的新结果事件。

---

## Task 4：拆出纯结构化快照落库 reducer（≤4h）

**Files:**

- Create: `GroupMetadataSnapshotObservation.java`
- Create: `GroupMetadataSnapshotReducer.java` 及实现/测试
- Modify: `GroupMetadataSnapshotServiceImpl.java`
- Reuse: `GroupMetadataSnapshotPersistence`、成员事实、新群模型兼容写组件
- Reuse: `GroupInviteLinkService` / `GroupCurrentInvitePersistence`

- [ ] 写纯落库测试：完整成员、管理员关系、false、描述空值、invite 大小写、部分成功、旧观察和重复命令。
- [ ] 提取 metadata 归一、国家解析、成员构造和持久化；输入只接受结构化 observation。
- [ ] 过渡期 HTTP service 改为“读取 -> observation -> reducer”，Kafka sink 直接调用 reducer。
- [ ] invite 成功调用 current invite 事实服务；FAILED 不传空 code、不覆盖旧值。

```bash
cd armada-api
mvn -Dtest='GroupMetadataSnapshot*Test,GroupInviteLinkServiceImplTest,GroupParticipantObservationServiceImplTest' test
```

**Exit gate:** Kafka 结果落库不会再次调用 metadata/invite 协议端口。

---

## Task 5：Armada Outbox 新增快照命令与现有 Topic 路由（≤4h）

**Files:**

- Create: `ProtocolGroupSnapshotSyncCommandRequest.java` 及 reference/scope/source records
- Modify: `ProtocolCommandOutboxService.java`
- Modify: `ProtocolCommandOutboxServiceImpl.java`
- Modify/Create: Outbox service、Mapper、topic routing 测试

- [ ] 写失败测试：Web -> master；Android -> group-action；key=protocolAccountId；非法 scope/JID/backend 拒绝。
- [ ] 实现 `group.snapshot_sync.requested`，复用现有 ID、batch insert、dispatcher trigger；不加 producer/topic property。
- [ ] payload 不传旧邀请码、原始 node 或敏感日志字段。
- [ ] claim、选择账号、Outbox insert、任务回绑 commandId/deadline 必须同一事务。

```bash
cd armada-api
mvn -Dtest='ProtocolCommandOutboxServiceImplTest,*ProtocolCommandOutbox*Test,*GroupSnapshot*Dispatch*Test' test
```

**Exit gate:** 不新增 topic/表；事务失败不产生任务与 Outbox 半状态。

---

## Task 6：Web 协议执行快照命令（≤4h）

**Files:**

- Modify: `protocol-layer/src/commands/types.ts`
- Modify: master/worker consumer 与 socket 最小接口
- Create: `protocol-layer/src/commands/group-snapshot-sync-executor.ts`
- Create: executor/consumer/event publisher tests
- Modify: event subjects/publisher 类型白名单（只加 event，不改 topic 路由）

- [ ] 先测 scope、owner、socket generation、双成功、部分成功、权限、不在群、离线、超时和 publish 失败。
- [ ] owner worker 直接调用 `groupMetadata` / `groupInviteCode`，不得 HTTP 回调自身。
- [ ] 完整解析 PN/LID、角色和群设置；完整成员才能标 `snapshotComplete=true`。
- [ ] 把 Baileys/Boom 错误归一为稳定码；各 scope 独立结算。
- [ ] broker ACK 后才确认输入命令；commandId 重放补发缓存结果，避免重复 IQ。

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
npm test -- --runInBand src/commands/types.test.ts src/commands/group-snapshot-sync-executor.test.ts
npm run lint
npm run build
```

**Exit gate:** Web 发布完整/部分结果，发布失败不丢命令，没有新 topic。

---

## Task 7：Android 协议执行快照命令（≤4h）

**Files（实施前按 Android AGENTS 和现有成员查询模式确认）:**

- Create: `internal/armada/group_snapshot_sync_command.go`
- Create: `internal/armada/group_snapshot_sync_executor.go`
- Create: `internal/armada/group_snapshot_sync_event.go`
- Create: 对应 Go tests
- Modify: group-action router/start wiring/options/event publisher whitelist
- Reuse: 详细群查询、`GetGroupCode`、account operation gate、结果 publisher

- [ ] 先测 JSON、账号定位、单账号串行、双成功、部分成功、权限、不在群、离线、超时、publish/DLQ。
- [ ] 调用现有详细群和 `GetGroupCode` 能力，输出与 Web fixture 完全一致。
- [ ] PN/LID 只能来自明确响应；字段缺失保持 unknown，不用 Go 零值伪造 false。
- [ ] 协议边界归一 401/403；结果可靠发布前不提交输入 offset，WhatsApp ACK 不算业务成功。
- [ ] commandId 重放不重复查询 WhatsApp。

```bash
cd /Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan
gofmt -w <本任务改动的.go文件>
go vet ./...
go build ./...
go test ./...
go test -race ./internal/armada
```

**Exit gate:** Android 与 Web fixture 等价；强制命令通过或如实记录环境阻塞。

---

## Task 8：自动首次快照改为 Kafka 派发（≤4h）

**Files:**

- Modify: `AccountGroupMembershipReportServiceImpl.java`
- Modify: `GroupMetadataSyncTaskServiceImpl.java`
- Modify: `GroupMetadataSyncJob.java`
- Create: `GroupSnapshotSyncDispatchService.java`
- Modify/Create: report/task/job/dispatch tests 和 Mapper H2 tests

- [ ] 模拟 100 账号重复回报同一批 500 群，关系照常写入，自动任务严格 500 行。
- [ ] 测试 job 只 claim + Outbox，不调用 metadata/invite HTTP port。
- [ ] 仅首次无完整快照、自建群缺邀请码、REPAIR/BACKFILL 时 enqueue。
- [ ] 普通重连、重复群列表和可直接解析的群变更不触发完整查询。
- [ ] selector 选在线正常在群账号，首条命令 scope=`METADATA+INVITE_CODE`，job 不等待结果。

```bash
cd armada-api
mvn -Dtest='AccountGroupMembershipReportServiceImplTest,GroupMetadataSync*Test,GroupSnapshotSyncDispatchServiceTest' test
```

**Exit gate:** 500 唯一群只生成 500 任务；自动主链无同步 HTTP。

---

## Task 9：结果结算、候选轮换与超时恢复（≤4h）

**Files:**

- Create: `GroupSnapshotSyncResultService.java` 及实现/sink adapter
- Modify: `GroupExecutionAccountSelector.java`
- Modify: task Mapper/XML 和超时恢复
- Create/Modify: result/idempotency/selector/timeout tests

- [ ] 测试双成功、部分成功、invite-only 第二候选、4 候选耗尽、旧/重复结果、超时、rerun、DEFERRED。
- [ ] 一个事务内落 metadata/成员/关系和 completed scope，再决定是否生成 invite-only 下一命令。
- [ ] 管理员/群主优先、普通成员兜底、排除已尝试账号、稳定排序、最多 4 个。
- [ ] 权限换账号；不在群校准单账号关系；离线换候选；网络有限重试；群不可用停止。
- [ ] 所有失败保留旧邀请码；metadata 已成功后不重复查询完整成员。

```bash
cd armada-api
mvn -Dtest='GroupSnapshotSyncResultServiceTest,GroupExecutionAccountSelectorTest,*GroupMetadataSyncTask*Test' test
```

**Exit gate:** 重启和候选变化不会无限回第一个账号，迟到结果不覆盖新 attempt。

---

## Task 10：手工批量刷新切换到 Kafka（≤4h）

**Files:**

- Modify: `GroupBatchLinkRefreshWorker.java`
- Modify: `GroupBatchInfoRefreshWorker.java`
- Modify: `GroupBatchTaskJob.java`、`GroupBatchTaskSettlement.java`
- Modify/Create: batch worker/job/API progress tests

- [ ] worker 发命令后进入 `WAITING_RESULT`，不能当场成功；下一轮不重复派发。
- [ ] result consumer 独立结算；取消后晚到结果可更新群事实，但不得改变已取消任务计数。
- [ ] 刷新链接 scope=`INVITE_CODE`；获取最新群信息 scope=`METADATA`，保持两个按钮既有职责。
- [ ] 批量任务查询/取消 API 不改，错误日志不泄露邀请码或成员。

```bash
cd armada-api
mvn -Dtest='GroupBatch*Test,GroupSnapshotSyncResultServiceTest' test
```

**Exit gate:** 手工批量不占 Armada 线程等待 HTTP，页面进度仍准确。

---

## Task 11：历史群刷新与主动邀请码兜底收口（≤4h）

**Files:**

- Modify: `HistoricalGroupAccountGroupRefreshService.java`
- Modify: `GroupInviteLinkServiceImpl.java`
- Modify: 相关 service tests
- Audit: 所有 `GroupInvitePort.getInvite` / metadata port 调用方

- [ ] 用 `rg` 列出同步读取点，区分批量主链和必须保留的低频兼容/写前校验。
- [ ] 历史群刷新先更新关系，再按唯一群 upsert/派发 Kafka。
- [ ] 删除各入口重复“只选管理员”逻辑，统一候选和结果服务。
- [ ] 同步返回值 API 若不能立即异步化，明确保留兼容 HTTP 和退出条件，不静默改变语义。

```bash
cd armada-api
rg -n 'GroupInvitePort|getInvite\(|FixedAccountGroupMetadataPort|getMetadata\(' src/main/java/com/armada
mvn -Dtest='HistoricalGroup*Test,GroupInviteLinkServiceImplTest' test
```

**Exit gate:** 首次/批量/后台刷新走 Kafka；保留 HTTP 调用均有明确低频理由和测试。

---

## Task 12：开关、指标、日志与结果超时（≤4h）

**Files:**

- Create: group snapshot Kafka properties/metrics
- Modify: application/deploy config templates（只加业务参数，不加 topic）
- Modify: dispatch/result/timeout structured logging
- Create/Modify: property binding、metrics、timeout tests

- [ ] 固定 enabled、batch size、account concurrency、result timeout、max candidates=4、HTTP fallback 默认关闭。
- [ ] 增加 task/command/result/candidate/payload/lag/end-to-end/stale/permission 指标。
- [ ] deadline 到期后按当前 commandId CAS 重试/换候选；旧结果可落事实但不结算新 attempt。
- [ ] 日志和指标 label 不含邀请码、成员列表、手机号或原始 node。

```bash
cd armada-api
mvn -Dtest='*GroupSnapshot*PropertiesTest,*GroupSnapshot*MetricsTest,*GroupSnapshot*Timeout*Test' test
```

**Exit gate:** 可停止新派发且不影响结果消费，超时可恢复，无敏感日志。

---

## Task 13：三仓完整验证与差异审计（≤4h）

- [ ] Armada 定向与全量：

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest='*GroupSnapshot*,*GroupMetadataSync*,*GroupBatch*,ProtocolGroupEventConsumerTest,ProtocolCommandOutboxServiceImplTest' test
mvn test
```

- [ ] Web 完整验证：

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
npm test -- --runInBand
npm run lint
npm run build
```

- [ ] Android 强制验证：

```bash
cd /Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan
gofmt -w <本功能改动的.go文件>
go vet ./...
go build ./...
go test ./...
go test -race ./internal/armada
```

- [ ] 静态确认无新 topic、无新 Outbox 表、批量主链无同步 HTTP、事件直投影不 enqueue metadata。
- [ ] 逐仓执行 `git status --short`、`git diff --check`、`git diff --name-only`，排除无关在途文件。
- [ ] 失败和环境阻塞写入 change record，不以“应该通过”代替真实证据。

**Exit gate:** 三仓强制验证有真实输出且 diff 只包含本功能范围。

---

## Task 14：test1 验收、500 群压测与发布（≤4h/轮）

**前置:** 再次确认目标为 test1；真实 Kafka、真库、部署和生产操作不由本计划自动授权。

- [ ] 先部署 Armada migration/result consumer/reducer，派发开关关闭。
- [ ] 再部署 Web/Android command executor，开关关闭。
- [ ] 小流量验证：Web 管理员、Android 管理员、普通成员权限关闭/打开、部分成功、邀请轮换。
- [ ] 100账号/500唯一群记录任务数、Outbox数、账号分布、lag、payload、完成时间、权限失败和DB写入量。
- [ ] 验收 500 唯一群严格 500 自动任务；额外命令只能由稳定错误码触发。
- [ ] 验收控端 metadata、成员、管理员、账号群关系、邀请码均可由现有 API/页面读取。
- [ ] 验收 ACK 不提前成功，重复/迟到不回滚，普通增量事件仍无 metadata 后置查询。
- [ ] 打开 Kafka 主链、关闭批量 HTTP，演练停止派发但继续消费晚到结果。
- [ ] 生产发布另行获取明确授权。

---

## 完成定义

1. 三仓契约 fixture 一致；
2. 未新增 topic 和 Outbox 表；
3. 自动任务按唯一群去重；
4. Web/Android 均完整解析并可靠回报；
5. Armada 落库后页面可见最新群事实；
6. 部分成功、权限轮换、重复/迟到/超时均有测试；
7. 批量主链不再同步 HTTP；
8. 普通群变更事件不触发完整 metadata；
9. test1 权限 A/B 与 100账号/500群有真实验收证据；
10. change record 包含测试输出、部署环境、回滚演练和遗留项。
