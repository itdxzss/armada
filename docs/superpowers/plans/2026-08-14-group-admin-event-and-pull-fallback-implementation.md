# Group Admin Event and Pull Fallback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkboxes so progress can be tracked without re-deriving scope.

**Goal:** 让 Web/Android 的 promote、demote 群事件实时维护 Armada 管理员事实；拉群在本地找不到管理员时只做一次异步定点成员查询后重选；停止成功群的 60 秒周期 metadata 轮询，并唤醒 #122 同类存量执行。

**Architecture:** 两个协议端统一发布 `group.participant_changed`，后端把角色事件和成员查询结果汇入同一个有时序保护的群成员事实服务，再更新成员状态、详情快照和 `account_group_membership`。`MANAGER_ADMIN` 保留原严格选号，只有严格候选为空时才走现有 Outbox/Kafka 成员查询，使用稳定业务键复用结果，不同步等待、不循环查询。

**Tech Stack:** Java 17、Spring Boot、MyBatis、Flyway、JUnit 5、H2/MySQL Testcontainers；TypeScript、Fastify、Baileys、Vitest；Go 1.25、zap、标准 `testing`。

**Repositories:**

- 后端：`/Users/daishuaishuai/IdeaProjects/armada`
- Web 协议：`/Users/daishuaishuai/IdeaProjects/armada-protocol`
- Android 协议：`/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan`

**Constraints:** 不改 `group.action_result_reported` 的任务内语义；不做历史快照批量回填；不新增同步网络查询；不恢复成功群周期扫描；每个仓库只提交本任务文件，保留现有未提交改动。

---

## Task 1: 停止成功群周期 metadata 轮询

**Files:**

- Modify: `armada-api/src/main/java/com/armada/group/service/impl/GroupMetadataSyncTaskServiceImpl.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/impl/GroupMetadataSyncTaskServiceImplTest.java`
- Verify: `armada-api/src/test/java/com/armada/group/mapper/GroupMetadataSyncTaskMapperDbTest.java`
- Verify: `armada-api/src/test/java/com/armada/group/scheduler/GroupMetadataSyncJobTest.java`

- [ ] **Step 1: 写出历史成功行不再被选中、成功后不再重排的失败测试**

在 `GroupMetadataSyncTaskServiceImplTest` 增加/修改断言：

```java
assertThat(service.findDue(10, now)).doesNotContain(succeededDueTask);
service.succeed(taskId, lockOwner, now);
verify(mapper).markSucceeded(taskId, lockOwner, null, now);
```

同时保留 `PENDING`、`RETRY`、租约恢复、事件重新入队可被 `findDue` 获取的测试。

- [ ] **Step 2: 运行目标测试确认先失败**

```bash
mvn -pl armada-api -Dtest=GroupMetadataSyncTaskServiceImplTest test
```

Expected: 旧实现仍把 `SUCCEEDED` 加入后台候选，且成功后写入 `now + periodicRefreshMs`。

- [ ] **Step 3: 删除周期候选和成功重排分支**

在 `GroupMetadataSyncTaskServiceImpl` 中：

- 删除 `PERIODIC_STATUSES`、`MAX_REFRESH_TASKS_PER_RUN` 和 `periodicRefreshMs`；
- `findDue` 只取业务触发/失败重试任务，不再追加 `SUCCEEDED` 候选；
- `succeed` 把 `next_run_at` 写为 `NULL`；
- `defer` 不再为 `SUCCEEDED` 计算下一轮时间；
- 保留首次建档、事件、人工刷新、重试与租约恢复。

- [ ] **Step 4: 运行 metadata 测试**

```bash
mvn -pl armada-api -Dtest=GroupMetadataSyncTaskServiceImplTest,GroupMetadataSyncTaskMapperDbTest,GroupMetadataSyncJobTest test
```

Expected: 全部 PASS。

- [ ] **Step 5: 提交本任务**

```bash
git add armada-api/src/main/java/com/armada/group/service/impl/GroupMetadataSyncTaskServiceImpl.java armada-api/src/test/java/com/armada/group/service/impl/GroupMetadataSyncTaskServiceImplTest.java
git commit -m "fix: 停止成功群周期元数据轮询"
```

---

## Task 2: 建立统一的群成员角色事实写入服务

**Files:**

- Create: `armada-api/src/main/java/com/armada/group/model/dto/GroupParticipantObservation.java`
- Create: `armada-api/src/main/java/com/armada/group/model/enums/GroupParticipantObservationSource.java`
- Create: `armada-api/src/main/java/com/armada/group/service/GroupParticipantObservationService.java`
- Create: `armada-api/src/main/java/com/armada/group/service/impl/GroupParticipantObservationServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/group/mapper/WhatsappGroupMemberCacheMapper.java`
- Modify: `armada-api/src/main/resources/mapper/group/WhatsappGroupMemberCacheMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/group/mapper/AccountGroupMembershipMapper.java`
- Modify: `armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/group/mapper/WhatsappGroupMemberSnapshotMapper.java`
- Modify: `armada-api/src/test/java/com/armada/group/mapper/WhatsappGroupMemberCacheMapperH2Test.java`
- Modify: `armada-api/src/test/java/com/armada/group/mapper/WhatsappGroupMemberCacheMapperMysqlTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/mapper/AccountGroupMembershipMapperSqlTest.java`
- Create: `armada-api/src/test/java/com/armada/group/service/impl/GroupParticipantObservationServiceImplTest.java`

- [ ] **Step 1: 用服务测试固定事实收敛行为**

新增以下用例：

- promote 写 `ROLE_EVENT`、更新已有详情快照并把受控账号关系设为 `IN_GROUP/is_admin=1`；
- demote 把 `is_admin` 设为 `0`；
- `MEMBER_QUERY` 的 `inGroup=false` 清除管理员角色；
- 外部号码只写成员事实，不创建受控账号关系；
- LID 没有 phone 时复用已有状态行 phone；
- 旧事件、重复事件、同时间低优先级快照不覆盖新角色事实；
- tenant/group/member 隔离。

- [ ] **Step 2: 运行新增服务与 Mapper 测试确认先失败**

```bash
mvn -pl armada-api -Dtest=GroupParticipantObservationServiceImplTest,WhatsappGroupMemberCacheMapperH2Test,AccountGroupMembershipMapperSqlTest test
```

Expected: 新类型/方法尚不存在或新优先级断言失败。

- [ ] **Step 3: 定义领域输入与来源**

```java
public record GroupParticipantObservation(
        Long tenantId,
        Long observerAccountId,
        String groupJid,
        String targetJid,
        String participantJid,
        String phone,
        boolean inGroup,
        boolean admin,
        GroupParticipantObservationSource source,
        long observedAt,
        String sourceEventId) {
}
```

```java
public enum GroupParticipantObservationSource {
    ROLE_PROMOTE,
    ROLE_DEMOTE,
    MEMBER_QUERY
}
```

服务入口固定为批量事务方法：

```java
void apply(List<GroupParticipantObservation> observations);
```

- [ ] **Step 4: 实现成员状态的时序保护与胜出事实回读**

新增 Mapper 查询，可按 `tenantId + groupJid + participantJids` 回读 upsert 后胜出的状态。来源映射：角色事件写 `ROLE_EVENT`，查询写 `MEMBER_QUERY`。同时间优先级固定为：

```text
REMOVE/LEAVE > ROLE_EVENT > ADD_EVENT > MEMBER_QUERY/FULL_SNAPSHOT
```

仍以 `state_updated_at` 为第一排序、来源为第二排序、`source_event_id` 为最终确定性裁决；输入 phone 为 null 时保留已有 phone。

- [ ] **Step 5: 只依据胜出事实更新详情快照和受控账号关系**

先按群链接读取现有详情快照，以 participant JID 或规范化 phone 找到实际快照行；再调用现有角色更新 SQL。只用当前租户的有效账号 phone 建立关系。`status_source` 使用：

```text
ROLE_PROMOTE -> WGP2_PROMOTE
ROLE_DEMOTE  -> WGP2_DEMOTE
MEMBER_QUERY -> GROUP_MEMBER_QUERY
```

`AccountGroupMembershipMapper.xml` 的同时间来源优先级统一为：

```text
WGP2_REMOVE/WGP2_LEAVE = 5
WGP2_PROMOTE/WGP2_DEMOTE = 4
WGP2_ADD = 3
GROUP_MEMBER_QUERY = 2
GROUP_SNAPSHOT = 1
```

- [ ] **Step 6: 运行 H2 与真实 MySQL Mapper 验证**

```bash
mvn -pl armada-api -Dtest=GroupParticipantObservationServiceImplTest,WhatsappGroupMemberCacheMapperH2Test,WhatsappGroupMemberCacheMapperMysqlTest,AccountGroupMembershipMapperSqlTest test
```

Expected: 全部 PASS；如果 Docker 不可用，只记录 MySQL Testcontainers 的环境阻塞，H2 与单测必须通过。

- [ ] **Step 7: 提交本任务**

```bash
git add armada-api/src/main/java/com/armada/group armada-api/src/main/resources/mapper/group armada-api/src/test/java/com/armada/group
git commit -m "feat: 收敛群管理员角色事实"
```

提交前用 `git diff --cached --name-only` 排除不属于本任务的群列表改动。

---

## Task 3: 后端消费统一角色事件

**Files:**

- Modify: `armada-api/src/main/java/com/armada/platform/kafka/consumer/group/ProtocolGroupEventConsumer.java`
- Create: `armada-api/src/main/java/com/armada/group/service/ProtocolGroupParticipantChangedSink.java`
- Create: `armada-api/src/main/java/com/armada/group/service/impl/ProtocolGroupParticipantChangedSinkAdapter.java`
- Modify: `armada-api/src/test/java/com/armada/platform/kafka/consumer/group/ProtocolGroupEventConsumerTest.java`
- Create: `armada-api/src/test/java/com/armada/group/service/impl/ProtocolGroupParticipantChangedSinkAdapterTest.java`

- [ ] **Step 1: 写事件契约的失败测试**

覆盖：

- WEB、ANDROID promote/demote 都交给角色 sink；
- envelope `accountId` 必须等于 `data.protocolAccountId`；
- 正数 tenant/account、受支持 backend、`@g.us` 群 JID；
- participants 为 1..500，每项至少有 `id/lid/phoneNumber`；
- add/remove 可继续由旧路径处理或忽略，但不得写角色事实；
- 无法解析 phone 的合法 LID 事件仍可进入成员事实服务。

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -pl armada-api -Dtest=ProtocolGroupEventConsumerTest,ProtocolGroupParticipantChangedSinkAdapterTest test
```

- [ ] **Step 3: 增加事件 record、解析和 sink 路由**

在 consumer 中增加 `EVENT_GROUP_PARTICIPANT_CHANGED`，数据 record 明确包含：

```java
Long tenantId;
Long accountId;
String protocolAccountId;
String protocolBackend;
String groupJid;
String action;
List<ProtocolGroupParticipantIdentity> participants;
String operator;
String source;
```

发生时间取 envelope `occurredAt`，`eventId` 传给 sink。consumer 只把 `promote/demote` 送入角色写路径。

- [ ] **Step 4: 在 adapter 中规范化 PN/LID 并调用统一事实服务**

promote 映射 `ROLE_PROMOTE/admin=true`，demote 映射 `ROLE_DEMOTE/admin=false`，两者均为 `inGroup=true`。`sourceEventId` 使用 `<eventId>:<targetJid>`，保证同批不同成员独立幂等。

- [ ] **Step 5: 运行 consumer 与事实服务回归测试**

```bash
mvn -pl armada-api -Dtest=ProtocolGroupEventConsumerTest,ProtocolGroupParticipantChangedSinkAdapterTest,GroupParticipantObservationServiceImplTest test
```

- [ ] **Step 6: 提交本任务**

```bash
git add armada-api/src/main/java/com/armada/platform/kafka/consumer/group/ProtocolGroupEventConsumer.java armada-api/src/main/java/com/armada/group/service armada-api/src/test/java/com/armada/platform/kafka/consumer/group/ProtocolGroupEventConsumerTest.java armada-api/src/test/java/com/armada/group/service/impl/ProtocolGroupParticipantChangedSinkAdapterTest.java
git commit -m "feat: 消费群管理员角色事件"
```

---

## Task 4: Web 协议补齐角色事件业务上下文并停止角色 metadata 刷新

**Files:**

- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/worker/event-bridge.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/worker/event-bridge.test.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/worker/account-manager.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/worker/account-manager.heartbeat.test.ts`

- [ ] **Step 1: 写 Web 事件与 metadata 行为测试**

断言 promote/demote 事件含 `tenantId/accountId/protocolAccountId/protocolBackend=WEB`、group、action、PN/LID、operator、source；无 businessRef、旧 generation 或 terminating socket 不发布。断言 promote/demote 不再发布 `account.group_metadata_sync_requested`，add/remove 与 `groups.update` 仍发布。

- [ ] **Step 2: 运行目标测试确认失败**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
npm test -- --run src/worker/event-bridge.test.ts src/worker/account-manager.heartbeat.test.ts
```

- [ ] **Step 3: 给 event bridge 注入当前 socket 的业务引用**

在 `EventBridgeContext` 增加只读回调：

```ts
getBusinessEventData: () => Record<string, unknown> | null;
```

`account-manager.ts` 用现有 `businessEventData(ctx)` 提供值；bridge 发布角色事件时把它展开到 data，并显式写入：

```ts
protocolBackend: "WEB",
source: "wa_group_participants_update"
```

不得从事件里的手机号猜 tenant/account。

- [ ] **Step 4: 缩小 metadata 请求触发集合**

`groupParticipantsSignalHandler` 只让 `add/remove` 发布 metadata 同步请求；`promote/demote` 仅走实时角色事件。

- [ ] **Step 5: 运行 Web 测试与类型检查**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
npm test -- --run src/worker/event-bridge.test.ts src/worker/account-manager.heartbeat.test.ts
npm run typecheck
```

- [ ] **Step 6: 仅提交本任务文件**

```bash
git add protocol-layer/src/worker/event-bridge.ts protocol-layer/src/worker/event-bridge.test.ts protocol-layer/src/worker/account-manager.ts protocol-layer/src/worker/account-manager.heartbeat.test.ts
git diff --cached --name-only
git commit -m "feat: 发布完整群管理员角色事件"
```

不得包含工作区现有的 `master-consumer.ts`、`master-consumer.test.ts` 和 `.codegraph/`。

---

## Task 5: Android 协议发布同契约角色事件

**Files:**

- Modify: `/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/service/events/type.go`
- Modify: `/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/service/node/processor/group_notification.go`
- Modify: `/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/service/node/processor/group_notification_test.go`
- Create: `/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/group_participant_role_event.go`
- Create: `/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/group_participant_role_event_test.go`
- Modify: `/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/event.go`
- Modify: `/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/group_snapshot_coordinator.go`
- Modify: `/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/group_snapshot_coordinator_test.go`
- Modify: `/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/start.go`
- Modify: `/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/client_test.go`

- [ ] **Step 1: 把原“忽略 promote/demote”测试改成角色事件测试**

解析测试要求 WGP2 promote/demote 保留 group、PN/LID participants、通知 sourceEventId、发生时间和可选 operator。协调器测试要求发布一次角色事件，同时不调用 `GetAllGroup(true)`、不安排群快照。

- [ ] **Step 2: 运行 Go 目标测试确认失败**

```bash
cd /Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan
go test ./internal/service/node/processor ./internal/armada
```

- [ ] **Step 3: 解析器放行角色事件**

把 WGP2 action 允许集合扩为 `add/remove/leave/promote/demote`，并在 `GroupParticipantsChangedEvent` 增加 `OperatorJID string`。operator 优先取通知 `participant_pn`，再取 `participant`；participant 本身继续使用现有 resolver 保留 PN/LID。

- [ ] **Step 4: 定义并发布 Android 角色事件**

新增：

```go
const EventGroupParticipantChanged = "group.participant_changed"

type GroupParticipantRolePublisher interface {
    PublishGroupParticipantRoleChanged(ctx context.Context, change GroupParticipantRoleChange) error
}
```

正文与 Web 契约一致，写 `protocolBackend=ANDROID`、`source=android_wgp2`。每个 participant 按现有 JID 类型填 `id/lid/phoneNumber`；事件 ID 用 protocol account、通知 sourceEventId、group、action 生成稳定值。

- [ ] **Step 5: 协调器只对角色变化发布增量事件**

在 options 注入 `RolePublisher`。promote/demote 分支通过已有 `phone -> CommandContext` 补齐业务上下文，异步写 group topic 后立即返回；无上下文时记录结构化日志并安全跳过。add/remove/leave 原逻辑不变。

- [ ] **Step 6: 接线和 Kafka topic 测试**

`start.go` 用现有 `AccountEventWriter` 构造 publisher；`AccountEventPublisher.topicFor` 把 `group.participant_changed` 路由到 group topic。测试 data 必须包含 tenant/account/protocol account/backend。

- [ ] **Step 7: 格式化并验证 Android 协议**

```bash
cd /Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan
gofmt -w internal/service/events/type.go internal/service/node/processor/group_notification.go internal/service/node/processor/group_notification_test.go internal/armada/group_participant_role_event.go internal/armada/group_participant_role_event_test.go internal/armada/event.go internal/armada/group_snapshot_coordinator.go internal/armada/group_snapshot_coordinator_test.go internal/armada/start.go internal/armada/client_test.go
go test ./internal/service/node/processor ./internal/armada
go vet ./...
go test ./...
```

- [ ] **Step 8: 提交 Android 任务**

```bash
git add internal/service/events/type.go internal/service/node/processor/group_notification.go internal/service/node/processor/group_notification_test.go internal/armada/group_participant_role_event.go internal/armada/group_participant_role_event_test.go internal/armada/event.go internal/armada/group_snapshot_coordinator.go internal/armada/group_snapshot_coordinator_test.go internal/armada/start.go internal/armada/client_test.go
git commit -m "feat: 发布安卓群管理员角色事件"
```

---

## Task 6: 拉群管理员缺失时发起一次定点成员查询

**Files:**

- Modify: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskMemberQueryPurpose.java`
- Modify: `armada-api/src/main/java/com/armada/task/model/dto/PullTaskMemberQueryCallback.java`
- Modify: `armada-api/src/main/java/com/armada/platform/kafka/consumer/group/ProtocolGroupEventConsumer.java`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/ProtocolGroupMembersResultAdapter.java`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskMemberQueryResultServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/group/mapper/AccountGroupMembershipMapper.java`
- Modify: `armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/group/service/GroupExecutionAccountSelector.java`
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskManagerAdminPreparation.java`
- Create: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskManagerAdminDiscoveryWork.java`
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskManagerAdminTransactionService.java`
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskManagerAdminProcessor.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/GroupExecutionAccountSelectorDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskManagerAdminTransactionIntegrationTest.java`
- Modify: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskManagerAdminProcessorTest.java`
- Modify: `armada-api/src/test/java/com/armada/task/service/impl/PullTaskMemberQueryResultServiceImplTest.java`

- [ ] **Step 1: 写状态机失败测试**

必须覆盖：

- 严格管理员存在时直接创建提权工作，不查成员；
- 严格管理员为空，但有在线、正常、未风控、未禁言、协议身份完整且本地在群账号时，返回 discovery work；
- discovery 最多 500 个目标；
- PENDING 原子释放租约；
- SUCCESS 回调先写全局事实，再精确唤醒 `MANAGER_ADMIN`；
- SUCCESS 仍无管理员时进入 `WAIT_RESOURCE`，再次调度复用 AVAILABLE 结果，不再发命令；
- FAILED 沿用现有成员查询退避；
- 没有查询 actor 时沿用 `MANAGER_ADMIN_ACTOR_UNAVAILABLE`。

- [ ] **Step 2: 运行目标测试确认失败**

```bash
mvn -pl armada-api -Dtest=GroupExecutionAccountSelectorDbTest,PullTaskManagerAdminTransactionIntegrationTest,PullTaskManagerAdminProcessorTest,PullTaskMemberQueryResultServiceImplTest test
```

- [ ] **Step 3: 添加 purpose 与候选查询**

新增：

```java
MANAGER_ADMIN_DISCOVERY(false)
```

并把它加入 consumer 支持集合、结果阶段映射 `MANAGER_ADMIN`。新增候选 SQL 只要求本地 `IN_GROUP`，不要求 `is_admin=1`，限制 500，按稳定账号顺序返回；actor 从同一列表中选取。

- [ ] **Step 4: 冻结并复用 discovery 身份**

业务键固定为：

```text
manager-admin-discovery:<managerRoleId>
```

若该键已有成员查询记录，必须从记录重建 actor、protocol ref 和 targetJids，再调用 `PullTaskMemberQueryAwaitService.readOrDefer`；不得因在线候选排序变化重新组装请求，避免 `validateIdentity` 冲突。只有尚无记录时才冻结本轮候选。

- [ ] **Step 5: 扩展 preparation 与 processor**

`PullTaskManagerAdminPreparation` 明确三种结果：完成、promotion work、discovery work。prepare 在严格候选为空时返回 discovery，且不插入 PROMOTER 角色/动作。processor 对查询状态执行：

```text
PENDING   -> defer execution lease
FAILED    -> existing retry/backoff path
AVAILABLE -> rerun strict admin preparation
```

若 AVAILABLE 后仍无严格管理员，转既有资源等待，不再次创建查询。

- [ ] **Step 6: 查询结果事务先落事实再唤醒**

给 `PullTaskMemberQueryCallback` 传递协议 `eventId`。当 purpose 为 `MANAGER_ADMIN_DISCOVERY` 且结果 SUCCESS 时，把每个 fact 映射为 `MEMBER_QUERY` observation：

```java
sourceEventId = callback.eventId() + ":" + fact.targetJid();
```

在同一 `@Transactional` 回调内先调用 `GroupParticipantObservationService.apply`，再 settle pending 和 wake；因此下一次 prepare 一定读到新关系。

- [ ] **Step 7: 运行拉群与查询回归测试**

```bash
mvn -pl armada-api -Dtest=GroupExecutionAccountSelectorDbTest,PullTaskManagerAdminTransactionIntegrationTest,PullTaskManagerAdminProcessorTest,PullTaskMemberQueryResultServiceImplTest,ProtocolGroupEventConsumerTest test
```

- [ ] **Step 8: 提交本任务**

```bash
git add armada-api/src/main/java/com/armada/task armada-api/src/main/java/com/armada/group/mapper/AccountGroupMembershipMapper.java armada-api/src/main/java/com/armada/group/service/GroupExecutionAccountSelector.java armada-api/src/main/java/com/armada/platform/kafka/consumer/group/ProtocolGroupEventConsumer.java armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml armada-api/src/test/java/com/armada/group/service/GroupExecutionAccountSelectorDbTest.java armada-api/src/test/java/com/armada/task
git commit -m "fix: 拉群缺少管理员时定点查询"
```

---

## Task 7: 一次性唤醒 #122 同类活动执行

**Files:**

- Create: `armada-api/src/main/resources/db/migration/V114__group_admin_event_and_pull_fallback.sql`
- Create: `armada-api/src/test/java/com/armada/task/GroupAdminEventAndPullFallbackMigrationSqlTest.java`
- Modify: `armada-api/src/test/java/com/armada/boot/FlywayMigrationVersionContractTest.java` only if current contract requires an explicit latest version

- [ ] **Step 1: 写迁移范围契约测试**

测试必须验证 SQL 只命中：父任务仍执行、普通链接模式、执行非终态、`WAIT_RESOURCE`、阶段 `MANAGER_ADMIN`、reason `MANAGER_ADMIN_ACTOR_UNAVAILABLE` 的行；并断言清空锁与等待字段、`next_run_at=0`、version 自增。

- [ ] **Step 2: 运行迁移测试确认失败**

```bash
mvn -pl armada-api -Dtest=GroupAdminEventAndPullFallbackMigrationSqlTest,FlywayMigrationVersionContractTest test
```

- [ ] **Step 3: 实现 V114 状态迁移**

迁移不改 schema、不读旧成员快照、不批量更新成功 metadata 任务。只把符合条件的执行行恢复为 `EXECUTING`，清除 `wait_resource_type/reason_code/reason_message/lock_owner/lock_until`，并立刻可调度。

- [ ] **Step 4: 运行 Flyway 契约与拉群迁移测试**

```bash
mvn -pl armada-api -Dtest=GroupAdminEventAndPullFallbackMigrationSqlTest,FlywayMigrationSqlContractTest,FlywayMigrationVersionContractTest,PullTaskManagerAdminStageMigrationSqlTest test
```

- [ ] **Step 5: 提交迁移**

```bash
git add armada-api/src/main/resources/db/migration/V114__group_admin_event_and_pull_fallback.sql armada-api/src/test/java/com/armada/task/GroupAdminEventAndPullFallbackMigrationSqlTest.java armada-api/src/test/java/com/armada/boot/FlywayMigrationVersionContractTest.java
git commit -m "fix: 唤醒管理员缺失的拉群执行"
```

如果 version contract 文件无须变更，不将它加入提交。

---

## Task 8: 跨仓库回归、文档和 test1 验收准备

**Files:**

- Modify: `.harness/changes/2026-08-14-group-admin-event-and-pull-fallback.md`
- Modify: `docs/superpowers/specs/2026-08-14-group-admin-event-and-pull-fallback-design.md` only if implementation reveals a contract correction

- [ ] **Step 1: 后端完整验证**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
mvn -pl armada-api test
```

- [ ] **Step 2: Web 协议完整验证**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
npm test -- --run
npm run typecheck
```

- [ ] **Step 3: Android 协议完整验证**

```bash
cd /Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan
go vet ./...
go test ./...
go build ./...
```

- [ ] **Step 4: 检查差异与提交边界**

每个仓库分别执行：

```bash
git diff --check
git status --short
git log --oneline -8
```

确认 `armada` 原有批处理脚本改动和 `.claude/worktrees`、`armada-protocol` 原有 master-consumer 改动均未进入本任务提交；Android 仓库只含计划文件中的变更。

- [ ] **Step 5: 更新变更记录并提交**

在 change record 中填写实际测试命令、结果、风险和回滚点；不声称已部署 test1。

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add .harness/changes/2026-08-14-group-admin-event-and-pull-fallback.md docs/superpowers/specs/2026-08-14-group-admin-event-and-pull-fallback-design.md
git commit -m "docs: 记录群管理员修复验证结果"
```

若设计文档无变化，只提交 change record。

- [ ] **Step 6: 部署授权后才执行 test1 验收**

部署属于独立外部变更，必须先确认 test1 目标和部署范围。获授权后验证：执行行 169 被 V114 唤醒；只产生一次 `MANAGER_ADMIN_DISCOVERY`；结果先更新受控管理员关系；下一轮生成 `PROMOTE_MANAGER`；任务 #122 继续推进；空闲成功群不再产生周期 metadata 命令。

