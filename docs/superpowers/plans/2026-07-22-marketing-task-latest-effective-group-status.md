# Marketing Task Latest Effective Group Status Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 营销任务详情在账号离线时保留同账号、同群最近一次有效识别状态，同时继续展示最新一轮账号离线发送失败及其原因。

**Architecture:** 在现有 MySQL 聚合查询中为成功/失败发送记录计算“有效群状态证据”标记，群状态窗口优先选择最近有效证据、无有效证据时回退最近未识别记录；最新执行窗口保持独立，并把自己的原始群状态字段映射到行对象。Service 分别归一群状态和执行原因，避免历史群状态污染最新离线失败原因。

**Tech Stack:** Java 17、Spring Boot 3.3.5、MyBatis XML、MySQL 8 窗口函数、JUnit 5、Mockito、AssertJ、Maven。

---

## Baseline and Scope

设计依据：

- `docs/superpowers/specs/2026-07-22-marketing-task-latest-effective-group-status-design.md`

只修改 Armada 后端：

- 不改数据库表、列、索引或 Flyway。
- 不改 HTTP API 字段和前端。
- 不改协议回执契约。
- 不做账号在线检测。
- 不改任务调度、暂停恢复、重试、计数和最后成功时间。
- 不部署，不连接远程环境，不修改测试环境数据。

执行开始前：

- 使用 `using-git-worktrees` 技能建立或确认隔离工作区。
- 读取隔离工作区中的 `AGENTS.md` 和本计划。
- 保留根工作树已有 `.claude/worktrees/*` 状态，不纳入任何提交。
- 真库 DbTest 前只检查 `.env` 的非敏感目标标识；若不是明确获准的本地测试库，停止并向用户确认。

## File Map

- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingGroupExecutionNormalizer.java`
  - 让“发送失败但群检测正常”的记录产生 `NORMAL` 群状态，同时保留真实失败原因。
- Modify: `armada-api/src/test/java/com/armada/marketing/service/impl/MarketingGroupExecutionNormalizerTest.java`
  - 覆盖失败发送携带 `NORMAL / GROUP_SEND_ALLOWED` 的归一化。
- Modify: `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskAccountGroupStatRow.java`
  - 增加最新执行记录自己的原始群状态和群状态原因字段。
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`
  - 计算有效群状态证据、调整群状态窗口排序、映射最新执行记录的群状态字段。
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java`
  - 最新执行原因只使用最新执行记录自己的原始字段。
- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskServiceImplLifecycleTest.java`
  - 覆盖历史群封禁状态与最新账号离线原因的解耦。
- Modify: `armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java`
  - 锁定窗口排序和字段来源。
- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java`
  - 使用真实 MySQL 覆盖正常、封禁和从未识别三个离线场景。
- Create: `.harness/changes/2026-07-22-marketing-task-latest-effective-group-status.md`
  - 记录边界、实现和真实验证证据。

### Task 1: Recognize a Normal Group Probe on a Failed Send

**Files:**

- Modify: `armada-api/src/test/java/com/armada/marketing/service/impl/MarketingGroupExecutionNormalizerTest.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingGroupExecutionNormalizer.java`

- [ ] **Step 1: Write the failing normalizer test**

Add this test to `MarketingGroupExecutionNormalizerTest`:

```java
@Test
void failedSendKeepsRecognizedNormalGroupStatusAndItsOwnFailureReason() {
    var result = MarketingGroupExecutionNormalizer.normalize(
            MarketingSendAttemptStatus.FAILED.code(),
            "SEND_FAILED",
            "socket closed",
            "NORMAL",
            "GROUP_SEND_ALLOWED");

    assertThat(result.groupStatus()).isEqualTo("NORMAL");
    assertThat(result.executionResult()).isEqualTo("FAILED");
    assertThat(result.executionReason()).isEqualTo("socket closed");
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run from `armada-api/`:

```bash
mvn -Dtest=MarketingGroupExecutionNormalizerTest#failedSendKeepsRecognizedNormalGroupStatusAndItsOwnFailureReason test
```

Expected: the assertion fails because the current fallback returns `UNCONFIRMED`.

- [ ] **Step 3: Implement the narrow normal-state branch**

In `MarketingGroupExecutionNormalizer`, add the protocol reason constant beside the other reason constants:

```java
/** 协议已确认当前群组允许发送。 */
private static final String REASON_GROUP_SEND_ALLOWED = "GROUP_SEND_ALLOWED";
```

After the account-banned, kicked-out, group-banned and no-permission branches, but before the unknown fallback, add:

```java
if (matches(rawGroupStatus, STATUS_NORMAL)
        || matches(groupStatusReason, REASON_GROUP_SEND_ALLOWED)) {
    return failed(STATUS_NORMAL, firstText(reasonMessage, reasonCode, MESSAGE_UNKNOWN));
}
```

Known failure signals must stay ahead of this branch so an explicit account/group restriction wins over a stale normal snapshot.

- [ ] **Step 4: Run the full normalizer test class and verify GREEN**

```bash
mvn -Dtest=MarketingGroupExecutionNormalizerTest test
```

Expected: all tests in `MarketingGroupExecutionNormalizerTest` pass with zero failures and errors.

- [ ] **Step 5: Commit the focused normalization change**

```bash
git add armada-api/src/main/java/com/armada/marketing/service/impl/MarketingGroupExecutionNormalizer.java armada-api/src/test/java/com/armada/marketing/service/impl/MarketingGroupExecutionNormalizerTest.java
git commit -m "fix(marketing): retain recognized normal group state"
```

### Task 2: Capture the Offline-Shadowing Regression in a Real Database Test

**Files:**

- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java`

- [ ] **Step 1: Let test fixtures set the real group-status reason**

Keep all existing callers compatible by changing the current helper into a delegating overload:

```java
private void insertAttempt(long taskId,
                           long targetId,
                           long groupLinkId,
                           String groupJid,
                           String groupName,
                           long roundNo,
                           int status,
                           String reasonCode,
                           String reasonMessage,
                           String groupStatus,
                           long resultAt) {
    insertAttempt(taskId, targetId, groupLinkId, groupJid, groupName, roundNo, status,
            reasonCode, reasonMessage, groupStatus, "TEST_STATUS", resultAt);
}

private void insertAttempt(long taskId,
                           long targetId,
                           long groupLinkId,
                           String groupJid,
                           String groupName,
                           long roundNo,
                           int status,
                           String reasonCode,
                           String reasonMessage,
                           String groupStatus,
                           String groupStatusReason,
                           long resultAt) {
    jdbc.update("""
            INSERT INTO marketing_task_send_attempt
                (tenant_id, marketing_task_id, target_id, group_link_id, group_jid, group_name,
                 round_no, attempt_no, is_retry, command_id, status, reason_code, reason_message,
                 group_status, group_status_reason, group_status_checked_at,
                 submitted_at, result_at, attempted_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, 1, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, TEST_TENANT_ID, taskId, targetId, groupLinkId, groupJid, groupName, roundNo,
            "cmd-" + targetId + "-" + resultAt, status, reasonCode, reasonMessage,
            groupStatus, groupStatusReason, resultAt - 15,
            resultAt - 10, resultAt, resultAt - 20, resultAt - 30);
}
```

- [ ] **Step 2: Add the real-database regression test**

Add this import to `MarketingTaskCreateReadDbTest`:

```java
import com.armada.marketing.model.enums.MarketingSendAttemptStatus;
```

Add this test to `MarketingTaskCreateReadDbTest`:

```java
@Test
void getDetail_preservesLatestEffectiveGroupStatusWhenNewestAttemptIsOffline() {
    Fixture fixture = seedFixture("detail-effective-status");
    GroupFixture bannedGroup = seedGroup(
            "detail-effective-status-banned",
            "120363262@g.us",
            "https://chat.whatsapp.com/detail-effective-status-banned");
    GroupFixture unknownGroup = seedGroup(
            "detail-effective-status-unknown",
            "120363263@g.us",
            "https://chat.whatsapp.com/detail-effective-status-unknown");
    seedMembership(fixture.accountId(), bannedGroup);
    seedMembership(fixture.accountId(), unknownGroup);

    MarketingTaskVO created = service.createTask(request(
            "离线保留最近有效群状态",
            fixture.accountGroupId(),
            fixture.templateId(),
            "PENDING",
            List.of(new MarketingSelectionDTO(
                    fixture.accountId(),
                    List.of(fixture.groupLinkId(), bannedGroup.groupLinkId(), unknownGroup.groupLinkId())))));

    long normalTargetId = jdbc.queryForObject("""
            SELECT id FROM marketing_task_target
            WHERE marketing_task_id = ? AND group_link_id = ?
            """, Long.class, created.id(), fixture.groupLinkId());
    long bannedTargetId = jdbc.queryForObject("""
            SELECT id FROM marketing_task_target
            WHERE marketing_task_id = ? AND group_link_id = ?
            """, Long.class, created.id(), bannedGroup.groupLinkId());
    long unknownTargetId = jdbc.queryForObject("""
            SELECT id FROM marketing_task_target
            WHERE marketing_task_id = ? AND group_link_id = ?
            """, Long.class, created.id(), unknownGroup.groupLinkId());

    insertAttempt(created.id(), normalTargetId, fixture.groupLinkId(), fixture.groupJid(),
            "正常群", 1, MarketingSendAttemptStatus.SUCCESS.code(), null, null,
            "NORMAL", "GROUP_SEND_ALLOWED", 1000L);
    insertAttempt(created.id(), normalTargetId, fixture.groupLinkId(), fixture.groupJid(),
            "正常群", 2, MarketingSendAttemptStatus.FAILED.code(), "ACCOUNT_OFFLINE",
            "安卓账号当前不在线", "UNCONFIRMED", "STATUS_RESOLUTION_UNAVAILABLE", 2000L);

    insertAttempt(created.id(), bannedTargetId, bannedGroup.groupLinkId(), bannedGroup.groupJid(),
            "封禁群", 1, MarketingSendAttemptStatus.FAILED.code(), "SEND_FAILED",
            "群组不可发送", "BANNED", "CHAT_SUSPENDED", 1100L);
    insertAttempt(created.id(), bannedTargetId, bannedGroup.groupLinkId(), bannedGroup.groupJid(),
            "封禁群", 2, MarketingSendAttemptStatus.FAILED.code(), "ACCOUNT_OFFLINE",
            "安卓账号当前不在线", "UNCONFIRMED", "STATUS_RESOLUTION_UNAVAILABLE", 2100L);

    insertAttempt(created.id(), unknownTargetId, unknownGroup.groupLinkId(), unknownGroup.groupJid(),
            "从未识别群", 2, MarketingSendAttemptStatus.FAILED.code(), "ACCOUNT_OFFLINE",
            "安卓账号当前不在线", "UNCONFIRMED", "STATUS_RESOLUTION_UNAVAILABLE", 2200L);

    MarketingTaskDetailVO detail = service.getDetail(created.id());

    assertThat(detail.accountTargets()).singleElement().satisfies(account -> {
        assertThat(account.groups())
                .filteredOn(item -> fixture.groupJid().equals(item.groupJid()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.groupStatus()).isEqualTo("NORMAL");
                    assertThat(item.executionResult()).isEqualTo("FAILED");
                    assertThat(item.executionReason()).isEqualTo("安卓账号当前不在线");
                });
        assertThat(account.groups())
                .filteredOn(item -> bannedGroup.groupJid().equals(item.groupJid()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.groupStatus()).isEqualTo("GROUP_BANNED");
                    assertThat(item.executionResult()).isEqualTo("FAILED");
                    assertThat(item.executionReason()).isEqualTo("安卓账号当前不在线");
                });
        assertThat(account.groups())
                .filteredOn(item -> unknownGroup.groupJid().equals(item.groupJid()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.groupStatus()).isEqualTo("UNCONFIRMED");
                    assertThat(item.executionResult()).isEqualTo("FAILED");
                    assertThat(item.executionReason()).isEqualTo("安卓账号当前不在线");
                });
    });
}
```

- [ ] **Step 3: Confirm the DbTest target before any connection**

From `armada-api/`, first verify `.env` exists:

```bash
test -f .env
```

Inspect only non-secret host/schema settings. If the target is remote, shared, ambiguous, or not explicitly authorized, stop before running `dbtest.sh` and ask the user. Do not print usernames, passwords, tokens, or the full `.env`.

- [ ] **Step 4: Run the regression test and verify RED**

Only after the target is confirmed as an authorized test database, run:

```bash
./dbtest.sh 'MarketingTaskCreateReadDbTest#getDetail_preservesLatestEffectiveGroupStatusWhenNewestAttemptIsOffline'
```

Expected on the old implementation: the normal and banned groups are returned as `UNCONFIRMED`; the banned group may also expose `群组已封禁` instead of the latest offline execution reason.

Do not commit the failing regression alone. Keep it for Task 3's implementation commit.

### Task 3: Separate Effective Group Evidence from Latest Execution Evidence

**Files:**

- Modify: `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskAccountGroupStatRow.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskServiceImplLifecycleTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java`
- Test: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java`

- [ ] **Step 1: Add a failing Service-level independence test**

Add to `MarketingTaskServiceImplLifecycleTest`:

```java
@Test
void getDetailKeepsHistoricalGroupStatusAndLatestOfflineReasonIndependent() {
    MarketingTaskAccountGroupStatRow group = new MarketingTaskAccountGroupStatRow();
    group.setAccountId(31L);
    group.setGroupJid("120363031@g.us");
    group.setLatestAttemptStatus(MarketingSendAttemptStatus.FAILED.code());
    group.setReasonCode("SEND_FAILED");
    group.setReasonMessage("群组不可发送");
    group.setGroupStatus("BANNED");
    group.setGroupStatusReason("CHAT_SUSPENDED");
    group.setLatestExecutionStatus(MarketingSendAttemptStatus.FAILED.code());
    group.setExecutionReasonCode("ACCOUNT_OFFLINE");
    group.setExecutionReasonMessage("安卓账号当前不在线");
    group.setExecutionGroupStatus("UNCONFIRMED");
    group.setExecutionGroupStatusReason("STATUS_RESOLUTION_UNAVAILABLE");
    stubDetail(detailTask(), detailTarget(), group);

    var detail = service.getDetail(TASK_ID);

    assertThat(detail.accountTargets()).singleElement()
            .satisfies(account -> assertThat(account.groups()).singleElement().satisfies(item -> {
                assertThat(item.groupStatus()).isEqualTo("GROUP_BANNED");
                assertThat(item.executionResult()).isEqualTo("FAILED");
                assertThat(item.executionReason()).isEqualTo("安卓账号当前不在线");
            }));
}
```

- [ ] **Step 2: Add a failing SQL-shape test**

Add to `MarketingTaskMapperSqlShapeTest`:

```java
@Test
void detailRollupPrefersEffectiveGroupEvidenceAndKeepsExecutionEvidenceIndependent()
        throws IOException {
    String xml = new String(
            getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
            StandardCharsets.UTF_8);

    String sql = selectBlock(xml, "selectAccountGroupStatsByTaskId");

    assertThat(sql)
            .contains("END AS effectiveGroupStatus")
            .contains("effectiveGroupStatus DESC")
            .contains("ended.rawGroupStatus AS executionGroupStatus")
            .contains("ended.groupStatusReason AS executionGroupStatusReason");
}
```

- [ ] **Step 3: Run the two focused tests and verify RED**

```bash
mvn -Dtest=MarketingTaskServiceImplLifecycleTest#getDetailKeepsHistoricalGroupStatusAndLatestOfflineReasonIndependent,MarketingTaskMapperSqlShapeTest#detailRollupPrefersEffectiveGroupEvidenceAndKeepsExecutionEvidenceIndependent test
```

Expected: test compilation fails because the row object does not yet expose the two execution group fields. After those fields exist, the SQL-shape assertion must still fail until the Mapper XML is changed.

- [ ] **Step 4: Add independent execution group fields to the Mapper row**

Add fields beside `executionReasonCode` and `executionReasonMessage` in `MarketingTaskAccountGroupStatRow`:

```java
/** 最后已结束尝试携带的原始群组检测状态。 */
private String executionGroupStatus;

/** 最后已结束尝试携带的群组检测原因。 */
private String executionGroupStatusReason;
```

Add their accessors:

```java
public String getExecutionGroupStatus() {
    return executionGroupStatus;
}

public void setExecutionGroupStatus(String executionGroupStatus) {
    this.executionGroupStatus = executionGroupStatus;
}

public String getExecutionGroupStatusReason() {
    return executionGroupStatusReason;
}

public void setExecutionGroupStatusReason(String executionGroupStatusReason) {
    this.executionGroupStatusReason = executionGroupStatusReason;
}
```

- [ ] **Step 5: Compute effective evidence in `attempt_facts`**

In `MarketingTaskMapper.xml`, add this expression immediately after `groupStatusReason` in the `attempt_facts` projection:

```xml
CASE
    WHEN a.status = 1 THEN 1
    WHEN UPPER(TRIM(COALESCE(a.group_status, ''))) IN (
        'NORMAL', 'BANNED', 'NO_PERMISSION'
    ) THEN 1
    WHEN UPPER(TRIM(COALESCE(a.reason_code, ''))) IN (
        'ACCOUNT_BANNED',
        'KICKED_OUT', 'ACCOUNT_NOT_PARTICIPANT',
        'GROUP_BANNED', 'BANNED', 'CHAT_SUSPENDED', 'CHAT_TERMINATED',
        'NO_PERMISSION', 'ANNOUNCE_ONLY_NON_ADMIN'
    ) THEN 1
    WHEN UPPER(TRIM(COALESCE(a.group_status_reason, ''))) IN (
        'GROUP_SEND_ALLOWED',
        'ACCOUNT_NOT_PARTICIPANT',
        'CHAT_SUSPENDED', 'CHAT_TERMINATED',
        'ANNOUNCE_ONLY_NON_ADMIN'
    ) THEN 1
    ELSE 0
END AS effectiveGroupStatus,
```

`ACCOUNT_OFFLINE` and `STATUS_RESOLUTION_UNAVAILABLE` are deliberately absent, so an offline observation cannot displace recognized history.

- [ ] **Step 6: Prefer effective evidence in the group-status window**

Change only `latest_protocol` ordering to:

```xml
ROW_NUMBER() OVER (
    PARTITION BY tenant_id, accountId, groupKey
    ORDER BY effectiveGroupStatus DESC,
             roundNo DESC,
             attemptNo DESC,
             attemptId DESC
) AS protocolRank
```

Keep `WHERE attemptStatus IN (1, 2)`. Do not change `latest_ended`; it must remain a pure latest-execution window.

- [ ] **Step 7: Map the latest execution record's own group fields**

Add these projections after `executionReasonMessage` in the final SELECT:

```xml
ended.rawGroupStatus AS executionGroupStatus,
ended.groupStatusReason AS executionGroupStatusReason,
```

- [ ] **Step 8: Use execution-owned fields in the Service**

In the failed-execution branch of `MarketingTaskServiceImpl.toGroupStatVO`, replace the two group arguments with:

```java
executionReason = MarketingGroupExecutionNormalizer.normalize(
        row.getLatestExecutionStatus(),
        row.getExecutionReasonCode(),
        row.getExecutionReasonMessage(),
        row.getExecutionGroupStatus(),
        row.getExecutionGroupStatusReason()).executionReason();
```

Do not fall back to `row.getGroupStatus()` when the execution fields are null; that would reintroduce cross-record contamination for historical data.

- [ ] **Step 9: Run focused unit and SQL-shape tests and verify GREEN**

```bash
mvn -Dtest=MarketingGroupExecutionNormalizerTest,MarketingTaskServiceImplLifecycleTest,MarketingTaskMapperSqlShapeTest test
```

Expected: all three classes pass with zero failures and errors.

- [ ] **Step 10: Run the real-database regression and verify GREEN**

After the Task 2 database target check remains valid:

```bash
./dbtest.sh 'MarketingTaskCreateReadDbTest#getDetail_preservesLatestEffectiveGroupStatusWhenNewestAttemptIsOffline'
```

Expected: the normal group remains `NORMAL`, the banned group remains `GROUP_BANNED`, the never-recognized group remains `UNCONFIRMED`, and all three expose the latest offline execution reason.

- [ ] **Step 11: Commit the regression and implementation together**

```bash
git add armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskAccountGroupStatRow.java armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java armada-api/src/test/java/com/armada/marketing/service/MarketingTaskServiceImplLifecycleTest.java armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java
git commit -m "fix(marketing): preserve effective group status across offline attempts"
```

### Task 4: Record the Change and Run Delivery Verification

**Files:**

- Create: `.harness/changes/2026-07-22-marketing-task-latest-effective-group-status.md`
- Verify: all files changed in Tasks 1–3

- [ ] **Step 1: Create the change record**

Create `.harness/changes/2026-07-22-marketing-task-latest-effective-group-status.md` with:

```markdown
# 营销任务明细最近有效群状态

## 背景

账号离线时协议无法解析群状态，最新离线记录会把历史已识别状态覆盖为 `UNCONFIRMED`。

## 已确认口径

- 群组状态取同账号、同群最近一次有效识别结果。
- 最新执行结果与执行原因仍取最新已结束记录。
- 没有历史有效结果时展示 `UNCONFIRMED`。
- 不增加在线检测，不改数据库结构、前端或协议契约。

## 实现

- Mapper 为成功和明确群状态信号标记有效证据，并优先选择最近有效证据。
- 最新执行窗口独立映射原始群状态字段。
- Service 使用执行记录自己的字段生成执行原因。
- 失败发送携带 `NORMAL / GROUP_SEND_ALLOWED` 时，群状态归一为 `NORMAL`，执行仍为失败。

## 验证命令

- `mvn -Dtest=MarketingGroupExecutionNormalizerTest,MarketingTaskServiceImplLifecycleTest,MarketingTaskMapperSqlShapeTest test`
- `./dbtest.sh 'MarketingTaskCreateReadDbTest#getDetail_preservesLatestEffectiveGroupStatusWhenNewestAttemptIsOffline'`
- `mvn test`

## 部署与回滚

- 本次未部署、未修改远程数据。
- 无数据库迁移；回滚代码提交即可。
```

- [ ] **Step 2: Run the focused regression suite**

From `armada-api/`:

```bash
mvn -Dtest=MarketingGroupExecutionNormalizerTest,MarketingTaskServiceImplLifecycleTest,MarketingTaskMapperSqlShapeTest test
```

Expected: exit code 0, zero failures and errors.

- [ ] **Step 3: Run the full ordinary unit-test suite**

```bash
mvn test
```

Expected: exit code 0. Record the actual test count, failures, errors and skipped count in the change record; if an unrelated baseline failure appears, report it separately and do not claim the full suite passed.

- [ ] **Step 4: Re-run the focused real-database test**

Only if `.env` is still confirmed as an authorized target:

```bash
./dbtest.sh 'MarketingTaskCreateReadDbTest#getDetail_preservesLatestEffectiveGroupStatusWhenNewestAttemptIsOffline'
```

Expected: exit code 0 and output proving the named test executed. If the environment is unavailable or unauthorized, record that exact limitation instead of treating `mvn test` as a substitute.

- [ ] **Step 5: Review the final diff and repository boundaries**

Run from the repository root:

```bash
git diff --check
git status --short
git diff --stat HEAD~2..HEAD
```

Expected:

- no whitespace errors;
- no `.env`, PEM, credential, frontend, protocol, migration or unrelated `.claude/worktrees/*` files staged;
- production changes limited to the row DTO, Mapper XML, normalizer and task Service;
- tests and the change record match the confirmed scope.

- [ ] **Step 6: Update the change record with observed verification evidence**

Under `## 验证命令`, append the actual exit code and Maven/DbTest test counts from Steps 2–4. State explicitly whether the DbTest target was authorized and whether the test truly executed. Do not include connection strings or credentials.

- [ ] **Step 7: Commit the change record**

```bash
git add .harness/changes/2026-07-22-marketing-task-latest-effective-group-status.md
git commit -m "docs(marketing): record effective group status verification"
```

- [ ] **Step 8: Apply completion verification before reporting success**

Use the `verification-before-completion` skill. Base the final report only on the fresh commands from this task, list any unrun or blocked DbTest explicitly, and state that no deployment or online detection was performed.
