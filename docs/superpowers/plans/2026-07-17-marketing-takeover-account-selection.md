# 普通营销任务允许在线抢登账号 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让普通营销任务允许选择并创建在线的正常、被抢登、抢登中账号目标，同时继续拒绝离线账号。

**Architecture:** 在营销域新增一个小型账号候选策略，集中维护允许的 `AccountStateCode` 集合；账号树 Java 判断和创建任务 Mapper 参数都消费该策略。固定群组与账号动态创建 SQL 通过 MyBatis `foreach` 使用同一组状态，发送阶段和其他营销类型保持不变。

**Tech Stack:** Java 17、Spring Boot 3.3.5、MyBatis XML、JUnit 5、AssertJ、Mockito、MySQL DbTest

---

### Task 1: 用单元测试锁定账号树状态口径

**Files:**
- Create: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingAccountEligibility.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingAccountTreeRealtimeService.java:180-210`
- Test: `armada-api/src/test/java/com/armada/marketing/service/impl/MarketingAccountTreeRealtimeServiceTest.java`

- [ ] **Step 1: 写在线被抢登、在线抢登中可选且离线抢登中不可选的失败测试**

在 `MarketingAccountTreeRealtimeServiceTest` 引入 `AccountStateCode` 与
`AccountLoginStateCode`，增加一个覆盖三条状态事实的测试：

```java
@Test
void accountTreeAllowsOnlyOnlineTakeoverLifecycleStates() {
    MarketingAccountTreeAccountRow replaced = accountRow(6L, "923300000006", 2);
    replaced.setAccountState(AccountStateCode.LOGIN_REPLACED);
    MarketingAccountTreeAccountRow takingOver = accountRow(7L, "923300000007", 2);
    takingOver.setAccountState(AccountStateCode.TAKING_OVER);
    MarketingAccountTreeAccountRow offlineTakingOver = accountRow(8L, "923300000008", 2);
    offlineTakingOver.setAccountState(AccountStateCode.TAKING_OVER);
    offlineTakingOver.setLoginState(AccountLoginStateCode.OFFLINE);
    when(taskMapper.selectAccountTreeAccounts(8L))
            .thenReturn(List.of(replaced, takingOver, offlineTakingOver));

    var tree = service.accountTree(8L);

    assertThat(tree.accounts()).extracting(account -> account.accountId(), account -> account.selectable())
            .containsExactly(
                    org.assertj.core.groups.Tuple.tuple(6L, true),
                    org.assertj.core.groups.Tuple.tuple(7L, true),
                    org.assertj.core.groups.Tuple.tuple(8L, false));
    assertThat(tree.accounts().get(2).disabledReason()).isEqualTo("离线");
}
```

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```bash
cd armada-api
mvn -Dtest=MarketingAccountTreeRealtimeServiceTest test
```

Expected: FAIL；账号 `6`、`7` 的 `selectable` 实际仍为 `false`，离线账号断言保持通过。

- [ ] **Step 3: 新增集中状态策略**

创建 `MarketingAccountEligibility.java`：

```java
package com.armada.marketing.service.impl;

import com.armada.account.model.entity.AccountStateCode;
import java.util.List;

/**
 * 普通营销任务发送账号候选状态策略。
 */
final class MarketingAccountEligibility {

    /** 普通营销任务允许使用的账号生命周期状态。 */
    private static final List<Integer> SELECTABLE_ACCOUNT_STATES = List.of(
            AccountStateCode.NORMAL,
            AccountStateCode.LOGIN_REPLACED,
            AccountStateCode.TAKING_OVER);

    private MarketingAccountEligibility() {
    }

    /** 判断账号生命周期状态是否允许进入普通营销任务。 */
    static boolean supportsAccountState(Integer accountState) {
        return SELECTABLE_ACCOUNT_STATES.contains(accountState);
    }

    /** 返回传给创建候选 SQL 的只读账号状态集合。 */
    static List<Integer> selectableAccountStates() {
        return SELECTABLE_ACCOUNT_STATES;
    }
}
```

- [ ] **Step 4: 让账号树使用统一状态策略**

把 `MarketingAccountTreeRealtimeService.selectable` 中的：

```java
&& Integer.valueOf(AccountStateCode.NORMAL).equals(account.getAccountState())
```

替换为：

```java
&& MarketingAccountEligibility.supportsAccountState(account.getAccountState())
```

同时把 `disabledReason` 中在线但账号不可用的判断替换为：

```java
if (STATUS_ONLINE.equals(status)
        && !MarketingAccountEligibility.supportsAccountState(account.getAccountState())) {
    return "账号不可用";
}
```

保留 `AccountStateCode` import；同一服务的封禁状态映射仍使用
`AccountStateCode.BANNED`。

- [ ] **Step 5: 运行账号树测试并确认 GREEN**

Run:

```bash
cd armada-api
mvn -Dtest=MarketingAccountTreeRealtimeServiceTest test
```

Expected: PASS，包含现有正常、占用、离线、群同步测试和新增抢登状态测试。

- [ ] **Step 6: 提交账号树策略**

```bash
git add armada-api/src/main/java/com/armada/marketing/service/impl/MarketingAccountEligibility.java \
  armada-api/src/main/java/com/armada/marketing/service/impl/MarketingAccountTreeRealtimeService.java \
  armada-api/src/test/java/com/armada/marketing/service/impl/MarketingAccountTreeRealtimeServiceTest.java
git commit -m "feat: allow online takeover accounts in marketing tree"
```

### Task 2: 用 SQL 形状测试锁定两类创建候选状态集合

**Files:**
- Modify: `armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java:175-185`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java:486-505`
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml:590-642`
- Test: `armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java`
- Test: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskServiceImplLifecycleTest.java`

- [ ] **Step 1: 写固定群组与账号动态创建候选的失败形状测试**

在 `marketingAccountSelectionUsesSnapshotForFixedTargetsAndMembershipForDynamicTargets`
中读取 `selectAccountTargetCandidate`，并增加：

```java
String accountCandidateSql = selectBlock(xml, "selectAccountTargetCandidate");

assertThat(candidateSql)
        .contains("collection=\"selectableAccountStates\"")
        .doesNotContain("s.account_state = 2");
assertThat(accountCandidateSql)
        .contains("collection=\"selectableAccountStates\"")
        .doesNotContain("s.account_state = 2");
```

- [ ] **Step 2: 运行形状测试并确认 RED**

Run:

```bash
cd armada-api
mvn -Dtest=MarketingTaskMapperSqlShapeTest test
```

Expected: FAIL；两个创建候选查询仍包含 `s.account_state = 2`，没有状态集合参数。

- [ ] **Step 3: 扩展 Mapper 方法参数**

修改 `MarketingTaskMapper`：

```java
MarketingTargetCandidateRow selectTargetCandidate(
        @Param("accountGroupId") Long accountGroupId,
        @Param("accountId") Long accountId,
        @Param("groupLinkId") Long groupLinkId,
        @Param("selectableAccountStates") List<Integer> selectableAccountStates);

MarketingTargetCandidateRow selectAccountTargetCandidate(
        @Param("accountGroupId") Long accountGroupId,
        @Param("accountId") Long accountId,
        @Param("selectableAccountStates") List<Integer> selectableAccountStates);
```

同步更新 Javadoc，明确状态集合由普通营销候选策略提供。

- [ ] **Step 4: 创建任务服务传入统一状态集合**

修改 `MarketingTaskServiceImpl` 两个候选调用：

```java
MarketingTargetCandidateRow row = taskMapper.selectTargetCandidate(
        accountGroupId,
        accountId,
        groupLinkId,
        MarketingAccountEligibility.selectableAccountStates());
```

```java
MarketingTargetCandidateRow row = taskMapper.selectAccountTargetCandidate(
        accountGroupId,
        accountId,
        MarketingAccountEligibility.selectableAccountStates());
```

- [ ] **Step 5: 两类 SQL 改为集合过滤**

在 `selectTargetCandidate` 与 `selectAccountTargetCandidate` 中把
`AND s.account_state = 2` 替换为：

```xml
AND s.account_state IN
<foreach collection="selectableAccountStates"
         item="accountState"
         open="("
         separator=","
         close=")">
    #{accountState}
</foreach>
```

只改创建候选 SQL；`selectDynamicTargetGroups` 等发送阶段查询保持现状。

- [ ] **Step 6: 修正生命周期单测的 Mapper stub**

将：

```java
when(taskMapper.selectAccountTargetCandidate(12L, 31L)).thenReturn(accountCandidate());
```

改为：

```java
when(taskMapper.selectAccountTargetCandidate(
        eq(12L),
        eq(31L),
        org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(accountCandidate());
```

- [ ] **Step 7: 运行相关单元测试并确认 GREEN**

Run:

```bash
cd armada-api
mvn -Dtest=MarketingTaskMapperSqlShapeTest,MarketingTaskServiceImplLifecycleTest test
```

Expected: PASS；固定群组和账号动态创建候选都通过同名集合参数过滤，生命周期测试可正常建任务。

- [ ] **Step 8: 提交创建候选门禁**

```bash
git add armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java \
  armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java \
  armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml \
  armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java \
  armada-api/src/test/java/com/armada/marketing/service/MarketingTaskServiceImplLifecycleTest.java
git commit -m "feat: accept takeover states for marketing targets"
```

### Task 3: 用真库创建测试验证在线与离线边界

**Files:**
- Test: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java`

- [ ] **Step 1: 增加可执行抢登账号 fixture**

引入 `AccountLoginStateCode` 与 `AccountStateCode`，增加不扰动现有 fixture 的 helper：

```java
private Fixture seedTakeoverFixture(String suffix, int accountState, int loginState) {
    Fixture fixture = seedFixture(suffix, false, accountState);
    jdbc.update("""
            UPDATE account
            SET protocol_account_id = ?,
                group_baseline_state = 3
            WHERE id = ?
            """, "acc_" + fixture.phone(), fixture.accountId());
    jdbc.update("""
            UPDATE account_state
            SET login_state = ?
            WHERE account_id = ?
            """, loginState, fixture.accountId());
    return fixture;
}
```

- [ ] **Step 2: 写在线被抢登与抢登中账号动态创建测试**

```java
@Test
void createTask_accountDynamicAllowsOnlineTakeoverLifecycleStates() {
    for (int accountState : List.of(
            AccountStateCode.LOGIN_REPLACED,
            AccountStateCode.TAKING_OVER)) {
        Fixture fixture = seedTakeoverFixture(
                "takeover-dynamic-" + accountState,
                accountState,
                AccountLoginStateCode.ONLINE);

        MarketingTaskVO created = service.createTask(request(
                "抢登动态任务-" + accountState,
                fixture.accountGroupId(),
                fixture.templateId(),
                "PENDING",
                List.of(new MarketingSelectionDTO(
                        fixture.accountId(),
                        "ACCOUNT_DYNAMIC",
                        List.of()))));

        assertThat(created.selectedAccountCount()).isEqualTo(1);
        assertThat(created.targetPairCount()).isEqualTo(1);
    }
}
```

- [ ] **Step 3: 写在线被抢登与抢登中固定群组创建测试**

```java
@Test
void createTask_fixedGroupAllowsOnlineTakeoverLifecycleStates() {
    for (int accountState : List.of(
            AccountStateCode.LOGIN_REPLACED,
            AccountStateCode.TAKING_OVER)) {
        Fixture fixture = seedTakeoverFixture(
                "takeover-fixed-" + accountState,
                accountState,
                AccountLoginStateCode.ONLINE);

        MarketingTaskVO created = service.createTask(request(
                "抢登固定群任务-" + accountState,
                fixture.accountGroupId(),
                fixture.templateId(),
                "PENDING",
                List.of(new MarketingSelectionDTO(
                        fixture.accountId(),
                        List.of(fixture.groupLinkId())))));

        assertThat(created.selectedAccountCount()).isEqualTo(1);
        assertThat(created.targetPairCount()).isEqualTo(1);
    }
}
```

- [ ] **Step 4: 写离线抢登中仍拒绝的测试**

```java
@Test
void createTask_rejectsOfflineTakingOverAccount() {
    Fixture fixture = seedTakeoverFixture(
            "takeover-offline",
            AccountStateCode.TAKING_OVER,
            AccountLoginStateCode.OFFLINE);
    CreateMarketingTaskDTO req = request(
            "离线抢登中任务",
            fixture.accountGroupId(),
            fixture.templateId(),
            "PENDING",
            List.of(new MarketingSelectionDTO(
                    fixture.accountId(),
                    "ACCOUNT_DYNAMIC",
                    List.of())));

    assertThatThrownBy(() -> service.createTask(req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("账号不可用");
}
```

- [ ] **Step 5: 运行真库 DbTest**

Run:

```bash
cd armada-api
./dbtest.sh 'MarketingTaskCreateReadDbTest#createTask_accountDynamicAllowsOnlineTakeoverLifecycleStates'
./dbtest.sh 'MarketingTaskCreateReadDbTest#createTask_fixedGroupAllowsOnlineTakeoverLifecycleStates'
./dbtest.sh 'MarketingTaskCreateReadDbTest#createTask_rejectsOfflineTakingOverAccount'
```

Expected: 三条 DbTest 全部 PASS；两种在线抢登状态可创建两类目标，离线抢登中被拒绝。

- [ ] **Step 6: 提交真库回归测试**

```bash
git add armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java
git commit -m "test: cover takeover marketing target creation"
```

### Task 4: 完成验证、变更记录与交付

**Files:**
- Modify: `.harness/changes/2026-07-17-marketing-takeover-account-selection.md`

- [ ] **Step 1: 运行聚焦回归**

Run:

```bash
cd armada-api
mvn -Dtest=MarketingAccountTreeRealtimeServiceTest,MarketingTaskMapperSqlShapeTest,MarketingTaskServiceImplLifecycleTest test
```

Expected: BUILD SUCCESS，三个测试类全部通过。

- [ ] **Step 2: 运行营销模块相关测试**

Run:

```bash
cd armada-api
mvn test
```

Expected: BUILD SUCCESS；完整 Maven 测试通过。

- [ ] **Step 3: 检查差异与仓库状态**

Run:

```bash
git diff --check
git status --short
git diff --stat HEAD~3..HEAD
```

Expected: `git diff --check` 无输出；状态中只保留用户原有 `.claude/worktrees/*` 项，不出现未提交的本任务代码。

- [ ] **Step 4: 更新变更记录**

把 `.harness/changes/2026-07-17-marketing-takeover-account-selection.md` 的任务清单全部勾选，并在“验证”中记录实际执行命令、测试数和 BUILD SUCCESS/失败原因；状态改为“已完成”，部署仍记“未部署”。

- [ ] **Step 5: 提交验证记录**

```bash
git add .harness/changes/2026-07-17-marketing-takeover-account-selection.md
git commit -m "docs: record takeover marketing verification"
```

- [ ] **Step 6: 最终核对**

Run:

```bash
git log -5 --oneline
git status --short --branch
```

Expected: 本任务提交完整，未覆盖或提交用户原有 `.claude/worktrees/*` 变更，未执行部署。
