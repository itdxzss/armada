# Group Creation Marketing Account Restricted State Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建群营销遇到 WhatsApp 建群受限错误时，把实际执行账号标记为 `账号受限` 并下线，账号列表可展示/筛选该状态，后续建群派单自动排除。

**Architecture:** 协议层先把 Baileys 原始 `account_reachout_restricted` / `rate-overlimit` 映射成显式 `ACCOUNT_REACHOUT_RESTRICTED`，后端 Worker 只根据该显式码落账号状态，避免把普通 500 或 `ACCOUNT_BUSY` 误归类。账号生命周期新增 `account_state=8`，候选账号 SQL 继续只选 `account_state=2 AND login_state=1`，无需改派单口径。前端同步增加状态展示、筛选和异常统计子项。

**Tech Stack:** Java 17, Spring Boot, MyBatis XML, Flyway, JUnit 5, Mockito, AssertJ, Fastify, TypeScript, Jest, Vue 3, Element Plus, pure-admin-thin.

---

## Scope Check

这次变更跨 `armada-protocol`、`armada`、`wheel-saas-pure-web` 三个仓库，但不是三个独立功能：协议层显式错误码是后端准确落状态的前置，前端字段和筛选依赖后端新增状态值。因此使用一个端到端实现计划，并按仓库分任务提交。

## Source Spec

- `/Users/daishuaishuai/IdeaProjects/armada/docs/superpowers/specs/2026-07-07-group-creation-marketing-account-restricted-design.md`
- 已确认需求：
  - `account_reachout_restricted` 和 `rate-overlimit` 归类为账号受限。
  - `ACCOUNT_BUSY` 不归类为账号受限。
  - 新增 `account_state=8`，展示名 `账号受限`。
  - 建群发现账号受限时，本地标记账号离线，并投递协议下线 outbox 命令。

## File Structure

### Protocol Layer: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer`

- Modify `src/routes/groups.ts`
  - 在 `/v1/groups/create` 内捕获 `sock.groupCreate` 的 Baileys 原始错误。
  - 只把 `account_reachout_restricted` / `rate-overlimit` 转成 `ProtocolError`。
  - 保持 `operationGate` 抛出的 `ACCOUNT_BUSY` 原样。
- Modify `src/routes/groups-create-announcement.test.ts`
  - 增加两个 Jest 用例覆盖受限错误映射。

### Backend: `/Users/daishuaishuai/IdeaProjects/armada/armada-api`

- Create `src/main/resources/db/migration/V044__account_restricted_state.sql`
  - 更新 `account_state.account_state` 列注释，追加 `8账号受限`。
- Modify `src/main/java/com/armada/account/model/entity/AccountStateCode.java`
  - 新增 `RESTRICTED = 8`。
- Modify state/list/stat comments:
  - `src/main/java/com/armada/account/model/entity/AccountState.java`
  - `src/main/java/com/armada/account/model/dto/AccountQuery.java`
  - `src/main/java/com/armada/account/model/vo/AccountListVO.java`
  - `src/main/java/com/armada/account/model/vo/AccountListVoRow.java`
- Modify stats:
  - `src/main/java/com/armada/account/model/vo/AccountStatsVoRow.java`
  - `src/main/java/com/armada/account/model/vo/AccountStatsVO.java`
  - `src/main/java/com/armada/account/service/impl/AccountServiceImpl.java`
  - `src/main/resources/mapper/account/AccountMapper.xml`
- Create `src/main/java/com/armada/account/service/AccountRestrictionService.java`
  - 账号受限落状态应用服务接口。
- Create `src/main/java/com/armada/account/service/impl/AccountRestrictionServiceImpl.java`
  - 标记 `account_state=8`、`login_state=2`、写 `block_reason`、投递下线 outbox。
- Create `src/main/java/com/armada/marketing/model/support/GroupCreateRestrictionClassifier.java`
  - 从 `ProtocolException` 中识别建群受限原因。
- Modify `src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingWorker.java`
  - 建群失败 catch 内识别受限错误，使用实际执行账号 `context.account()` 落状态。
- Tests:
  - Modify `src/test/java/com/armada/account/AccountSchemaDbTest.java`
  - Modify `src/test/java/com/armada/account/mapper/AccountStatsMapperDbTest.java`
  - Modify `src/test/java/com/armada/account/mapper/AccountListMapperDbTest.java`
  - Create `src/test/java/com/armada/account/service/impl/AccountServiceImplTest.java`
  - Create `src/test/java/com/armada/account/service/impl/AccountRestrictionServiceImplTest.java`
  - Create `src/test/java/com/armada/marketing/model/support/GroupCreateRestrictionClassifierTest.java`
  - Modify `src/test/java/com/armada/marketing/service/GroupCreationMarketingWorkerTest.java`

### Frontend: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web`

- Modify `src/api/account.ts`
  - `AccountState` union 增加 `8`。
  - `TenantAccountSummary` 增加 `restricted`。
- Modify `src/views/account/index/account-display.ts`
  - 状态标签、tag 风格、异常统计子项增加 `账号受限`。
- Modify `src/views/account/index/account-display.test.ts`
  - 覆盖状态标签和异常统计子项。
- Create `src/views/account/index/account-status-filter.ts`
  - 把中文状态筛选到 query 的映射抽成可测纯函数。
- Create `src/views/account/index/account-status-filter.test.ts`
  - 覆盖 `账号受限 -> accountState=8`，以及禁言筛选不混入账号状态。
- Modify `src/views/account/index/composables/useAccountListPage.ts`
  - 使用 `account-status-filter.ts` 的 options/helper，`ZERO_SUMMARY` 增加 `restricted: 0`。

---

### Task 1: Protocol Group Create Restricted Error Mapping

**Files:**
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/routes/groups.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/routes/groups-create-announcement.test.ts`

- [ ] **Step 1: Write failing Jest tests**

Add these two tests inside `describe('POST /v1/groups/create announcement mode', () => { ... })`:

```typescript
  it('maps account_reachout_restricted group create failure to ACCOUNT_REACHOUT_RESTRICTED', async () => {
    const sock = {
      async groupCreate() {
        throw new Error('Boom: account_reachout_restricted data=463')
      }
    }
    const app = buildApp(sock)

    const res = await app.inject({
      method: 'POST',
      url: '/v1/groups/create',
      payload: {
        accountId: 'acc_861111',
        subject: '测试群',
        participants: ['8613900000000@s.whatsapp.net']
      }
    })

    expect(res.statusCode).toBe(422)
    expect(res.json()).toMatchObject({
      code: 'ACCOUNT_REACHOUT_RESTRICTED',
      details: {
        accountId: 'acc_861111',
        reason: 'account_reachout_restricted',
        rawMessage: 'Boom: account_reachout_restricted data=463'
      }
    })
    await app.close()
  })

  it('maps rate-overlimit group create failure to ACCOUNT_REACHOUT_RESTRICTED', async () => {
    const sock = {
      async groupCreate() {
        throw new Error('rate-overlimit data=429')
      }
    }
    const app = buildApp(sock)

    const res = await app.inject({
      method: 'POST',
      url: '/v1/groups/create',
      payload: {
        accountId: 'acc_861112',
        subject: '测试群',
        participants: ['8613900000001@s.whatsapp.net']
      }
    })

    expect(res.statusCode).toBe(429)
    expect(res.json()).toMatchObject({
      code: 'ACCOUNT_REACHOUT_RESTRICTED',
      details: {
        accountId: 'acc_861112',
        reason: 'rate-overlimit',
        rawMessage: 'rate-overlimit data=429'
      }
    })
    await app.close()
  })
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
npm test -- --runTestsByPath src/routes/groups-create-announcement.test.ts
```

Expected: the two new tests fail because the route currently returns `500 INTERNAL_ERROR` instead of `422/429 ACCOUNT_REACHOUT_RESTRICTED`.

- [ ] **Step 3: Implement minimal protocol mapping**

In `src/routes/groups.ts`, add `ProtocolError` import:

```typescript
import { ProtocolError } from '../error/error-handler.js'
```

Add helpers near the existing local helper functions:

```typescript
type GroupCreateRestrictedReason = 'account_reachout_restricted' | 'rate-overlimit'

function rawErrorMessage(err: unknown): string {
  return err instanceof Error ? err.message : String(err)
}

function groupCreateRestrictedReason(err: unknown): GroupCreateRestrictedReason | null {
  const message = rawErrorMessage(err)
  if (message.includes('account_reachout_restricted')) return 'account_reachout_restricted'
  if (message.includes('rate-overlimit')) return 'rate-overlimit'
  return null
}

function groupCreateRestrictedStatus(reason: GroupCreateRestrictedReason): number {
  return reason === 'rate-overlimit' ? 429 : 422
}

class GroupCreateRestrictedError extends ProtocolError {
  constructor(accountId: string, reason: GroupCreateRestrictedReason, rawMessage: string) {
    super(
      groupCreateRestrictedStatus(reason),
      'ACCOUNT_REACHOUT_RESTRICTED',
      `account ${accountId} group create restricted: ${reason}`,
      {
        accountId,
        reason,
        rawMessage
      }
    )
  }
}
```

Replace only the `sock.groupCreate` call inside `/v1/groups/create` with a restricted-error-aware block:

```typescript
      const sock = ctx.accounts.getSocket(accountId)
      let created: Awaited<ReturnType<typeof sock.groupCreate>>
      try {
        created = await sock.groupCreate(subject, participants)
      } catch (err) {
        const reason = groupCreateRestrictedReason(err)
        if (reason) {
          throw new GroupCreateRestrictedError(accountId, reason, rawErrorMessage(err))
        }
        throw err
      }
```

Do not catch errors around `ctx.operationGate.runGroup(...)`; that preserves `ACCOUNT_BUSY` and other `ProtocolError` values thrown by the gate.

- [ ] **Step 4: Verify GREEN**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
npm test -- --runTestsByPath src/routes/groups-create-announcement.test.ts
npm run build
```

Expected: Jest test file passes, TypeScript build passes.

- [ ] **Step 5: Commit protocol change**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol
git status --short
git add protocol-layer/src/routes/groups.ts protocol-layer/src/routes/groups-create-announcement.test.ts
git commit -m "fix(protocol): map restricted group create errors"
```

---

### Task 2: Backend Restricted State Model, Migration, List Filter, Stats

**Files:**
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/db/migration/V044__account_restricted_state.sql`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/model/entity/AccountStateCode.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/model/entity/AccountState.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/model/dto/AccountQuery.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/model/vo/AccountListVO.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/model/vo/AccountListVoRow.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/model/vo/AccountStatsVoRow.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/model/vo/AccountStatsVO.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/service/impl/AccountServiceImpl.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/account/AccountMapper.xml`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/account/AccountSchemaDbTest.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/account/mapper/AccountStatsMapperDbTest.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/account/mapper/AccountListMapperDbTest.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/account/service/impl/AccountServiceImplTest.java`

- [ ] **Step 1: Write failing DB/schema tests**

In `AccountSchemaDbTest`, add:

```java
    @Test
    void accountStateCommentIncludesRestrictedState() {
        String comment = jdbc.queryForObject(
                "SELECT column_comment FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                String.class,
                "account_state",
                "account_state");

        assertThat(comment).contains("8账号受限");
    }
```

In `AccountListMapperDbTest`, add:

```java
    @Test
    void listAccounts_filterByRestrictedState_onlyMatchingReturned() {
        long now = System.currentTimeMillis();

        Account restricted = insertAccount("86188" + (now % 10000000L), now);
        insertDefaultState(restricted.getId(), now);
        jdbc.update("UPDATE account_state SET account_state = 8 WHERE account_id = ?", restricted.getId());

        Account normal = insertAccount("86189" + (now % 10000000L), now);
        insertDefaultState(normal.getId(), now);
        jdbc.update("UPDATE account_state SET account_state = 2 WHERE account_id = ?", normal.getId());

        AccountQuery query = new AccountQuery();
        query.setAccountState(8);

        List<AccountListVoRow> rows = accountMapper.selectPage(query);

        assertThat(accountMapper.countPage(query)).isGreaterThanOrEqualTo(1);
        assertThat(rows).extracting(AccountListVoRow::getId).contains(restricted.getId());
        assertThat(rows).extracting(AccountListVoRow::getId).doesNotContain(normal.getId());
    }
```

In `AccountStatsMapperDbTest#statsSummary_pendingOnlineNormalOfflineAndRestrictedBreakdown`, add a restricted account to the existing fixture and assertion:

```java
        Account restricted = insertAccount("86207" + (now % 100000000L), now);
        insertDefaultState(restricted.getId(), now);
        jdbc.update("UPDATE account_state SET account_state = 8, login_state = ? WHERE account_id = ?",
                AccountLoginStateCode.OFFLINE, restricted.getId());
```

Add after the existing assertions:

```java
        assertThat(after.getRestricted() - before.getRestricted()).isEqualTo(1L);
        assertThat(after.getOffline() - before.getOffline()).isEqualTo(1L);
```

Because the test already asserts `offline` increases by `1L` from the normal offline account, update that existing offline assertion to keep restricted out of offline:

```java
        assertThat(after.getOffline() - before.getOffline()).isEqualTo(1L);
```

Create `AccountServiceImplTest`:

```java
package com.armada.account.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.armada.account.converter.AccountConverter;
import com.armada.account.mapper.AccountGroupMapper;
import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.vo.AccountStatsVO;
import com.armada.account.model.vo.AccountStatsVoRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountServiceImplTest {

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private AccountGroupMapper accountGroupMapper;

    @Mock
    private AccountConverter accountConverter;

    @Test
    void getStatsIncludesRestrictedAccountStateInRestrictedTotal() {
        AccountStatsVoRow row = new AccountStatsVoRow();
        row.setTotal(30);
        row.setBanned(1);
        row.setUnbound(2);
        row.setMuted(3);
        row.setExported(4);
        row.setRestricted(5);
        row.setAssigned(9);
        when(accountMapper.statsSummary()).thenReturn(row);

        AccountStatsVO result = new AccountServiceImpl(accountMapper, accountGroupMapper, accountConverter).getStats();

        assertThat(result.restricted()).isEqualTo(5);
        assertThat(result.restrictedTotal()).isEqualTo(15);
        assertThat(result.unassigned()).isEqualTo(21);
    }
}
```

- [ ] **Step 2: Run tests and verify RED**

Confirm target DB is the test database configured by `armada-api/.env`, then run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
./dbtest.sh AccountSchemaDbTest#accountStateCommentIncludesRestrictedState
./dbtest.sh AccountListMapperDbTest#listAccounts_filterByRestrictedState_onlyMatchingReturned
./dbtest.sh AccountStatsMapperDbTest#statsSummary_pendingOnlineNormalOfflineAndRestrictedBreakdown
mvn -q -Dtest=AccountServiceImplTest test
```

Expected:
- Schema/comment test fails because `8账号受限` is not in the column comment.
- Stats mapper test fails because `AccountStatsVoRow#getRestricted()` does not exist or maps zero.
- Service test fails because `restricted` does not exist on the VO/row yet.
- List filter test may compile after `AccountStateCode.RESTRICTED` is not used; if it passes before code change, keep it as a regression test because SQL already supports arbitrary `accountState`.

- [ ] **Step 3: Add migration and state constant**

Create `V044__account_restricted_state.sql`:

```sql
ALTER TABLE account_state
  MODIFY COLUMN account_state TINYINT DEFAULT NULL
  COMMENT '账号状态:1新增 2正常 3封禁 4导出 5解绑 6被抢登 7抢登中 8账号受限;NULL=未上报';
```

In `AccountStateCode.java`, add after `TAKING_OVER`:

```java
    /**
     * 账号受限:WhatsApp 限制账号主动触达、建群或拉人,不可继续派单。
     */
    public static final int RESTRICTED = 8;
```

Update comments in `AccountState.java`, `AccountQuery.java`, `AccountListVO.java`, and `AccountListVoRow.java` so every `account_state` comment says:

```java
账号状态:1新增 2正常 3封禁 4导出 5解绑 6被抢登 7抢登中 8账号受限;NULL=未上报。
```

- [ ] **Step 4: Add backend stats field**

In `AccountStatsVoRow`, add field, getter, and setter:

```java
    /** account_state=8 账号受限账号数。 */
    private long restricted;

    public long getRestricted() {
        return restricted;
    }

    public void setRestricted(long restricted) {
        this.restricted = restricted;
    }
```

In `AccountStatsVO`, add `restricted` after `exported`:

```java
        long exported,
        long restricted,
        long risk,
```

Update the record javadoc so `restrictedTotal = banned + unbound + muted + exported + restricted`.

In `AccountMapper.xml#statsSummary`, add this column after `exported`:

```xml
      COALESCE(SUM(CASE WHEN s.account_state = 8 THEN 1 ELSE 0 END), 0) AS restricted,
```

Keep `offline` as:

```xml
      COALESCE(SUM(CASE WHEN s.account_state IN (1, 2, 6, 7) AND s.login_state = 2 THEN 1 ELSE 0 END), 0) AS offline,
```

This keeps `account_state=8` out of ordinary offline statistics.

In `AccountServiceImpl#getStats()`, compute and return:

```java
        long restrictedTotal = row.getBanned()
                + row.getUnbound()
                + row.getMuted()
                + row.getExported()
                + row.getRestricted();
```

Pass `row.getRestricted()` into the `AccountStatsVO` constructor after `row.getExported()`.

- [ ] **Step 5: Verify backend model GREEN**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -Dtest=AccountServiceImplTest test
./dbtest.sh AccountSchemaDbTest#accountStateCommentIncludesRestrictedState
./dbtest.sh AccountListMapperDbTest#listAccounts_filterByRestrictedState_onlyMatchingReturned
./dbtest.sh AccountStatsMapperDbTest#statsSummary_pendingOnlineNormalOfflineAndRestrictedBreakdown
xmllint --noout src/main/resources/mapper/account/AccountMapper.xml
```

Expected: unit test passes, DB tests pass with zero skipped tests, XML validates.

- [ ] **Step 6: Commit backend state/stat change**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git status --short
git add armada-api/src/main/resources/db/migration/V044__account_restricted_state.sql \
  armada-api/src/main/java/com/armada/account/model/entity/AccountStateCode.java \
  armada-api/src/main/java/com/armada/account/model/entity/AccountState.java \
  armada-api/src/main/java/com/armada/account/model/dto/AccountQuery.java \
  armada-api/src/main/java/com/armada/account/model/vo/AccountListVO.java \
  armada-api/src/main/java/com/armada/account/model/vo/AccountListVoRow.java \
  armada-api/src/main/java/com/armada/account/model/vo/AccountStatsVoRow.java \
  armada-api/src/main/java/com/armada/account/model/vo/AccountStatsVO.java \
  armada-api/src/main/java/com/armada/account/service/impl/AccountServiceImpl.java \
  armada-api/src/main/resources/mapper/account/AccountMapper.xml \
  armada-api/src/test/java/com/armada/account/AccountSchemaDbTest.java \
  armada-api/src/test/java/com/armada/account/mapper/AccountStatsMapperDbTest.java \
  armada-api/src/test/java/com/armada/account/mapper/AccountListMapperDbTest.java \
  armada-api/src/test/java/com/armada/account/service/impl/AccountServiceImplTest.java
git commit -m "feat(account): add restricted account state stats"
```

---

### Task 3: Backend Mark Restricted on Group Create Failure

**Files:**
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/service/AccountRestrictionService.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/service/impl/AccountRestrictionServiceImpl.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/marketing/model/support/GroupCreateRestrictionClassifier.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingWorker.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/account/service/impl/AccountRestrictionServiceImplTest.java`
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/marketing/model/support/GroupCreateRestrictionClassifierTest.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingWorkerTest.java`

- [ ] **Step 1: Write failing classifier test**

Create `GroupCreateRestrictionClassifierTest`:

```java
package com.armada.marketing.model.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import org.junit.jupiter.api.Test;

class GroupCreateRestrictionClassifierTest {

    @Test
    void restrictedReasonDetectsAccountReachoutRestrictedProtocolException() {
        ProtocolException ex = new ProtocolException(
                ProtocolErrorCode.ACCOUNT_REACHOUT_RESTRICTED,
                "协议层错误 422 ACCOUNT_REACHOUT_RESTRICTED: account_reachout_restricted");

        assertThat(GroupCreateRestrictionClassifier.restrictedReason(ex))
                .contains("account_reachout_restricted");
    }

    @Test
    void restrictedReasonDetectsRateOverlimitProtocolException() {
        ProtocolException ex = new ProtocolException(
                ProtocolErrorCode.ACCOUNT_REACHOUT_RESTRICTED,
                "协议层错误 429 ACCOUNT_REACHOUT_RESTRICTED: rate-overlimit");

        assertThat(GroupCreateRestrictionClassifier.restrictedReason(ex))
                .contains("rate-overlimit");
    }

    @Test
    void restrictedReasonDoesNotMatchAccountBusy() {
        ProtocolException ex = new ProtocolException(
                ProtocolErrorCode.ACCOUNT_BUSY,
                "协议层错误 429 ACCOUNT_BUSY: group operation in progress");

        assertThat(GroupCreateRestrictionClassifier.restrictedReason(ex)).isEmpty();
    }
}
```

- [ ] **Step 2: Write failing restriction service test**

Create `AccountRestrictionServiceImplTest`:

```java
package com.armada.account.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountState;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.platform.protocol.model.command.ProtocolOfflineCommandRequest;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountRestrictionServiceImplTest {

    @Mock
    private AccountStateMapper stateMapper;

    @Mock
    private ProtocolCommandOutboxService outboxService;

    @Test
    void markGroupCreateRestrictedMarksAccountRestrictedOfflineAndEnqueuesOffline() {
        long occurredAt = 1_725_000_000_000L;

        new AccountRestrictionServiceImpl(stateMapper, outboxService)
                .markGroupCreateRestricted(7L, "acc_7", "rate-overlimit", occurredAt);

        ArgumentCaptor<AccountState> lifecycleCaptor = ArgumentCaptor.forClass(AccountState.class);
        verify(stateMapper).updateLifecycleState(lifecycleCaptor.capture());
        AccountState lifecycle = lifecycleCaptor.getValue();
        assertThat(lifecycle.getAccountId()).isEqualTo(7L);
        assertThat(lifecycle.getAccountState()).isEqualTo(AccountStateCode.RESTRICTED);
        assertThat(lifecycle.getLoginState()).isEqualTo(AccountLoginStateCode.OFFLINE);
        assertThat(lifecycle.getStateSource()).isEqualTo("GROUP_CREATE_RESTRICTED");
        assertThat(lifecycle.getLastStateSyncTime()).isEqualTo(occurredAt);

        ArgumentCaptor<AccountState> reasonCaptor = ArgumentCaptor.forClass(AccountState.class);
        verify(stateMapper).updateBlockReason(reasonCaptor.capture());
        assertThat(reasonCaptor.getValue().getBlockReason()).isEqualTo("rate-overlimit");

        ArgumentCaptor<List<ProtocolOfflineCommandRequest>> offlineCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxService).enqueueOfflineCommands(offlineCaptor.capture());
        assertThat(offlineCaptor.getValue()).singleElement().satisfies(command -> {
            assertThat(command.accountId()).isEqualTo(7L);
            assertThat(command.protocolAccountId()).isEqualTo("acc_7");
            assertThat(command.source()).isEqualTo("group_create_restricted");
        });
    }

    @Test
    void markGroupCreateRestrictedSkipsOfflineCommandWhenProtocolAccountIdMissing() {
        new AccountRestrictionServiceImpl(stateMapper, outboxService)
                .markGroupCreateRestricted(7L, "", "account_reachout_restricted", 1_725_000_000_000L);

        verify(stateMapper).updateLifecycleState(any(AccountState.class));
        verify(stateMapper).updateBlockReason(any(AccountState.class));
        org.mockito.Mockito.verifyNoInteractions(outboxService);
    }
}
```

- [ ] **Step 3: Write failing Worker tests**

In `GroupCreationMarketingWorkerTest`, add:

```java
    @Mock
    private AccountRestrictionService accountRestrictionService;
```

Update the Worker constructor in `setUp()` by inserting `accountRestrictionService` before `new ObjectMapper()`.

Add imports:

```java
import com.armada.account.service.AccountRestrictionService;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
```

Add tests:

```java
    @Test
    void processRestrictedGroupCreateFailureMarksActualAccountRestrictedAndRetries() {
        GroupCreationMarketingItem item = item();
        GroupCreationMarketingTask task = task(null);
        when(groupCreationMapper.selectDueItems(anyInt(), anyLong())).thenReturn(List.of(item));
        when(groupCreationMapper.claimItem(eq(11L), eq(GroupCreationMarketingItemStatus.PENDING.code()),
                eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()), anyLong())).thenReturn(1);
        when(groupCreationMapper.selectTaskById(22L)).thenReturn(task);
        when(groupCreationMapper.selectAccountCandidateByAccountId(7L))
                .thenReturn(account(AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE));
        when(groupCreatePort.create(eq("acc_7"), eq("活动群-1"), anyList(), eq(true)))
                .thenThrow(new ProtocolException(ProtocolErrorCode.ACCOUNT_REACHOUT_RESTRICTED,
                        "协议层错误 422 ACCOUNT_REACHOUT_RESTRICTED: account_reachout_restricted"));
        when(retryService.resetItemForAccountRetry(eq(item), eq(task),
                eq(GroupCreationMarketingRetryService.STAGE_GROUP_CREATE), eq("GROUP_CREATE_FAILED"),
                anyString(), anyLong())).thenReturn(true);

        worker.processDueItems(10);

        verify(accountRestrictionService).markGroupCreateRestricted(
                eq(7L), eq("acc_7"), eq("account_reachout_restricted"), anyLong());
        verify(retryService).resetItemForAccountRetry(eq(item), eq(task),
                eq(GroupCreationMarketingRetryService.STAGE_GROUP_CREATE), eq("GROUP_CREATE_FAILED"),
                anyString(), anyLong());
    }

    @Test
    void processRestrictedFailureAfterAccountReplacementMarksReplacementAccount() {
        GroupCreationMarketingItem item = item();
        GroupCreationMarketingTask task = task(null);
        GroupCreationMarketingAccountCandidate replacement =
                account(8L, "8613000000001", "acc_8", AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE);
        when(groupCreationMapper.selectDueItems(anyInt(), anyLong())).thenReturn(List.of(item));
        when(groupCreationMapper.claimItem(eq(11L), eq(GroupCreationMarketingItemStatus.PENDING.code()),
                eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()), anyLong())).thenReturn(1);
        when(groupCreationMapper.selectTaskById(22L)).thenReturn(task);
        when(groupCreationMapper.selectAccountCandidateByAccountId(7L))
                .thenReturn(account(AccountStateCode.NORMAL, AccountLoginStateCode.OFFLINE));
        when(retryService.replaceClaimedItemAccountForRetry(eq(item), eq(task),
                eq(GroupCreationMarketingRetryService.STAGE_ACCOUNT_CHECK),
                eq("ACCOUNT_OFFLINE"), eq("账号离线"), anyLong())).thenReturn(replacement);
        when(groupCreatePort.create(eq("acc_8"), eq("活动群-1"), anyList(), eq(true)))
                .thenThrow(new ProtocolException(ProtocolErrorCode.ACCOUNT_REACHOUT_RESTRICTED,
                        "协议层错误 429 ACCOUNT_REACHOUT_RESTRICTED: rate-overlimit"));
        when(retryService.resetItemForAccountRetry(eq(item), eq(task),
                eq(GroupCreationMarketingRetryService.STAGE_GROUP_CREATE), eq("GROUP_CREATE_FAILED"),
                anyString(), anyLong())).thenReturn(true);

        worker.processDueItems(10);

        verify(accountRestrictionService).markGroupCreateRestricted(
                eq(8L), eq("acc_8"), eq("rate-overlimit"), anyLong());
    }

    @Test
    void processAccountBusyGroupCreateFailureDoesNotMarkRestricted() {
        GroupCreationMarketingItem item = item();
        GroupCreationMarketingTask task = task(null);
        when(groupCreationMapper.selectDueItems(anyInt(), anyLong())).thenReturn(List.of(item));
        when(groupCreationMapper.claimItem(eq(11L), eq(GroupCreationMarketingItemStatus.PENDING.code()),
                eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()), anyLong())).thenReturn(1);
        when(groupCreationMapper.selectTaskById(22L)).thenReturn(task);
        when(groupCreationMapper.selectAccountCandidateByAccountId(7L))
                .thenReturn(account(AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE));
        when(groupCreatePort.create(eq("acc_7"), eq("活动群-1"), anyList(), eq(true)))
                .thenThrow(new ProtocolException(ProtocolErrorCode.ACCOUNT_BUSY,
                        "协议层错误 429 ACCOUNT_BUSY: group operation in progress"));
        when(retryService.resetItemForAccountRetry(eq(item), eq(task),
                eq(GroupCreationMarketingRetryService.STAGE_GROUP_CREATE), eq("GROUP_CREATE_FAILED"),
                anyString(), anyLong())).thenReturn(true);

        worker.processDueItems(10);

        verify(accountRestrictionService, never()).markGroupCreateRestricted(anyLong(), anyString(), anyString(), anyLong());
        verify(retryService).resetItemForAccountRetry(eq(item), eq(task),
                eq(GroupCreationMarketingRetryService.STAGE_GROUP_CREATE), eq("GROUP_CREATE_FAILED"),
                anyString(), anyLong());
    }
```

- [ ] **Step 4: Run tests and verify RED**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -Dtest=GroupCreateRestrictionClassifierTest,AccountRestrictionServiceImplTest,GroupCreationMarketingWorkerTest test
```

Expected: compile/test failures because `AccountRestrictionService`, `AccountRestrictionServiceImpl`, and `GroupCreateRestrictionClassifier` do not exist, and Worker constructor has not been updated.

- [ ] **Step 5: Implement classifier**

Create `GroupCreateRestrictionClassifier.java`:

```java
package com.armada.marketing.model.support;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import java.util.Locale;
import java.util.Optional;

public final class GroupCreateRestrictionClassifier {

    private static final String REASON_ACCOUNT_REACHOUT_RESTRICTED = "account_reachout_restricted";
    private static final String REASON_RATE_OVERLIMIT = "rate-overlimit";

    private GroupCreateRestrictionClassifier() {
    }

    public static Optional<String> restrictedReason(RuntimeException ex) {
        if (!(ex instanceof ProtocolException protocolException)
                || protocolException.errorCode() != ProtocolErrorCode.ACCOUNT_REACHOUT_RESTRICTED) {
            return Optional.empty();
        }
        String message = protocolException.getMessage() == null
                ? ""
                : protocolException.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains(REASON_RATE_OVERLIMIT)) {
            return Optional.of(REASON_RATE_OVERLIMIT);
        }
        if (message.contains(REASON_ACCOUNT_REACHOUT_RESTRICTED)) {
            return Optional.of(REASON_ACCOUNT_REACHOUT_RESTRICTED);
        }
        return Optional.of(REASON_ACCOUNT_REACHOUT_RESTRICTED);
    }
}
```

- [ ] **Step 6: Implement account restriction service**

Create `AccountRestrictionService.java`:

```java
package com.armada.account.service;

public interface AccountRestrictionService {

    void markGroupCreateRestricted(Long accountId, String protocolAccountId, String reason, long occurredAt);
}
```

Create `AccountRestrictionServiceImpl.java`:

```java
package com.armada.account.service.impl;

import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountState;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.service.AccountRestrictionService;
import com.armada.platform.protocol.model.command.ProtocolOfflineCommandRequest;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AccountRestrictionServiceImpl implements AccountRestrictionService {

    private static final String STATE_SOURCE_GROUP_CREATE_RESTRICTED = "GROUP_CREATE_RESTRICTED";
    private static final String OFFLINE_SOURCE_GROUP_CREATE_RESTRICTED = "group_create_restricted";
    private static final String DEFAULT_REASON = "account_reachout_restricted";
    private static final int BLOCK_REASON_MAX_LENGTH = 255;

    private final AccountStateMapper stateMapper;
    private final ProtocolCommandOutboxService outboxService;

    public AccountRestrictionServiceImpl(AccountStateMapper stateMapper,
                                         ProtocolCommandOutboxService outboxService) {
        this.stateMapper = stateMapper;
        this.outboxService = outboxService;
    }

    @Override
    public void markGroupCreateRestricted(Long accountId, String protocolAccountId, String reason, long occurredAt) {
        if (accountId == null) {
            throw new IllegalArgumentException("accountId must not be null");
        }
        long updatedAt = System.currentTimeMillis();
        String blockReason = clamp(StringUtils.hasText(reason) ? reason : DEFAULT_REASON, BLOCK_REASON_MAX_LENGTH);
        AccountState row = new AccountState();
        row.setAccountId(accountId);
        row.setLoginState(AccountLoginStateCode.OFFLINE);
        row.setAccountState(AccountStateCode.RESTRICTED);
        row.setStateSource(STATE_SOURCE_GROUP_CREATE_RESTRICTED);
        row.setBlockReason(blockReason);
        row.setLastStateSyncTime(occurredAt);
        row.setUpdatedAt(updatedAt);

        stateMapper.updateLifecycleState(row);
        stateMapper.updateBlockReason(row);
        if (StringUtils.hasText(protocolAccountId)) {
            outboxService.enqueueOfflineCommands(List.of(new ProtocolOfflineCommandRequest(
                    accountId,
                    protocolAccountId,
                    OFFLINE_SOURCE_GROUP_CREATE_RESTRICTED)));
        }
    }

    private static String clamp(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
```

- [ ] **Step 7: Integrate Worker**

In `GroupCreationMarketingWorker`, add imports:

```java
import com.armada.account.service.AccountRestrictionService;
import com.armada.marketing.model.support.GroupCreateRestrictionClassifier;
import java.util.Optional;
```

Add field:

```java
    private final AccountRestrictionService accountRestrictionService;
```

Update constructor by inserting `AccountRestrictionService accountRestrictionService` after `GroupCreationMarketingRetryService retryService`, then assign:

```java
        this.accountRestrictionService = accountRestrictionService;
```

Replace the `catch (RuntimeException ex)` block around `groupCreatePort.create(...)` with:

```java
        } catch (RuntimeException ex) {
            String reason = readableMessage(ex);
            Optional<String> restrictedReason = GroupCreateRestrictionClassifier.restrictedReason(ex);
            long failedAt = System.currentTimeMillis();
            transactionOperations.executeWithoutResult(status -> {
                restrictedReason.ifPresent(value -> accountRestrictionService.markGroupCreateRestricted(
                        account.getAccountId(),
                        account.getProtocolAccountId(),
                        value,
                        failedAt));
                retryService.resetItemForAccountRetry(
                        item, task, GroupCreationMarketingRetryService.STAGE_GROUP_CREATE,
                        REASON_GROUP_CREATE_FAILED, reason, failedAt);
            });
            return;
        }
```

Use `account.getAccountId()` and `account.getProtocolAccountId()`, not the original `item` snapshot, because the Worker may have replaced the item account during `ACCOUNT_CHECK` before the group-create call.

- [ ] **Step 8: Verify backend restricted handling GREEN**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -Dtest=GroupCreateRestrictionClassifierTest,AccountRestrictionServiceImplTest,GroupCreationMarketingWorkerTest test
```

Expected: all focused unit tests pass.

- [ ] **Step 9: Commit backend Worker integration**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git status --short
git add armada-api/src/main/java/com/armada/account/service/AccountRestrictionService.java \
  armada-api/src/main/java/com/armada/account/service/impl/AccountRestrictionServiceImpl.java \
  armada-api/src/main/java/com/armada/marketing/model/support/GroupCreateRestrictionClassifier.java \
  armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingWorker.java \
  armada-api/src/test/java/com/armada/account/service/impl/AccountRestrictionServiceImplTest.java \
  armada-api/src/test/java/com/armada/marketing/model/support/GroupCreateRestrictionClassifierTest.java \
  armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingWorkerTest.java
git commit -m "feat(marketing): restrict account on group create limit"
```

---

### Task 4: Frontend Account Restricted Display and Filter

**Files:**
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/api/account.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/account-display.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/account-display.test.ts`
- Create: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/account-status-filter.ts`
- Create: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/account-status-filter.test.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/composables/useAccountListPage.ts`

- [ ] **Step 1: Write failing frontend tests**

In `account-display.test.ts`, add:

```typescript
  it("maps restricted account status label and warning tag", () => {
    assert.equal(accountStatusLabel({ account_state: 8 }), "账号受限");
    assert.equal(accountStatusTagType({ account_state: 8 }), "warning");
  });
```

Update the existing stats test input:

```typescript
      exported: 4,
      restricted: 5,
      restrictedTotal: 15,
```

Update expected card row:

```typescript
        ["restricted", "异常账号", 15],
```

Update expected subitems:

```typescript
    assert.deepEqual(cards[1].subItems, [
      { label: "封禁", value: 1 },
      { label: "解绑", value: 2 },
      { label: "禁言", value: 3 },
      { label: "导出", value: 4 },
      { label: "受限", value: 5 }
    ]);
```

Create `account-status-filter.test.ts`:

```typescript
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { accountStatusOptions, accountStatusToQuery } from "./account-status-filter";

describe("account status filter mapping", () => {
  it("includes restricted account status option", () => {
    assert.equal(accountStatusOptions.includes("账号受限"), true);
  });

  it("maps restricted status to accountState 8", () => {
    assert.deepEqual(accountStatusToQuery("账号受限"), { accountState: 8 });
  });

  it("maps mute status without accountState", () => {
    assert.deepEqual(accountStatusToQuery("禁言6小时"), { muteStatus: 1 });
    assert.deepEqual(accountStatusToQuery("禁言24小时"), { muteStatus: 2 });
  });

  it("maps empty status to empty query patch", () => {
    assert.deepEqual(accountStatusToQuery(""), {});
  });
});
```

- [ ] **Step 2: Run frontend tests/typecheck and verify RED**

The frontend package has no dedicated test script. Use TypeScript to compile these `node:test` files into `/private/tmp`, then run Node on the emitted JavaScript:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
mkdir -p /private/tmp/wheel-account-tests
pnpm exec tsc --module commonjs --target es2022 --moduleResolution node --esModuleInterop --skipLibCheck --outDir /private/tmp/wheel-account-tests \
  src/views/account/index/account-display.test.ts \
  src/views/account/index/account-status-filter.test.ts
node --test /private/tmp/wheel-account-tests/views/account/index/account-display.test.js \
  /private/tmp/wheel-account-tests/views/account/index/account-status-filter.test.js
```

Expected: compile or test failures because `AccountState` excludes `8`, `restricted` is missing, and `account-status-filter.ts` does not exist.

- [ ] **Step 3: Update frontend API types**

In `src/api/account.ts`, change:

```typescript
export type AccountState = 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8;
```

Add `restricted` to `TenantAccountSummary`:

```typescript
  restricted: number;
```

- [ ] **Step 4: Update display helpers**

In `account-display.ts`, add label:

```typescript
    8: "账号受限"
```

Update warning tag condition:

```typescript
  if (row.account_state === 6 || row.account_state === 7 || row.account_state === 8) return "warning";
```

Add restricted stat subitem after exported:

```typescript
        { label: "导出", value: summary.exported },
        { label: "受限", value: summary.restricted }
```

Keep `canDeleteAccount` unchanged so `account_state=8` is not deletable unless product later changes deletion policy.

- [ ] **Step 5: Extract account status filter helper**

Create `account-status-filter.ts`:

```typescript
import type {
  AccountState,
  MuteStatus,
  TenantAccountListQuery
} from "../../../api/account";

export type AccountStatusFilter =
  | ""
  | "正常"
  | "账号受限"
  | "封禁"
  | "导出"
  | "禁言6小时"
  | "禁言24小时"
  | "解绑"
  | "被抢登"
  | "抢登中";

export const accountStatusOptions: Exclude<AccountStatusFilter, "">[] = [
  "正常",
  "账号受限",
  "被抢登",
  "抢登中",
  "封禁",
  "导出",
  "禁言6小时",
  "禁言24小时",
  "解绑"
];

const accountStateMap: Partial<Record<AccountStatusFilter, AccountState>> = {
  正常: 2,
  账号受限: 8,
  封禁: 3,
  导出: 4,
  解绑: 5,
  被抢登: 6,
  抢登中: 7
};

const muteStatusMap: Partial<Record<AccountStatusFilter, MuteStatus>> = {
  禁言6小时: 1,
  禁言24小时: 2
};

export function accountStatusToQuery(
  status: AccountStatusFilter
): Pick<TenantAccountListQuery, "accountState" | "muteStatus"> {
  const accountState = accountStateMap[status];
  if (accountState) return { accountState };
  const muteStatus = muteStatusMap[status];
  if (muteStatus) return { muteStatus };
  return {};
}
```

- [ ] **Step 6: Wire helper into composable**

In `useAccountListPage.ts`, add import:

```typescript
import {
  accountStatusOptions,
  accountStatusToQuery,
  type AccountStatusFilter
} from "../account-status-filter";
```

Change `AccountSearchForm.accountStatus` type to:

```typescript
  accountStatus: AccountStatusFilter;
```

Remove the local `const accountStatusOptions = [...]` block from inside `useAccountListPage()`.

In `ZERO_SUMMARY`, add:

```typescript
  restricted: 0,
```

Replace the `if (searchForm.accountStatus) { ... }` block inside `buildQuery()` with:

```typescript
    if (searchForm.accountStatus) {
      Object.assign(query, accountStatusToQuery(searchForm.accountStatus));
    }
```

- [ ] **Step 7: Verify frontend GREEN**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
mkdir -p /private/tmp/wheel-account-tests
pnpm exec tsc --module commonjs --target es2022 --moduleResolution node --esModuleInterop --skipLibCheck --outDir /private/tmp/wheel-account-tests \
  src/views/account/index/account-display.test.ts \
  src/views/account/index/account-status-filter.test.ts
node --test /private/tmp/wheel-account-tests/views/account/index/account-display.test.js \
  /private/tmp/wheel-account-tests/views/account/index/account-status-filter.test.js
pnpm typecheck
```

Expected: node tests pass and `pnpm typecheck` passes.

- [ ] **Step 8: Commit frontend change**

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
git status --short
git add src/api/account.ts \
  src/views/account/index/account-display.ts \
  src/views/account/index/account-display.test.ts \
  src/views/account/index/account-status-filter.ts \
  src/views/account/index/account-status-filter.test.ts \
  src/views/account/index/composables/useAccountListPage.ts
git commit -m "feat(account): show restricted account state"
```

---

### Task 5: Final Verification

**Files:**
- No source edits in this task.

- [ ] **Step 1: Run focused protocol verification**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
npm test -- --runTestsByPath src/routes/groups-create-announcement.test.ts
npm run build
```

Expected: Jest and TypeScript build pass.

- [ ] **Step 2: Run focused backend unit verification**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -Dtest=AccountServiceImplTest,GroupCreateRestrictionClassifierTest,AccountRestrictionServiceImplTest,GroupCreationMarketingWorkerTest test
```

Expected: all focused unit tests pass.

- [ ] **Step 3: Run focused backend DB verification**

Confirm `armada-api/.env` points at the test database, then run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
./dbtest.sh AccountSchemaDbTest#accountStateCommentIncludesRestrictedState
./dbtest.sh AccountListMapperDbTest#listAccounts_filterByRestrictedState_onlyMatchingReturned
./dbtest.sh AccountStatsMapperDbTest#statsSummary_pendingOnlineNormalOfflineAndRestrictedBreakdown
xmllint --noout src/main/resources/mapper/account/AccountMapper.xml
```

Expected: DB tests pass with zero skipped tests; XML validates.

- [ ] **Step 4: Run focused frontend verification**

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
mkdir -p /private/tmp/wheel-account-tests
pnpm exec tsc --module commonjs --target es2022 --moduleResolution node --esModuleInterop --skipLibCheck --outDir /private/tmp/wheel-account-tests \
  src/views/account/index/account-display.test.ts \
  src/views/account/index/account-status-filter.test.ts
node --test /private/tmp/wheel-account-tests/views/account/index/account-display.test.js \
  /private/tmp/wheel-account-tests/views/account/index/account-status-filter.test.js
pnpm typecheck
```

Expected: node tests pass and typecheck passes.

- [ ] **Step 5: Manual test-env sanity check**

After deployment to test environment, use one controlled account or existing task fixture that triggers `account_reachout_restricted` or `rate-overlimit`.

Expected observations:
- Protocol `/v1/groups/create` response body has `code=ACCOUNT_REACHOUT_RESTRICTED`.
- Backend `account_state` row for the actual execution account has:
  - `account_state = 8`
  - `login_state = 2`
  - `state_source = GROUP_CREATE_RESTRICTED`
  - `block_reason = account_reachout_restricted` or `rate-overlimit`
  - `invalidated_at` populated
- `protocol_command_outbox` has an offline command with `source=group_create_restricted`.
- 建群营销后续候选账号不再选择该账号。
- 前端账号列表可筛选“账号受限”，异常账号卡片显示“受限”子项。

- [ ] **Step 6: Review diff**

Run in each repo:

```bash
git status --short
git diff --stat
```

Expected:
- `armada-protocol`: only protocol group route/test files changed.
- `armada`: migration, account state/stat files, new restriction service/classifier, Worker/tests changed.
- `wheel-saas-pure-web`: account API type, account display/filter helper/composable/tests changed.

## Self-Review

**Spec coverage:**
- 新增账号状态 `8=账号受限`: Task 2.
- 协议层映射 `account_reachout_restricted` / `rate-overlimit`: Task 1.
- `ACCOUNT_BUSY` 不归类受限: Task 1 preserves operation gate errors; Task 3 classifier/Worker tests assert no restricted mark.
- 建群发现受限时标记账号受限并下线: Task 3 local lifecycle update plus offline outbox.
- 建群候选自动排除: Task 2 keeps candidate requirement unchanged through `account_state=2`; Task 5 validates.
- 账号列表展示/筛选: Task 4.
- 异常统计包含受限: Task 2 backend stats and Task 4 frontend card.

**Placeholder scan:**
- 未发现空白占位项、延后实现描述或泛泛错误处理说明。
- All new types, methods, and commands are named explicitly.

**Type consistency:**
- Protocol code wire value is `ACCOUNT_REACHOUT_RESTRICTED`, already present in backend `ProtocolErrorCode`.
- Backend state constant is `AccountStateCode.RESTRICTED = 8`.
- Backend summary field is `restricted`; frontend `TenantAccountSummary.restricted` uses the same JSON field name.
- Worker uses `context.account()` as the actual execution account for restriction marking.
