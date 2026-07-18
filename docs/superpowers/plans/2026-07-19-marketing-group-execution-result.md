# Marketing Group Execution Result Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在营销任务明细的账号群组列表中展示最新有效轮次的「发送成功 / 发送失败」，并仅在执行中任务的详情抽屉打开时每 5 秒刷新。

**Architecture:** Armada 后端继续以 `marketing_task_send_attempt` 为唯一发送事实源，在现有账号+实际群组聚合查询中按 `round_no, attempt_no, id` 选择最新成功或失败并输出可空 `executionResult`。Vue 前端扩展详情类型和组合列，在页面 composable 内管理单任务轮询、请求互斥、迟到响应隔离和清理，不修改协议层、Kafka 或数据库结构。

**Tech Stack:** Java 17, Spring Boot 3.3.5, MyBatis, MySQL 8, JUnit 5, AssertJ, Vue 3, TypeScript, Element Plus, Node test runner, pnpm, Vite.

---

## Repository and File Map

### Backend: `/Users/daishuaishuai/IdeaProjects/armada`

- Modify `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`: 从现有 attempt 聚合中投影最新有效执行结果。
- Modify `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskAccountGroupStatRow.java`: 承接 Mapper 的 `executionResult` 原始值。
- Modify `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskGroupStatVO.java`: 扩展详情 API 群组行契约。
- Modify `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java`: 把原始行字段映射到详情 VO。
- Modify `armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java`: 锁定有效状态和轮次排序 SQL。
- Modify `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java`: 真库验证固定群、动态群、跳过和迟到结果。
- Create `.harness/changes/2026-07-19-marketing-group-execution-result.md`: 记录后端/API 变更、验证和回滚。

### Frontend: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web`

- Modify `src/api/marketing-task.ts`: 增加 `MarketingGroupExecutionResult` 和群组行字段。
- Create `src/views/task/group-marketing/components/group-execution-result.ts`: 中文文案和标签元数据映射。
- Create `src/views/task/group-marketing/components/group-execution-result.test.ts`: 映射测试。
- Modify `src/views/task/group-marketing/components/GroupMarketingDetailDrawer.vue`: 在收起和展开群组行展示执行情况。
- Modify `src/views/task/group-marketing/components/GroupMarketingDetailDrawer.test.ts`: 锁定列顺序和渲染入口。
- Modify `src/views/task/group-marketing/composables/useGroupMarketingTaskPage.ts`: 管理执行中任务详情的 5 秒轮询。
- Modify `src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts`: 验证轮询、互斥、清理、失败提示和迟到响应隔离。
- Create `.harness/changes/marketing-group-execution-result/summary.md`: 记录前端变更和验证。

### Protocol: `/Users/daishuaishuai/IdeaProjects/armada-protocol`

- No files changed. Existing `message.send_result_reported` already supplies the success/failure fact consumed by Armada.

## Execution Preconditions

- Execute backend and frontend work in isolated worktrees created at implementation time.
- Before editing, read each repository's `AGENTS.md`, confirm branch/worktree, and inspect `git status --short --branch`.
- Preserve the existing unrelated `.claude/worktrees/*` entries in the Armada root worktree.
- Do not run deployment, SSH, shared-database schema changes, or remote mutations.

### Task 1: Backend Latest Effective Group Result

**Files:**
- Modify: `armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskAccountGroupStatRow.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskGroupStatVO.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java`

- [ ] **Step 1: Add the failing SQL contract test**

Add this method to `MarketingTaskMapperSqlShapeTest`:

```java
@Test
void detailRollupUsesLatestEffectiveRoundForExecutionResult() throws IOException {
    String xml = new String(
            getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
            StandardCharsets.UTF_8);

    String sql = selectBlock(xml, "selectAccountGroupStatsByTaskId");

    assertThat(sql)
            .contains("WHEN a.status = 1 THEN 'SUCCESS'")
            .contains("WHEN a.status = 2 THEN 'FAILED'")
            .contains("ORDER BY a.round_no DESC, a.attempt_no DESC, a.id DESC")
            .contains("AS executionResult");
}
```

- [ ] **Step 2: Run the SQL contract test and verify RED**

From `armada/armada-api` run:

```bash
mvn -Dtest=MarketingTaskMapperSqlShapeTest test
```

Expected: `detailRollupUsesLatestEffectiveRoundForExecutionResult` fails because the SQL has no `executionResult` projection.

- [ ] **Step 3: Add failing DbTests for fixed and dynamic groups**

Add these methods to `MarketingTaskCreateReadDbTest`:

```java
@Test
void getDetail_usesLatestEffectiveRoundForGroupExecutionResult() {
    Fixture fixture = seedFixture("detail-execution-result");
    GroupFixture secondGroup = seedGroup(
            "detail-execution-result-empty",
            "120363188@g.us",
            "https://chat.whatsapp.com/detail-execution-result-empty");
    MarketingTaskVO created = service.createTask(request(
            "群执行结果任务",
            fixture.accountGroupId(),
            fixture.templateId(),
            "PENDING",
            List.of(new MarketingSelectionDTO(
                    fixture.accountId(),
                    List.of(fixture.groupLinkId(), secondGroup.groupLinkId())))));
    List<Long> targetIds = jdbc.queryForList(
            "SELECT id FROM marketing_task_target WHERE marketing_task_id = ? ORDER BY id ASC",
            Long.class,
            created.id());

    insertAttempt(created.id(), targetIds.get(0), fixture.groupLinkId(), fixture.groupJid(),
            "群A", 1, 1, null, null, "NORMAL", 4000L);
    insertAttempt(created.id(), targetIds.get(0), fixture.groupLinkId(), fixture.groupJid(),
            "群A", 2, 2, "SEND_FAILED", "发送失败", "NORMAL", 2000L);
    insertAttempt(created.id(), targetIds.get(0), fixture.groupLinkId(), fixture.groupJid(),
            "群A", 3, 3, "ACCOUNT_OCCUPIED", "账号被占用", "UNCONFIRMED", 6000L);
    insertAttempt(created.id(), targetIds.get(1), secondGroup.groupLinkId(), secondGroup.groupJid(),
            "群B", 1, 3, "ACCOUNT_OCCUPIED", "账号被占用", "UNCONFIRMED", 5000L);

    MarketingTaskDetailVO detail = service.getDetail(created.id());

    assertThat(detail.accountTargets()).singleElement().satisfies(account -> {
        assertThat(account.groups())
                .filteredOn(group -> fixture.groupJid().equals(group.groupJid()))
                .singleElement()
                .satisfies(group -> assertThat(group.executionResult()).isEqualTo("FAILED"));
        assertThat(account.groups())
                .filteredOn(group -> secondGroup.groupJid().equals(group.groupJid()))
                .singleElement()
                .satisfies(group -> assertThat(group.executionResult()).isNull());
    });
}

@Test
void getDetail_rollsUpDynamicGroupExecutionResult() {
    Fixture fixture = seedFixture("detail-dynamic-execution-result");
    MarketingTaskVO created = service.createTask(request(
            "动态群执行结果任务",
            fixture.accountGroupId(),
            fixture.templateId(),
            "PENDING",
            List.of(new MarketingSelectionDTO(
                    fixture.accountId(),
                    "ACCOUNT_DYNAMIC",
                    List.of()))));
    Long targetId = jdbc.queryForObject(
            "SELECT id FROM marketing_task_target WHERE marketing_task_id = ?",
            Long.class,
            created.id());

    insertAttempt(created.id(), targetId, fixture.groupLinkId(), fixture.groupJid(),
            "动态群A", 1, 1, null, null, "NORMAL", 1000L);

    MarketingTaskDetailVO detail = service.getDetail(created.id());

    assertThat(detail.accountTargets()).singleElement().satisfies(account ->
            assertThat(account.groups()).singleElement().satisfies(group -> {
                assertThat(group.groupJid()).isEqualTo(fixture.groupJid());
                assertThat(group.executionResult()).isEqualTo("SUCCESS");
            }));
}
```

The first test proves three independent rules: round 2 beats round 1 even though round 1 has a later `result_at`; round 3 skipped does not erase round 2; a group with only skipped history returns `null`.

- [ ] **Step 4: Run the DbTests and verify RED**

```bash
./dbtest.sh 'MarketingTaskCreateReadDbTest#getDetail_usesLatestEffectiveRoundForGroupExecutionResult+getDetail_rollsUpDynamicGroupExecutionResult'
```

Expected: test compilation fails because `MarketingTaskGroupStatVO` does not yet expose `executionResult()`.

- [ ] **Step 5: Add the backend raw-row and API fields**

Add this field and accessors to `MarketingTaskAccountGroupStatRow` next to `groupStatus`:

```java
private String executionResult;

public String getExecutionResult() {
    return executionResult;
}

public void setExecutionResult(String executionResult) {
    this.executionResult = executionResult;
}
```

Add `String executionResult` immediately after `String groupStatus` in `MarketingTaskGroupStatVO`:

```java
public record MarketingTaskGroupStatVO(
        Long groupLinkId,
        String groupJid,
        String groupLinkUrl,
        String groupName,
        String groupStatus,
        String executionResult,
        Integer sentMessageCount,
        Integer failedMessageCount,
        Long lastAttemptAt,
        Long lastSentAt,
        String lastReason) {
}
```

Replace `toGroupStatVO` in `MarketingTaskServiceImpl` with:

```java
private static MarketingTaskGroupStatVO toGroupStatVO(MarketingTaskAccountGroupStatRow row) {
    return new MarketingTaskGroupStatVO(row.getGroupLinkId(), row.getGroupJid(), row.getGroupLinkUrl(),
            row.getGroupName(), groupStatus(row.getGroupStatus()), row.getExecutionResult(),
            zero(row.getSentMessageCount()), zero(row.getFailedMessageCount()),
            row.getLastAttemptAt(), row.getLastSentAt(), row.getLastReason());
}
```

- [ ] **Step 6: Project the latest effective result in the existing aggregate SQL**

In `selectAccountGroupStatsByTaskId`, insert this projection immediately after `groupStatus`:

```xml
               SUBSTRING_INDEX(
                   GROUP_CONCAT(
                       CASE
                           WHEN a.status = 1 THEN 'SUCCESS'
                           WHEN a.status = 2 THEN 'FAILED'
                           ELSE NULL
                       END
                       ORDER BY a.round_no DESC, a.attempt_no DESC, a.id DESC
                       SEPARATOR '\n'
                   ),
                   '\n',
                   1
               ) AS executionResult,
```

Keep the existing outer `WHERE a.status IN (1, 2, 3)`. `GROUP_CONCAT` ignores `NULL`, so submitted/skipped attempts cannot overwrite the last success/failure, and an only-skipped group produces `null`.

- [ ] **Step 7: Run the focused tests and verify GREEN**

Run:

```bash
mvn -Dtest=MarketingTaskMapperSqlShapeTest test
./dbtest.sh 'MarketingTaskCreateReadDbTest#getDetail_usesLatestEffectiveRoundForGroupExecutionResult+getDetail_rollsUpDynamicGroupExecutionResult'
```

Expected: both commands exit 0; the Maven test reports `BUILD SUCCESS`, and both DbTest methods pass against real MySQL.

- [ ] **Step 8: Commit the backend feature**

```bash
git add armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml \
  armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskAccountGroupStatRow.java \
  armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskGroupStatVO.java \
  armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java \
  armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java \
  armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java
git commit -m "feat(marketing): expose latest group execution result"
```

### Task 2: Frontend Execution Result Column

**Files:**
- Modify: `src/api/marketing-task.ts`
- Create: `src/views/task/group-marketing/components/group-execution-result.ts`
- Create: `src/views/task/group-marketing/components/group-execution-result.test.ts`
- Modify: `src/views/task/group-marketing/components/GroupMarketingDetailDrawer.vue`
- Modify: `src/views/task/group-marketing/components/GroupMarketingDetailDrawer.test.ts`

- [ ] **Step 1: Write the failing result-mapping test**

Create `group-execution-result.test.ts`:

```ts
import assert from "node:assert/strict";
import { describe, it } from "node:test";

// @ts-expect-error Node's built-in TypeScript runner needs the explicit extension here.
import { groupExecutionResultMeta } from "./group-execution-result.ts";

describe("group execution result meta", () => {
  it("maps backend results to the confirmed Chinese labels", () => {
    assert.deepEqual(groupExecutionResultMeta("SUCCESS"), {
      label: "发送成功",
      tagType: "success",
      tagged: true
    });
    assert.deepEqual(groupExecutionResultMeta("FAILED"), {
      label: "发送失败",
      tagType: "danger",
      tagged: true
    });
  });

  it("shows a plain dash for missing or unknown values", () => {
    assert.deepEqual(groupExecutionResultMeta(null), {
      label: "-",
      tagType: "info",
      tagged: false
    });
    assert.equal(groupExecutionResultMeta("FUTURE_VALUE").label, "-");
  });
});
```

- [ ] **Step 2: Extend the component source test before implementation**

Replace the existing status-column test in `GroupMarketingDetailDrawer.test.ts` with:

```ts
it("shows status and execution result before the remaining group fields", () => {
  for (const label of [
    "状态",
    "执行情况",
    "单群发送条数",
    "群组链接",
    "群组名称",
    "最近原因",
    "最后发送时间"
  ]) {
    assert.match(source, new RegExp(label));
  }
  assert.match(
    source,
    /<span>状态<\/span>\s*<span>执行情况<\/span>\s*<span>单群发送条数<\/span>/
  );
  assert.match(source, /groupSendStatusMeta/);
  assert.match(source, /groupExecutionResultMeta/);
  assert.match(source, /group-status--no-permission/);
  assert.match(source, /group\.executionResult/);
  assert.match(source, /firstGroup\(asAccountRow\(row\)\)\?\.executionResult/);
});
```

- [ ] **Step 3: Run the frontend display tests and verify RED**

From `wheel-saas-pure-web` run:

```bash
node --import ./src/api/__tests__/node-test-alias.mjs --test \
  src/views/task/group-marketing/components/group-execution-result.test.ts \
  src/views/task/group-marketing/components/GroupMarketingDetailDrawer.test.ts
```

Expected: the new helper module is missing and the component lacks the `执行情况` header.

- [ ] **Step 4: Add the API type and result metadata helper**

In `src/api/marketing-task.ts`, add:

```ts
export type MarketingGroupExecutionResult = "SUCCESS" | "FAILED";
```

Add this field to `MarketingTaskGroupStatRow` immediately after `groupStatus`:

```ts
executionResult?: MarketingGroupExecutionResult | null;
```

Create `group-execution-result.ts`:

```ts
export interface GroupExecutionResultMeta {
  label: string;
  tagType: "success" | "danger" | "info";
  tagged: boolean;
}

const EMPTY_META: GroupExecutionResultMeta = {
  label: "-",
  tagType: "info",
  tagged: false
};

const RESULT_META: Record<string, GroupExecutionResultMeta> = {
  SUCCESS: {
    label: "发送成功",
    tagType: "success",
    tagged: true
  },
  FAILED: {
    label: "发送失败",
    tagType: "danger",
    tagged: true
  }
};

export function groupExecutionResultMeta(
  result: string | null | undefined
): GroupExecutionResultMeta {
  return result ? (RESULT_META[result] ?? EMPTY_META) : EMPTY_META;
}
```

- [ ] **Step 5: Render execution results in collapsed and expanded group rows**

Import the helper in `GroupMarketingDetailDrawer.vue`:

```ts
import { groupExecutionResultMeta } from "./group-execution-result";
```

In the expanded `v-for` row, insert this block immediately after the existing group-status tag:

```vue
<el-tag
  v-if="groupExecutionResultMeta(group.executionResult).tagged"
  size="small"
  effect="plain"
  :type="groupExecutionResultMeta(group.executionResult).tagType"
>
  {{ groupExecutionResultMeta(group.executionResult).label }}
</el-tag>
<span v-else class="group-rollup-empty">-</span>
```

Add the header immediately after `状态`:

```vue
<span>状态</span>
<span>执行情况</span>
<span>单群发送条数</span>
```

In the collapsed first-group row, insert this block immediately after the existing group-status tag:

```vue
<el-tag
  v-if="
    groupExecutionResultMeta(
      firstGroup(asAccountRow(row))?.executionResult
    ).tagged
  "
  size="small"
  effect="plain"
  :type="
    groupExecutionResultMeta(
      firstGroup(asAccountRow(row))?.executionResult
    ).tagType
  "
>
  {{
    groupExecutionResultMeta(
      firstGroup(asAccountRow(row))?.executionResult
    ).label
  }}
</el-tag>
<span v-else class="group-rollup-empty">-</span>
```

Increase the combination column minimum width from `880` to `1000`, and replace the desktop grid columns with:

```css
grid-template-columns:
  104px 104px 112px minmax(190px, 1.35fr) minmax(150px, 1fr)
  minmax(130px, 0.9fr) 170px;
```

Keep the existing two-column mobile media rule.

- [ ] **Step 6: Run the display tests and typecheck**

```bash
node --import ./src/api/__tests__/node-test-alias.mjs --test \
  src/views/task/group-marketing/components/group-execution-result.test.ts \
  src/views/task/group-marketing/components/GroupMarketingDetailDrawer.test.ts
pnpm typecheck
```

Expected: all Node tests pass and both `tsc` and `vue-tsc` exit 0.

- [ ] **Step 7: Commit the frontend display feature**

```bash
git add src/api/marketing-task.ts \
  src/views/task/group-marketing/components/group-execution-result.ts \
  src/views/task/group-marketing/components/group-execution-result.test.ts \
  src/views/task/group-marketing/components/GroupMarketingDetailDrawer.vue \
  src/views/task/group-marketing/components/GroupMarketingDetailDrawer.test.ts
git commit -m "feat(marketing): show group execution result"
```

### Task 3: Execution-Only Detail Polling

**Files:**
- Modify: `src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts`
- Modify: `src/views/task/group-marketing/composables/useGroupMarketingTaskPage.ts`

- [ ] **Step 1: Add reusable fake-timer and deferred helpers to the page test**

Update the API type import to include `MarketingTaskDetail` and add these helpers near the top of `useGroupMarketingTaskPage.test.ts`:

```ts
import { effectScope } from "vue";
import type {
  MarketingGroupExecutionResult,
  MarketingTaskDetail,
  MarketingTaskRow
} from "@/api/marketing-task";

function marketingDetail(
  id: number,
  status: 1 | 2 | 5 | 7 | 8,
  executionResult: MarketingGroupExecutionResult | null
): MarketingTaskDetail {
  return {
    id,
    status,
    taskName: `任务${id}`,
    accountTargets: [
      {
        accountId: 3,
        accountPhone: "923300000003",
        status: 2,
        sentMessageCount: executionResult === "SUCCESS" ? 1 : 0,
        failedMessageCount: executionResult === "FAILED" ? 1 : 0,
        groups: [
          {
            groupJid: "120363003@g.us",
            executionResult,
            sentMessageCount: executionResult === "SUCCESS" ? 1 : 0,
            failedMessageCount: executionResult === "FAILED" ? 1 : 0
          }
        ]
      }
    ]
  } as MarketingTaskDetail;
}

function deferred<T>(): {
  promise: Promise<T>;
  resolve: (value: T) => void;
} {
  let resolvePromise!: (value: T) => void;
  const promise = new Promise<T>(resolve => {
    resolvePromise = resolve;
  });
  return { promise, resolve: resolvePromise };
}

function installIntervalHarness(): {
  delays: number[];
  cleared: Array<ReturnType<typeof setInterval>>;
  run: () => void;
  restore: () => void;
} {
  const originalSetInterval = globalThis.setInterval;
  const originalClearInterval = globalThis.clearInterval;
  const callbacks: Array<() => void> = [];
  const delays: number[] = [];
  const cleared: Array<ReturnType<typeof setInterval>> = [];
  const handle = 51 as unknown as ReturnType<typeof setInterval>;

  globalThis.setInterval = ((callback: () => void, delay?: number) => {
    callbacks.push(callback);
    delays.push(Number(delay));
    return handle;
  }) as typeof setInterval;
  globalThis.clearInterval = ((value: ReturnType<typeof setInterval>) => {
    cleared.push(value);
  }) as typeof clearInterval;

  return {
    delays,
    cleared,
    run: () => callbacks.at(-1)?.(),
    restore: () => {
      globalThis.setInterval = originalSetInterval;
      globalThis.clearInterval = originalClearInterval;
    }
  };
}

async function flushAsyncWork(): Promise<void> {
  await new Promise<void>(resolve => setImmediate(resolve));
}
```

Also add `resetArmadaMockFailure` to the existing import from `armada-test-double`.

- [ ] **Step 2: Add failing polling lifecycle tests**

Add these tests to `useGroupMarketingTaskPage.test.ts`:

```ts
it("polls every five seconds only while the detail task is sending", async () => {
  const timers = installIntervalHarness();
  try {
    resetArmadaMockQueue([
      marketingDetail(42, 2, "SUCCESS"),
      marketingDetail(42, 7, "FAILED")
    ]);
    const pageState = useGroupMarketingTaskPage();

    await pageState.openDetailDrawer({ id: 42, status: 2 } as MarketingTaskRow);

    assert.deepEqual(timers.delays, [5000]);
    assert.equal(
      pageState.detailTask.value?.accountTargets?.[0].groups[0].executionResult,
      "SUCCESS"
    );

    timers.run();
    await flushAsyncWork();

    assert.equal(pageState.detailTask.value?.status, 7);
    assert.equal(
      pageState.detailTask.value?.accountTargets?.[0].groups[0].executionResult,
      "FAILED"
    );
    assert.equal(timers.cleared.length, 1);
    assert.equal(armadaCalls().length, 2);
  } finally {
    timers.restore();
  }
});

it("does not start polling for a non-sending detail task", async () => {
  const timers = installIntervalHarness();
  try {
    resetArmadaMock(marketingDetail(42, 7, "SUCCESS"));
    const pageState = useGroupMarketingTaskPage();

    await pageState.openDetailDrawer({ id: 42, status: 7 } as MarketingTaskRow);

    assert.deepEqual(timers.delays, []);
  } finally {
    timers.restore();
  }
});

it("skips overlapping polls and ignores a stale response after switching tasks", async () => {
  const timers = installIntervalHarness();
  try {
    resetArmadaMock(marketingDetail(42, 2, "SUCCESS"));
    const pageState = useGroupMarketingTaskPage();
    await pageState.openDetailDrawer({ id: 42, status: 2 } as MarketingTaskRow);

    const oldPoll = deferred<MarketingTaskDetail>();
    resetArmadaMock(oldPoll.promise);
    timers.run();
    timers.run();
    assert.equal(armadaCalls().length, 1);

    resetArmadaMock(marketingDetail(43, 7, "SUCCESS"));
    await pageState.openDetailDrawer({ id: 43, status: 7 } as MarketingTaskRow);
    oldPoll.resolve(marketingDetail(42, 2, "FAILED"));
    await flushAsyncWork();

    assert.equal(pageState.detailTask.value?.id, 43);
    assert.equal(
      pageState.detailTask.value?.accountTargets?.[0].groups[0].executionResult,
      "SUCCESS"
    );
  } finally {
    timers.restore();
  }
});

it("keeps old detail data and reports a background failure once per streak", async () => {
  const timers = installIntervalHarness();
  try {
    resetElementPlusMock();
    resetArmadaMock(marketingDetail(42, 2, "SUCCESS"));
    const pageState = useGroupMarketingTaskPage();
    await pageState.openDetailDrawer({ id: 42, status: 2 } as MarketingTaskRow);

    resetArmadaMockFailure(new Error("temporary refresh failure"));
    timers.run();
    await flushAsyncWork();
    timers.run();
    await flushAsyncWork();

    assert.equal(
      pageState.detailTask.value?.accountTargets?.[0].groups[0].executionResult,
      "SUCCESS"
    );
    assert.equal(
      elementPlusCalls().filter(call => call.type === "error").length,
      1
    );

    resetArmadaMock(marketingDetail(42, 2, "FAILED"));
    timers.run();
    await flushAsyncWork();
    resetArmadaMockFailure(new Error("second refresh failure"));
    timers.run();
    await flushAsyncWork();

    assert.equal(
      elementPlusCalls().filter(call => call.type === "error").length,
      2
    );
  } finally {
    timers.restore();
  }
});

it("clears polling when the drawer closes", async () => {
  const timers = installIntervalHarness();
  try {
    resetArmadaMock(marketingDetail(42, 2, "SUCCESS"));
    const pageState = useGroupMarketingTaskPage();
    await pageState.openDetailDrawer({ id: 42, status: 2 } as MarketingTaskRow);

    pageState.closeDetailDrawer();

    assert.equal(timers.cleared.length, 1);
    assert.equal(pageState.detailTask.value, null);
  } finally {
    timers.restore();
  }
});

it("clears polling when the composable scope is disposed", async () => {
  const timers = installIntervalHarness();
  try {
    resetArmadaMock(marketingDetail(42, 2, "SUCCESS"));
    const scope = effectScope();
    const pageState = scope.run(() => useGroupMarketingTaskPage());
    assert.ok(pageState);
    await pageState.openDetailDrawer({ id: 42, status: 2 } as MarketingTaskRow);

    scope.stop();

    assert.equal(timers.cleared.length, 1);
  } finally {
    timers.restore();
  }
});
```

- [ ] **Step 3: Run the page test and verify RED**

```bash
node --import ./src/api/__tests__/node-test-alias.mjs --test \
  src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts
```

Expected: polling tests fail because `openDetailDrawer` performs only the initial request and never registers a 5000 ms interval.

- [ ] **Step 4: Add polling state and lifecycle imports**

Extend the Vue import in `useGroupMarketingTaskPage.ts`:

```ts
import {
  computed,
  onMounted,
  onScopeDispose,
  reactive,
  ref,
  watch,
  type ComputedRef,
  type Ref
} from "vue";
```

Add this constant beside the existing lookback constant:

```ts
const DETAIL_POLL_INTERVAL_MS = 5000;
```

After `selectedCount`, add:

```ts
let detailPollTimer: ReturnType<typeof setInterval> | null = null;
let detailPollInFlight = false;
let detailRequestVersion = 0;
let detailTaskId: number | null = null;
let detailRefreshFailureNotified = false;
```

- [ ] **Step 5: Replace one-shot detail loading with guarded polling**

Replace the existing `openDetailDrawer` and `closeDetailDrawer` block with:

```ts
function clearDetailPollTimer(): void {
  if (detailPollTimer == null) return;
  clearInterval(detailPollTimer);
  detailPollTimer = null;
}

function invalidateDetailRequests(): void {
  detailRequestVersion += 1;
  detailPollInFlight = false;
  detailRefreshFailureNotified = false;
  clearDetailPollTimer();
}

function isCurrentDetailRequest(taskId: number, version: number): boolean {
  return (
    detailDrawerOpen.value &&
    detailTaskId === taskId &&
    detailRequestVersion === version
  );
}

function ensureDetailPolling(taskId: number): void {
  if (
    detailPollTimer != null ||
    !detailDrawerOpen.value ||
    detailTaskId !== taskId
  ) {
    return;
  }
  detailPollTimer = setInterval(() => {
    void refreshDetailTask(taskId, true);
  }, DETAIL_POLL_INTERVAL_MS);
}

async function refreshDetailTask(
  taskId: number,
  background: boolean
): Promise<void> {
  if (background && detailPollInFlight) return;
  const version = detailRequestVersion;
  if (background) {
    detailPollInFlight = true;
  } else {
    detailLoading.value = true;
  }
  try {
    const nextDetail = await getMarketingTaskDetail(taskId);
    if (!isCurrentDetailRequest(taskId, version)) return;
    detailTask.value = nextDetail;
    detailRefreshFailureNotified = false;
    if (nextDetail.status === 2) {
      ensureDetailPolling(taskId);
    } else {
      clearDetailPollTimer();
    }
  } catch (error) {
    if (!isCurrentDetailRequest(taskId, version)) return;
    if (background) {
      if (!detailRefreshFailureNotified) {
        ElMessage.error(apiErrorMessage(error, "营销任务明细刷新失败"));
        detailRefreshFailureNotified = true;
      }
    } else {
      detailTask.value = null;
      ElMessage.error(apiErrorMessage(error, "营销任务明细加载失败"));
    }
  } finally {
    if (isCurrentDetailRequest(taskId, version)) {
      if (background) {
        detailPollInFlight = false;
      } else {
        detailLoading.value = false;
      }
    }
  }
}

async function openDetailDrawer(row: MarketingTaskRow): Promise<void> {
  invalidateDetailRequests();
  detailTaskId = row.id;
  detailTask.value = null;
  detailDrawerOpen.value = true;
  await refreshDetailTask(row.id, false);
}

function resetDetailState(): void {
  invalidateDetailRequests();
  detailTaskId = null;
  detailTask.value = null;
  detailLoading.value = false;
}

function closeDetailDrawer(): void {
  detailDrawerOpen.value = false;
  resetDetailState();
}
```

Add lifecycle cleanup before the existing `onMounted` block:

```ts
watch(detailDrawerOpen, visible => {
  if (!visible && detailTaskId != null) {
    resetDetailState();
  }
});

onScopeDispose(resetDetailState);
```

The watcher handles the drawer's built-in close button, while `closeDetailDrawer` handles explicit callers. Both cleanup paths are idempotent.

- [ ] **Step 6: Run polling tests and the complete group-marketing test set**

```bash
node --import ./src/api/__tests__/node-test-alias.mjs --test \
  src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts
node --import ./src/api/__tests__/node-test-alias.mjs --test \
  src/views/task/group-marketing/components/group-execution-result.test.ts \
  src/views/task/group-marketing/components/group-send-status.test.ts \
  src/views/task/group-marketing/components/detail-rollup.test.ts \
  src/views/task/group-marketing/components/GroupMarketingDetailDrawer.test.ts \
  src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts
pnpm typecheck
```

Expected: all Node tests pass, no timer remains active after each test, and typecheck exits 0.

- [ ] **Step 7: Commit detail polling**

```bash
git add src/views/task/group-marketing/composables/useGroupMarketingTaskPage.ts \
  src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts
git commit -m "feat(marketing): poll running task detail"
```

### Task 4: Change Records and Cross-Repository Verification

**Files:**
- Backend create: `.harness/changes/2026-07-19-marketing-group-execution-result.md`
- Frontend create: `.harness/changes/marketing-group-execution-result/summary.md`

- [ ] **Step 1: Run backend final verification**

From `armada/armada-api`:

```bash
xmllint --noout src/main/resources/mapper/marketing/MarketingTaskMapper.xml
mvn -Dtest=MarketingTaskMapperSqlShapeTest test
./dbtest.sh 'MarketingTaskCreateReadDbTest#getDetail_usesLatestEffectiveRoundForGroupExecutionResult+getDetail_rollsUpDynamicGroupExecutionResult'
```

Expected: all commands exit 0. The DbTest must connect to the explicitly configured local/test MySQL from the gitignored `.env`; do not substitute an in-memory database.

- [ ] **Step 2: Run frontend final verification**

From `wheel-saas-pure-web`:

```bash
node --import ./src/api/__tests__/node-test-alias.mjs --test \
  src/views/task/group-marketing/components/group-execution-result.test.ts \
  src/views/task/group-marketing/components/group-send-status.test.ts \
  src/views/task/group-marketing/components/detail-rollup.test.ts \
  src/views/task/group-marketing/components/GroupMarketingDetailDrawer.test.ts \
  src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts
pnpm typecheck
pnpm exec eslint --max-warnings 0 \
  src/api/marketing-task.ts \
  src/views/task/group-marketing/components/group-execution-result.ts \
  src/views/task/group-marketing/components/group-execution-result.test.ts \
  src/views/task/group-marketing/components/GroupMarketingDetailDrawer.vue \
  src/views/task/group-marketing/components/GroupMarketingDetailDrawer.test.ts \
  src/views/task/group-marketing/composables/useGroupMarketingTaskPage.ts \
  src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts
pnpm exec prettier --check \
  src/api/marketing-task.ts \
  src/views/task/group-marketing/components/group-execution-result.ts \
  src/views/task/group-marketing/components/group-execution-result.test.ts \
  src/views/task/group-marketing/components/GroupMarketingDetailDrawer.vue \
  src/views/task/group-marketing/components/GroupMarketingDetailDrawer.test.ts \
  src/views/task/group-marketing/composables/useGroupMarketingTaskPage.ts \
  src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts
pnpm exec stylelint \
  "src/views/task/group-marketing/components/GroupMarketingDetailDrawer.vue" \
  --cache=false
pnpm build
```

Expected: all commands exit 0 with no ESLint, Prettier, or Stylelint warnings and a successful Vite production build.

- [ ] **Step 3: Write the backend change record**

Create `armada/.harness/changes/2026-07-19-marketing-group-execution-result.md` with:

```markdown
# 营销任务群组执行情况

## 目标

营销任务详情按账号和实际群组返回最新有效轮次的发送成功/失败结果。

## 影响

- API：`GET /api/marketing-tasks/{id}` 的 `accountTargets[].groups[]` 新增可空 `executionResult`。
- 数据库：无表结构和数据迁移；读取现有 `marketing_task_send_attempt`。
- 协议与 Kafka：无变更。
- 租户：沿用任务详情查询的租户拦截。

## 关键约束

- 仅状态 1/2 是有效执行结果。
- 按 `round_no DESC, attempt_no DESC, id DESC` 取最新有效记录。
- 提交中和跳过不覆盖上一轮结果。

## 验证

- `mvn -Dtest=MarketingTaskMapperSqlShapeTest test`：通过。
- `./dbtest.sh 'MarketingTaskCreateReadDbTest#getDetail_usesLatestEffectiveRoundForGroupExecutionResult+getDetail_rollsUpDynamicGroupExecutionResult'`：真库通过。

## 回滚

回退详情 SQL、原始行、VO 和 Service 映射；无数据回滚。
```

- [ ] **Step 4: Write the frontend change record**

Create `wheel-saas-pure-web/.harness/changes/marketing-group-execution-result/summary.md` with:

```markdown
# 营销任务群组执行情况

## 目标

在营销任务明细群组行展示「发送成功 / 发送失败」，执行中详情每 5 秒刷新。

## 改动

- 群组组合列新增「执行情况」，成功为绿色、失败为红色、无结果为 `-`。
- 仅打开的执行中任务轮询；终态、关闭、切换任务和页面销毁时停止。
- 后台刷新互斥，连续失败只提示一次，旧任务迟到响应不会覆盖当前详情。

## API

消费 `accountTargets[].groups[].executionResult`：`SUCCESS | FAILED | null`。

## 验证

- 群营销聚焦 Node 测试：通过。
- `pnpm typecheck`：通过。
- 定向 ESLint、Stylelint：通过。
- `pnpm build`：通过。

## 回滚

回退 API 类型、执行情况列、映射辅助模块和详情轮询；后端新增字段保持兼容。
```

- [ ] **Step 5: Commit change records in each repository**

Backend:

```bash
git add .harness/changes/2026-07-19-marketing-group-execution-result.md
git commit -m "docs: record marketing group execution result"
```

Frontend:

```bash
git add .harness/changes/marketing-group-execution-result/summary.md
git commit -m "docs: record marketing group execution result"
```

- [ ] **Step 6: Inspect final repository state**

Run in each changed repository:

```bash
git status --short --branch
git log -4 --oneline
git show --check --stat HEAD
```

Expected: only the execution worktree's intentional commits are present, `git show --check` reports no whitespace errors, and no credential, `.env`, PEM, deployment, protocol, or unrelated user file is included.

## Acceptance Checklist

- [ ] Backend returns `SUCCESS`, `FAILED`, or `null` for each returned account-group row.
- [ ] A newer valid round wins even when an older round reports later by wall-clock time.
- [ ] Submitted and skipped rows do not erase the previous confirmed result.
- [ ] Fixed and dynamic group targets share the same result projection.
- [ ] Frontend displays `发送成功`, `发送失败`, or `-` in both collapsed and expanded rows.
- [ ] The detail API is polled every 5 seconds only while the open task remains status 2.
- [ ] Poll requests do not overlap; stale responses cannot overwrite a switched task.
- [ ] Closing the drawer or leaving the component clears the timer.
- [ ] Background failures preserve the last detail and notify once per failure streak.
- [ ] No schema, protocol, Kafka, deployment, SSH, or remote-environment mutation occurs.
