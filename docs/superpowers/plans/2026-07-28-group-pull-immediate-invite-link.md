# Group Pull Immediate Invite Link Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 拉群营销在群创建成功后立即尽力获取并保存邀请链接，同时保留 `SAVE_GROUP_INFO` 阶段的缺链兜底查询。

**Architecture:** 保持现有建群结果事务不变，在 `group_jid` 成功落库后执行一次非阻断邀请链接读取，并通过 JID 匹配、仅空值可写的 Mapper SQL 保存。完整群信息阶段优先复用已保存链接，只有链接仍为空时才调用协议层补取，因此无需修改前端、协议层、数据库结构或阶段枚举。

**Tech Stack:** Java 17、Spring Boot 3.3.5、MyBatis XML、JUnit 5、Mockito、AssertJ、Maven、H2 MySQL mode

---

## 实施约束

- 事实规格：`docs/superpowers/specs/2026-07-28-group-pull-immediate-invite-link-design.md`。
- 当前工作区的 `GroupPullMarketingExecutionWorker.java`、`GroupPullMarketingExecutionWorkerTest.java` 等文件已有另一组“任务释放收敛”在途修改。必须在当前内容上追加最小差异，不得恢复、覆盖或重排这些修改。
- 不修改 `wheel-saas-pure-web/`、`armada-protocol/`、Flyway 迁移或数据库表结构。
- 不回填历史执行，不连接共享数据库，不部署。
- 因 Worker 已含其他未提交修改，不得用整文件 `git add`/`git commit` 把它们混入本次提交；完成后保留精确 diff 并向用户说明。若执行时这些在途修改已由其所属会话提交，再按当时实际状态决定是否创建本次实现提交。

## 文件职责

- `armada-api/src/main/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingMapper.java`：声明立即保存邀请链接的条件更新接口。
- `armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml`：实现 JID 匹配且仅空值可写的幂等 SQL。
- `armada-api/src/test/java/com/armada/marketing/grouppull/GroupPullMarketingRecoveryDbTest.java`：用真实 Mapper/H2 验证条件写入、拒绝错 JID 和禁止覆盖。
- `armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingExecutionWorker.java`：建群落库后立即取链，完整群信息阶段复用或兜底。
- `armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingInviteCaptureTest.java`：集中验证立即取链、失败非阻断和已有链接复用。

### Task 1: 用真库测试锁定邀请链接条件写入

**Files:**
- Modify: `armada-api/src/test/java/com/armada/marketing/grouppull/GroupPullMarketingRecoveryDbTest.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml`

- [ ] **Step 1: 写入会因 Mapper 方法不存在而失败的真库测试**

在 `GroupPullMarketingRecoveryDbTest` 的 `repeatedRecoveryDoesNotPersistGroupMaterialOrTerminalResultTwice` 之后加入：

```java
    @Test
    void initialInviteUrlWriteRequiresMatchingGroupAndDoesNotOverwrite() {
        long executionId = insertExecution(3, "立即取链测试群");
        long now = System.currentTimeMillis();
        String groupJid = "invite-" + executionId + "@g.us";

        assertThat(mapper.markGroupCreated(new GroupPullMarketingMapper.GroupCreatedUpdate(
                executionId, 3, groupJid, 4, now, now))).isEqualTo(1);
        assertThat(mapper.saveInitialGroupInviteUrl(
                executionId,
                "other-" + executionId + "@g.us",
                "https://chat.whatsapp.com/wrong",
                now + 1)).isZero();
        assertThat(mapper.saveInitialGroupInviteUrl(
                executionId,
                groupJid,
                "https://chat.whatsapp.com/first",
                now + 2)).isEqualTo(1);
        assertThat(mapper.saveInitialGroupInviteUrl(
                executionId,
                groupJid,
                "https://chat.whatsapp.com/replacement",
                now + 3)).isZero();

        assertThat(jdbc.queryForObject(
                "SELECT group_invite_url FROM group_pull_marketing_execution WHERE id = ?",
                String.class,
                executionId)).isEqualTo("https://chat.whatsapp.com/first");
    }
```

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```bash
cd armada-api && mvn -Dtest='GroupPullMarketingRecoveryDbTest#initialInviteUrlWriteRequiresMatchingGroupAndDoesNotOverwrite' test
```

Expected: 编译失败，明确提示 `GroupPullMarketingMapper` 没有 `saveInitialGroupInviteUrl`；失败必须来自待实现行为，而不是测试语法或环境问题。

- [ ] **Step 3: 声明最小 Mapper 接口**

在 `markGroupCreated` 之后加入：

```java
    /** 建群结果落库后，仅在相同群 JID 尚无邀请链接时保存首次取链结果。 */
    int saveInitialGroupInviteUrl(@Param("id") Long id,
                                  @Param("groupJid") String groupJid,
                                  @Param("inviteUrl") String inviteUrl,
                                  @Param("now") long now);
```

- [ ] **Step 4: 实现只写空值且 JID 必须匹配的 SQL**

在 `GroupPullMarketingMapper.xml` 的 `markGroupCreated` 后加入：

```xml
    <update id="saveInitialGroupInviteUrl">
        UPDATE group_pull_marketing_execution
        SET group_invite_url = #{inviteUrl},
            updated_at = #{now}
        WHERE id = #{id}
          AND group_jid = #{groupJid}
          AND group_invite_url IS NULL
    </update>
```

不要增加执行状态条件：任务释放与立即取链并发时，只要 ID/JID 仍对应同一个已创建群，保存已经取得的链接仍是安全且有价值的。

- [ ] **Step 5: 校验 XML 并确认 GREEN**

Run:

```bash
xmllint --noout armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml
cd armada-api && mvn -Dtest='GroupPullMarketingRecoveryDbTest#initialInviteUrlWriteRequiresMatchingGroupAndDoesNotOverwrite' test
```

Expected: `xmllint` 退出码 0；Maven 输出该测试 `Tests run: 1, Failures: 0, Errors: 0` 并 `BUILD SUCCESS`。

- [ ] **Step 6: 只检查本任务三处 Mapper 差异**

Run:

```bash
git diff --check -- \
  armada-api/src/main/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingMapper.java \
  armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml \
  armada-api/src/test/java/com/armada/marketing/grouppull/GroupPullMarketingRecoveryDbTest.java
```

Expected: 退出码 0、无输出。仅当这三个文件在执行开始时和此刻都没有其他会话修改时，才可单独提交：

```bash
git add \
  armada-api/src/main/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingMapper.java \
  armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml \
  armada-api/src/test/java/com/armada/marketing/grouppull/GroupPullMarketingRecoveryDbTest.java
git commit -m "fix(marketing): 幂等保存拉群邀请链接"
```

若任一文件出现非本任务在途修改，跳过提交并保持工作区内容不丢失。

### Task 2: 建群落库后立即取链且失败不阻断

**Files:**
- Create: `armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingInviteCaptureTest.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingExecutionWorker.java`

- [ ] **Step 1: 新建聚焦邀请链接时序的失败测试**

创建完整测试文件：

```java
package com.armada.marketing.grouppull.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.marketing.grouppull.mapper.GroupPullMarketingMapper;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingExecution;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingTask;
import com.armada.marketing.grouppull.model.enums.GroupPullExecutionStage;
import com.armada.marketing.grouppull.model.enums.GroupPullExecutionStatus;
import com.armada.marketing.grouppull.model.enums.GroupPullResourceStatus;
import com.armada.marketing.grouppull.model.vo.GroupPullAccountRefRow;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.GroupCreateCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.result.GroupCreateResult;
import com.armada.platform.protocol.model.result.GroupInviteResult;
import com.armada.platform.protocol.port.ContactPort;
import com.armada.platform.protocol.port.GroupCreatePort;
import com.armada.platform.protocol.port.GroupInvitePort;
import com.armada.platform.protocol.port.GroupLeavePort;
import com.armada.platform.protocol.port.GroupMemberListPort;
import com.armada.platform.protocol.port.GroupParticipantPort;
import com.armada.platform.protocol.port.GroupSettingsPort;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** 拉群建群结果落库后的邀请链接捕获与兜底测试。 */
@ExtendWith(MockitoExtension.class)
class GroupPullMarketingInviteCaptureTest {

    private static final PlatformTransactionManager NO_OP_TRANSACTION_MANAGER =
            new PlatformTransactionManager() {
                @Override
                public TransactionStatus getTransaction(TransactionDefinition definition) {
                    return new SimpleTransactionStatus();
                }

                @Override
                public void commit(TransactionStatus status) {
                    // 测试只验证事务回调触发的 Mapper 行为。
                }

                @Override
                public void rollback(TransactionStatus status) {
                    // 测试只验证事务回调触发的 Mapper 行为。
                }
            };

    @Mock private GroupPullMarketingMapper mapper;
    @Mock private GroupPullMarketingFinalizer finalizer;
    @Mock private GroupLinkRegistryService groupRegistry;
    @Mock private ContactPort contactPort;
    @Mock private GroupCreatePort groupCreatePort;
    @Mock private GroupParticipantPort participantPort;
    @Mock private GroupSettingsPort settingsPort;
    @Mock private GroupMemberListPort memberListPort;
    @Mock private GroupInvitePort invitePort;
    @Mock private GroupLeavePort leavePort;
    @Mock private GroupPullMarketingMaterialEntryService materialEntryService;

    private GroupPullMarketingExecutionWorker worker;

    @BeforeEach
    void setUp() {
        GroupPullMaterialEntryDelayPolicy delayPolicy =
                new GroupPullMaterialEntryDelayPolicy((origin, bound) -> origin);
        worker = new GroupPullMarketingExecutionWorker(
                mapper, finalizer, groupRegistry, contactPort, groupCreatePort,
                participantPort, settingsPort, memberListPort, invitePort, leavePort,
                materialEntryService, delayPolicy, NO_OP_TRANSACTION_MANAGER);
    }

    @Test
    void capturesInviteImmediatelyAfterCreatedGroupIsPersisted() {
        GroupPullMarketingExecution execution = execution(GroupPullExecutionStage.CREATE_GROUP);
        stubDispatch(execution, builder());
        when(mapper.selectAccountRef(301L)).thenReturn(marketer());
        when(groupCreatePort.create(any(GroupCreateCommand.class)))
                .thenReturn(new GroupCreateResult("group@g.us", false, List.of()));
        when(mapper.markGroupCreated(any())).thenReturn(1);
        when(invitePort.getInvite(any(ProtocolAccountRef.class), eq("group@g.us")))
                .thenReturn(new GroupInviteResult(
                        "group@g.us", "invite-code", "https://chat.whatsapp.com/invite-code"));
        when(mapper.saveInitialGroupInviteUrl(
                eq(501L), eq("group@g.us"),
                eq("https://chat.whatsapp.com/invite-code"), anyLong())).thenReturn(1);
        when(mapper.updateBlockReason(eq(101L), anyInt(), anyLong())).thenReturn(1);

        worker.process(501L);

        InOrder order = inOrder(mapper, invitePort);
        order.verify(mapper).markGroupCreated(any());
        order.verify(invitePort).getInvite(any(ProtocolAccountRef.class), eq("group@g.us"));
        order.verify(mapper).saveInitialGroupInviteUrl(
                eq(501L), eq("group@g.us"),
                eq("https://chat.whatsapp.com/invite-code"), anyLong());
        verify(finalizer, never()).fail(anyLong(), anyString());
    }

    @Test
    void immediateInviteFailureDoesNotFailCreatedExecution() {
        GroupPullMarketingExecution execution = execution(GroupPullExecutionStage.CREATE_GROUP);
        stubDispatch(execution, builder());
        when(mapper.selectAccountRef(301L)).thenReturn(marketer());
        when(groupCreatePort.create(any(GroupCreateCommand.class)))
                .thenReturn(new GroupCreateResult("group@g.us", false, List.of()));
        when(mapper.markGroupCreated(any())).thenReturn(1);
        when(invitePort.getInvite(any(ProtocolAccountRef.class), eq("group@g.us")))
                .thenThrow(new ProtocolException(
                        ProtocolErrorCode.TEMPORARY_FAILURE, "expected immediate failure"));
        when(mapper.updateBlockReason(eq(101L), anyInt(), anyLong())).thenReturn(1);

        worker.process(501L);

        verify(mapper).markGroupCreated(any());
        verify(mapper, never()).saveInitialGroupInviteUrl(
                anyLong(), anyString(), anyString(), anyLong());
        verify(finalizer, never()).fail(anyLong(), anyString());
    }

    @Test
    void saveGroupInfoReusesInitialInviteWithoutProtocolCall() {
        GroupPullMarketingExecution execution = execution(GroupPullExecutionStage.SAVE_GROUP_INFO);
        execution.setGroupJid("group@g.us");
        execution.setGroupInviteUrl("https://chat.whatsapp.com/already-saved");
        stubDispatch(execution, builder());
        when(mapper.selectAccountRef(301L)).thenReturn(marketer());
        when(memberListPort.list(any())).thenReturn(List.of());
        when(groupRegistry.registerSelfBuiltGroup(
                eq("group@g.us"), eq("邀请链接测试群"), eq(201L),
                eq("8613800000201"), eq(0), anyLong())).thenReturn(801L);
        when(mapper.saveGroupInfo(
                eq(501L), eq(801L), anyString(), eq(0), isNull(), anyLong()))
                .thenReturn(1);
        when(mapper.advanceExecutionStage(
                eq(501L), eq(2), eq(8), eq(9), eq(2), anyLong(), anyLong()))
                .thenReturn(1);
        ArgumentCaptor<String> inviteUrl = ArgumentCaptor.forClass(String.class);

        worker.process(501L);

        verifyNoInteractions(invitePort);
        verify(mapper).saveGroupInfo(
                eq(501L), eq(801L), inviteUrl.capture(), eq(0), isNull(), anyLong());
        assertThat(inviteUrl.getValue())
                .isEqualTo("https://chat.whatsapp.com/already-saved");
    }

    private void stubDispatch(
            GroupPullMarketingExecution execution,
            GroupPullAccountRefRow builder) {
        when(mapper.selectExecutionById(501L)).thenReturn(execution);
        when(mapper.tryLeaseExecution(eq(501L), anyInt(), anyInt(), anyLong(), anyLong()))
                .thenReturn(1);
        when(mapper.selectTaskById(101L)).thenReturn(task());
        when(mapper.selectAccountRef(201L)).thenReturn(builder);
    }

    private static GroupPullMarketingExecution execution(GroupPullExecutionStage stage) {
        GroupPullMarketingExecution execution = new GroupPullMarketingExecution();
        execution.setId(501L);
        execution.setTaskId(101L);
        execution.setBuilderAccountId(201L);
        execution.setMarketingAccountId(301L);
        execution.setGroupName("邀请链接测试群");
        execution.setExecutionStatus(stage == GroupPullExecutionStage.CREATE_GROUP
                ? GroupPullExecutionStatus.PREPARING.code()
                : GroupPullExecutionStatus.EXECUTING.code());
        execution.setCurrentStage(stage.code());
        execution.setStageRetryCount(0);
        execution.setNextExecuteAt(0L);
        return execution;
    }

    private static GroupPullMarketingTask task() {
        GroupPullMarketingTask task = new GroupPullMarketingTask();
        task.setMarketingTaskId(101L);
        task.setGroupNamePrefix("邀请链接测试群");
        task.setMaterialEntryIntervalSeconds(300);
        task.setResourceStatus(GroupPullResourceStatus.LOCKED.code());
        return task;
    }

    private static GroupPullAccountRefRow builder() {
        return account(201L, "8613800000201");
    }

    private static GroupPullAccountRefRow marketer() {
        return account(301L, "8613800000301");
    }

    private static GroupPullAccountRefRow account(Long id, String phone) {
        GroupPullAccountRefRow account = new GroupPullAccountRefRow();
        account.setAccountId(id);
        account.setWsPhone(phone);
        account.setProtocolId("WEB");
        account.setProtocolAccountId("acc-" + id);
        account.setAccountState(AccountStateCode.NORMAL);
        account.setLoginState(AccountLoginStateCode.ONLINE);
        return account;
    }
}
```

- [ ] **Step 2: 运行新测试类并确认 RED**

Run:

```bash
cd armada-api && mvn -Dtest='GroupPullMarketingInviteCaptureTest' test
```

Expected: 当前 Worker 不会在建群后立即调用 `GroupInvitePort`，且 `SAVE_GROUP_INFO` 会无条件重新取链，因此测试失败。Mapper 方法已在 Task 1 存在，不能接受“方法不存在”作为本阶段 RED。

- [ ] **Step 3: 在建群结果落库后追加非阻断立即取链**

在 `createGroup` 成功分支保持 `saveCreatedGroup` 在前：

```java
                saveCreatedGroup(execution, marketer, task, result);
                captureInviteAfterCreate(execution, builder, result.groupJid());
                mapper.updateBlockReason(
                        execution.getTaskId(),
                        GroupPullBlockReason.NONE.code(),
                        System.currentTimeMillis());
```

在 `saveCreatedGroup` 后加入：

```java
    private void captureInviteAfterCreate(
            GroupPullMarketingExecution execution,
            GroupPullAccountRefRow builder,
            String groupJid) {
        try {
            GroupInviteResult invite = invitePort.getInvite(builder.protocolRef(), groupJid);
            String inviteUrl = invite == null ? null : invite.inviteUrl();
            if (!StringUtils.hasText(inviteUrl)) {
                throw new IllegalStateException("协议层未返回群邀请链接");
            }
            if (mapper.saveInitialGroupInviteUrl(
                    execution.getId(), groupJid, inviteUrl, System.currentTimeMillis()) != 1) {
                log.warn(
                        "拉群营销建群后邀请链接未写入 executionId={} groupJid={}",
                        execution.getId(),
                        groupJid);
            }
        } catch (RuntimeException exception) {
            log.warn(
                    "拉群营销建群后立即获取群链接失败 executionId={} reason={}",
                    execution.getId(),
                    compactReason(exception));
        }
    }
```

辅助方法必须吞掉协议读取和条件写入异常；不得让异常回到建群重试循环，否则可能对已创建群错误地再次执行建群。

- [ ] **Step 4: 让完整群信息阶段优先复用已有链接**

把 `saveGroupInfo` 中的链接初始化改为：

```java
        List<GroupParticipantResult> members = null;
        String inviteUrl = StringUtils.hasText(execution.getGroupInviteUrl())
                ? execution.getGroupInviteUrl()
                : null;
        List<String> nonFatalReasons = new ArrayList<>();
```

把当前无条件取链改为仅缺链时补取：

```java
        if (!StringUtils.hasText(inviteUrl)) {
            try {
                GroupInviteResult invite = invitePort.getInvite(
                        builder.protocolRef(), execution.getGroupJid());
                inviteUrl = invite == null ? null : invite.inviteUrl();
            } catch (RuntimeException exception) {
                nonFatalReasons.add("群链接获取失败：" + compactReason(exception));
            }
        }
```

保留现有成员查询、任务释放检查、`groupRegistry` 登记、`mapper.saveGroupInfo` 和阶段推进代码。不要移动群组池登记时机。

- [ ] **Step 5: 运行新测试并确认 GREEN**

Run:

```bash
cd armada-api && mvn -Dtest='GroupPullMarketingInviteCaptureTest' test
```

Expected: `Tests run: 3, Failures: 0, Errors: 0` 且 `BUILD SUCCESS`，Mockito 不报告多余协议交互。

- [ ] **Step 6: 检查与在途 Worker 修改的边界**

Run:

```bash
git diff --check -- \
  armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingExecutionWorker.java \
  armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingInviteCaptureTest.java
git diff -- armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingExecutionWorker.java
```

Expected: `diff --check` 无输出；Worker diff 保留原有任务释放检查，只新增立即取链辅助方法及 `SAVE_GROUP_INFO` 的已有链接复用逻辑。由于 Worker 在实施前已脏，本任务不得整文件提交它。

### Task 3: 拉群营销定向回归与全量验证

**Files:**
- Verify only: `armada-api/src/main/java/com/armada/marketing/grouppull/**`
- Verify only: `armada-api/src/test/java/com/armada/marketing/grouppull/**`

- [ ] **Step 1: 运行 Mapper 与 Worker 定向测试**

Run:

```bash
cd armada-api && mvn -Dtest='GroupPullMarketingRecoveryDbTest,GroupPullMarketingMapperDbTest,GroupPullMarketingInviteCaptureTest,GroupPullMarketingExecutionWorkerTest,GroupPullMarketingFirstMaterialDelayTest' test
```

Expected: 所列测试全部通过，Maven 汇总 `Failures: 0, Errors: 0` 并 `BUILD SUCCESS`。

- [ ] **Step 2: 运行后端全量测试**

Run:

```bash
cd armada-api && mvn test
```

Expected: Maven 最终输出 `BUILD SUCCESS`，全量汇总无 failure/error。若失败，记录具体测试、判断是否由本次改动引入并先修复，不能跳过。

- [ ] **Step 3: 最终检查只修改 Armada 范围且无格式错误**

Run:

```bash
git status --short
git diff --check
git diff --name-only
```

Expected: 本任务新增/修改文件均在 `armada/`；没有 `wheel-saas-pure-web/`、`armada-protocol/`、Flyway 或表结构改动；`git diff --check` 退出码 0。状态中原有其他会话修改必须原样保留并在交付说明中单独列出。

- [ ] **Step 4: 形成交付说明，不部署**

交付说明必须包含：立即取链时机、首次失败非阻断、阶段 8 兜底、实际测试命令与结果、工作区仍存在的其他在途修改。不运行部署脚本，不修改测试环境数据。
