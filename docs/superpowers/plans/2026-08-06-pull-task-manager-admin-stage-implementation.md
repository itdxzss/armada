# Pull Task Manager Admin Stage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** 在普通群链接拉群中增加独立“管理员设置”阶段，由群内已有的我方群主/管理员把任务管理员提权后再邀请拉手，并在提权异常时可恢复重试、清晰展示，同时修复失败拉手被重复占用的问题。

**Architecture:** Armada 后端以 account_group_membership 产生候选，以实时群成员列表确认候选和目标权限，以 pull_task_group_account / pull_task_account_action 记录 PROMOTER 和 PROMOTE_MANAGER 审计事实，并通过现有 Outbox、group.participants.requested 和 group.action_result_reported 契约执行提权。协议成功仅唤醒复核，只有实时成员列表确认任务管理员为管理员/群主才推进到联系人阶段；稳定失败轮换候选，临时失败按 attempt_no 重试。前端继续以执行行 reasonCode/reasonMessage 为事实，增加阶段、角色、动作和异常映射。

**Tech Stack:** Java 17、Spring Boot 3、MyBatis/MySQL/Flyway、JUnit 5/AssertJ/Mockito、TypeScript、Baileys 7、Jest、Vue 3、Element Plus、Node test runner。

---

## Repository and file map

本功能横跨三个仓库，但属于同一条端到端状态机。每个仓库单独提交，不把跨仓文件放进同一个 Git commit。

- Backend repository: /Users/daishuaishuai/IdeaProjects/armada
  - armada-api/src/main/resources/db/migration/V101__pull_task_manager_admin_stage.sql：阶段和审计字段迁移、活动执行行回退。
  - armada-api/src/main/java/com/armada/task/model：阶段、角色、动作、原因码和回调 DTO。
  - armada-api/src/main/java/com/armada/task/scheduler：管理员设置处理器、短事务、路由和资源恢复。
  - armada-api/src/main/java/com/armada/group/mapper：按 tenantId + groupJid 查我方可执行管理员候选。
  - armada-api/src/main/java/com/armada/platform/protocol 与 task/service/impl：Outbox 引用、payload 补全和结果适配。
  - armada-api/src/test/java：迁移、Mapper、processor、事务、回调和读模型测试。
- Protocol repository: /Users/daishuaishuai/IdeaProjects/armada-protocol
  - protocol-layer/src/commands/pull-task-action.ts：participants source 唯一契约。
  - protocol-layer/src/commands/group-participants-executor.ts：PROMOTE 执行和错误语义。
  - 对应 Jest 测试：source 校验、错误映射、幂等缓存和 owner 缺失兜底。
- Frontend repository: /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
  - src/views/task/pull-task/constants.ts：1～8 阶段和中文标签。
  - src/views/task/pull-task/composables/usePullTaskPage.ts：管理员设置异常映射。
  - src/views/task/pull-task/components/PullTaskExecutionDetailDrawer.vue：PROMOTER、PROMOTE_MANAGER 和管理员权限状态。
  - src/api/pull-task.ts：详情角色 adminStatus 类型。

### Task 1: Add the schema contract and renumbered enums

**Files:**
- Create: armada-api/src/main/resources/db/migration/V101__pull_task_manager_admin_stage.sql
- Create: armada-api/src/test/java/com/armada/task/PullTaskManagerAdminStageMigrationSqlTest.java
- Modify: armada-api/src/test/java/com/armada/task/mapper/PullTaskNormalLinkSchema.java
- Modify: armada-api/src/main/java/com/armada/task/model/enums/PullTaskExecutionStage.java
- Modify: armada-api/src/main/java/com/armada/task/model/enums/PullTaskExecutionReasonCode.java
- Modify: armada-api/src/main/java/com/armada/task/model/enums/PullTaskGroupAccountRole.java
- Modify: armada-api/src/main/java/com/armada/task/model/enums/PullTaskAccountActionType.java
- Modify: armada-api/src/main/java/com/armada/task/model/entity/PullTaskAccountAction.java
- Modify: armada-api/src/main/java/com/armada/task/mapper/PullTaskAccountActionMapper.java
- Modify: armada-api/src/main/resources/mapper/task/PullTaskAccountActionMapper.xml

- [ ] **Step 1: Write the failing migration and enum contract test**

~~~java
class PullTaskManagerAdminStageMigrationSqlTest {
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V101__pull_task_manager_admin_stage.sql");

    @Test
    void addsRetryFactsAndRewindsOnlyActiveManagersNeedingPromotion() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");
        assertThat(sql)
                .contains("ADD COLUMN attempt_no INT NOT NULL DEFAULT 0")
                .contains("ADD COLUMN retryable TINYINT(1) DEFAULT NULL")
                .contains("WHEN stage BETWEEN 3 AND 7 THEN stage + 1")
                .contains("manager_row.membership_status = 2")
                .contains("COALESCE(manager_row.admin_status, 0) <> 3")
                .contains("execution_row.execution_status IN (1, 2, 3)")
                .contains("task_row.status NOT IN ('COMPLETED', 'ENDED')")
                .contains("wait_resource_type = NULL")
                .contains("next_run_at = 0");
    }

    @Test
    void javaEnumsMatchTheEightPersistedStages() {
        assertThat(PullTaskExecutionStage.values())
                .extracting(PullTaskExecutionStage::code)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(PullTaskExecutionStage.MANAGER_ADMIN.code()).isEqualTo(3);
        assertThat(PullTaskGroupAccountRole.PROMOTER.code()).isEqualTo(4);
        assertThat(PullTaskAccountActionType.PROMOTE_MANAGER.code()).isEqualTo(4);
    }
}
~~~

- [ ] **Step 2: Run the test and verify it fails**

Run from armada/armada-api:

~~~bash
mvn -Dtest=PullTaskManagerAdminStageMigrationSqlTest test
~~~

Expected: FAIL because V101 and MANAGER_ADMIN/PROMOTER/PROMOTE_MANAGER do not exist.

- [ ] **Step 3: Implement the guarded migration**

V101 must:

~~~sql
-- 1. Guard each ADD COLUMN with information_schema.columns.
ALTER TABLE pull_task_account_action
  ADD COLUMN attempt_no INT NOT NULL DEFAULT 0
    COMMENT '当前命令尝试序号;每次提交新commandId递增' AFTER command_id,
  ADD COLUMN retryable TINYINT(1) DEFAULT NULL
    COMMENT '最近结果是否允许业务重试' AFTER reason_message;

-- 2. Renumber every historical stage once so old history keeps its meaning.
UPDATE pull_task_group_execution
SET stage = CASE WHEN stage BETWEEN 3 AND 7 THEN stage + 1 ELSE stage END;

-- 3. Rewind only non-terminal normal-link rows whose task manager is in-group
--    but has not been confirmed as admin.
UPDATE pull_task_group_execution execution_row
JOIN pull_task task_row
  ON task_row.tenant_id = execution_row.tenant_id
 AND task_row.id = execution_row.task_id
SET execution_row.execution_status = 2,
    execution_row.stage = 3,
    execution_row.wait_resource_type = NULL,
    execution_row.reason_code = NULL,
    execution_row.reason_message = NULL,
    execution_row.next_run_at = 0,
    execution_row.updated_at = CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)
WHERE execution_row.execution_status IN (1, 2, 3)
  AND task_row.task_type = 'STANDARD'
  AND task_row.mode = 'NORMAL_LINK'
  AND task_row.status NOT IN ('COMPLETED', 'ENDED')
  AND EXISTS (
    SELECT 1
    FROM pull_task_group_account manager_row
    WHERE manager_row.tenant_id = execution_row.tenant_id
      AND manager_row.group_execution_id = execution_row.id
      AND manager_row.role_type = 1
      AND manager_row.membership_status = 2
      AND COALESCE(manager_row.admin_status, 0) <> 3
  );
~~~

Use PREPARE/EXECUTE guards for table/column existence, and guarded MODIFY COLUMN statements for the stage, role_type and action_type comments. Do not alter completed, failed or abandoned execution outcomes.

- [ ] **Step 4: Update Java enums, entity and mapper columns**

Use these persisted values:

~~~java
public enum PullTaskExecutionStage {
    LINK_VALIDATION(1),
    MANAGER_JOIN(2),
    MANAGER_ADMIN(3),
    MANAGER_PULLER_CONTACT(4),
    PULLER_INVITE(5),
    PULL_EXECUTION(6),
    MATERIAL_ADMIN(7),
    CLOSING(8);
}

public enum PullTaskGroupAccountRole {
    MANAGER(1), PULLER(2), STATION(3), PROMOTER(4);
}

public enum PullTaskAccountActionType {
    SAVE_CONTACT(1), INVITE_TO_GROUP(2), JOIN_BY_LINK(3), PROMOTE_MANAGER(4);
}
~~~

Add these entity properties and accessors:

~~~java
private Integer attemptNo;
private Boolean retryable;

public Integer getAttemptNo() {
    return attemptNo;
}

public void setAttemptNo(Integer attemptNo) {
    this.attemptNo = attemptNo;
}

public Boolean getRetryable() {
    return retryable;
}

public void setRetryable(Boolean retryable) {
    this.retryable = retryable;
}
~~~

Persist and read them through PullTaskAccountActionMapper.xml. Add a manager-admin-specific CAS that increments the attempt and replaces commandId:

~~~java
int submitAttempt(@Param("id") long id,
                  @Param("expectedStatuses") List<Integer> expectedStatuses,
                  @Param("commandId") String commandId,
                  @Param("now") long now);

int transitionManagerAdminResult(
        @Param("id") long id,
        @Param("commandId") String commandId,
        @Param("attemptNo") int attemptNo,
        @Param("expectedStatuses") List<Integer> expectedStatuses,
        @Param("targetStatus") int targetStatus,
        @Param("retryable") boolean retryable,
        @Param("reasonCode") String reasonCode,
        @Param("reasonMessage") String reasonMessage,
        @Param("now") long now);
~~~

~~~xml
<update id="submitAttempt">
  UPDATE pull_task_account_action
  SET action_status = 2,
      command_id = #{commandId},
      attempt_no = attempt_no + 1,
      retryable = NULL,
      reason_code = NULL,
      reason_message = NULL,
      submitted_at = #{now},
      result_at = NULL,
      updated_at = #{now}
  WHERE id = #{id}
    AND action_status IN
    <foreach collection="expectedStatuses" item="status"
             open="(" separator="," close=")">#{status}</foreach>
</update>

<update id="transitionManagerAdminResult">
  UPDATE pull_task_account_action
  SET action_status = #{targetStatus},
      retryable = #{retryable},
      reason_code = #{reasonCode},
      reason_message = #{reasonMessage},
      result_at = #{now},
      updated_at = #{now}
  WHERE id = #{id}
    AND command_id = #{commandId}
    AND attempt_no = #{attemptNo}
    AND action_status IN
    <foreach collection="expectedStatuses" item="status"
             open="(" separator="," close=")">#{status}</foreach>
</update>
~~~

- [ ] **Step 5: Update the H2 mirror and run focused tests**

Add attempt_no and retryable to PullTaskNormalLinkSchema.ACCOUNT_ACTION. Then run:

~~~bash
mvn -Dtest=PullTaskManagerAdminStageMigrationSqlTest,PullTaskAccountActionMapperInMemoryTest,PullTaskNormalLinkSchemaSelfTest test
~~~

Expected: PASS.

- [ ] **Step 6: Commit the backend schema contract**

~~~bash
git add armada-api/src/main/resources/db/migration/V101__pull_task_manager_admin_stage.sql armada-api/src/main/java/com/armada/task/model/enums/PullTaskExecutionStage.java armada-api/src/main/java/com/armada/task/model/enums/PullTaskExecutionReasonCode.java armada-api/src/main/java/com/armada/task/model/enums/PullTaskGroupAccountRole.java armada-api/src/main/java/com/armada/task/model/enums/PullTaskAccountActionType.java armada-api/src/main/java/com/armada/task/model/entity/PullTaskAccountAction.java armada-api/src/main/java/com/armada/task/mapper/PullTaskAccountActionMapper.java armada-api/src/main/resources/mapper/task/PullTaskAccountActionMapper.xml armada-api/src/test/java/com/armada/task/PullTaskManagerAdminStageMigrationSqlTest.java armada-api/src/test/java/com/armada/task/mapper/PullTaskNormalLinkSchema.java
git commit -m "feat: add pull task manager admin schema"
~~~

### Task 2: Select self-controlled in-group admin candidates

**Files:**
- Modify: armada-api/src/main/java/com/armada/group/mapper/AccountGroupMembershipMapper.java
- Modify: armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml
- Modify: armada-api/src/test/java/com/armada/group/mapper/AccountGroupMembershipMapperSqlTest.java

- [ ] **Step 1: Write the failing SQL contract test**

~~~java
@Test
void pullTaskPromoterCandidatesAreTenantGroupAndPermissionScoped() throws IOException {
    String xml = mapperXml();
    int start = xml.indexOf(
            "<select id=\"selectPullTaskAdminPromoterCandidatesByTenant\"");
    int end = xml.indexOf("</select>", start);
    assertTrue(start >= 0 && end > start);
    String query = xml.substring(start, end);
    assertTrue(query.contains("a.tenant_id = #{tenantId}"));
    assertTrue(query.contains("m.group_jid = #{groupJid}"));
    assertTrue(query.contains("m.membership_status = #{inGroupStatus}"));
    assertTrue(query.contains("m.is_admin = 1"));
    assertTrue(query.contains("a.id <> #{managerAccountId}"));
    assertTrue(query.contains("s.login_state = #{onlineLoginState}"));
    assertTrue(query.contains("s.account_state = #{normalAccountState}"));
    assertTrue(query.contains("(s.risk_status IS NULL OR s.risk_status = 1)"));
    assertTrue(query.contains("s.mute_status IS NULL"));
    assertTrue(query.contains("SUBSTRING_INDEX(p.owner_phone, '@', 1)"));
    assertTrue(query.contains("COALESCE(m.last_seen_at, 0) DESC"));
    assertFalse(query.contains("account_group_id = #{accountGroupId}"));
    assertFalse(query.contains("LIMIT 1"));
}
~~~

- [ ] **Step 2: Run the test and verify it fails**

~~~bash
mvn -Dtest=AccountGroupMembershipMapperSqlTest test
~~~

Expected: FAIL because the new select does not exist.

- [ ] **Step 3: Add the explicit-tenant list query**

~~~java
@InterceptorIgnore(tenantLine = "true")
List<GroupExecutionAccount> selectPullTaskAdminPromoterCandidatesByTenant(
        @Param("tenantId") Long tenantId,
        @Param("groupJid") String groupJid,
        @Param("managerAccountId") Long managerAccountId);
~~~

The XML query must return all ordered candidates, not LIMIT 1:

~~~sql
SELECT a.id AS accountId,
       a.protocol_id AS protocolId,
       a.protocol_account_id AS protocolAccountId,
       a.ws_phone AS wsPhone,
       TRUE AS groupAdmin
FROM account_group_membership m
JOIN account a
  ON a.tenant_id = m.tenant_id AND a.id = m.account_id
JOIN account_state s
  ON s.tenant_id = a.tenant_id AND s.account_id = a.id
LEFT JOIN group_link_preview p
  ON p.tenant_id = m.tenant_id AND p.group_jid = m.group_jid
WHERE a.tenant_id = #{tenantId}
  AND m.group_jid = #{groupJid}
  AND m.deleted_at IS NULL
  AND m.membership_status = #{inGroupStatus}
  AND m.is_admin = 1
  AND a.deleted_at IS NULL
  AND a.id <> #{managerAccountId}
  AND a.protocol_id IS NOT NULL AND TRIM(a.protocol_id) <> ''
  AND a.protocol_account_id IS NOT NULL AND TRIM(a.protocol_account_id) <> ''
  AND a.ws_phone IS NOT NULL AND TRIM(a.ws_phone) <> ''
  AND s.login_state = #{onlineLoginState}
  AND s.account_state = #{normalAccountState}
  AND (s.risk_status IS NULL OR s.risk_status = 1)
  AND s.mute_status IS NULL
ORDER BY CASE
           WHEN REPLACE(SUBSTRING_INDEX(a.ws_phone, '@', 1), '+', '')
                = REPLACE(SUBSTRING_INDEX(p.owner_phone, '@', 1), '+', '')
           THEN 0 ELSE 1
         END,
         COALESCE(m.last_seen_at, 0) DESC,
         a.id ASC
~~~

- [ ] **Step 4: Run the mapper contract and commit**

~~~bash
mvn -Dtest=AccountGroupMembershipMapperSqlTest test
git add armada-api/src/main/java/com/armada/group/mapper/AccountGroupMembershipMapper.java armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml armada-api/src/test/java/com/armada/group/mapper/AccountGroupMembershipMapperSqlTest.java
git commit -m "feat: select pull task admin promoters"
~~~

Expected: PASS; the query has no configured account-group dependency.

### Task 3: Add the manager-promotion Outbox contract and payload hydration

**Files:**
- Create: armada-api/src/main/java/com/armada/platform/protocol/model/command/ProtocolPullTaskManagerAdminCommandRequest.java
- Create: armada-api/src/main/java/com/armada/platform/protocol/model/command/ProtocolPullTaskParticipantActionReference.java
- Modify: armada-api/src/main/java/com/armada/platform/protocol/model/command/ProtocolPullTaskPullerInviteCommandRequest.java
- Delete: armada-api/src/main/java/com/armada/platform/protocol/model/command/ProtocolPullTaskPullerInviteReference.java
- Rename/Modify: armada-api/src/main/java/com/armada/task/service/impl/PullTaskPullerInvitePayloadHydrator.java to PullTaskParticipantActionPayloadHydrator.java
- Rename/Modify: armada-api/src/test/java/com/armada/task/service/impl/PullTaskPullerInvitePayloadHydratorTest.java to PullTaskParticipantActionPayloadHydratorTest.java
- Modify: armada-api/src/main/java/com/armada/platform/protocol/service/ProtocolCommandOutboxService.java
- Modify: armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java
- Modify: armada-api/src/test/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImplTest.java

- [ ] **Step 1: Write failing tests for a reference-only PROMOTE command**

Add tests asserting:

~~~java
assertThat(row.getCommandType()).isEqualTo("group.participants.requested");
assertThat(row.getAggregateType()).isEqualTo("PULL_TASK_ACCOUNT_ACTION");
assertThat(payload).containsEntry("source", "pull_task_manager_admin");
assertThat(payload).doesNotContainKeys("groupJid", "participants", "wsPhone");

assertThat(hydrated.get("action").asText()).isEqualTo("PROMOTE");
assertThat(hydrated.get("attemptNo").asInt()).isEqualTo(2);
assertThat(hydrated.get("participants").get(0).asText())
        .isEqualTo("8615000000000@s.whatsapp.net");
~~~

Also retain the existing puller-invite ADD assertions to prove the hydrator refactor is non-regressive.

- [ ] **Step 2: Run focused tests and verify failure**

~~~bash
mvn -Dtest=ProtocolCommandOutboxServiceImplTest,PullTaskParticipantActionPayloadHydratorTest test
~~~

Expected: compilation/test failure because the manager-admin request and generic hydrator do not exist.

- [ ] **Step 3: Implement the common reference and manager request**

~~~java
public record ProtocolPullTaskParticipantActionReference(
        Long tenantId,
        Long pullTaskId,
        Long groupExecutionId,
        Long actionId,
        String source) {
}

public record ProtocolPullTaskManagerAdminCommandRequest(
        Long tenantId,
        Long pullTaskId,
        Long groupExecutionId,
        Long actionId,
        ProtocolAccountRef actor) {
    public static final String SOURCE = "pull_task_manager_admin";

    public ProtocolPullTaskParticipantActionReference reference() {
        return new ProtocolPullTaskParticipantActionReference(
                tenantId, pullTaskId, groupExecutionId, actionId, SOURCE);
    }
}
~~~

Make ProtocolPullTaskPullerInviteCommandRequest.reference() return the same common record with its existing source.

- [ ] **Step 4: Refactor to one participant-action hydrator**

The publisher rejects multiple hydrators matching the same commandType + aggregateType, so replace the invite-only hydrator with one class. Its source table is:

~~~java
private static ActionSpec actionSpec(String source) {
    if (ProtocolPullTaskPullerInviteCommandRequest.SOURCE.equals(source)) {
        return new ActionSpec(PullTaskAccountActionType.INVITE_TO_GROUP, "ADD", false);
    }
    if (ProtocolPullTaskManagerAdminCommandRequest.SOURCE.equals(source)) {
        return new ActionSpec(PullTaskAccountActionType.PROMOTE_MANAGER, "PROMOTE", true);
    }
    throw validation("普通拉群成员动作 source 非法");
}
~~~

For manager promotion, validate actor role PROMOTER, target role MANAGER, action status SUBMITTED, action commandId equal to the Outbox row, and use action.attemptNo. For invite, preserve action ADD and attemptNo=1. Both payloads contain exactly one target JID and never persist sensitive hydrated fields back into Outbox.

- [ ] **Step 5: Add the Outbox enqueue method**

~~~java
ProtocolCommandOutboxEnqueueResult enqueuePullTaskManagerAdminCommands(
        List<ProtocolPullTaskManagerAdminCommandRequest> commands);
~~~

Implement it by the existing material-admin/invite pattern, routing Web to the master topic and Android to group-action topic, aggregate type PULL_TASK_ACCOUNT_ACTION, aggregate ID actionId, and payloadJson(command.reference()).

- [ ] **Step 6: Run tests and commit**

~~~bash
mvn -Dtest=ProtocolCommandOutboxServiceImplTest,PullTaskParticipantActionPayloadHydratorTest,ProtocolCommandPublisherTest test
git add armada-api/src/main/java/com/armada/platform/protocol/model/command/ProtocolPullTaskManagerAdminCommandRequest.java armada-api/src/main/java/com/armada/platform/protocol/model/command/ProtocolPullTaskParticipantActionReference.java armada-api/src/main/java/com/armada/platform/protocol/model/command/ProtocolPullTaskPullerInviteCommandRequest.java armada-api/src/main/java/com/armada/platform/protocol/model/command/ProtocolPullTaskPullerInviteReference.java armada-api/src/main/java/com/armada/platform/protocol/service/ProtocolCommandOutboxService.java armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskPullerInvitePayloadHydrator.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskParticipantActionPayloadHydrator.java armada-api/src/test/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImplTest.java armada-api/src/test/java/com/armada/task/service/impl/PullTaskPullerInvitePayloadHydratorTest.java armada-api/src/test/java/com/armada/task/service/impl/PullTaskParticipantActionPayloadHydratorTest.java
git commit -m "feat: enqueue manager promotion commands"
~~~

Expected: PASS and only one payload hydrator matches participant account actions.

### Task 4: Implement the MANAGER_ADMIN processor and short transactions

**Files:**
- Create: armada-api/src/main/java/com/armada/task/model/dto/PullTaskManagerAdminWork.java
- Create: armada-api/src/main/java/com/armada/task/scheduler/PullTaskManagerAdminPreparation.java
- Create: armada-api/src/main/java/com/armada/task/scheduler/PullTaskManagerAdminObservation.java
- Create: armada-api/src/main/java/com/armada/task/scheduler/PullTaskManagerAdminCandidateSelector.java
- Create: armada-api/src/main/java/com/armada/task/scheduler/PullTaskManagerAdminResources.java
- Create: armada-api/src/main/java/com/armada/task/scheduler/PullTaskManagerAdminTransactionService.java
- Create: armada-api/src/main/java/com/armada/task/scheduler/PullTaskManagerAdminProcessor.java
- Create: armada-api/src/test/java/com/armada/task/scheduler/PullTaskManagerAdminProcessorTest.java
- Create: armada-api/src/test/java/com/armada/task/scheduler/PullTaskManagerAdminTransactionIntegrationTest.java

- [ ] **Step 1: Write processor tests before implementation**

Cover these observable cases:

Use the processor's explicit preparation/work API. The first test is:

~~~java
@Test
void managerAlreadyAdminAdvancesWithoutSendingPromote() {
    PullTaskGroupExecution candidate = executionAtManagerAdmin();
    PullTaskManagerAdminWork work = work(906L, 15L);
    when(transactions.prepare(candidate, "worker-1", 1_000L))
            .thenReturn(PullTaskManagerAdminPreparation.ready(work));
    when(memberListPort.list(work.memberQuery())).thenReturn(List.of(
            new GroupParticipantResult(
                    "906@s.whatsapp.net", "906", true, false, "admin"),
            new GroupParticipantResult(
                    "15@s.whatsapp.net", "15", true, false, "admin")));
    when(transactions.confirmManagerAdmin(work, 1_000L))
            .thenReturn(PullTaskExecutionDispatchResult.ADVANCED);

    assertThat(processor.process(candidate, "worker-1", 1_000L))
            .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
    verify(transactions).confirmManagerAdmin(work, 1_000L);
    verify(transactions, never()).submitOrDefer(any(), anyLong());
}
~~~

Add equivalent tests with these exact inputs and transitions:

- Candidate 906 is ordinary member, candidate 887 is admin: reject 906, next preparation selects 887.
- Candidate 906 is admin and manager 15 is ordinary member: submit one action whose target role is manager 15.
- Existing action is SUCCESS but manager 15 is still ordinary member: mark the action UNKNOWN/retryable, defer, and do not advance.
- Empty ordered candidates: WAIT_RESOURCE/MANAGER with MANAGER_ADMIN_ACTOR_UNAVAILABLE.
- Every existing candidate action is FAILED/retryable=false: WAIT_RESOURCE/MANAGER with MANAGER_ADMIN_SETUP_FAILED.
- Existing FAILED/retryable=true action and no untried candidate: enqueue a new commandId and persist attemptNo+1.

Use GroupParticipantResult rows such as:

~~~java
new GroupParticipantResult("906@s.whatsapp.net", "906", true, false, "admin");
new GroupParticipantResult("15@s.whatsapp.net", "15", false, false, null);
~~~

- [ ] **Step 2: Run and verify failure**

~~~bash
mvn -Dtest=PullTaskManagerAdminProcessorTest,PullTaskManagerAdminTransactionIntegrationTest test
~~~

Expected: compilation failure because the new stage classes do not exist.

- [ ] **Step 3: Define work and observation records**

~~~java
public record PullTaskManagerAdminWork(
        long tenantId,
        long taskId,
        long executionId,
        int expectedVersion,
        String lockOwner,
        String groupJid,
        PullTaskGroupAccount manager,
        GroupExecutionAccount promoter,
        PullTaskGroupAccount promoterRole,
        PullTaskAccountAction action) {

    GroupMemberListQuery memberQuery() {
        return new GroupMemberListQuery(
                promoter.protocolRef(), groupJid,
                "pull-task-manager-admin-verify:" + executionId + ":" + promoter.accountId());
    }
}

public record PullTaskManagerAdminObservation(
        boolean promoterStillAdmin,
        boolean managerAlreadyAdmin) {
}
~~~

- [ ] **Step 4: Implement transaction preparation and candidate rotation**

The transaction service must:

1. Recheck parent task, execution status, stage=MANAGER_ADMIN and lock owner.
2. Load the single MANAGER role and require membership_status=IN_GROUP.
3. If admin_status=SUCCESS, CAS directly to MANAGER_PULLER_CONTACT.
4. Read ordered mapper candidates.
5. Prefer candidates with no PROMOTE_MANAGER action; keep SUBMITTED/SUCCESS actions available for fact verification; then retry UNKNOWN or FAILED/retryable=true actions after untried candidates.
6. Persist/reuse PROMOTER role rows with membership_status=IN_GROUP and admin_status=SUCCESS.
7. Persist/reuse one PROMOTE_MANAGER action per promoter/manager pair.
8. Never count or occupy PROMOTER as manager/puller resources.

Put candidate/action classification in PullTaskManagerAdminCandidateSelector so the transaction service and resource recovery use the same rule. The classification must be explicit:

~~~java
List<PullTaskAccountAction> actions = actionMapper.selectByExecutionAndType(
        executionId, PullTaskAccountActionType.PROMOTE_MANAGER.code());
Set<Long> permanentlyFailedActors = actions.stream()
        .filter(row -> row.getActionStatus() == PullTaskActionStatus.FAILED.code())
        .filter(row -> Boolean.FALSE.equals(row.getRetryable()))
        .map(PullTaskAccountAction::getActorGroupAccountId)
        .collect(Collectors.toSet());
~~~

The selector maps actor role IDs back to PROMOTER account IDs before comparing them with GroupExecutionAccount.accountId. It returns, in order: a verification action, the first untried candidate, then the oldest retryable action. It never returns FAILED/retryable=false.

- [ ] **Step 5: Keep realtime calls outside transactions**

Processor flow:

~~~java
PullTaskManagerAdminPreparation preparation = transactions.prepare(candidate, lockOwner, now);
if (!preparation.ready()) {
    return preparation.result();
}
PullTaskManagerAdminWork work = preparation.work();
List<GroupParticipantResult> members = memberListPort.list(work.memberQuery());
PullTaskManagerAdminObservation observation = observe(
        members, work.promoter().wsPhone(), work.manager().getAccountPhone());
if (observation.managerAlreadyAdmin()) {
    return transactions.confirmManagerAdmin(work, now);
}
if (!observation.promoterStillAdmin()) {
    return transactions.rejectPromoter(work, now);
}
if (work.action().getActionStatus() == PullTaskActionStatus.SUCCESS.code()) {
    return transactions.deferUnconfirmed(work, now);
}
return transactions.submitOrDefer(work, now);
~~~

Phone/JID normalization must match PullTaskManagerJoinProcessor semantics. A command response is never used as the final permission fact.

rejectPromoter CASes the prepared action to FAILED/retryable=false with reason MANAGER_ADMIN_SETUP_FAILED, returns the target manager to adminStatus=PENDING, and wakes stage 3 immediately so the selector rotates to the next candidate. It does not write account_group_membership; that table remains a candidate source owned by the group snapshot flow.

deferUnconfirmed changes SUCCESS to UNKNOWN/retryable=true, writes MANAGER_ADMIN_UNCONFIRMED, and schedules one reconciliation delay. On the next selection pass an untried promoter is preferred; only after all untried promoters are exhausted can the UNKNOWN action receive a new commandId.

- [ ] **Step 6: Submit a new attempt atomically**

Within submitOrDefer:

~~~java
ProtocolCommandOutboxEnqueueResult enqueued =
        outboxService.enqueuePullTaskManagerAdminCommands(List.of(
                new ProtocolPullTaskManagerAdminCommandRequest(
                        work.tenantId(), work.taskId(), work.executionId(),
                        work.action().getId(), work.promoter().protocolRef())));
String commandId = requireSingleCommandId(enqueued);
int changed = actionMapper.submitAttempt(
        work.action().getId(),
        List.of(PENDING, FAILED, UNKNOWN),
        commandId,
        now);
if (changed != 1) throw new IllegalStateException("管理员设置动作状态已变化");
accountMapper.transitionAdminStatus(
        work.manager().getId(),
        List.of(PENDING, UNKNOWN, FAILED),
        PullTaskGroupAccountAdminStatus.SUBMITTED.code(),
        now);
~~~

Then release the execution lock at stage 3 with nextRunAt=resultReconciliationDelayMs. If an action is already SUBMITTED, do not resend; defer for callback/reconciliation.

- [ ] **Step 7: Persist recoverable waiting states**

Use:

~~~java
MANAGER_ADMIN_ACTOR_UNAVAILABLE("当前没有在线的我方群主或管理员"),
MANAGER_ADMIN_SETUP_FAILED("管理员设置失败"),
MANAGER_ADMIN_UNCONFIRMED("管理员权限结果暂未确认");
~~~

No candidates or exhausted candidates write executionStatus=WAIT_RESOURCE, stage=MANAGER_ADMIN, waitResourceType=MANAGER. They do not set finishedAt and do not trigger parent completion.

- [ ] **Step 8: Run tests and commit**

~~~bash
mvn -Dtest=PullTaskManagerAdminProcessorTest,PullTaskManagerAdminTransactionIntegrationTest test
git add armada-api/src/main/java/com/armada/task/model/dto/PullTaskManagerAdminWork.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskManagerAdminPreparation.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskManagerAdminObservation.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskManagerAdminCandidateSelector.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskManagerAdminResources.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskManagerAdminTransactionService.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskManagerAdminProcessor.java armada-api/src/test/java/com/armada/task/scheduler/PullTaskManagerAdminProcessorTest.java armada-api/src/test/java/com/armada/task/scheduler/PullTaskManagerAdminTransactionIntegrationTest.java
git commit -m "feat: execute manager admin stage"
~~~

Expected: PASS.

### Task 5: Converge promotion callbacks without trusting protocol success

**Files:**
- Create: armada-api/src/main/java/com/armada/task/model/dto/PullTaskManagerAdminCallback.java
- Create: armada-api/src/main/java/com/armada/task/service/PullTaskManagerAdminResultService.java
- Create: armada-api/src/main/java/com/armada/task/service/impl/PullTaskManagerAdminResultServiceImpl.java
- Create: armada-api/src/test/java/com/armada/task/service/impl/PullTaskManagerAdminResultServiceImplTest.java
- Modify: armada-api/src/main/java/com/armada/platform/kafka/consumer/group/ProtocolGroupEventConsumer.java
- Modify: armada-api/src/main/java/com/armada/task/service/impl/ProtocolGroupActionResultAdapter.java
- Modify: armada-api/src/main/java/com/armada/task/scheduler/PullTaskUnknownResultReconciliationService.java
- Modify: armada-api/src/test/java/com/armada/platform/kafka/consumer/group/ProtocolGroupEventConsumerTest.java
- Modify: armada-api/src/test/java/com/armada/task/service/ProtocolGroupActionResultAdapterTest.java
- Modify: armada-api/src/test/java/com/armada/task/scheduler/PullTaskUnknownResultReconciliationServiceTest.java

- [ ] **Step 1: Write callback state-machine tests**

~~~java
@Test void successKeepsManagerUnconfirmedAndWakesStageThree();
@Test void permissionDeniedIsStableAndRotatesImmediately();
@Test void rateLimitedIsRetryableAndUsesBackoff();
@Test void unknownSchedulesRealtimeVerification();
@Test void duplicateCallbackIsIdempotent();
@Test void lateOldCommandIdCannotOverwriteCurrentAttempt();
@Test void promotionIsNotConfirmedWhenTargetIsOnlyAnOrdinaryMember();
@Test void promotionIsConfirmedOnlyWhenRealtimeMemberIsAdminOrOwner();
~~~

- [ ] **Step 2: Run and verify failure**

~~~bash
mvn -Dtest=PullTaskManagerAdminResultServiceImplTest,ProtocolGroupEventConsumerTest,ProtocolGroupActionResultAdapterTest,PullTaskUnknownResultReconciliationServiceTest test
~~~

Expected: compilation/test failure for the new source and service, while the reconciliation regression test exposes that every non-SAVE_CONTACT action is currently treated as successful as soon as the target is merely in-group.

- [ ] **Step 3: Add the strict callback DTO and source routing**

~~~java
public record PullTaskManagerAdminCallback(
        long tenantId,
        long pullTaskId,
        long groupExecutionId,
        long actionId,
        long accountId,
        String protocolAccountId,
        String commandId,
        int attemptNo,
        String targetJid,
        PullTaskProtocolOutcome outcome,
        String reasonCode,
        String reasonMessage,
        boolean retryable,
        long occurredAt) {
}
~~~

ProtocolGroupEventConsumer accepts source=pull_task_manager_admin only with operation=PARTICIPANT_PROMOTE and requires targetJid. ProtocolGroupActionResultAdapter routes only that pair to PullTaskManagerAdminResultService.

- [ ] **Step 4: Implement commandId + attemptNo idempotency**

Before any write, require:

~~~java
Objects.equals(action.getId(), callback.actionId())
    && Objects.equals(action.getActionType(), PROMOTE_MANAGER.code())
    && Objects.equals(action.getCommandId(), callback.commandId())
    && Objects.equals(action.getAttemptNo(), callback.attemptNo())
    && Objects.equals(actor.getAccountId(), callback.accountId())
    && sameUserJid(manager.getAccountPhone(), callback.targetJid());
~~~

State outcomes:

- SUCCESS: action SUCCESS/retryable=false; manager remains SUBMITTED; execution stays stage 3, nextRunAt=0, reason MANAGER_ADMIN_UNCONFIRMED.
- FAILED + retryable=false: action FAILED; manager returns PENDING; execution stays stage 3, nextRunAt=0, reason MANAGER_ADMIN_SETUP_FAILED so another candidate is selected.
- FAILED + retryable=true: action FAILED/retryable=true; manager UNKNOWN; nextRunAt=occurredAt+retryDelay; reason MANAGER_ADMIN_SETUP_FAILED.
- UNKNOWN: action UNKNOWN/retryable=true; manager UNKNOWN; nextRunAt=occurredAt+retryDelay; reason MANAGER_ADMIN_UNCONFIRMED.

Map protocol reasons to safe messages in this service; never persist the raw exception text:

~~~java
private static String safeReasonMessage(String reasonCode) {
    return switch (reasonCode == null ? "" : reasonCode) {
        case "GROUP_PERMISSION_DENIED" -> "提权账号已无群管理员权限";
        case "RATE_LIMITED" -> "群操作触发限流，稍后重试";
        case "ACCOUNT_NOT_ONLINE" -> "提权账号当前离线";
        case "ACCOUNT_BUSY", "WORKER_BUSY" -> "提权账号当前繁忙，稍后重试";
        default -> "管理员设置暂时失败";
    };
}
~~~

- [ ] **Step 5: Make realtime reconciliation permission-aware**

PullTaskUnknownResultReconciliationService must classify actions explicitly instead of using `actionType != SAVE_CONTACT` as the success rule:

~~~java
boolean promotion = Objects.equals(
        action.getActionType(), PullTaskAccountActionType.PROMOTE_MANAGER.code());
boolean membershipAction = Objects.equals(
        action.getActionType(), PullTaskAccountActionType.INVITE_TO_GROUP.code())
        || Objects.equals(
        action.getActionType(), PullTaskAccountActionType.JOIN_BY_LINK.code());
GroupParticipantResult member = target == null
        ? null : context.snapshot().member(target.getAccountPhone());
boolean effectObserved = promotion ? hasAdmin(member) : membershipAction && member != null;
~~~

Only `effectObserved` may converge the action to SUCCESS. Only membership actions call `confirmMembership` or `markMembershipUnknown`; PROMOTE_MANAGER never succeeds from presence alone. When a submitted promotion grows stale, change its action to UNKNOWN/retryable=true and let `reconcileAccountAdmins` change the target manager admin status to UNKNOWN. The stage-3 processor then verifies or rotates the action without resending an unconfirmed SUBMITTED command.

- [ ] **Step 6: Run tests and commit**

~~~bash
mvn -Dtest=PullTaskManagerAdminResultServiceImplTest,ProtocolGroupEventConsumerTest,ProtocolGroupActionResultAdapterTest,PullTaskUnknownResultReconciliationServiceTest test
git add armada-api/src/main/java/com/armada/task/model/dto/PullTaskManagerAdminCallback.java armada-api/src/main/java/com/armada/task/service/PullTaskManagerAdminResultService.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskManagerAdminResultServiceImpl.java armada-api/src/main/java/com/armada/platform/kafka/consumer/group/ProtocolGroupEventConsumer.java armada-api/src/main/java/com/armada/task/service/impl/ProtocolGroupActionResultAdapter.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskUnknownResultReconciliationService.java armada-api/src/test/java/com/armada/task/service/impl/PullTaskManagerAdminResultServiceImplTest.java armada-api/src/test/java/com/armada/platform/kafka/consumer/group/ProtocolGroupEventConsumerTest.java armada-api/src/test/java/com/armada/task/service/ProtocolGroupActionResultAdapterTest.java armada-api/src/test/java/com/armada/task/scheduler/PullTaskUnknownResultReconciliationServiceTest.java
git commit -m "feat: converge manager admin results"
~~~

Expected: PASS; success alone never advances stage 3.

### Task 6: Wire stage transitions, recovery and detail facts

**Files:**
- Modify: armada-api/src/main/java/com/armada/task/scheduler/PullTaskExecutionStageRouter.java
- Modify: armada-api/src/main/java/com/armada/task/scheduler/PullTaskExecutionDispatchCoordinator.java
- Modify: armada-api/src/main/java/com/armada/task/scheduler/PullTaskResourceRecoveryTransactionService.java
- Modify: armada-api/src/main/java/com/armada/task/scheduler/PullTaskManagerJoinTransactionService.java
- Modify: armada-api/src/main/java/com/armada/task/service/impl/PullTaskManagerJoinResultServiceImpl.java
- Modify: armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullerInviteTransactionService.java
- Modify: armada-api/src/main/java/com/armada/task/scheduler/PullTaskManagerPullerContactTransactionService.java
- Modify: armada-api/src/main/java/com/armada/task/model/vo/PullTaskStandardRoleVO.java
- Modify: armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardReadServiceImpl.java
- Modify: armada-api/src/main/resources/mapper/task/PullTaskStandardReadMapper.xml
- Modify: armada-api/src/test/java/com/armada/task/scheduler/PullTaskExecutionStageRouterTest.java
- Modify: armada-api/src/test/java/com/armada/task/scheduler/PullTaskManagerJoinTransactionIntegrationTest.java
- Modify: armada-api/src/test/java/com/armada/task/service/impl/PullTaskManagerJoinResultServiceImplTest.java
- Modify: armada-api/src/test/java/com/armada/task/scheduler/PullTaskResourceRecoveryTransactionIntegrationTest.java
- Modify: armada-api/src/test/java/com/armada/task/service/PullTaskStandardReadServiceTest.java

- [ ] **Step 1: Change tests to require the new gate**

Assertions:

~~~java
assertThat(joinSuccess.executionStage()).isEqualTo(PullTaskExecutionStage.MANAGER_ADMIN.code());
verify(managerPullerContactProcessor, never()).process(any(), anyString(), anyLong());
assertThat(detail.roles()).anyMatch(role -> role.roleType() == PROMOTER.code());
assertThat(detail.actions()).anyMatch(action -> action.actionType() == PROMOTE_MANAGER.code());
~~~

Add a resource-recovery case proving WAIT_RESOURCE + MANAGER + stage 3 resumes when an ordered candidate exists.

- [ ] **Step 2: Run and verify failure**

~~~bash
mvn -Dtest=PullTaskExecutionStageRouterTest,PullTaskManagerJoinTransactionIntegrationTest,PullTaskManagerJoinResultServiceImplTest,PullTaskResourceRecoveryTransactionIntegrationTest,PullTaskStandardReadServiceTest test
~~~

Expected: existing join tests still expect stage 3 to mean contact, and router lacks managerAdminProcessor.

- [ ] **Step 3: Route and dispatch all eight enum stages**

Inject PullTaskManagerAdminProcessor and add:

~~~java
if (candidate.getStage() == PullTaskExecutionStage.MANAGER_ADMIN.code()) {
    return managerAdminProcessor.process(candidate, lockOwner, now);
}
~~~

Replace hard-coded stage lists with enum-derived or explicit 1～8 lists. Audit all comparisons with:

~~~bash
rg -n "stage.?[<>=]|List\\.of\\(PullTaskExecutionStage|MANAGER_PULLER_CONTACT|MATERIAL_ADMIN|CLOSING" armada-api/src/main/java armada-api/src/test/java
~~~

- [ ] **Step 4: Make both manager-join completion paths enter MANAGER_ADMIN**

Update the synchronous completion in PullTaskManagerJoinTransactionService and Kafka completion in PullTaskManagerJoinResultServiceImpl:

~~~java
case SUCCESS -> new Target(
        PullTaskExecutionStatus.EXECUTING.code(),
        PullTaskExecutionStage.MANAGER_ADMIN.code(),
        callback.groupJid(), null, null, null, 0L, null);
~~~

Do not set adminStatus SUCCESS here; manager membership and manager permission remain separate facts.

- [ ] **Step 5: Enforce admin SUCCESS before contacts and puller invites**

Both manager pools must require:

~~~java
Objects.equals(row.getMembershipStatus(), IN_GROUP.code())
    && Objects.equals(row.getAdminStatus(), SUCCESS.code())
    && Objects.equals(row.getAvailabilityStatus(), AVAILABLE.code());
~~~

This is a defensive invariant even though stage routing should already enforce it.

- [ ] **Step 6: Extend recovery and detail projection**

managerCheck treats stage MANAGER_ADMIN specially: it checks the target manager is still in-group and asks PullTaskManagerAdminCandidateSelector whether an untried/retryable candidate or a verification action exists. A waiting manager-admin row resumes at the same stage and keeps no terminal status.

Add adminStatus to PullTaskStandardRoleVO and map row.getAdminStatus() in PullTaskStandardReadServiceImpl.role(). The existing enum iteration then includes PROMOTER automatically. In PullTaskStandardReadMapper.xml, current_manager_count must also require role_type=MANAGER and admin_status=SUCCESS; PROMOTER is never included in manager resource counts.

- [ ] **Step 7: Run tests and commit**

~~~bash
mvn -Dtest=PullTaskExecutionStageRouterTest,PullTaskManagerJoinTransactionIntegrationTest,PullTaskManagerJoinResultServiceImplTest,PullTaskResourceRecoveryTransactionIntegrationTest,PullTaskStandardReadServiceTest test
git add armada-api/src/main/java/com/armada/task/scheduler/PullTaskExecutionStageRouter.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskExecutionDispatchCoordinator.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskResourceRecoveryTransactionService.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskManagerJoinTransactionService.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullerInviteTransactionService.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskManagerPullerContactTransactionService.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskManagerJoinResultServiceImpl.java armada-api/src/main/java/com/armada/task/model/vo/PullTaskStandardRoleVO.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardReadServiceImpl.java armada-api/src/main/resources/mapper/task/PullTaskStandardReadMapper.xml armada-api/src/test/java/com/armada/task/scheduler/PullTaskExecutionStageRouterTest.java armada-api/src/test/java/com/armada/task/scheduler/PullTaskManagerJoinTransactionIntegrationTest.java armada-api/src/test/java/com/armada/task/service/impl/PullTaskManagerJoinResultServiceImplTest.java armada-api/src/test/java/com/armada/task/scheduler/PullTaskResourceRecoveryTransactionIntegrationTest.java armada-api/src/test/java/com/armada/task/service/PullTaskStandardReadServiceTest.java
git commit -m "feat: gate pull flow on manager permission"
~~~

Expected: PASS.

### Task 7: Replace failed pullers instead of reoccupying them

**Files:**
- Modify: armada-api/src/main/java/com/armada/task/scheduler/PullTaskManagerPullerContactTransactionService.java
- Modify: armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullerInviteTransactionService.java
- Modify: armada-api/src/main/java/com/armada/task/scheduler/PullTaskResourceRecoveryTransactionService.java
- Modify: armada-api/src/test/java/com/armada/task/scheduler/PullTaskManagerPullerContactTransactionIntegrationTest.java
- Modify: armada-api/src/test/java/com/armada/task/scheduler/PullTaskPullerInviteTransactionIntegrationTest.java
- Modify: armada-api/src/test/java/com/armada/task/scheduler/PullTaskResourceRecoveryTransactionIntegrationTest.java

- [ ] **Step 1: Write regression tests with 45/47-shaped facts**

~~~java
@Test
void joinFailedReleasedPullersAreSkippedAndUnusedCandidatesFillThePlan() {
    insertReleasedPuller(45L, PullTaskGroupAccountMembershipStatus.JOIN_FAILED);
    insertReleasedPuller(47L, PullTaskGroupAccountMembershipStatus.JOIN_FAILED);
    when(accountLookup.findOnlineNormalByGroupId(PULLER_GROUP_ID)).thenReturn(List.of(
            protocolRef(45L), protocolRef(47L), protocolRef(48L), protocolRef(50L)));

    service.prepare(claimedContactExecution(), "worker-1", 1_000L);

    assertThat(activePullerAccountIds()).containsExactly(48L, 50L);
    assertThat(role(45L).getReleasedAt()).isNotNull();
    assertThat(role(47L).getReleasedAt()).isNotNull();
}

@Test
void inviteStageWithNoJoinedPullerReturnsToContactSelection() {
    PullTaskGroupExecution execution = claimedInviteExecution();
    insertFailedInvite(45L);
    insertFailedInvite(47L);

    PullTaskExecutionDispatchResult result =
            service.prepare(execution, "worker-1", 1_000L);

    assertThat(result).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
    assertThat(reloadExecution().getStage())
            .isEqualTo(PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code());
    assertThat(reloadExecution().getWaitResourceType()).isNull();
}
~~~

- [ ] **Step 2: Run and verify failure**

~~~bash
mvn -Dtest=PullTaskManagerPullerContactTransactionIntegrationTest,PullTaskPullerInviteTransactionIntegrationTest,PullTaskResourceRecoveryTransactionIntegrationTest test
~~~

Expected: FAIL because released JOIN_FAILED rows are currently reoccupied.

- [ ] **Step 3: Exclude failed role rows in both reoccupation paths**

Add this guard to restoreReleasedPullers and reoccupyValidatedPullers:

~~~java
if (Objects.equals(row.getMembershipStatus(),
        PullTaskGroupAccountMembershipStatus.JOIN_FAILED.code())) {
    continue;
}
~~~

Keep existingIds containing 45/47 so a new role row for the same account cannot be inserted into the same execution; the selector continues to 48/50.

- [ ] **Step 4: Return exhausted failed invites to contact selection**

When finishInvites sees no IN_GROUP puller:

1. Release every JOIN_FAILED puller still occupied.
2. CAS the execution from PULLER_INVITE to MANAGER_PULLER_CONTACT.
3. Clear waitResourceType/reason and set nextRunAt=0.
4. Let ensurePullers select accounts not previously present in the execution.
5. Only let MANAGER_PULLER_CONTACT write PULLER_UNAVAILABLE if no untried eligible account exists.

Core transition:

~~~java
if (!hasJoinedPuller) {
    pullers.stream()
            .filter(row -> Objects.equals(row.getMembershipStatus(), JOIN_FAILED.code()))
            .filter(row -> row.getReleasedAt() == null)
            .forEach(row -> groupAccountMapper.releasePuller(row.getId(), now));
    return transitionStage(
            candidate,
            PullTaskExecutionStage.MANAGER_PULLER_CONTACT,
            PullTaskExecutionDispatchResult.ADVANCED,
            now);
}
~~~

Add transitionStage as a private CAS helper that sets executionStatus=EXECUTING, clears waitResourceType/reasonCode/reasonMessage, sets nextRunAt=0 and calls transitionClaimed with expected stage PULLER_INVITE.

- [ ] **Step 5: Run tests and commit**

~~~bash
mvn -Dtest=PullTaskManagerPullerContactTransactionIntegrationTest,PullTaskPullerInviteTransactionIntegrationTest,PullTaskResourceRecoveryTransactionIntegrationTest test
git add armada-api/src/main/java/com/armada/task/scheduler/PullTaskManagerPullerContactTransactionService.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullerInviteTransactionService.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskResourceRecoveryTransactionService.java armada-api/src/test/java/com/armada/task/scheduler/PullTaskManagerPullerContactTransactionIntegrationTest.java armada-api/src/test/java/com/armada/task/scheduler/PullTaskPullerInviteTransactionIntegrationTest.java armada-api/src/test/java/com/armada/task/scheduler/PullTaskResourceRecoveryTransactionIntegrationTest.java
git commit -m "fix: replace failed pull task pullers"
~~~

Expected: PASS; 45/47 remain normal/online globally but are excluded only from their failed execution row.

### Task 8: Extend protocol-layer source validation and semantic errors

**Files:**
- Modify: /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/commands/pull-task-action.ts
- Modify: /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/commands/group-participants-executor.ts
- Modify: /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/commands/group-participants-executor.test.ts
- Modify: /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/commands/master-consumer.test.ts

- [ ] **Step 1: Add failing source and error-mapping tests**

~~~ts
const managerAdminPayload = {
  ...materialAdminPayload,
  source: 'pull_task_manager_admin',
  actionId: 901,
  action: 'PROMOTE',
  attemptNo: 2
}

it('executes manager admin as one native promote', async () => {
  await executeGroupParticipants(command(managerAdminPayload), deps)
  expect(groupParticipantsUpdate).toHaveBeenCalledWith(
    managerAdminPayload.groupJid,
    managerAdminPayload.participants,
    'promote'
  )
})

it.each([
  [new Error('not-authorized'), 'GROUP_PERMISSION_DENIED', false],
  [new Error('rate-overlimit data=429'), 'RATE_LIMITED', true]
])('preserves semantic manager admin failures', async (error, code, retryable) => {
  const { deps, publisher } = buildDeps({
    update: async () => { throw error }
  })
  await executeGroupParticipants(command(managerAdminPayload), deps)
  expect(publisher.publish).toHaveBeenCalledWith(
    'group.action_result_reported',
    managerAdminPayload.protocolAccountId,
    expect.objectContaining({
      source: 'pull_task_manager_admin',
      operation: 'PARTICIPANT_PROMOTE',
      outcome: 'FAILED',
      reasonCode: code,
      retryable
    }),
    undefined,
    expect.objectContaining({ requireBrokerAck: true })
  )
})
~~~

- [ ] **Step 2: Run and verify failure**

Run from armada-protocol/protocol-layer:

~~~bash
npm test -- --runInBand src/commands/group-participants-executor.test.ts src/commands/master-consumer.test.ts
~~~

Expected: manager source is rejected and raw Baileys errors become TEMPORARY_FAILURE.

- [ ] **Step 3: Add the shared source spec**

~~~ts
pull_task_manager_admin: {
  operation: 'PARTICIPANT_PROMOTE',
  action: 'PROMOTE',
  nativeAction: 'promote',
  correlationKey: 'actionId',
  singleTarget: true
}
~~~

Because worker and master fallback both call participantsSourceSpec, this one addition covers normal routing and owner-missing failure publication.

- [ ] **Step 4: Normalize raw Baileys errors before the generic fallback**

~~~ts
const message = error instanceof Error ? error.message.toLowerCase() : String(error).toLowerCase()
if (message.includes('not-authorized') || message.includes('not authorized')
    || message.includes('forbidden')) {
  return failure('GROUP_PERMISSION_DENIED', 'Group admin permission denied', false)
}
if (message.includes('rate-overlimit') || message.includes('rate limit')
    || message.includes('too many requests')) {
  return failure('RATE_LIMITED', 'Group action rate limited', true)
}
return failure('TEMPORARY_FAILURE', 'Group action temporarily failed', true)
~~~

Do not publish raw exception text as reasonMessage.

- [ ] **Step 5: Run protocol tests, lint and commit**

~~~bash
npm test -- --runInBand src/commands/group-participants-executor.test.ts src/commands/master-consumer.test.ts
npm run lint
git add protocol-layer/src/commands/pull-task-action.ts protocol-layer/src/commands/group-participants-executor.ts protocol-layer/src/commands/group-participants-executor.test.ts protocol-layer/src/commands/master-consumer.test.ts
git commit -m "feat: support pull task manager promotion"
~~~

Expected: PASS.

### Task 9: Display the new stage, anomaly, promoter and action

**Files:**
- Modify: /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/api/pull-task.ts
- Modify: /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/task/pull-task/constants.ts
- Create: /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/task/pull-task/standard-execution-display.ts
- Modify: /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/task/pull-task/composables/usePullTaskPage.ts
- Modify: /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/task/pull-task/components/PullTaskExecutionDetailDrawer.vue
- Modify: /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/task/pull-task/components/PullTaskExecutionResourceActions.vue
- Create: /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/task/pull-task/standard-execution-display.test.ts
- Modify: /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/task/pull-task/components/PullTaskExecutionResourceActions.test.ts

- [ ] **Step 1: Write a failing display contract test**

~~~ts
it('maps the eight stages and manager setup anomaly', () => {
  assert.equal(standardStageLabel(3), '管理员设置')
  assert.equal(standardStageLabel(4), '管理—拉手联系人')
  assert.equal(standardStageLabel(8), '执行收口')
  assert.equal(
    standardExecutionStatus({
      executionStatus: 3,
      stage: 3,
      waitResourceType: 1,
      reasonCode: 'MANAGER_ADMIN_SETUP_FAILED'
    } as PullTaskStandardExecutionSummary),
    'ADMIN_SETUP_FAILED'
  )
})

it('declares promoter and promote-manager labels', () => {
  assert.equal(roleLabel(4), '提权管理员')
  assert.equal(actionTypeLabel(4), '设置任务管理员')
})
~~~

Extend PullTaskExecutionResourceActions.test.ts with a source contract proving manager supplement is excluded at the automatic promotion stage:

~~~ts
assert.match(source, /props\.row\.stage !== 3/)
assert.doesNotMatch(source, /reasonCode ===/)
~~~

The stage check is deliberate: stage 3 needs an existing self-controlled group admin/owner, not another task-manager account. Other WAIT_RESOURCE/MANAGER stages retain the manual “补充管理员” action.

Put the pure stage/status/role/action helpers in standard-execution-display.ts so the Node test does not load Vue or runtime @ path aliases. constants.ts and the drawer import/re-export those helpers.

- [ ] **Step 2: Run and verify failure**

Run from wheel-saas-pure-web:

~~~bash
node --experimental-strip-types --test src/views/task/pull-task/standard-execution-display.test.ts src/views/task/pull-task/components/PullTaskExecutionResourceActions.test.ts
~~~

Expected: FAIL because stage 3 and type 4 still have old/unknown labels, and the resource action still exposes manual manager supplementation at the automatic promotion stage.

- [ ] **Step 3: Update stage and anomaly mapping**

~~~ts
export const standardStageOptions = [
  { label: '链接校验', value: 1 },
  { label: '管理员进群', value: 2 },
  { label: '管理员设置', value: 3 },
  { label: '管理—拉手联系人', value: 4 },
  { label: '管理员邀请拉手', value: 5 },
  { label: '拉人执行', value: 6 },
  { label: '料子提权', value: 7 },
  { label: '执行收口', value: 8 }
]

const managerAdminReasons = new Set([
  'MANAGER_ADMIN_ACTOR_UNAVAILABLE',
  'MANAGER_ADMIN_SETUP_FAILED',
  'MANAGER_ADMIN_UNCONFIRMED'
])
if (execution.reasonCode && managerAdminReasons.has(execution.reasonCode)) {
  return 'ADMIN_SETUP_FAILED'
}
~~~

Change the group status label from “管理员无法设置” to “管理员设置失败”. Check manager-admin reasons before generic MANAGER_SHORTAGE so the task situation is not mislabeled.

Keep the backend's existing manager-supplement validation unchanged: its `SUPPLEMENTABLE_STAGES` already excludes MANAGER_ADMIN. Match that invariant in PullTaskExecutionResourceActions.vue:

~~~ts
const managerVisible = computed(
  () =>
    normalLinkWait.value &&
    props.row.waitResourceType === 1 &&
    props.row.stage !== 3
)
~~~

- [ ] **Step 4: Show permission and audit labels in the drawer**

Add adminStatus to PullTaskStandardRole. Display:

~~~ts
export function roleLabel(value: number): string {
  return ({ 1: '管理员', 2: '拉手', 3: '站台', 4: '提权管理员' } as const)[value] ?? '未知'
}

export function actionTypeLabel(value: number): string {
  return ({
    1: '保存联系人',
    2: '邀请入群',
    3: '踩链接入群',
    4: '设置任务管理员'
  } as const)[value] ?? '未知'
}
~~~

Add an “管理员权限” column using the existing adminStatusLabel. Keep current exception rendered from reasonMessage first, reasonCode second; do not infer raw protocol errors in Vue.

- [ ] **Step 5: Run frontend tests, typecheck and build**

~~~bash
node --experimental-strip-types --test src/views/task/pull-task/task-list-display.test.ts src/views/task/pull-task/standard-execution-display.test.ts src/views/task/pull-task/components/PullTaskExecutionResourceActions.test.ts
pnpm typecheck
pnpm build
~~~

Expected: PASS.

- [ ] **Step 6: Commit frontend changes**

~~~bash
git add src/api/pull-task.ts src/views/task/pull-task/constants.ts src/views/task/pull-task/standard-execution-display.ts src/views/task/pull-task/composables/usePullTaskPage.ts src/views/task/pull-task/components/PullTaskExecutionDetailDrawer.vue src/views/task/pull-task/components/PullTaskExecutionResourceActions.vue src/views/task/pull-task/standard-execution-display.test.ts src/views/task/pull-task/components/PullTaskExecutionResourceActions.test.ts
git commit -m "feat: show pull task manager admin stage"
~~~

### Task 10: Run the complete local gate and prepare first-environment verification

**Files:**
- Modify only if a failing test exposes a defect in files already listed above.
- Do not modify remote data or deploy without a new explicit environment confirmation.

- [ ] **Step 1: Run the complete backend test gate**

Run from armada/armada-api:

~~~bash
mvn test
~~~

Expected: BUILD SUCCESS. If unrelated pre-existing tests fail, capture their exact names and verify all focused tests from Tasks 1～7 still pass; do not mask failures.

- [ ] **Step 2: Run complete protocol and frontend gates**

Run from armada-protocol/protocol-layer:

~~~bash
npm test -- --runInBand
npm run lint
npm run build
~~~

Run from wheel-saas-pure-web:

~~~bash
pnpm typecheck
pnpm build
~~~

Expected: all commands exit 0.

- [ ] **Step 3: Audit stage literals and sensitive logging**

Run from armada:

~~~bash
rg -n "stage.?=.?[3-8]|stage.?==.?[3-8]" armada-api/src/main armada-api/src/test
rg -n "pull_task_manager_admin|MANAGER_ADMIN_|PROMOTE_MANAGER|PROMOTER" armada-api/src
rg -n "reasonMessage.*payload|log.*groupJid|log.*wsPhone" armada-api/src/main
~~~

Run from armada-protocol:

~~~bash
rg -n "pull_task_manager_admin|PARTICIPANT_PROMOTE" protocol-layer/src
rg -n "reasonMessage.*payload|console.*groupJid|logger.*wsPhone" protocol-layer/src
~~~

Run from wheel-saas-pure-web:

~~~bash
rg -n "管理员设置|PROMOTE_MANAGER|PROMOTER|MANAGER_ADMIN_" src/views/task/pull-task src/api/pull-task.ts
~~~

Expected: remaining stage decisions use enums; every new source has producer, hydrator, consumer and tests; no new logs expose full numbers, group JIDs or payloads.

- [ ] **Step 4: Review Git scope in all repositories**

~~~bash
git status --short
git diff --check
git log --oneline -8
~~~

Expected: only intended files are changed/committed in each repository. Preserve unrelated user work, including existing Flyway files and worktree metadata.

- [ ] **Step 5: Request explicit approval before first-environment deployment**

After approval, stop the old backend scheduler, migrate V101, deploy backend/protocol/frontend, then restart scheduling. Do not start the old scheduler against stage values 3～8.

- [ ] **Step 6: Verify task 2 in the first environment**

Read-only acceptance sequence:

1. Both task-2 executions move to stage 3 “管理员设置”.
2. Candidate query selects account 906 for each group and realtime member list confirms it is admin/owner.
3. Account 906 sends PROMOTE for account 15.
4. A later realtime member list confirms account 15 is admin before stage advances to 4.
5. Details show PROMOTER, PROMOTE_MANAGER, attemptNo/result and no raw payload.
6. Failed account roles 45/47 stay excluded in these executions; accounts such as 48/50/52/54/58 can fill the plan.
7. A forced permission denial or rate limit keeps the row non-terminal and shows “管理员设置失败” plus a safe current exception.

Any remote write, replay, pause/resume, migration or deployment still requires confirmation of the exact target environment.
