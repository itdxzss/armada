# Marketing Task Detail Hierarchy and Status Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把普通群组营销任务详情调整为“账号 → 实际发送群组”两级固定字段，按成功回执统计发送次数，并统一最后有效发送记录对应的群组状态、执行结果和失败原因。

**Architecture:** 协议层只把明确的账号封禁异常归一为稳定原因码，继续保留原始群可发送性快照；Armada 后端以 MySQL 8 窗口函数一次选出每个账号、实际群组的最后有效发送记录，并在 Service 层从同一行派生 groupStatus、executionResult、executionReason；账号在线状态通过 Account 域 Service 批量读取；Vue 前端只消费稳定业务枚举，一级使用账号表格，二级在展开区严格展示五个字段。现有 5 秒详情轮询保持不变。

**Tech Stack:** Node.js 24, TypeScript, Jest, Baileys 7.x, Java 17, Spring Boot 3.3.5, MyBatis, MySQL 8, JUnit 5, AssertJ, Vue 3, Element Plus, Node test runner, pnpm, Vite.

---

## Baseline and Scope

已有基础：

- Backend commit 7dcf4ca 已返回可空 executionResult。
- Frontend commit 35ae2b6d 已展示执行结果并实现详情 5 秒轮询。
- Design commit cda701d 已确认最终业务口径。
- 本计划只做增量调整，不重复实现或改变已有轮询。

非目标：

- 不改营销任务创建、调度、自动重试、账号占用和任务状态机。
- 不改历史群营销、建群营销页面。
- 不新增或修改数据库表、列、索引。
- 不修改 OpenAPI HTTP 接口；协议变化仅是现有 Kafka 回执 reasonCode 的新增稳定取值。
- 不执行部署、SSH、远程数据库或共享环境数据修改。

发布依赖：

~~~text
armada-protocol 稳定失败原因码
        ↓
armada 统一归一化、实时登录态和详情 API
        ↓
wheel-saas-pure-web 最终字段与文案
~~~

滚动发布兼容性：

- 旧后端会把 ACCOUNT_BANNED 当普通字符串保存。
- 旧前端会忽略新增 loginState 和 executionReason。
- 新后端对旧协议的 BANNED、NO_PERMISSION、SEND_FAILED 保留兼容映射。

## Repository and File Map

### Protocol: /Users/daishuaishuai/IdeaProjects/armada-protocol

- Create: protocol-layer/src/commands/message-send-failure.ts
- Create: protocol-layer/src/commands/message-send-failure.test.ts
- Modify: protocol-layer/src/commands/worker-consumer.ts
- Modify: protocol-layer/src/commands/worker-consumer.test.ts
- Create: .harness/changes/marketing-send-failure-reason/summary.md
- Create: .harness/changes/marketing-send-failure-reason/contract.md

### Backend: /Users/daishuaishuai/IdeaProjects/armada

- Create: armada-api/src/main/java/com/armada/account/model/vo/AccountLoginStateRow.java
- Modify: armada-api/src/main/java/com/armada/account/mapper/AccountMapper.java
- Modify: armada-api/src/main/resources/mapper/account/AccountMapper.xml
- Modify: armada-api/src/main/java/com/armada/account/service/AccountService.java
- Modify: armada-api/src/main/java/com/armada/account/service/impl/AccountServiceImpl.java
- Modify: armada-api/src/test/java/com/armada/account/service/impl/AccountServiceImplTest.java
- Create: armada-api/src/main/java/com/armada/marketing/service/impl/MarketingGroupExecutionNormalizer.java
- Create: armada-api/src/test/java/com/armada/marketing/service/impl/MarketingGroupExecutionNormalizerTest.java
- Modify: armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskAccountGroupStatRow.java
- Modify: armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskAccountTargetVO.java
- Modify: armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskGroupStatVO.java
- Modify: armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java
- Modify: armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml
- Modify: armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java
- Modify: armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java
- Modify: .harness/changes/2026-07-19-marketing-task-detail-hierarchy-status.md

### Frontend: /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web

- Modify: src/api/marketing-task.ts
- Modify: src/views/task/group-marketing/components/group-send-status.ts
- Modify: src/views/task/group-marketing/components/group-send-status.test.ts
- Modify: src/views/task/group-marketing/components/group-execution-result.test.ts
- Modify: src/views/task/group-marketing/components/GroupMarketingDetailDrawer.vue
- Modify: src/views/task/group-marketing/components/GroupMarketingDetailDrawer.test.ts
- Delete: src/views/task/group-marketing/components/detail-rollup.ts
- Delete: src/views/task/group-marketing/components/detail-rollup.test.ts
- Create: .harness/changes/marketing-task-detail-hierarchy-status/summary.md

## Execution Preconditions

- [ ] 进入每个仓库后重新读取规则和目标文件，确认没有并发修改覆盖风险。
- [ ] 分别执行 git status --short --branch，保留 Armada 根工作树已有的 .claude/worktrees/* 非本任务状态。
- [ ] 实施代码时使用独立 worktree；协议、后端、前端各自提交。
- [ ] Backend 真库 DbTest 前确认 armada-api/.env 指向获准的本地测试库；未确认时不得连接共享库或把测试静默跳过。
- [ ] 不修改或提交私钥、.env、creds、token 或代理密码。

---

### Task 1: Protocol Stable Account-Banned Failure Reason

**Files:**

- Create: protocol-layer/src/commands/message-send-failure.ts
- Create: protocol-layer/src/commands/message-send-failure.test.ts
- Modify: protocol-layer/src/commands/worker-consumer.ts
- Modify: protocol-layer/src/commands/worker-consumer.test.ts
- Create: .harness/changes/marketing-send-failure-reason/summary.md
- Create: .harness/changes/marketing-send-failure-reason/contract.md

- [ ] **Step 1: Write failing classifier tests**

Create message-send-failure.test.ts:

~~~ts
import { describe, expect, it } from '@jest/globals'

import { AccountUnavailableError, NeedReauthError } from '../error/error-handler.js'
import { normalizeMessageSendFailure } from './message-send-failure.js'

describe('normalizeMessageSendFailure', () => {
  it.each([
    new NeedReauthError('acc_1', 'logged out'),
    new AccountUnavailableError('acc_1', 'NEED_REAUTH', { state: 'NEED_REAUTH' }),
    { output: { statusCode: 401 }, message: 'not authorized' },
    { statusCode: 403, message: 'forbidden' }
  ])('returns ACCOUNT_BANNED for an explicit account signal', error => {
    expect(normalizeMessageSendFailure(error)).toEqual({
      reasonCode: 'ACCOUNT_BANNED',
      reasonMessage: expect.any(String)
    })
  })

  it('keeps unknown failures generic and redacts credentials', () => {
    expect(normalizeMessageSendFailure(
      new Error('socket closed authorization=Bearer secret-value')
    )).toEqual({
      reasonCode: 'SEND_FAILED',
      reasonMessage: 'socket closed authorization=[redacted]'
    })
  })
})
~~~

- [ ] **Step 2: Run RED**

From armada-protocol/protocol-layer:

~~~bash
npm test -- --runInBand src/commands/message-send-failure.test.ts
~~~

Expected: Jest fails because message-send-failure.ts does not exist.

- [ ] **Step 3: Implement the narrow classifier**

Create message-send-failure.ts:

~~~ts
import { ProtocolError } from '../error/error-handler.js'
import { sanitizePublicText } from '../events/redaction.js'

interface ErrorShape {
  code?: unknown
  data?: unknown
  details?: { state?: unknown }
  output?: { statusCode?: unknown }
  statusCode?: unknown
}

export interface MessageSendFailure {
  reasonCode: 'ACCOUNT_BANNED' | 'SEND_FAILED'
  reasonMessage: string
}

function shape(error: unknown): ErrorShape {
  return typeof error === 'object' && error !== null ? error as ErrorShape : {}
}

function numericStatus(value: unknown): number | undefined {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string' && /^\d+$/.test(value)) return Number(value)
  return undefined
}

function statusCode(error: unknown): number | undefined {
  const value = shape(error)
  return numericStatus(value.statusCode)
    ?? numericStatus(value.output?.statusCode)
    ?? numericStatus(value.data)
}

function errorCode(error: unknown): string | undefined {
  if (error instanceof ProtocolError) return error.code
  const value = shape(error).code
  return typeof value === 'string' ? value : undefined
}

function publicMessage(error: unknown): string {
  const raw = error instanceof Error ? error.message : String(error)
  return sanitizePublicText(raw) ?? 'message send failed'
}

export function normalizeMessageSendFailure(error: unknown): MessageSendFailure {
  const state = shape(error).details?.state
  const banned =
    errorCode(error) === 'NEED_REAUTH'
    || state === 'NEED_REAUTH'
    || statusCode(error) === 401
    || statusCode(error) === 403

  return {
    reasonCode: banned ? 'ACCOUNT_BANNED' : 'SEND_FAILED',
    reasonMessage: publicMessage(error)
  }
}
~~~

Do not infer group bans, membership, or announce-only permission from free-form text. Those facts remain in groupStatusReason.

- [ ] **Step 4: Integrate it into worker-consumer.ts**

Import normalizeMessageSendFailure and replace the message-send catch body:

~~~ts
  } catch (error) {
    const failure = normalizeMessageSendFailure(error)
    deps.logger?.warn({
      ...messageSendLogFields(command, payload),
      success: false,
      ...failure,
      elapsedMs: Date.now() - startedAt
    }, 'message send command failed')
    await deps.publisher.publish('message.send_result_reported', command.accountId, {
      ...messageResultBase(command, payload),
      ...groupSendability,
      success: false,
      messageId: null,
      ...failure,
      timestamp: Date.now()
    })
    await deps.ack()
    return
  }
~~~

- [ ] **Step 5: Add worker-level coverage**

Add a case where getSocket throws NeedReauthError and assert the published event contains success=false, reasonCode=ACCOUNT_BANNED, a sanitized reasonMessage, the unchanged group snapshot, and one ack. Keep the current unknown socket failure assertion as SEND_FAILED.

- [ ] **Step 6: Run GREEN**

~~~bash
npm test -- --runInBand src/commands/message-send-failure.test.ts src/commands/worker-consumer.test.ts src/worker/group-sendability.test.ts
npm run lint
npm run build
~~~

Expected: all suites pass and TypeScript/build exit 0.

- [ ] **Step 7: Record and commit the additive event contract**

contract.md must state: existing event message.send_result_reported, existing field reasonCode, new value ACCOUNT_BANNED, exact explicit triggers, unknown failures remain SEND_FAILED, groupStatus fields unchanged, no OpenAPI change.

~~~bash
git add protocol-layer/src/commands/message-send-failure.ts protocol-layer/src/commands/message-send-failure.test.ts protocol-layer/src/commands/worker-consumer.ts protocol-layer/src/commands/worker-consumer.test.ts .harness/changes/marketing-send-failure-reason
git commit -m "feat(protocol): classify banned marketing accounts"
~~~

---

### Task 2: Backend Batch Account Login-State Read

**Files:**

- Create: armada-api/src/main/java/com/armada/account/model/vo/AccountLoginStateRow.java
- Modify: armada-api/src/main/java/com/armada/account/mapper/AccountMapper.java
- Modify: armada-api/src/main/resources/mapper/account/AccountMapper.xml
- Modify: armada-api/src/main/java/com/armada/account/service/AccountService.java
- Modify: armada-api/src/main/java/com/armada/account/service/impl/AccountServiceImpl.java
- Modify: armada-api/src/test/java/com/armada/account/service/impl/AccountServiceImplTest.java

- [ ] **Step 1: Add failing Account Service tests**

Cover a map containing online and null login states, plus empty input short-circuit with no Mapper call:

~~~java
Map<Long, Integer> states = service.getLoginStatesByIds(List.of(11L, 12L));
assertThat(states).containsEntry(11L, 1);
assertThat(states).containsKey(12L);
assertThat(states.get(12L)).isNull();

assertThat(service.getLoginStatesByIds(List.of())).isEmpty();
verify(accountMapper, never()).selectLoginStatesByIds(anyList());
~~~

- [ ] **Step 2: Run RED**

~~~bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=AccountServiceImplTest test
~~~

Expected: compilation fails because the new row and methods do not exist.

- [ ] **Step 3: Add the Mapper projection**

Create AccountLoginStateRow as a MyBatis read row with Long accountId and nullable Integer loginState getters/setters. Add:

~~~java
List<AccountLoginStateRow> selectLoginStatesByIds(@Param("ids") List<Long> ids);
~~~

Add to AccountMapper.xml:

~~~xml
  <select id="selectLoginStatesByIds"
          resultType="com.armada.account.model.vo.AccountLoginStateRow">
    <if test="ids != null and ids.size() &gt; 0">
      SELECT a.id AS accountId,
             s.login_state AS loginState
      FROM account a
      LEFT JOIN account_state s ON s.account_id = a.id
                               AND s.tenant_id = a.tenant_id
      WHERE a.deleted_at IS NULL
        AND a.id IN
      <foreach collection="ids" item="id" open="(" separator="," close=")">
        #{id}
      </foreach>
      ORDER BY a.id ASC
    </if>
    <if test="ids == null or ids.size() == 0">
      SELECT NULL AS accountId, NULL AS loginState FROM DUAL WHERE 1 = 0
    </if>
  </select>
~~~

Starting from non-deleted account rows makes a soft-deleted account absent from the map while the marketing task still retains its phone snapshot.

- [ ] **Step 4: Expose the Account-domain batch Service**

Add Map<Long, Integer> getLoginStatesByIds(List<Long> accountIds) to AccountService and implement:

~~~java
@Override
public Map<Long, Integer> getLoginStatesByIds(List<Long> accountIds) {
    if (accountIds == null || accountIds.isEmpty()) {
        return Map.of();
    }
    Map<Long, Integer> states = new LinkedHashMap<>();
    for (AccountLoginStateRow row : accountMapper.selectLoginStatesByIds(accountIds)) {
        states.put(row.getAccountId(), row.getLoginState());
    }
    return states;
}
~~~

- [ ] **Step 5: Verify GREEN and commit**

~~~bash
mvn -Dtest=AccountServiceImplTest test
git add src/main/java/com/armada/account/model/vo/AccountLoginStateRow.java src/main/java/com/armada/account/mapper/AccountMapper.java src/main/resources/mapper/account/AccountMapper.xml src/main/java/com/armada/account/service/AccountService.java src/main/java/com/armada/account/service/impl/AccountServiceImpl.java src/test/java/com/armada/account/service/impl/AccountServiceImplTest.java
git commit -m "feat(account): expose batch login states"
~~~

Expected: BUILD SUCCESS. The commit is made from armada/armada-api, so paths are relative to that directory.

---

### Task 3: Backend Same-Row Execution Normalization and Detail API

**Files:**

- Create: armada-api/src/main/java/com/armada/marketing/service/impl/MarketingGroupExecutionNormalizer.java
- Create: armada-api/src/test/java/com/armada/marketing/service/impl/MarketingGroupExecutionNormalizerTest.java
- Modify: armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskAccountGroupStatRow.java
- Modify: armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskAccountTargetVO.java
- Modify: armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskGroupStatVO.java
- Modify: armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java
- Modify: armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml
- Modify: armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java
- Modify: armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java

- [ ] **Step 1: Write failing normalization tests**

Create MarketingGroupExecutionNormalizerTest.java. Cover success overriding stale precheck state:

~~~java
@Test
void successAlwaysOverridesStalePrecheckStatus() {
    var result = MarketingGroupExecutionNormalizer.normalize(
            1, null, null, "BANNED", "CHAT_SUSPENDED");

    assertThat(result.groupStatus()).isEqualTo("NORMAL");
    assertThat(result.executionResult()).isEqualTo("SUCCESS");
    assertThat(result.executionReason()).isNull();
}
~~~

Use a parameterized test for the canonical failures:

~~~java
static Stream<Arguments> knownFailures() {
    return Stream.of(
            arguments("ACCOUNT_BANNED", "BANNED", "CHAT_SUSPENDED",
                    "ACCOUNT_BANNED", "账号封禁"),
            arguments("SEND_FAILED", "NO_PERMISSION", "ACCOUNT_NOT_PARTICIPANT",
                    "KICKED_OUT", "账号已被踢出群聊"),
            arguments("SEND_FAILED", "BANNED", "CHAT_TERMINATED",
                    "GROUP_BANNED", "群组已封禁"),
            arguments("SEND_FAILED", "NO_PERMISSION", "ANNOUNCE_ONLY_NON_ADMIN",
                    "NO_PERMISSION", "当前账号没有发言权限"),
            arguments("SEND_FAILED", "BANNED", null,
                    "GROUP_BANNED", "群组已封禁")
    );
}
~~~

Also assert an unknown failure returns UNCONFIRMED, FAILED, and its non-empty reasonMessage; null latest status returns UNCONFIRMED with null result and reason.

- [ ] **Step 2: Run RED**

~~~bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=MarketingGroupExecutionNormalizerTest test
~~~

Expected: compilation fails because the normalizer does not exist.

- [ ] **Step 3: Implement the pure normalizer**

Create MarketingGroupExecutionNormalizer.java:

~~~java
package com.armada.marketing.service.impl;

import org.springframework.util.StringUtils;

final class MarketingGroupExecutionNormalizer {

    private MarketingGroupExecutionNormalizer() {
    }

    static NormalizedExecution normalize(Integer attemptStatus,
                                         String reasonCode,
                                         String reasonMessage,
                                         String rawGroupStatus,
                                         String groupStatusReason) {
        if (Integer.valueOf(1).equals(attemptStatus)) {
            return new NormalizedExecution("NORMAL", "SUCCESS", null);
        }
        if (!Integer.valueOf(2).equals(attemptStatus)) {
            return new NormalizedExecution("UNCONFIRMED", null, null);
        }
        if (matches(reasonCode, "ACCOUNT_BANNED")) {
            return failed("ACCOUNT_BANNED", "账号封禁");
        }
        if (matches(reasonCode, "KICKED_OUT", "ACCOUNT_NOT_PARTICIPANT")
                || matches(groupStatusReason, "ACCOUNT_NOT_PARTICIPANT")) {
            return failed("KICKED_OUT", "账号已被踢出群聊");
        }
        if (matches(reasonCode, "GROUP_BANNED", "CHAT_SUSPENDED", "CHAT_TERMINATED")
                || matches(groupStatusReason, "CHAT_SUSPENDED", "CHAT_TERMINATED")
                || matches(rawGroupStatus, "BANNED")) {
            return failed("GROUP_BANNED", "群组已封禁");
        }
        if (matches(reasonCode, "NO_PERMISSION", "ANNOUNCE_ONLY_NON_ADMIN")
                || matches(groupStatusReason, "ANNOUNCE_ONLY_NON_ADMIN")
                || matches(rawGroupStatus, "NO_PERMISSION")) {
            return failed("NO_PERMISSION", "当前账号没有发言权限");
        }
        String fallback = StringUtils.hasText(reasonMessage)
                ? reasonMessage.trim()
                : "未知原因";
        return failed("UNCONFIRMED", fallback);
    }

    private static NormalizedExecution failed(String groupStatus, String reason) {
        return new NormalizedExecution(groupStatus, "FAILED", reason);
    }

    private static boolean matches(String value, String... expected) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        for (String item : expected) {
            if (item.equalsIgnoreCase(value.trim())) {
                return true;
            }
        }
        return false;
    }

    record NormalizedExecution(
            String groupStatus,
            String executionResult,
            String executionReason) {
    }
}
~~~

- [ ] **Step 4: Add a failing SQL-shape contract**

Replace the current independent executionResult SQL assertion with:

~~~java
@Test
void detailRollupJoinsOneLatestEffectiveAttemptForAllDerivedFields() throws IOException {
    String xml = new String(
            getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
            StandardCharsets.UTF_8);
    String sql = selectBlock(xml, "selectAccountGroupStatsByTaskId");

    assertThat(sql)
            .contains("a.status IN (0, 1, 2, 3)")
            .contains("latest_effective AS")
            .contains("WHERE attemptStatus IN (1, 2)")
            .contains("PARTITION BY accountId, groupKey")
            .contains("ORDER BY roundNo DESC, attemptNo DESC, attemptId DESC")
            .contains("e.attemptStatus AS latestAttemptStatus")
            .contains("e.reasonCode AS reasonCode")
            .contains("e.reasonMessage AS reasonMessage")
            .contains("e.rawGroupStatus AS groupStatus")
            .contains("e.groupStatusReason AS groupStatusReason");
}
~~~

Keep count assertions for success-only sentMessageCount, failure count, and success-only lastSentAt.

- [ ] **Step 5: Run SQL RED**

~~~bash
mvn -Dtest=MarketingTaskMapperSqlShapeTest test
~~~

Expected: the same-row CTE assertions fail against the current independent GROUP_CONCAT projections.

- [ ] **Step 6: Rewrite the aggregate around one ranked effective row**

Replace selectAccountGroupStatsByTaskId with three CTEs:

1. attempt_facts includes status 0, 1, 2, 3; creates eventAt and a stable groupKey using groupJid, then groupLinkId, then targetId.
2. latest_effective filters status 1 and 2 before ROW_NUMBER, partitioned by accountId and groupKey, ordered roundNo DESC, attemptNo DESC, attemptId DESC.
3. group_aggregate computes cumulative counts, last attempt, last successful send time, current snapshots, and legacy lastReason.

attempt_facts must filter and build identity with:

~~~xml
            WHERE t.marketing_task_id = #{taskId}
              AND a.marketing_task_id = #{taskId}
              AND a.status IN (0, 1, 2, 3)
~~~

~~~sql
COALESCE(
    NULLIF(TRIM(a.group_jid), ''),
    NULLIF(TRIM(p.group_jid), ''),
    NULLIF(TRIM(t.group_jid), ''),
    CONCAT('link:', COALESCE(a.group_link_id, p.group_link_id, t.group_link_id)),
    CONCAT('target:', t.id)
) AS groupKey
~~~

The exact ranking and final join must be:

~~~xml
        latest_effective AS (
            SELECT ranked.*
            FROM (
                SELECT effective.*,
                       ROW_NUMBER() OVER (
                           PARTITION BY accountId, groupKey
                           ORDER BY roundNo DESC, attemptNo DESC, attemptId DESC
                       ) AS effectiveRank
                FROM attempt_facts effective
                WHERE attemptStatus IN (1, 2)
            ) ranked
            WHERE ranked.effectiveRank = 1
        )
~~~

~~~xml
        SELECT g.accountId,
               g.accountPhone,
               g.groupLinkId,
               g.groupJid,
               g.groupLinkUrl,
               g.groupName,
               e.attemptStatus AS latestAttemptStatus,
               e.reasonCode AS reasonCode,
               e.reasonMessage AS reasonMessage,
               e.rawGroupStatus AS groupStatus,
               e.groupStatusReason AS groupStatusReason,
               g.sentMessageCount,
               g.failedMessageCount,
               g.lastAttemptAt,
               g.lastSentAt,
               g.lastReason
        FROM group_aggregate g
        LEFT JOIN latest_effective e
          ON e.accountId = g.accountId
         AND e.groupKey = g.groupKey
        ORDER BY g.accountId ASC,
                 g.lastAttemptAt DESC,
                 g.groupKey ASC
~~~

The aggregate expressions are:

~~~sql
SUM(CASE WHEN attemptStatus = 1 THEN 1 ELSE 0 END) AS sentMessageCount,
SUM(CASE WHEN attemptStatus = 2 THEN 1 ELSE 0 END) AS failedMessageCount,
MAX(eventAt) AS lastAttemptAt,
MAX(CASE WHEN attemptStatus = 1 THEN eventAt ELSE NULL END) AS lastSentAt
~~~

Use the existing latest-snapshot GROUP_CONCAT expressions for groupLinkUrl, groupName, and compatibility lastReason, but order them by eventAt DESC, attemptId DESC. Remove the old independent groupStatus and executionResult GROUP_CONCAT expressions. Status 0 and 3 groups remain visible with zero successful sends, but neither can become the effective execution result.

- [ ] **Step 7: Extend raw row and API records**

In MarketingTaskAccountGroupStatRow keep groupStatus and replace Mapper-level executionResult with:

~~~java
private Integer latestAttemptStatus;
private String reasonCode;
private String reasonMessage;
private String groupStatusReason;
~~~

Add matching getters/setters.

Change MarketingTaskAccountTargetVO:

~~~java
public record MarketingTaskAccountTargetVO(
        Long accountId,
        String accountPhone,
        Integer loginState,
        Integer status,
        Integer sentMessageCount,
        Integer failedMessageCount,
        Long lastAttemptAt,
        Long lastSentAt,
        String lastReason,
        List<MarketingTaskGroupStatVO> groups) {
}
~~~

Change MarketingTaskGroupStatVO:

~~~java
public record MarketingTaskGroupStatVO(
        Long groupLinkId,
        String groupJid,
        String groupLinkUrl,
        String groupName,
        String groupStatus,
        String executionResult,
        String executionReason,
        Integer sentMessageCount,
        Integer failedMessageCount,
        Long lastAttemptAt,
        Long lastSentAt,
        String lastReason) {
}
~~~

- [ ] **Step 8: Batch-load login state and normalize each group in Service**

Inject AccountService through the MarketingTaskServiceImpl constructor. In getDetail perform one batch query:

~~~java
Set<Long> accountIds = targets.stream()
        .map(MarketingTaskTargetVO::accountId)
        .collect(Collectors.toCollection(LinkedHashSet::new));
Map<Long, Integer> loginStates =
        accountService.getLoginStatesByIds(List.copyOf(accountIds));
List<MarketingTaskAccountTargetVO> accountTargets =
        toAccountTargets(targets, groupStats, loginStates);
~~~

Pass loginStates.get(target.accountId()) immediately after the account-phone snapshot when constructing MarketingTaskAccountTargetVO.

Replace toGroupStatVO with:

~~~java
private static MarketingTaskGroupStatVO toGroupStatVO(MarketingTaskAccountGroupStatRow row) {
    MarketingGroupExecutionNormalizer.NormalizedExecution execution =
            MarketingGroupExecutionNormalizer.normalize(
                    row.getLatestAttemptStatus(),
                    row.getReasonCode(),
                    row.getReasonMessage(),
                    row.getGroupStatus(),
                    row.getGroupStatusReason());
    return new MarketingTaskGroupStatVO(
            row.getGroupLinkId(),
            row.getGroupJid(),
            row.getGroupLinkUrl(),
            row.getGroupName(),
            execution.groupStatus(),
            execution.executionResult(),
            execution.executionReason(),
            zero(row.getSentMessageCount()),
            zero(row.getFailedMessageCount()),
            row.getLastAttemptAt(),
            row.getLastSentAt(),
            row.getLastReason());
}
~~~

Delete the old groupStatus(String) helper after its final call site disappears. Marketing must depend only on AccountService, never AccountMapper, AccountStateMapper, or Account entity.

- [ ] **Step 9: Add real-MySQL detail scenarios**

Extend MarketingTaskCreateReadDbTest with these focused methods:

~~~text
getDetail_derivesCountsAndExecutionFromLatestEffectiveAttempt
getDetail_normalizesKnownGroupFailureStates
getDetail_readsCurrentLoginStateAndKeepsDeletedAccountHistory
getDetail_isTenantIsolated
~~~

Required assertions:

1. Two successes plus one failure for the same account/group produce sentMessageCount=2 at group and account level.
2. A later status-3 skip does not replace the preceding success/failure.
3. Round/attempt/id ordering beats result timestamp.
4. Latest success with stale BANNED/CHAT_SUSPENDED returns NORMAL, SUCCESS, null reason.
5. Latest failures cover ACCOUNT_BANNED, CHAT_TERMINATED, ANNOUNCE_ONLY_NON_ADMIN, ACCOUNT_NOT_PARTICIPANT, and unknown failure.
6. A historical success followed by failure keeps its success count and lastSentAt while returning the latest failed execution.
7. A group with only status 0 is visible with zero counts and null execution; a later status 0 does not replace an earlier effective result.
8. Changing account_state.login_state between two getDetail calls changes loginState without changing the task phone snapshot.
9. Soft-deleting the account keeps historical account/group detail but returns null loginState.
10. Switching TenantContext to another tenant makes the task unreadable; restore TEST_TENANT_ID in finally.

- [ ] **Step 10: Run backend GREEN**

~~~bash
mvn -Dtest=AccountServiceImplTest,MarketingGroupExecutionNormalizerTest,MarketingTaskMapperSqlShapeTest test
./dbtest.sh 'MarketingTaskCreateReadDbTest#getDetail_derivesCountsAndExecutionFromLatestEffectiveAttempt+getDetail_normalizesKnownGroupFailureStates+getDetail_readsCurrentLoginStateAndKeepsDeletedAccountHistory+getDetail_isTenantIsolated'
~~~

Expected: BUILD SUCCESS and four real-MySQL methods pass with zero skips.

- [ ] **Step 11: Commit the backend marketing slice**

From armada/armada-api:

~~~bash
git add src/main/java/com/armada/marketing/service/impl/MarketingGroupExecutionNormalizer.java src/test/java/com/armada/marketing/service/impl/MarketingGroupExecutionNormalizerTest.java src/main/java/com/armada/marketing/model/vo/MarketingTaskAccountGroupStatRow.java src/main/java/com/armada/marketing/model/vo/MarketingTaskAccountTargetVO.java src/main/java/com/armada/marketing/model/vo/MarketingTaskGroupStatVO.java src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java src/main/resources/mapper/marketing/MarketingTaskMapper.xml src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java
git commit -m "feat(marketing): align task detail status semantics"
~~~

---

### Task 4: Frontend API Contract and Status Metadata

**Files:**

- Modify: src/api/marketing-task.ts
- Modify: src/views/task/group-marketing/components/group-send-status.ts
- Modify: src/views/task/group-marketing/components/group-send-status.test.ts
- Modify: src/views/task/group-marketing/components/group-execution-result.test.ts

- [ ] **Step 1: Expand the status test first**

Replace the four-state assertions with:

~~~ts
assert.equal(groupSendStatusMeta("NORMAL").label, "正常");
assert.equal(groupSendStatusMeta("ACCOUNT_BANNED").label, "账号封禁");
assert.equal(groupSendStatusMeta("GROUP_BANNED").label, "群组封禁");
assert.equal(groupSendStatusMeta("NO_PERMISSION").label, "没有权限");
assert.equal(groupSendStatusMeta("KICKED_OUT").label, "被踢出群聊");
assert.equal(groupSendStatusMeta("UNCONFIRMED").label, "未确认");
assert.equal(groupSendStatusMeta("FUTURE_STATUS").label, "未确认");
~~~

Keep tag assertions: normal=success; account/group ban and kicked-out=danger; no-permission and unconfirmed=info.

- [ ] **Step 2: Run RED**

~~~bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
node --import ./src/api/__tests__/node-test-alias.mjs --test src/views/task/group-marketing/components/group-send-status.test.ts src/views/task/group-marketing/components/group-execution-result.test.ts
~~~

Expected: new status cases fail.

- [ ] **Step 3: Update API types**

In src/api/marketing-task.ts:

~~~ts
export type MarketingGroupSendStatus =
  | "NORMAL"
  | "ACCOUNT_BANNED"
  | "GROUP_BANNED"
  | "NO_PERMISSION"
  | "KICKED_OUT"
  | "UNCONFIRMED";
~~~

Add executionReason?: string | null immediately after executionResult in MarketingTaskGroupStatRow. Add loginState?: number | null immediately after accountPhone in MarketingTaskAccountTargetRow.

- [ ] **Step 4: Replace legacy BANNED metadata**

~~~ts
const GROUP_SEND_STATUS_META: Record<string, GroupSendStatusMeta> = {
  NORMAL: {
    label: "正常",
    tagType: "success",
    className: ""
  },
  ACCOUNT_BANNED: {
    label: "账号封禁",
    tagType: "danger",
    className: ""
  },
  GROUP_BANNED: {
    label: "群组封禁",
    tagType: "danger",
    className: ""
  },
  NO_PERMISSION: {
    label: "没有权限",
    tagType: "info",
    className: "group-status--no-permission"
  },
  KICKED_OUT: {
    label: "被踢出群聊",
    tagType: "danger",
    className: ""
  },
  UNCONFIRMED: UNCONFIRMED_META
};
~~~

Do not retain a visible BANNED branch; rolling compatibility belongs to the backend normalizer.

- [ ] **Step 5: Verify GREEN and commit**

~~~bash
node --import ./src/api/__tests__/node-test-alias.mjs --test src/views/task/group-marketing/components/group-send-status.test.ts src/views/task/group-marketing/components/group-execution-result.test.ts
git add src/api/marketing-task.ts src/views/task/group-marketing/components/group-send-status.ts src/views/task/group-marketing/components/group-send-status.test.ts src/views/task/group-marketing/components/group-execution-result.test.ts
git commit -m "feat(marketing): align detail status contract"
~~~

---

### Task 5: Frontend Exact Two-Level Detail Layout

**Files:**

- Modify: src/views/task/group-marketing/components/GroupMarketingDetailDrawer.vue
- Modify: src/views/task/group-marketing/components/GroupMarketingDetailDrawer.test.ts
- Delete: src/views/task/group-marketing/components/detail-rollup.ts
- Delete: src/views/task/group-marketing/components/detail-rollup.test.ts
- Create: .harness/changes/marketing-task-detail-hierarchy-status/summary.md

- [ ] **Step 1: Replace the source contract test**

Update GroupMarketingDetailDrawer.test.ts:

~~~ts
it("renders the exact account and group detail fields in order", () => {
  assert.match(
    source,
    /label="在线状态"[\s\S]*label="发送账号"[\s\S]*label="账号发送总条数"[\s\S]*label="明细"/
  );
  assert.match(
    source,
    /<span>群组状态<\/span>\s*<span>群名称<\/span>\s*<span>单群发送条数<\/span>\s*<span>最后发送时间<\/span>\s*<span>执行情况<\/span>/
  );
  assert.doesNotMatch(source, />群组链接</);
  assert.doesNotMatch(source, />最近原因</);
  assert.doesNotMatch(source, />当前状态</);
  assert.doesNotMatch(source, />发言号码</);
});

it("uses live login state and renders failure reason inside execution", () => {
  assert.match(source, /loginStateLabel/);
  assert.match(source, /loginStateTagType/);
  assert.match(source, /row\.loginState/);
  assert.match(source, /group\.executionReason/);
  assert.match(source, /group\.executionResult === "FAILED"/);
  assert.match(source, /group\.executionReason \|\| "未知原因"/);
});

it("keeps empty groups and nullable fields safe", () => {
  assert.match(source, /暂无发送记录/);
  assert.match(source, /group\.groupName \|\| group\.groupJid \|\| "未命名群组"/);
  assert.match(source, /formatEpoch\(group\.lastSentAt\)/);
});
~~~

- [ ] **Step 2: Run RED**

~~~bash
node --import ./src/api/__tests__/node-test-alias.mjs --test src/views/task/group-marketing/components/GroupMarketingDetailDrawer.test.ts
~~~

Expected: old labels/link/reason columns and missing login state fail the test.

- [ ] **Step 3: Replace account-level columns**

Import existing mappings:

~~~ts
import {
  loginStateLabel,
  loginStateTagType
} from "@/views/account/index/account-display";
~~~

The parent table must contain exactly:

~~~vue
<el-table-column label="在线状态" width="110">
  <template #default="{ row }">
    <el-tag
      size="small"
      effect="plain"
      :type="loginStateTagType(row.loginState)"
    >
      {{ loginStateLabel(row.loginState) }}
    </el-tag>
  </template>
</el-table-column>
<el-table-column
  prop="accountPhone"
  label="发送账号"
  min-width="150"
  show-overflow-tooltip
/>
<el-table-column
  prop="sentMessageCount"
  label="账号发送总条数"
  width="150"
/>
~~~

Remove targetStatusLabel and targetStatusTagType imports and rendering. The fourth parent column is the complete expand column in Step 4.

- [ ] **Step 4: Render the exact five group fields**

Use one header and one repeated row inside the expand template:

~~~vue
<el-table-column type="expand" label="明细" width="80">
  <template #default="{ row }">
    <div class="group-rollup-expand">
      <template v-if="asAccountRow(row).groups.length > 0">
        <div class="group-rollup-header">
          <span>群组状态</span>
          <span>群名称</span>
          <span>单群发送条数</span>
          <span>最后发送时间</span>
          <span>执行情况</span>
        </div>
        <div class="group-rollup-detail-list">
          <div
            v-for="group in asAccountRow(row).groups"
            :key="groupRowKey(group)"
            class="group-rollup-detail-row"
          >
            <el-tag
              size="small"
              effect="plain"
              :type="groupSendStatusMeta(group.groupStatus).tagType"
              :class="groupSendStatusMeta(group.groupStatus).className"
            >
              {{ groupSendStatusMeta(group.groupStatus).label }}
            </el-tag>
            <span
              class="group-rollup-text"
              :title="group.groupName || group.groupJid || '未命名群组'"
            >
              {{ group.groupName || group.groupJid || "未命名群组" }}
            </span>
            <span class="group-rollup-number">{{ group.sentMessageCount }}</span>
            <span class="group-rollup-time">
              {{ formatEpoch(group.lastSentAt) }}
            </span>
            <div class="group-execution">
              <el-tag
                v-if="groupExecutionResultMeta(group.executionResult).tagged"
                size="small"
                effect="plain"
                :type="groupExecutionResultMeta(group.executionResult).tagType"
              >
                {{ groupExecutionResultMeta(group.executionResult).label }}
              </el-tag>
              <span v-else class="group-rollup-empty">-</span>
              <span
                v-if="group.executionResult === 'FAILED'"
                class="group-execution-reason"
              >
                {{ group.executionReason || "未知原因" }}
              </span>
            </div>
          </div>
        </div>
      </template>
      <div
        v-else
        class="group-rollup-empty group-rollup-expand-empty"
      >
        暂无发送记录
      </div>
    </div>
  </template>
</el-table-column>
~~~

Do not render groupLinkUrl or lastReason. They remain API compatibility fields only.

- [ ] **Step 5: Remove the obsolete collapsed-summary path**

Delete the parent-row first-group summary column and all calls to firstGroup, firstGroupDisplayName, firstGroupSummary, groupCountLabel, and hasGroupRows. Delete detail-rollup.ts and its test because nothing else imports them.

- [ ] **Step 6: Update the five-column grid**

~~~css
.group-rollup-header,
.group-rollup-detail-row {
  display: grid;
  grid-template-columns:
    120px minmax(180px, 1.3fr) 120px 170px minmax(180px, 1fr);
  gap: 16px;
  align-items: center;
  width: 100%;
  min-width: 0;
}

.group-execution {
  display: flex;
  gap: 8px;
  align-items: center;
  min-width: 0;
}

.group-execution-reason {
  min-width: 0;
  overflow: hidden;
  color: var(--el-color-danger);
  text-overflow: ellipsis;
  white-space: nowrap;
}
~~~

Keep a small-screen rule and keep the component below 600 lines.

- [ ] **Step 7: Run frontend GREEN**

~~~bash
node --import ./src/api/__tests__/node-test-alias.mjs --test src/views/task/group-marketing/components/group-send-status.test.ts src/views/task/group-marketing/components/group-execution-result.test.ts src/views/task/group-marketing/components/GroupMarketingDetailDrawer.test.ts
pnpm exec eslint --max-warnings 0 src/api/marketing-task.ts src/views/task/group-marketing/components/group-send-status.ts src/views/task/group-marketing/components/group-send-status.test.ts src/views/task/group-marketing/components/group-execution-result.ts src/views/task/group-marketing/components/group-execution-result.test.ts src/views/task/group-marketing/components/GroupMarketingDetailDrawer.vue src/views/task/group-marketing/components/GroupMarketingDetailDrawer.test.ts
pnpm exec prettier --check src/api/marketing-task.ts src/views/task/group-marketing/components/group-send-status.ts src/views/task/group-marketing/components/group-send-status.test.ts src/views/task/group-marketing/components/group-execution-result.ts src/views/task/group-marketing/components/group-execution-result.test.ts src/views/task/group-marketing/components/GroupMarketingDetailDrawer.vue src/views/task/group-marketing/components/GroupMarketingDetailDrawer.test.ts
pnpm typecheck
pnpm build
~~~

Expected: Node tests, static checks, typecheck, and production build all exit 0.

- [ ] **Step 8: Record and commit**

The frontend summary must list new API fields, removed UI fields, preserved polling, verification, and rollback.

~~~bash
git add src/views/task/group-marketing/components/GroupMarketingDetailDrawer.vue src/views/task/group-marketing/components/GroupMarketingDetailDrawer.test.ts src/views/task/group-marketing/components/detail-rollup.ts src/views/task/group-marketing/components/detail-rollup.test.ts .harness/changes/marketing-task-detail-hierarchy-status
git commit -m "feat(marketing): refine task detail hierarchy"
~~~

The explicit git add stages the deleted detail-rollup paths.

---

### Task 6: Cross-Repository Regression, Review, and Release Readiness

**Files:**

- Modify: armada/.harness/changes/2026-07-19-marketing-task-detail-hierarchy-status.md
- Modify: protocol and frontend change summaries created above.

- [ ] **Step 1: Run complete protocol verification**

~~~bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
npm test -- --runInBand src/commands/message-send-failure.test.ts src/commands/worker-consumer.test.ts src/worker/group-sendability.test.ts
npm run lint
npm run build
~~~

- [ ] **Step 2: Run complete backend verification**

After confirming the local DbTest target:

~~~bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=AccountServiceImplTest,MarketingGroupExecutionNormalizerTest,MarketingTaskMapperSqlShapeTest test
./dbtest.sh 'MarketingTaskCreateReadDbTest#getDetail_returnsTargetRows+getDetail_rollsUpAccountGroupsFromSendAttempts+getDetail_usesLatestEffectiveRoundForGroupExecutionResult+getDetail_rollsUpDynamicGroupExecutionResult+getDetail_keepsAccountRowsWithoutSendAttempts+getDetail_derivesCountsAndExecutionFromLatestEffectiveAttempt+getDetail_normalizesKnownGroupFailureStates+getDetail_readsCurrentLoginStateAndKeepsDeletedAccountHistory+getDetail_isTenantIsolated'
mvn test
~~~

Expected: named unit and DbTests pass with no skipped DbTest, then the full suite reports BUILD SUCCESS.

- [ ] **Step 3: Run complete frontend verification**

~~~bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
node --import ./src/api/__tests__/node-test-alias.mjs --test src/views/account/index/account-display.test.ts src/views/task/group-marketing/components/group-send-status.test.ts src/views/task/group-marketing/components/group-execution-result.test.ts src/views/task/group-marketing/components/GroupMarketingDetailDrawer.test.ts src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts
pnpm typecheck
pnpm build
~~~

The unchanged polling test must remain green; do not alter the 5-second behavior to make this task pass.

- [ ] **Step 4: Review the four-layer acceptance matrix**

| Scenario | Protocol fact | API groupStatus | API execution | Page |
|---|---|---|---|---|
| Send succeeds after stale ban snapshot | success=true | NORMAL | SUCCESS / null | 正常；发送成功 |
| Account explicitly banned | reasonCode=ACCOUNT_BANNED | ACCOUNT_BANNED | FAILED / 账号封禁 | 账号封禁；发送失败；账号封禁 |
| Chat suspended/terminated | groupStatusReason=CHAT_SUSPENDED or CHAT_TERMINATED | GROUP_BANNED | FAILED / 群组已封禁 | 群组封禁；发送失败；群组已封禁 |
| Announce-only non-admin | groupStatusReason=ANNOUNCE_ONLY_NON_ADMIN | NO_PERMISSION | FAILED / 当前账号没有发言权限 | 没有权限；发送失败；当前账号没有发言权限 |
| Account not participant | groupStatusReason=ACCOUNT_NOT_PARTICIPANT | KICKED_OUT | FAILED / 账号已被踢出群聊 | 被踢出群聊；发送失败；账号已被踢出群聊 |
| Unknown socket failure | reasonCode=SEND_FAILED | UNCONFIRMED | FAILED / 脱敏描述 | 未确认；发送失败；脱敏描述 |

Account-banned acceptance must originate from a real or controlled protocol error path, not only a manual database update or frontend mock.

- [ ] **Step 5: Perform review gates**

- Protocol: no free-form group-status guessing, raw credential leak, or event field removal.
- Backend: one effective row drives all three derived fields; no N+1, cross-domain Mapper/entity dependency, @IgnoreTenant, or schema migration.
- Frontend: exact labels/order; old columns absent; unknown fallback safe; failure reason is inside execution; polling unchanged.
- All repositories: run git diff --check and git status --short; stage only task files.

- [ ] **Step 6: Update change records with evidence**

In the Armada change record check “按 TDD 实施并完成跨仓验证” only after actual implementation and real outputs exist. Before deployment its status is “实现完成，待部署确认”, not “已上线”.

- [ ] **Step 7: Release and rollback readiness**

Release only after explicit target-environment confirmation:

1. Protocol image.
2. Backend image.
3. Frontend assets.

Rollback in reverse user-facing order:

1. Frontend display and enum mapping.
2. Backend VO/query/normalizer/login-state integration.
3. Protocol classifier.

There is no database migration and therefore no data rollback.

## Final Self-Review Checklist

- [ ] 一级严格只有：在线状态、发送账号、账号发送总条数、明细。
- [ ] 二级严格只有：群组状态、群名称、单群发送条数、最后发送时间、执行情况。
- [ ] 账号和单群发送条数只累计 status=1 成功回执。
- [ ] 最后发送时间只取最近成功时间，从未成功展示 -。
- [ ] status=0 已提交和 status=3 跳过不覆盖最后有效结果。
- [ ] 最后有效记录严格按 round_no、attempt_no、id 倒序。
- [ ] 成功无条件覆盖为 NORMAL 并清空失败原因。
- [ ] 失败结果、失败原因、群组状态来自同一数据库行。
- [ ] 六个状态及未知枚举回退均有后端和前端测试。
- [ ] 在线状态批量读取、无 N+1、软删账号显示未知。
- [ ] 账号号码继续使用任务快照。
- [ ] 未新增表、列、索引、@IgnoreTenant 或跨域 Mapper 依赖。
- [ ] 现有 5 秒轮询行为及测试未改。
- [ ] 三仓均有验证证据、变更记录和独立提交。
- [ ] 文档中不存在未决字段或临时实现占位文字。
