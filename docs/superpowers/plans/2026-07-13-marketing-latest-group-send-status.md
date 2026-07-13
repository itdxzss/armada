# Marketing Latest Group Send Status Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在营销任务明细的账号展开行中，展示每个群在该任务最近一次发送时的 `正常 / 封禁 / 没有权限 / 未确认` 状态。

**Architecture:** 协议层在执行现有 `message.send.requested` 时，先用轻量群聊天状态缓存判断 `suspended/terminated`，再用短 TTL 的 `groupMetadata` 判断账号是否仍在群内以及管理员发言权限；状态快照随现有 `message.send_result_reported` 事件回传。Armada 把快照落到对应 `marketing_task_send_attempt`，现有账号+群聚合 SQL读取最近一次完成 attempt 的状态。前端只扩展类型和组合列，不新增接口或页面状态。

**Tech Stack:** TypeScript, Baileys 7.x, Jest, Kafka; Java 17, Spring Boot, MyBatis, Flyway, JUnit 5; Vue 3, TypeScript, Element Plus, Node test runner.

---

## Status Contract

- `NORMAL`: 群未命中封禁信号，账号仍是群成员，且群允许成员发言或账号是管理员。
- `BANNED`: 最近聊天状态明确出现 `suspended=true` 或 `terminated=true`。
- `NO_PERMISSION`: 群正常但账号不在参与者列表，或 `announce=true` 且账号不是管理员。
- `UNCONFIRMED`: 历史信号缺失、账号身份无法匹配、`groupMetadata` 查询失败等无法可靠判定的情况。
- 状态只做观测，不拦截发送，也不改变发送成功/失败语义。
- 事件新增可选字段 `groupStatus`, `groupStatusReason`, `groupStatusCheckedAt`，兼容协议与 Armada 滚动发布。

## File Structure

### Protocol: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer`

- Create `src/worker/group-sendability.ts`: 群状态信号缓存、metadata 判定、TTL 缓存和状态契约。
- Create `src/worker/group-sendability.test.ts`: 封禁、管理员发言、普通群、非成员、未确认和缓存测试。
- Modify `src/worker/account-manager.ts`: 订阅 `messaging-history.set`, `chats.upsert`, `chats.update`，仅提取群 JID 和 `suspended/terminated`；暴露发送时状态解析方法。
- Modify `src/commands/worker-consumer.ts`: 发送前读取状态快照，并随成功/失败结果事件回传。
- Modify `src/commands/worker-consumer.test.ts`: 断言状态快照随现有结果事件发布，状态解析失败不影响发送。

### Backend: `/Users/daishuaishuai/IdeaProjects/armada`

- Create `armada-api/src/main/resources/db/migration/V052__marketing_attempt_group_status.sql`: attempt 新增三个可空状态快照字段。
- Modify `armada-api/src/main/java/com/armada/platform/kafka/consumer/message/ProtocolMessageSendResultReportedEvent.java`: 接收可选状态字段。
- Modify `armada-api/src/main/java/com/armada/platform/kafka/consumer/message/ProtocolMessageEventConsumer.java`: 解析状态字段。
- Modify `armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java`: 回写方法接收状态快照。
- Modify `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`: 成功/失败 attempt 同步落状态；明细聚合取最近状态。
- Modify `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskAccountGroupStatRow.java`: 增加 `groupStatus`。
- Modify `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskGroupStatVO.java`: 明细契约增加 `groupStatus`。
- Modify `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java`: 映射新字段，空值回退 `UNCONFIRMED`。
- Modify focused tests under `armada-api/src/test/java/com/armada/platform/kafka/consumer/message`, `marketing/service`, and `marketing/mapper`.

### Frontend: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web`

- Modify `src/api/marketing-task.ts`: 增加群状态联合类型与字段。
- Create `src/views/task/group-marketing/components/group-send-status.ts`: 标签文案和类型映射。
- Create `src/views/task/group-marketing/components/group-send-status.test.ts`: 四种状态映射测试。
- Modify `src/views/task/group-marketing/components/GroupMarketingDetailDrawer.vue`: 在“单群发送条数”前展示状态标签。
- Modify `src/views/task/group-marketing/components/GroupMarketingDetailDrawer.test.ts`: 断言六列顺序和状态标签。
- Create `.harness/changes/marketing-latest-group-send-status/summary.md`: 记录契约、UI 和验证结果。

## Task 1: Protocol Group Status Resolver

**Files:**
- Create `src/worker/group-sendability.test.ts`
- Create `src/worker/group-sendability.ts`

- [ ] **Step 1: Write failing resolver tests**

覆盖：

```ts
it('returns BANNED when suspended chat state is known')
it('returns NO_PERMISSION for announce-only group when self is a member but not admin')
it('returns NORMAL for announce-only group when self is admin')
it('returns NORMAL for a regular group member')
it('returns NO_PERMISSION when self is absent from metadata participants')
it('returns UNCONFIRMED when metadata lookup or self identity resolution fails')
it('reuses metadata result inside the ttl and invalidates it on chat state change')
```

- [ ] **Step 2: Run focused test and verify RED**

Run: `npm test -- --runInBand src/worker/group-sendability.test.ts`

Expected: module/import does not exist.

- [ ] **Step 3: Implement the smallest resolver**

Use an account+group key. Keep only `{ suspended, terminated }`, checked status, expiry, and optional in-flight promise. Do not retain history messages, contacts, participant arrays, or raw metadata. Match PN/LID identities with Baileys `areJidsSameUser`. Cache metadata-derived results for 60 seconds. A positive ban signal overrides metadata.

- [ ] **Step 4: Re-run focused test and verify GREEN**

Run: `npm test -- --runInBand src/worker/group-sendability.test.ts`

## Task 2: Protocol Event Wiring and Result Contract

**Files:**
- Modify `src/worker/account-manager.ts`
- Modify `src/commands/worker-consumer.test.ts`
- Modify `src/commands/worker-consumer.ts`

- [ ] **Step 1: Add failing command tests**

Assert both success and send-failure events contain:

```ts
{
  groupStatus: 'NO_PERMISSION',
  groupStatusReason: 'ANNOUNCE_ONLY_NON_ADMIN',
  groupStatusCheckedAt: 1783159199000
}
```

Also assert resolver rejection falls back to `UNCONFIRMED` and still calls `sendMessage`, publishes, then ACKs.

- [ ] **Step 2: Run focused test and verify RED**

Run: `npm test -- --runInBand src/commands/worker-consumer.test.ts`

- [ ] **Step 3: Wire AccountManager and result publishing**

Register the three Baileys listeners per socket generation. Each handler filters `@g.us`, passes only status flags to the resolver, and follows the existing detacher lifecycle. Expose:

```ts
resolveGroupSendability(accountId: string, groupJid: string): Promise<GroupSendabilitySnapshot>
```

`executeMessageSend` captures the snapshot once before sending and spreads it into either success or failure `message.send_result_reported`. Resolver errors are logged and converted to `UNCONFIRMED`; they never fail the send.

- [ ] **Step 4: Re-run focused tests and typecheck**

Run:

```bash
npm test -- --runInBand src/worker/group-sendability.test.ts src/commands/worker-consumer.test.ts
npm run lint
```

## Task 3: Backend Event Parsing and Attempt Persistence

**Files:**
- Modify `armada-api/src/test/java/com/armada/platform/kafka/consumer/message/ProtocolMessageEventConsumerTest.java`
- Modify `armada-api/src/test/java/com/armada/marketing/service/MarketingSendResultServiceImplTest.java`
- Modify `armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java`
- Create `armada-api/src/main/resources/db/migration/V052__marketing_attempt_group_status.sql`
- Modify event, mapper, XML, and service files listed above.

- [ ] **Step 1: Add failing backend tests**

Consumer test parses all three optional status fields. Service tests verify success and failure both pass the snapshot into `markAttempt*`. SQL shape tests require the three columns in the update and require `selectAccountGroupStatsByTaskId` to order status by completed-at plus attempt id.

- [ ] **Step 2: Run focused tests and verify RED**

Run from `armada-api`:

```bash
mvn -Dtest=ProtocolMessageEventConsumerTest,MarketingSendResultServiceImplTest,MarketingTaskMapperSqlShapeTest test
```

- [ ] **Step 3: Add migration and backward-compatible parsing**

Migration:

```sql
ALTER TABLE marketing_task_send_attempt
    ADD COLUMN group_status VARCHAR(32) NULL COMMENT '发送时群状态: NORMAL/BANNED/NO_PERMISSION/UNCONFIRMED' AFTER message_id,
    ADD COLUMN group_status_reason VARCHAR(64) NULL COMMENT '发送时群状态判定原因' AFTER group_status,
    ADD COLUMN group_status_checked_at BIGINT NULL COMMENT '群状态判定时间(epoch毫秒)' AFTER group_status_reason;
```

Protocol event fields remain nullable. Armada accepts missing fields during rolling deployment; old attempts show `UNCONFIRMED` in detail.

- [ ] **Step 4: Persist only on the first final result**

Extend `markAttemptSuccess/markAttemptFailed` parameters and SQL. Keep the existing `WHERE status = 0` idempotency condition so duplicates cannot overwrite the first completed attempt snapshot.

- [ ] **Step 5: Re-run focused tests and verify GREEN**

Run the same Maven command.

## Task 4: Backend Detail Contract

**Files:**
- Modify group stat row/VO/service files listed above.
- Modify `armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java`
- Add or modify the focused service/detail test that constructs a group stat row.

- [ ] **Step 1: Add failing detail tests**

Assert the latest completed attempt status wins for each account+group, and null/legacy status maps to `UNCONFIRMED`.

- [ ] **Step 2: Implement latest-status projection**

Add `groupStatus` to the raw row and VO. In the existing `GROUP_CONCAT ... ORDER BY COALESCE(a.result_at, a.attempted_at, a.created_at) DESC, a.id DESC` projection, use `COALESCE(NULLIF(TRIM(a.group_status), ''), 'UNCONFIRMED')` so every returned group row has a stable value.

- [ ] **Step 3: Run focused mapper/service tests**

Run:

```bash
mvn -Dtest=MarketingTaskMapperSqlShapeTest,MarketingTaskServiceImplTest test
```

If the existing test class name differs, use the closest focused detail service test found by `rg` rather than running the whole suite first.

## Task 5: Frontend Status Column

**Files:**
- Modify/create frontend files listed above.

- [ ] **Step 1: Write failing mapping and component tests**

Expected labels/types:

```ts
NORMAL       -> { label: '正常', tagType: 'success' }
BANNED       -> { label: '封禁', tagType: 'danger' }
NO_PERMISSION -> { label: '没有权限', className: 'group-status--no-permission' }
UNCONFIRMED  -> { label: '未确认', tagType: 'info' }
```

Component source test must assert the header order starts with `状态`, followed by `单群发送条数`.

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```bash
node --test src/views/task/group-marketing/components/group-send-status.test.ts \
  src/views/task/group-marketing/components/GroupMarketingDetailDrawer.test.ts
```

- [ ] **Step 3: Implement type, tag, layout, and purple style**

Add a sixth grid column before the count. Render the tag in both the collapsed first-group row and every expanded group row. Use Element Plus tag types for green/red/gray and a scoped purple class for `NO_PERMISSION`.

- [ ] **Step 4: Add harness change note and verify GREEN**

Run:

```bash
node --test src/views/task/group-marketing/components/group-send-status.test.ts \
  src/views/task/group-marketing/components/GroupMarketingDetailDrawer.test.ts
pnpm typecheck
```

Record actual results in `.harness/changes/marketing-latest-group-send-status/summary.md`.

## Task 6: Cross-Repository Verification and Review

- [ ] **Step 1: Protocol verification**

Run focused Jest tests, `npm run lint`, and `npm run build`.

- [ ] **Step 2: Backend verification**

Run the focused Maven tests above, then `mvn test` only if the focused suite and local infrastructure allow it.

- [ ] **Step 3: Frontend verification**

Run focused Node tests and `pnpm typecheck`. Run a production build if dependencies and local environment allow it.

- [ ] **Step 4: Review diffs and repository state**

For all three repos, inspect `git diff --check`, `git diff --stat`, and `git status --short`. Confirm no unrelated files were modified, no secret/key files are staged, and no deployment/restart/remote mutation occurred.

## Explicit Non-Goals

- No new Kafka topic or consumer group.
- No waiting for WhatsApp delivery ACK before Armada receives the existing send result.
- No full chat/message history storage.
- No per-send unbounded metadata query; status uses a short TTL.
- No automatic send blocking based on the observed status.
- No remote deployment, restart, or test-environment mutation in this implementation session.

