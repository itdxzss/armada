# Historical Group Pull and Marketing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在一个“历史群管理”菜单内交付 baseline 历史群查询、按需群详情与成员管理、单群拉人和全部营销账号一次性发送的 MVP。

**Architecture:** `account_group_baseline` 是历史范围唯一来源；Armada 用轻量当前群列表与 baseline 求交集，再有界批量读取摘要。完整成员、邀请链接和所有操作只在单群详情上下文发生。拉人和营销使用独立持久化执行聚合，通过现有协议 HTTP/Kafka 能力单次调用、逐项记错、绝不业务重试。

**Tech Stack:** Java 17、Spring Boot、MyBatis-Plus、Flyway、JUnit 5、Testcontainers、Vue 3、TypeScript、Element Plus、Node test、Baileys 7.x、Fastify、Jest、OpenAPI。

---

## Scope and invariants

- 只改 `armada/`、`armada-protocol/`、`wheel-saas-pure-web/`，不改旧 `wheel/`。
- 前端只有一个新菜单和路由：`/group/history`。
- 列表只展示 baseline 群；当前新增但不在 baseline 的群不得出现。
- 当前群查询整体失败时标记 `FETCH_FAILED`，不得推断为已退出。
- 群详情获取不到邀请链接时，后端拒绝成员操作、创建拉人执行和营销发送；前端同步禁用。
- 拉手只能踩链接进群；不允许操作账号邀请拉手。
- 不实现账号任务占用、替换拉手、业务重试、自动上管理或手工填写群链接。
- 页面和 API 展示完整号码、JID、成员及协议错误；任何凭据、token、cookie、代理密钥不得落库或回传。
- 每次提交前先执行 `git status --short` 和 `git diff --cached --name-only`；若任务目录里出现用户的并行修改，把目录级 `git add` 改成 Files 清单中的逐文件暂存。

## Task 1: Add bounded protocol metadata summaries

**Files:**
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/routes/groups.ts`
- Create: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/routes/groups-metadata-summaries.test.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/routes/groups-test-harness.ts`

- [ ] Write the failing route tests

覆盖 `POST /v1/accounts/:accountId/groups/metadata-summaries`：请求体 `{ groupJids, concurrency }`；去重后最多 500 个 JID；并发默认 8、范围 1–16；每个 JID 独立返回 `success/error/subject/memberSize/selfRole/announceOnly/stateAbnormal`；响应不包含 `participants`。

- [ ] Run the focused test and confirm it fails because the route does not exist

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
npm test -- --runInBand src/routes/groups-metadata-summaries.test.ts
```

Expected: HTTP 404 or missing-handler assertion.

- [ ] Implement the smallest bounded worker pool in `groups.ts`

对每个 JID 调用一次 `sock.groupMetadata(groupJid)`；从 `participants` 中按当前账号 JID 计算 `OWNER/ADMIN/MEMBER`，但只返回 `memberSize`。单群异常转换为该项 `success=false`，不使整批失败；`announceOnly` 从 metadata 的 announce 字段映射。

- [ ] Run the focused route tests and commit

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
npm test -- --runInBand src/routes/groups-metadata-summaries.test.ts
git add src/routes/groups.ts src/routes/groups-test-harness.ts src/routes/groups-metadata-summaries.test.ts
git commit -m "feat(protocol): add bounded group metadata summaries"
```

Expected: all focused tests pass.

## Task 2: Route and document the protocol summary endpoint

**Files:**
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/master-gateway/register.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/master-gateway/register.test.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/master-gateway/routing.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/master-gateway/routing.test.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/openapi/protocol-v1.yaml`
- Regenerate: `/Users/daishuaishuai/IdeaProjects/armada-protocol/openapi/generated/types.ts`

- [ ] Add failing gateway tests for forwarding account ID, JID list and concurrency to the owning worker
- [ ] Add failing OpenAPI assertions for request limits and a response item that explicitly has no participants field
- [ ] Run the tests and confirm the new route is not registered

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
npm test -- --runInBand src/master-gateway/register.test.ts src/master-gateway/routing.test.ts
```

- [ ] Register the route in master gateway routing, update OpenAPI, then regenerate types

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol
bash openapi/regenerate-types.sh
cd protocol-layer
npm test -- --runInBand src/master-gateway/register.test.ts src/master-gateway/routing.test.ts src/routes/groups-metadata-summaries.test.ts
npm run build
cd ..
git add protocol-layer/src/master-gateway/register.ts protocol-layer/src/master-gateway/register.test.ts protocol-layer/src/master-gateway/routing.ts protocol-layer/src/master-gateway/routing.test.ts openapi/protocol-v1.yaml openapi/generated/types.ts
git commit -m "feat(protocol): expose metadata summary contract"
```

Expected: gateway tests and TypeScript build pass; generated types have no diff after a second regeneration.

## Task 3: Preserve baseline group subjects

**Files:**
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/db/migration/V056__historical_group_pull_marketing.sql`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/model/vo/AccountGroupBaselineRow.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/mapper/AccountGroupMembershipMapper.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/service/impl/AccountGroupMembershipReportServiceImpl.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/group/service/AccountGroupMembershipReportServiceDbTest.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/group/service/impl/AccountGroupMembershipReportServiceImplTest.java`

- [ ] Verify `V056` is still the next migration; if another committed migration owns it, renumber this file to the next free version before editing
- [ ] Add failing tests proving the first report stores a JSON JID→subject map, an old payload without subjects remains valid, and later reports cannot overwrite either JIDs or subjects
- [ ] Run the focused tests and confirm the missing column/mapping failure

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
mvn -pl armada-api -Dtest=AccountGroupMembershipReportServiceImplTest,AccountGroupMembershipReportServiceDbTest test
```

- [ ] Add nullable `baseline_group_subjects JSON` while keeping `baseline_group_jids` canonical
- [ ] Map `accountGroupReported.groups[].subject` without making metadata calls; use insert-if-absent/first-write semantics already used by baseline JIDs
- [ ] Run focused tests and commit only these files

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
mvn -pl armada-api -Dtest=AccountGroupMembershipReportServiceImplTest,AccountGroupMembershipReportServiceDbTest test
git add armada-api/src/main/resources/db/migration/V056__historical_group_pull_marketing.sql armada-api/src/main/java/com/armada/group/model/vo/AccountGroupBaselineRow.java armada-api/src/main/java/com/armada/group/mapper/AccountGroupMembershipMapper.java armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml armada-api/src/main/java/com/armada/group/service/impl/AccountGroupMembershipReportServiceImpl.java armada-api/src/test/java/com/armada/group/service/AccountGroupMembershipReportServiceDbTest.java armada-api/src/test/java/com/armada/group/service/impl/AccountGroupMembershipReportServiceImplTest.java
git commit -m "feat(group): preserve baseline group subjects"
```

Expected: legacy and subject-aware report tests pass.

## Task 4: Add Armada protocol ports for summaries, invite links and participant add

**Files:**
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/platform/protocol/port/AccountParticipatingGroupPort.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/platform/protocol/http/account/HttpAccountParticipatingGroupAdapter.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/platform/protocol/port/GroupInvitePort.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/platform/protocol/model/result/GroupInviteResult.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupInviteAdapter.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/platform/protocol/port/GroupParticipantPort.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupParticipantAdapter.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/platform/protocol/http/account/HttpAccountParticipatingGroupAdapterTest.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/platform/protocol/http/group/HttpGroupParticipantAdapterTest.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/platform/protocol/http/group/HttpGroupInviteAdapterTest.java`

- [ ] Write failing HTTP adapter tests for light `listCurrent`, batch `summarize`, `GET invite-code`, and participant action `ADD`
- [ ] Implement typed models; preserve per-JID protocol errors without masking or collapsing them
- [ ] Ensure invite result requires a nonblank `inviteUrl`; blank/missing link is a failure even if metadata succeeded
- [ ] Register `GroupInvitePort` in `ProtocolConfiguration` and keep the existing account/participant beans backward compatible
- [ ] Run focused tests and commit

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
mvn -pl armada-api -Dtest=HttpAccountParticipatingGroupAdapterTest,HttpGroupInviteAdapterTest,HttpGroupParticipantAdapterTest test
git add armada-api/src/main/java/com/armada/platform/protocol armada-api/src/test/java/com/armada/platform/protocol
git commit -m "feat(platform): add historical group protocol ports"
```

Expected: adapters call the exact protocol routes and retain item-level errors.

## Task 5: Add tenant-safe operation and puller account lookups

**Files:**
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/service/AccountProtocolLookupService.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/service/impl/AccountProtocolLookupServiceImpl.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/mapper/AccountMapper.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/account/AccountMapper.xml`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/account/service/AccountProtocolLookupServiceDbTest.java`

- [ ] Add failing DB tests for `findActiveProtocolRef(accountId)`, `findRandomOnlineNormalByGroupId(groupId)`, and bulk phone lookup
- [ ] Assert every lookup is tenant-scoped and ignores soft-deleted rows; only the random puller selector filters for online, normal and risk-allowed state
- [ ] Implement puller selection with database random ordering and no task-occupancy predicate; operation-account and A-account lookup must not precheck online/group role/speaking state
- [ ] Normalize imported phones exactly once, return a map keyed by canonical full phone, and never cross tenants
- [ ] Run focused DB tests and commit

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
mvn -pl armada-api -Dtest=AccountProtocolLookupServiceDbTest test
git add armada-api/src/main/java/com/armada/account armada-api/src/main/resources/mapper/account/AccountMapper.xml armada-api/src/test/java/com/armada/account/service/AccountProtocolLookupServiceDbTest.java
git commit -m "feat(account): add historical group account lookups"
```

Expected: random selector only returns an online, normal account in the selected tenant group.

## Task 6: Build historical group list and refresh aggregation

**Files:**
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/controller/HistoricalGroupController.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/service/HistoricalGroupService.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/service/impl/HistoricalGroupServiceImpl.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/model/dto/HistoricalGroupRefreshRequest.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/model/vo/HistoricalGroupItemVO.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/group/service/impl/HistoricalGroupServiceImplTest.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/group/controller/HistoricalGroupControllerTest.java`

- [ ] Write failing service tests for baseline-only output and initial `UNVERIFIED`
- [ ] Add refresh tests: whole light-list failure yields `FETCH_FAILED` for every row; success marks baseline intersection `CURRENT_IN_GROUP`, baseline difference `CURRENT_NOT_IN_GROUP`; non-baseline current groups are discarded
- [ ] Add summary mapping tests for `ADMIN/MEMBER` category and `NORMAL/ADMIN_CAN_SPEAK/CANNOT_SPEAK/ABNORMAL`; a per-JID summary failure stays current-in-group but marks abnormal with its error
- [ ] Implement `GET /api/historical-groups?accountId=` and `POST /api/historical-groups/refresh`; resolve the operation account with Task 5 and validate its tenant before protocol calls
- [ ] Keep refresh results request-scoped in MVP; do not rewrite baseline subjects or invent current-state persistence
- [ ] Run focused tests and commit

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
mvn -pl armada-api -Dtest=HistoricalGroupServiceImplTest,HistoricalGroupControllerTest test
git add armada-api/src/main/java/com/armada/group armada-api/src/test/java/com/armada/group
git commit -m "feat(group): list and refresh historical groups"
```

Expected: all historical list state transitions pass and current-only groups never appear.

## Task 7: Add lazy detail and guarded member operations

**Files:**
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/controller/HistoricalGroupController.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/service/HistoricalGroupService.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/service/impl/HistoricalGroupServiceImpl.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/model/dto/HistoricalGroupParticipantActionRequest.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/model/vo/HistoricalGroupDetailVO.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/model/vo/HistoricalGroupParticipantActionVO.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/group/service/impl/HistoricalGroupServiceImplTest.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/group/controller/HistoricalGroupControllerTest.java`

- [ ] Write failing tests proving detail calls full metadata and invite-code only after a baseline membership check
- [ ] Write failing guard tests: missing invite URL disables all actions; non-admin cannot mutate; self and owner cannot be demoted/removed; admin can promote members, demote other admins and remove eligible users
- [ ] Write batch tests proving item-level success/failure, input order retention, no rollback and no retry
- [ ] Implement `GET /api/historical-groups/detail?accountId=&groupJid=` and three action endpoints under `/api/historical-groups/participants/{promote|demote|remove}`
- [ ] Return complete phone/JID/role/error data; do not reuse `groupLinkId` or automatic account selection from current group detail
- [ ] Enforce the link gate with a server-side invite-code lookup before every write; never trust an invite URL echoed by the browser
- [ ] Run focused tests and commit

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
mvn -pl armada-api -Dtest=HistoricalGroupServiceImplTest,HistoricalGroupControllerTest test
git add armada-api/src/main/java/com/armada/group armada-api/src/test/java/com/armada/group
git commit -m "feat(group): add guarded historical group detail"
```

Expected: every mutation is rejected server-side without a fresh usable link and valid admin authority.

## Task 8: Add the independent execution schema and persistence layer

**Files:**
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/db/migration/V057__historical_group_pull_execution.sql`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/model/entity/HistoricalGroupPullExecution.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/model/entity/HistoricalGroupPullMember.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/mapper/HistoricalGroupPullExecutionMapper.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/mapper/HistoricalGroupPullMemberMapper.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/group/HistoricalGroupPullExecutionMapper.xml`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/group/HistoricalGroupPullMemberMapper.xml`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/group/mapper/HistoricalGroupPullPersistenceDbTest.java`

- [ ] Verify `V057` is still free after Task 3; if another committed migration owns it, use the next free version consistently
- [ ] Add a failing DB test for tenant isolation, same-execution unique normalized phone, status transitions and long unmasked protocol errors
- [ ] Create `historical_group_pull_execution` with target, puller, batch/template config, clean/result counters, `pull_status`, `marketing_status`, failure summary and timestamps
- [ ] Create `historical_group_pull_member` with material type, matched account snapshot, contact/add/send statuses, `send_command_id`, `send_result_event_id` and separate error code/message columns
- [ ] Use exact enum codes: pull `PENDING/RUNNING/SUCCESS/PARTIAL_SUCCESS/FAILED`; marketing `NOT_APPLICABLE/NOT_STARTED/SENDING/SUCCESS/PARTIAL_SUCCESS/FAILED`
- [ ] Use member codes: contact `PENDING/SUCCESS/FAILED`, add `PENDING/SUCCESS/FAILED`, send `NOT_APPLICABLE/PENDING/SENDING/SUCCESS/FAILED`; one A account has one final send result
- [ ] Add indexes on `(tenant_id, operation_account_id, group_jid, created_at)` and `(tenant_id, execution_id, material_type)` plus unique `(tenant_id, execution_id, phone)`
- [ ] Run the DB test and commit

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
mvn -pl armada-api -Dtest=HistoricalGroupPullPersistenceDbTest test
git add armada-api/src/main/resources/db/migration/V057__historical_group_pull_execution.sql armada-api/src/main/java/com/armada/group/model/entity armada-api/src/main/java/com/armada/group/mapper armada-api/src/main/resources/mapper/group/HistoricalGroupPullExecutionMapper.xml armada-api/src/main/resources/mapper/group/HistoricalGroupPullMemberMapper.xml armada-api/src/test/java/com/armada/group/mapper/HistoricalGroupPullPersistenceDbTest.java
git commit -m "feat(group): persist historical group executions"
```

Expected: migration applies on a clean test database and tenant/unique constraints are enforced.

## Task 9: Parse material and create idempotent pending executions

**Files:**
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/controller/HistoricalGroupPullExecutionController.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/service/HistoricalGroupPullExecutionService.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/service/impl/HistoricalGroupPullExecutionServiceImpl.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/service/HistoricalGroupMaterialParser.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/model/dto/HistoricalGroupPullCreateRequest.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/model/vo/HistoricalGroupPullExecutionVO.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/service/FileLinesExtractor.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/group/service/HistoricalGroupMaterialParserTest.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/group/service/impl/HistoricalGroupPullExecutionServiceImplTest.java`

- [ ] Add failing parser tests for TXT/CSV/XLSX/XLS, whitespace/punctuation cleanup, uppercase `A`, duplicate collapse, plain-plus-A marketing precedence, invalid/duplicate counts and stable line numbers
- [ ] Add failing create tests for `singleAddCount > 0`, tenant ownership of operation account/puller group/template, baseline membership and hard invite-link gate
- [ ] Add an idempotency-key test proving repeated multipart submission returns the existing execution and does not duplicate members
- [ ] Implement the parser using `FileLinesExtractor`; persist marketing members first while keeping source line for display; match all A phones to Armada accounts in one tenant-scoped query
- [ ] Implement `POST /api/historical-group-pull-executions` as multipart plus `GET /api/historical-group-pull-executions/{id}` and `/latest?accountId=&groupJid=`
- [ ] Run focused tests and commit

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
mvn -pl armada-api -Dtest=HistoricalGroupMaterialParserTest,HistoricalGroupPullExecutionServiceImplTest test
git add armada-api/src/main/java/com/armada/group armada-api/src/test/java/com/armada/group
git commit -m "feat(group): create historical pull executions"
```

Expected: every cleaned number has one persisted row and marketing precedence is deterministic.

## Task 10: Execute join, contact-save and participant-add without retry

**Files:**
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/service/HistoricalGroupPullWorker.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/service/impl/HistoricalGroupPullWorkerImpl.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/config/HistoricalGroupPullExecutorConfig.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/service/HistoricalGroupPullRecovery.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/service/impl/HistoricalGroupPullExecutionServiceImpl.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/group/service/impl/HistoricalGroupPullWorkerImplTest.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/group/service/HistoricalGroupPullRecoveryDbTest.java`

- [ ] Add failing worker tests: choose one online-normal puller; no candidate fails; puller joins by invite link; join failure stops; no replacement and no retry
- [ ] Add failing batch tests: marketing members are processed first; `singleAddCount` includes both types; contact-save failure is recorded but ADD is still attempted; ADD failure records exact error and processing continues
- [ ] Add failing recovery test proving startup converts lingering `RUNNING`/`SENDING` to failed with `SERVICE_INTERRUPTED` and never requeues work
- [ ] Implement `POST /api/historical-group-pull-executions/{id}/start` with an atomic `PENDING→RUNNING` claim and bounded Spring executor
- [ ] Revalidate tenant, baseline and invite link before start; call `GroupJoinPort` once for the selected puller, then `ContactPort` once and participant `ADD` once per member
- [ ] Compute `SUCCESS/PARTIAL_SUCCESS/FAILED` from persisted member rows and update counters in one final transaction
- [ ] Run focused tests and commit

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
mvn -pl armada-api -Dtest=HistoricalGroupPullWorkerImplTest,HistoricalGroupPullRecoveryDbTest test
git add armada-api/src/main/java/com/armada/group armada-api/src/test/java/com/armada/group
git commit -m "feat(group): execute historical group pulls"
```

Expected: each protocol action is called at most once per execution/member and later rows continue after item failures.

## Task 11: Carry historical correlation and skip protocol send preflight

**Files (Armada):**
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/platform/protocol/model/command/MessageSendCommand.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/platform/protocol/backend/web/WebMessageSendBackend.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidMessageSendBackend.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImplTest.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/platform/protocol/backend/web/WebMessageSendBackendTest.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidMessageSendBackendTest.java`

**Files (protocol):**
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/commands/worker-consumer.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/commands/worker-consumer.test.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/commands/worker-stream-consumer.test.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/commands/worker-inbox.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/commands/worker-inbox.test.ts`

- [ ] In Armada, add `HistoricalGroupCorrelation(executionId, memberId)` to `MessageCorrelation` and failing serialization tests for both Web and Android payloads
- [ ] Preserve current marketing/group-creation JSON exactly; only source `historical_group_pull` carries `historicalExecutionId` and `historicalMemberId`
- [ ] Run Armada focused tests, implement the correlation propagation, then commit in Armada

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
mvn -pl armada-api -Dtest='*MessageSend*Test,*ProtocolCommandOutbox*Test' test
git add armada-api/src/main/java/com/armada/platform/protocol armada-api/src/test/java/com/armada/platform/protocol
git commit -m "feat(protocol): add historical message correlation"
```

- [ ] In protocol, add a failing test proving `historical_group_pull` never calls `resolveGroupSendability`, invokes `sendMessage` once, and publishes `PRECHECK_SKIPPED_BY_SOURCE` plus historical correlation on success or exception
- [ ] Implement the source branch before `resolveGroupSendability`; do not change current preflight behavior for other sources
- [ ] Run protocol focused tests and commit in protocol

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
npm test -- --runInBand src/commands/worker-consumer.test.ts src/commands/worker-inbox.test.ts src/commands/worker-stream-consumer.test.ts
npm run build
git add src/commands/worker-consumer.ts src/commands/worker-consumer.test.ts src/commands/worker-stream-consumer.test.ts src/commands/worker-inbox.ts src/commands/worker-inbox.test.ts
git commit -m "feat(protocol): send historical marketing without preflight"
```

Expected: historical sends call the actual protocol once regardless of role/speaking cache, and actual errors are reported unchanged.

## Task 12: Enqueue one template send for every marketing member

**Files:**
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/service/HistoricalGroupMarketingService.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/service/impl/HistoricalGroupMarketingServiceImpl.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/model/dto/HistoricalGroupMarketingSendRequest.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/controller/HistoricalGroupPullExecutionController.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/group/service/impl/HistoricalGroupMarketingServiceImplTest.java`

- [ ] Write failing tests that include every A member and no ordinary member; no user-selected subset is accepted
- [ ] Cover missing Armada account/protocol identity and link-gate failures as persisted per-member failures while other A accounts continue; an offline account with a protocol identity must still enqueue so protocol reports the actual error
- [ ] Cover template tenant ownership and composition through existing `MarketingTemplateService`, `MarketingTemplateFileService` and `MarketingMessageComposer`
- [ ] Implement `POST /api/historical-group-pull-executions/{id}/marketing-send` with `{ marketingTemplateId }`; claim `NOT_STARTED→SENDING` atomically so duplicate calls return current status without a second enqueue
- [ ] Re-fetch the invite URL server-side before the atomic claim; a failed link lookup rejects the entire send start without enqueueing any account
- [ ] Build one `MessageSendCommand` per eligible A member using that member as sender, target the detail group JID, source `historical_group_pull`, and correlation `(executionId, memberId)`
- [ ] Treat enqueue exceptions as that member's send failure and continue; do not add a retry scheduler
- [ ] Run focused tests and commit

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
mvn -pl armada-api -Dtest=HistoricalGroupMarketingServiceImplTest test
git add armada-api/src/main/java/com/armada/group armada-api/src/test/java/com/armada/group/service/impl/HistoricalGroupMarketingServiceImplTest.java
git commit -m "feat(group): enqueue historical group marketing"
```

Expected: one and only one send command is created for each cleaned A member that has a usable system account.

## Task 13: Route send results idempotently to the correct execution

**Files:**
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/platform/kafka/consumer/message/ProtocolMessageSendResultReportedEvent.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/platform/kafka/consumer/message/ProtocolMessageSendResultReportedSink.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/platform/kafka/consumer/message/ProtocolMessageEventConsumer.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/marketing/service/impl/MarketingSendResultServiceImpl.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/service/impl/HistoricalGroupSendResultServiceImpl.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/platform/kafka/consumer/message/ProtocolMessageEventConsumerTest.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/group/service/impl/HistoricalGroupSendResultServiceImplDbTest.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/marketing/service/MarketingSendResultServiceImplTest.java`

- [ ] Add failing consumer tests for parsing historical IDs while retaining existing marketing and group-creation event compatibility
- [ ] Change the sink contract to `supports(event)` plus `handleSendResultReported(event)` and inject a list; assert exactly one sink supports every recognized source
- [ ] Make `MarketingSendResultServiceImpl` explicitly reject historical events
- [ ] Add failing DB tests proving the historical sink validates tenant/execution/member/command identity, stores complete error code/message, ignores duplicate event IDs/results, and finalizes only after all A members are terminal
- [ ] Implement result aggregation as `SUCCESS/PARTIAL_SUCCESS/FAILED`; never enqueue a retry from consumer or sink
- [ ] Run focused tests and commit

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
mvn -pl armada-api -Dtest=ProtocolMessageEventConsumerTest,HistoricalGroupSendResultServiceImplDbTest,MarketingSendResultServiceImplTest test
git add armada-api/src/main/java/com/armada/platform/kafka/consumer/message armada-api/src/main/java/com/armada/marketing/service/impl/MarketingSendResultServiceImpl.java armada-api/src/main/java/com/armada/group/service/impl/HistoricalGroupSendResultServiceImpl.java armada-api/src/test/java/com/armada/platform/kafka/consumer/message/ProtocolMessageEventConsumerTest.java armada-api/src/test/java/com/armada/group/service/impl/HistoricalGroupSendResultServiceImplDbTest.java armada-api/src/test/java/com/armada/marketing/service/MarketingSendResultServiceImplTest.java
git commit -m "feat(group): persist historical marketing results"
```

Expected: historical result events update only their own member/execution and duplicate delivery has no side effect.

## Task 14: Define the frontend API contract

**Files:**
- Create: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/api/historical-group.ts`
- Create: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/api/historical-group.test.ts`

- [ ] Write failing API wrapper tests for list, refresh, detail, three member actions, multipart create, start, execution/latest polling and marketing send
- [ ] Define explicit TypeScript unions matching backend status codes; do not use `any`
- [ ] Ensure multipart create sends the file, fixed operation account, group JID, selected puller account-group, single-add count and idempotency key
- [ ] Implement wrappers through the project's shared HTTP client and preserve backend `errorCode/errorMessage/memberErrors` values verbatim
- [ ] Run the API test and commit

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
node --import ./src/api/__tests__/node-test-alias.mjs --test src/api/historical-group.test.ts
git add src/api/historical-group.ts src/api/historical-group.test.ts
git commit -m "feat(group): add historical group api"
```

Expected: each wrapper uses the exact Armada path and request encoding defined in Tasks 6–13.

## Task 15: Build account-group-first historical group page

**Files:**
- Create: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/group/history/index.vue`
- Create: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/group/history/composables/useHistoricalGroupPage.ts`
- Create: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/group/history/components/HistoricalGroupAccountSelector.vue`
- Create: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/group/history/components/HistoricalGroupTable.vue`
- Create: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/group/history/HistoricalGroupPage.test.ts`

- [ ] Write failing source/component tests for account group → operation account order, fixed selected account and explicit “加载群列表” refresh
- [ ] Assert initial rows use `UNVERIFIED`; refresh failure displays `FETCH_FAILED` and never “已退出”; successful rows visibly tag “在群/已退出”
- [ ] Assert current-in rows group under “管理员群组/普通成员群组” and display all four speech states; current-not-in rows remain visible in a separate “已退出” section
- [ ] Implement the selector using existing `listAccountGroups` and `listTenantAccounts`; clear account/list/detail whenever parent group changes
- [ ] Implement the table with full group JID and error message; no masking utility or ellipsis-only inaccessible value
- [ ] Keep every `.vue` under 400 lines by moving state to the composable and cells/actions to components
- [ ] Run page tests and commit

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
node --import ./src/api/__tests__/node-test-alias.mjs --test src/views/group/history/HistoricalGroupPage.test.ts
git add src/views/group/history
git commit -m "feat(group): add historical group list page"
```

Expected: only an explicit account-scoped refresh calls current-group protocol-backed APIs.

## Task 16: Add link-gated detail and member management

**Files:**
- Create: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/group/history/components/HistoricalGroupDetailDrawer.vue`
- Create: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/group/history/components/HistoricalGroupMemberTable.vue`
- Create: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/group/history/composables/useHistoricalGroupDetail.ts`
- Create: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/group/history/HistoricalGroupDetail.test.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/group/history/index.vue`

- [ ] Write failing tests proving full detail is fetched only after opening one row and always uses the selected operation account
- [ ] Add link-gate tests: absent/failed invite URL shows the complete reason and disables member management, pull form and marketing controls together
- [ ] Add role tests: admin may batch promote ordinary members, demote other admins and remove eligible users; owner/self are disabled with reasons; non-admin sees no enabled mutations
- [ ] Implement confirmation dialogs and item-level result display; retain successful results when other members fail and never auto-retry
- [ ] Render full member phone/JID/role/permission and copyable invite URL without masking
- [ ] Run detail tests and commit

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
node --import ./src/api/__tests__/node-test-alias.mjs --test src/views/group/history/HistoricalGroupDetail.test.ts
git add src/views/group/history
git commit -m "feat(group): add historical group detail actions"
```

Expected: no detail operation is clickable until the backend returns a usable group link.

## Task 17: Add per-group pull, polling and all-account marketing controls

**Files:**
- Create: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/group/history/components/HistoricalGroupPullPanel.vue`
- Create: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/group/history/components/HistoricalGroupExecutionResult.vue`
- Create: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/group/history/composables/useHistoricalGroupExecution.ts`
- Create: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/group/history/HistoricalGroupExecution.test.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/group/history/components/HistoricalGroupDetailDrawer.vue`

- [ ] Write failing form tests for selected puller account group, TXT/CSV/XLSX/XLS file, positive single-add count and no manual invite-link input
- [ ] Add result tests showing clean counts, puller, join result, every phone's contact/add status and complete errors; polling stops on terminal status or drawer close
- [ ] Add marketing tests showing a template selector only after the pull phase; the action label states “全部营销账号发送” and exposes no subset selector
- [ ] Implement create then start with one generated idempotency key; disable double submit but treat repeated backend responses as the same execution
- [ ] Poll the execution endpoint at 2 seconds while pull or marketing is nonterminal; do not perform client retries of protocol operations
- [ ] Implement marketing send and display every A account's actual protocol result, including offline/not-admin/cannot-speak errors returned after direct send
- [ ] Run execution tests and commit

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
node --import ./src/api/__tests__/node-test-alias.mjs --test src/views/group/history/HistoricalGroupExecution.test.ts
git add src/views/group/history
git commit -m "feat(group): add historical pull and marketing controls"
```

Expected: one group detail contains the complete pull/marketing workflow and all individual outcomes.

## Task 18: Register exactly one menu and perform cross-repository verification

**Files:**
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/mock/asyncRoutes.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/.harness/changes/2026-07-16-historical-group-pull-marketing.md`
- Verify: all files changed by Tasks 1–17

- [ ] Add one development async route only

Use this exact production menu tuple when the external menu provider is configured:

```text
path=/group/history
name=HistoricalGroupManagement
component=group/history/index
module_key=historical_group
perm_key=tenant:historical_group:view
title=历史群管理
```

Do not add separate pull or marketing routes. Production menu data is outside the three scoped repositories, so record the tuple in the change log and apply it only after the target environment/provider is confirmed.

- [ ] Run the full protocol verification

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
npm test -- --runInBand
npm run build
```

Expected: Jest suite and TypeScript build pass.

- [ ] Run the full Armada verification

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
mvn -pl armada-api test
```

Expected: Maven exits 0 with historical-group DB, service, controller and event tests passing.

- [ ] Run the full frontend verification

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
node --import ./src/api/__tests__/node-test-alias.mjs --test src/api/historical-group.test.ts src/views/group/history/HistoricalGroupPage.test.ts src/views/group/history/HistoricalGroupDetail.test.ts src/views/group/history/HistoricalGroupExecution.test.ts
pnpm typecheck
pnpm build
```

Expected: Node tests, Vue/TypeScript checking and production build pass.

- [ ] Perform browser acceptance against local Armada/protocol test services

Verify account group → account selection, baseline-only list, refresh labels, lazy detail, link failure disabling every action, member partial results, marketing-first pull order, live polling and all-A send results. Capture screenshots or exact observations in the change log; do not connect to a remote or real tenant without explicit environment confirmation.

- [ ] Check diffs and commit only scoped files in each repository

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol
git diff --check
git status --short
cd /Users/daishuaishuai/IdeaProjects/armada
git diff --check
git status --short
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
git diff --check
git status --short
```

Expected: no whitespace errors, no credentials, and no unrelated dirty files staged.

- [ ] Commit the frontend menu and Armada evidence log separately

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
git add mock/asyncRoutes.ts
git commit -m "feat(group): register historical group menu"
cd /Users/daishuaishuai/IdeaProjects/armada
git add .harness/changes/2026-07-16-historical-group-pull-marketing.md
git commit -m "docs: record historical group verification"
```

## Release and rollback checklist

- [ ] Deploy protocol first, then Armada migration/application, then frontend and the single production menu record.
- [ ] Confirm protocol metadata-summary and message correlation compatibility before enabling the menu permission.
- [ ] Rollback by hiding/removing the menu permission first; roll back frontend and application next. Leave additive tables/column in place until a separately reviewed cleanup migration.
- [ ] Confirm no `RUNNING`/`SENDING` records remain before application rollback; interrupted executions are marked failed and never retried.

## Task 19: Restore legacy Web protocol identity compatibility

**Files:**
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/service/impl/AccountProtocolLookupServiceImpl.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/account/AccountMapper.xml`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/account/service/AccountProtocolLookupServiceTest.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/account/service/AccountProtocolLookupServiceDbTest.java`

- [ ] Add a failing unit test proving an active account with `protocol_id = NULL`, a nonblank
  `protocol_account_id`, and a nonblank phone resolves to `ProtocolBackend.WEB`.
- [ ] Update the existing incomplete-identity test so only a blank protocol account ID or missing
  phone is rejected; a blank protocol ID is accepted as legacy Web.
- [ ] Add a failing DbTest proving the Web-only random puller selector accepts a legacy null-protocol
  Web account while still excluding an explicit Android account.
- [ ] Remove `protocolId` from the required identity fields and route with the existing fallback:

```java
if (account == null
        || !hasText(account.getProtocolAccountId())
        || !hasText(account.getWsPhone())) {
    return Optional.empty();
}
ProtocolBackend backend = ProtocolBackend.fromProtocolId(account.getProtocolId());
```

- [ ] Align the Web-only selector with the same fallback by excluding only explicit Android rows:

```sql
AND (
  a.protocol_id IS NULL
  OR TRIM(a.protocol_id) = ''
  OR UPPER(TRIM(a.protocol_id)) = #{webProtocolId}
)
```

- [ ] Run the focused unit test, compile the DbTest, validate the mapper XML, run the affected
  historical-group test set, and deploy only the Armada backend from the current local worktree.

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=AccountProtocolLookupServiceTest,HistoricalGroupPullExecutionServiceImplTest,HistoricalGroupPullWorkerImplTest test
mvn -DskipTests test-compile
xmllint --noout src/main/resources/mapper/account/AccountMapper.xml
cd ..
./armada-deploy/deploy-test.sh --be -y
```

Expected: account `302 / 919755599869` is no longer rejected merely because its
`protocol_id` is null; no account data is backfilled or re-imported. The account's actual online
state remains an independent protocol/runtime requirement.

## Task 20: Repair historical-group HTTP configuration and master routing

**Files:**
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/platform/protocol/config/ProtocolPropertiesTest.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/application.yml`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/master-gateway/routing.test.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/master-gateway/register.test.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/master-gateway/routing.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/master-gateway/register.ts`

- [ ] Add a failing Armada configuration test that loads `application.yml`, supplies
  `ARMADA_PROTOCOL_BASE_URL` and `ARMADA_PROTOCOL_API_KEY`, and proves the resolved Web backend
  uses those values instead of `http://localhost:3000`.
- [ ] Run `ProtocolPropertiesTest` and confirm the new assertion fails because the explicit
  `backends.WEB` defaults currently win over the legacy deployment variables.
- [ ] Add failing protocol routing and gateway tests proving only
  `GET /v1/accounts/{accountId}/groups` is forwarded unchanged to the owner worker; neighboring
  account-group routes and collection route `/v1/accounts/groups/batch` keep their existing rules.
- [ ] Run the two Jest suites and confirm the new GET route reaches the local master route instead
  of the injected worker forwarder.
- [ ] Extend the Web placeholders without changing their precedence for dedicated overrides:

```yaml
base-url: ${PROTOCOL_WEB_BASE_URL:${ARMADA_PROTOCOL_BASE_URL:${PROTOCOL_BASE_URL:http://localhost:3000}}}
api-key: ${PROTOCOL_WEB_API_KEY:${ARMADA_PROTOCOL_API_KEY:${PROTOCOL_API_KEY:}}}
```

- [ ] Add an exact `isAccountCurrentGroupsRequest(method, url)` predicate in protocol routing and
  include it in the master gateway allowlist; do not broadly enable every account-scoped route.
- [ ] Re-run the focused Maven/Jest tests, Armada test compilation, protocol TypeScript build,
  deployment-script tests and `git diff --check` in both repositories.
- [ ] Deploy protocol first and backend second from the current local worktrees, without staging or
  committing files. Verify the master forwards the GET request and the backend no longer attempts
  `localhost:3000`.

Expected: historical-group refresh reaches the account owner worker through the protocol master.
If the selected account is offline or `NEED_REAUTH`, the response exposes that real protocol state
instead of a synthetic network error. Test-environment security-group access to master port 8080 is
an independent environment prerequisite and is not modified by this code task.

## Task 21: Restrict operation-account selection to normal online accounts

**Files:**
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/group/history/HistoricalGroupPage.test.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/group/history/composables/useHistoricalGroupPage.ts`

- [ ] Add a failing page-state assertion proving the `/api/accounts` request created after selecting
  an account group contains all of these exact params:

```ts
{
  accountGroupId: 8,
  accountState: 2,
  loginState: 1,
  page: 1,
  pageSize: 500
}
```

- [ ] Run `HistoricalGroupPage.test.ts` and confirm the assertion fails because the existing request
  only contains `accountGroupId`, `page` and `pageSize`.
- [ ] Add `accountState: 2` and `loginState: 1` to the existing `listTenantAccounts` call. Do not
  fetch all accounts and filter locally, do not add protocol calls, and do not modify other pages.
- [ ] Re-run the historical-group page tests, all historical-group frontend tests, TypeScript checks,
  Vite build, formatting/diff checks and the frontend deployment dry-run.
- [ ] Deploy only the frontend from the current local worktree, without staging or committing files,
  then verify the deployed HistoricalGroup chunk includes both query fields.

Expected: selecting an account group lists only accounts whose persisted account state is normal
and login state is online; account-group totals keep their existing all-account meaning.

## Task 22: Give explicit historical-group refresh a 60-second client timeout

**Files:**
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/api/historical-group.test.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/api/historical-group.ts`

- [ ] Extend the existing refresh API assertion so the recorded call must include the separate
  Axios config `{ timeout: 60_000 }` while the request body remains `{ accountId: 17 }`.
- [ ] Run `historical-group.test.ts` and confirm the assertion fails only because the existing
  refresh request has no per-request timeout config.
- [ ] Pass `{ timeout: 60_000 }` as the fourth `armadaRequest` argument in
  `refreshHistoricalGroups`. Do not change the global 10-second timeout, other historical-group
  APIs, backend protocol timeout, protocol concurrency or retry behavior.
- [ ] Re-run all historical-group frontend tests, TypeScript checks, Vite build, ESLint, Prettier
  and diff/index checks.
- [ ] Deploy only the frontend from the current local worktree without staging or committing, then
  verify the deployed HistoricalGroup API chunk contains a 60,000 ms request timeout.

Expected: clicking `加载群列表` may wait for up to 60 seconds before Axios reports a timeout; every
other request keeps its existing timeout behavior and the operation is never retried automatically.
