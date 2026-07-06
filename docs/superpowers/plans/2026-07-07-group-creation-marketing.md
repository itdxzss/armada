# Group Creation Marketing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first runnable “建群营销” flow: choose an account group, upload ordered material files, create one group per matched online account, then immediately send the selected marketing template to the new group.

**Architecture:** Add a new group-creation marketing aggregate for task/item tracking, but reuse the existing protocol `GroupCreatePort` and existing marketing `marketing_task/target/attempt/outbox` result loop for message sending. Offline or unusable accounts are abandoned in this version; no re-online retry state is implemented.

**Tech Stack:** Java 17, Spring Boot, MyBatis XML mappers, Flyway SQL migrations, existing Armada protocol outbox, Vue 3 + TypeScript + Element Plus.

---

## File Structure

Backend files:

- Create `armada-api/src/main/resources/db/migration/V041__group_creation_marketing_task.sql`: task and item tables.
- Create `armada-api/src/main/java/com/armada/marketing/model/enums/GroupCreationMarketingTaskStatus.java`: task status constants.
- Create `armada-api/src/main/java/com/armada/marketing/model/enums/GroupCreationMarketingItemStatus.java`: item status constants.
- Create `armada-api/src/main/java/com/armada/marketing/model/entity/GroupCreationMarketingTask.java`: task entity.
- Create `armada-api/src/main/java/com/armada/marketing/model/entity/GroupCreationMarketingItem.java`: item entity.
- Create `armada-api/src/main/java/com/armada/marketing/model/dto/CreateGroupCreationMarketingTaskDTO.java`: create request.
- Create `armada-api/src/main/java/com/armada/marketing/model/dto/GroupCreationMarketingMaterialDTO.java`: uploaded file payload.
- Create `armada-api/src/main/java/com/armada/marketing/model/dto/GroupCreationMarketingTaskQuery.java`: list query.
- Create `armada-api/src/main/java/com/armada/marketing/model/vo/GroupCreationMarketingTaskVO.java`: list row.
- Create `armada-api/src/main/java/com/armada/marketing/model/vo/GroupCreationMarketingItemVO.java`: detail item row.
- Create `armada-api/src/main/java/com/armada/marketing/model/vo/GroupCreationMarketingTaskDetailVO.java`: detail response.
- Create `armada-api/src/main/java/com/armada/marketing/model/vo/GroupCreationMarketingAccountCandidate.java`: mapper projection.
- Create `armada-api/src/main/java/com/armada/marketing/mapper/GroupCreationMarketingTaskMapper.java`: mapper interface.
- Create `armada-api/src/main/resources/mapper/marketing/GroupCreationMarketingTaskMapper.xml`: mapper SQL.
- Create `armada-api/src/main/java/com/armada/marketing/service/GroupCreationMarketingTaskService.java`: service interface.
- Create `armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingTaskServiceImpl.java`: create/list/detail service.
- Create `armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingWorker.java`: item executor.
- Create `armada-api/src/main/java/com/armada/marketing/scheduler/GroupCreationMarketingScheduler.java`: profile-gated polling scheduler.
- Create `armada-api/src/main/java/com/armada/marketing/controller/GroupCreationMarketingTaskController.java`: REST controller.
- Modify `armada-api/src/main/java/com/armada/group/service/GroupLinkRegistryService.java`: add `registerSelfBuiltGroup`.
- Modify `armada-api/src/main/java/com/armada/group/service/impl/GroupLinkRegistryServiceImpl.java`: insert/refresh self-built `wa://group/{jid}` group.
- Modify `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingSendResultServiceImpl.java`: update group-creation item status by `attemptId` after marketing result changes.

Backend tests:

- Create `armada-api/src/test/java/com/armada/marketing/GroupCreationMarketingMigrationDbTest.java`.
- Create `armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingTaskServiceImplTest.java`.
- Create `armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingWorkerTest.java`.
- Create `armada-api/src/test/java/com/armada/marketing/controller/GroupCreationMarketingTaskControllerTest.java`.
- Modify `armada-api/src/test/java/com/armada/group/service/impl/GroupLinkRegistryServiceImplTest.java` if it exists; otherwise create it.
- Modify `armada-api/src/test/java/com/armada/marketing/service/MarketingSendResultServiceImplTest.java` or create it if missing.

Frontend files:

- Create `wheel-saas-pure-web/src/api/group-creation-marketing.ts`.
- Create `wheel-saas-pure-web/src/api/group-creation-marketing.test.ts`.
- Create `wheel-saas-pure-web/src/views/task/group-creation-marketing/index.vue`.
- Create `wheel-saas-pure-web/src/views/task/group-creation-marketing/constants.ts`.
- Create `wheel-saas-pure-web/src/views/task/group-creation-marketing/composables/useGroupCreationMarketingPage.ts`.
- Create `wheel-saas-pure-web/src/views/task/group-creation-marketing/composables/useGroupCreationMarketingPage.test.ts`.
- Create `wheel-saas-pure-web/src/views/task/group-creation-marketing/components/GroupCreationMarketingCreateDrawer.vue`.
- Create `wheel-saas-pure-web/src/views/task/group-creation-marketing/components/GroupCreationMarketingTaskTable.vue`.
- Create `wheel-saas-pure-web/src/views/task/group-creation-marketing/components/GroupCreationMarketingDetailDrawer.vue`.
- Modify `wheel-saas-pure-web/mock/asyncRoutes.ts`: add task-center fallback route.

---

### Task 1: Backend Migration

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V041__group_creation_marketing_task.sql`
- Create: `armada-api/src/test/java/com/armada/marketing/GroupCreationMarketingMigrationDbTest.java`

- [ ] **Step 1: Write the failing migration test**

```java
package com.armada.marketing;

import com.armada.DbTestBase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GroupCreationMarketingMigrationDbTest extends DbTestBase {
    @Test
    void groupCreationMarketingTablesExist() {
        assertThat(columnType("group_creation_marketing_task", "task_name")).isEqualTo("varchar");
        assertThat(columnType("group_creation_marketing_task", "status")).isEqualTo("tinyint");
        assertThat(columnType("group_creation_marketing_item", "material_content")).isEqualTo("longtext");
        assertThat(columnType("group_creation_marketing_item", "marketing_attempt_id")).isEqualTo("bigint");
        assertThat(indexExists("group_creation_marketing_item", "idx_gcm_item_due")).isTrue();
        assertThat(indexExists("group_creation_marketing_item", "idx_gcm_item_attempt")).isTrue();
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=GroupCreationMarketingMigrationDbTest test
```

Expected: FAIL because `group_creation_marketing_task` does not exist.

- [ ] **Step 3: Add migration**

```sql
CREATE TABLE IF NOT EXISTS group_creation_marketing_task (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    task_name VARCHAR(128) NOT NULL COMMENT '任务名称',
    account_group_id BIGINT NOT NULL COMMENT '账号分组ID',
    account_group_name VARCHAR(100) NOT NULL COMMENT '账号分组名称快照',
    marketing_template_id BIGINT NOT NULL COMMENT '营销模板ID',
    marketing_template_name VARCHAR(128) NOT NULL COMMENT '营销模板名称快照',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态:1=待执行 2=执行中 3=成功 4=失败 5=部分失败',
    matched_item_count INT NOT NULL DEFAULT 0 COMMENT '匹配执行项数',
    unmatched_file_count INT NOT NULL DEFAULT 0 COMMENT '未匹配文件数',
    success_count INT NOT NULL DEFAULT 0 COMMENT '成功执行项数',
    failed_count INT NOT NULL DEFAULT 0 COMMENT '失败执行项数',
    abandoned_count INT NOT NULL DEFAULT 0 COMMENT '放弃执行项数',
    group_name_prefix VARCHAR(100) DEFAULT NULL COMMENT '群名前缀',
    remark VARCHAR(512) DEFAULT NULL COMMENT '备注',
    created_by BIGINT DEFAULT NULL COMMENT '创建人',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    finished_at BIGINT DEFAULT NULL COMMENT '完成时间(epoch毫秒)',
    deleted_at BIGINT DEFAULT NULL COMMENT '软删时间(epoch毫秒)',
    PRIMARY KEY (id),
    KEY idx_gcm_task_tenant (tenant_id, deleted_at, id),
    KEY idx_gcm_task_status (tenant_id, status, id),
    KEY idx_gcm_task_template (tenant_id, marketing_template_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='建群营销任务';

CREATE TABLE IF NOT EXISTS group_creation_marketing_item (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    task_id BIGINT NOT NULL COMMENT '建群营销任务ID',
    file_index INT NOT NULL COMMENT '上传文件顺序,从0开始',
    file_name VARCHAR(255) NOT NULL COMMENT '上传文件名',
    material_content LONGTEXT NOT NULL COMMENT '规范化后的号码文本,每行一个',
    participant_count INT NOT NULL DEFAULT 0 COMMENT '号码数',
    account_id BIGINT NOT NULL COMMENT '执行账号ID',
    account_phone VARCHAR(32) NOT NULL COMMENT '账号号码快照',
    protocol_account_id VARCHAR(128) DEFAULT NULL COMMENT '协议账号ID快照',
    group_subject VARCHAR(100) NOT NULL COMMENT '实际建群名称',
    group_jid VARCHAR(128) DEFAULT NULL COMMENT '协议返回群JID',
    group_link_id BIGINT DEFAULT NULL COMMENT '本地群入口ID',
    participant_result_json JSON DEFAULT NULL COMMENT '建群逐成员结果摘要',
    marketing_task_id BIGINT DEFAULT NULL COMMENT '复用的营销任务ID',
    marketing_target_id BIGINT DEFAULT NULL COMMENT '复用的营销目标ID',
    marketing_attempt_id BIGINT DEFAULT NULL COMMENT '复用的营销发送尝试ID',
    command_id VARCHAR(64) DEFAULT NULL COMMENT '营销发送协议命令ID',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态:1=待执行 2=建群中 3=营销发送中 4=成功 5=失败 6=放弃',
    reason_code VARCHAR(64) DEFAULT NULL COMMENT '原因码',
    reason_message VARCHAR(255) DEFAULT NULL COMMENT '原因描述',
    next_run_at BIGINT NOT NULL DEFAULT 0 COMMENT '下一次可执行时间(epoch毫秒)',
    started_at BIGINT DEFAULT NULL COMMENT '开始时间(epoch毫秒)',
    finished_at BIGINT DEFAULT NULL COMMENT '完成时间(epoch毫秒)',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_gcm_item_file (tenant_id, task_id, file_index),
    KEY idx_gcm_item_due (tenant_id, status, next_run_at, id),
    KEY idx_gcm_item_task (tenant_id, task_id, id),
    KEY idx_gcm_item_attempt (tenant_id, marketing_attempt_id),
    KEY idx_gcm_item_account (tenant_id, account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='建群营销执行项';
```

- [ ] **Step 4: Run test and verify GREEN**

Run:

```bash
mvn -Dtest=GroupCreationMarketingMigrationDbTest test
```

Expected: PASS.

---

### Task 2: Backend Create/List/Detail Service

**Files:**
- Create DTO/entity/VO/mapper/service/controller files listed in File Structure.
- Test: `armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingTaskServiceImplTest.java`
- Test: `armada-api/src/test/java/com/armada/marketing/controller/GroupCreationMarketingTaskControllerTest.java`

- [ ] **Step 1: Write failing service tests**

Test cases:

```java
@Test
void createPairsAccountsByIdAscendingWithUploadedFileOrder() {
    // given accounts [10, 20, 30] and files [a.txt, b.txt]
    // expect items: 10+a.txt, 20+b.txt and unmatchedFileCount=0
}

@Test
void createIgnoresExtraAccountsAndReturnsMatchedCount() {
    // given 3 accounts and 2 files
    // expect matchedItemCount=2 and no item for account 30
}

@Test
void createIgnoresExtraFilesAndReportsUnmatchedFileCount() {
    // given 1 account and 3 files
    // expect one item and unmatchedFileCount=2
}

@Test
void createRejectsWhenNoMatchedItemsExist() {
    // given no active accounts or no valid files
    // expect BusinessException VALIDATION
}
```

- [ ] **Step 2: Run service tests and verify RED**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=GroupCreationMarketingTaskServiceImplTest test
```

Expected: compile failure because service classes do not exist.

- [ ] **Step 3: Implement enums**

```java
public enum GroupCreationMarketingTaskStatus {
    PENDING(1), RUNNING(2), SUCCESS(3), FAILED(4), PARTIAL_FAILED(5);
    private final int code;
    GroupCreationMarketingTaskStatus(int code) { this.code = code; }
    public int code() { return code; }
}
```

```java
public enum GroupCreationMarketingItemStatus {
    PENDING(1), GROUP_CREATING(2), MARKETING_SENDING(3), SUCCESS(4), FAILED(5), ABANDONED(6);
    private final int code;
    GroupCreationMarketingItemStatus(int code) { this.code = code; }
    public int code() { return code; }
    public boolean terminal() { return this == SUCCESS || this == FAILED || this == ABANDONED; }
}
```

- [ ] **Step 4: Implement create DTOs**

```java
public record GroupCreationMarketingMaterialDTO(
        String fileName,
        String content
) {
}
```

```java
public record CreateGroupCreationMarketingTaskDTO(
        String taskName,
        Long accountGroupId,
        String accountGroupName,
        Long marketingTemplateId,
        String marketingTemplateName,
        String groupNamePrefix,
        String remark,
        List<GroupCreationMarketingMaterialDTO> materials
) {
}
```

- [ ] **Step 5: Implement mapper projection**

```java
public class GroupCreationMarketingAccountCandidate {
    private Long accountId;
    private String accountPhone;
    private String protocolAccountId;
    private Integer accountState;
    private Integer loginState;
    private Integer riskStatus;
    private Integer muteStatus;
    // getters and setters
}
```

- [ ] **Step 6: Implement mapper SQL**

The account candidate query must sort by account ID:

```xml
<select id="selectAccountCandidatesByGroupId"
        resultType="com.armada.marketing.model.vo.GroupCreationMarketingAccountCandidate">
  SELECT a.id AS accountId,
         a.ws_phone AS accountPhone,
         a.protocol_account_id AS protocolAccountId,
         s.account_state AS accountState,
         s.login_state AS loginState,
         s.risk_status AS riskStatus,
         s.mute_status AS muteStatus
  FROM account a
  LEFT JOIN account_state s ON s.account_id = a.id AND s.tenant_id = a.tenant_id
  WHERE a.deleted_at IS NULL
    AND a.account_group_id = #{accountGroupId}
  ORDER BY a.id ASC
</select>
```

- [ ] **Step 7: Implement material parsing**

Use a private helper in `GroupCreationMarketingTaskServiceImpl`:

```java
private static List<String> materialPhones(String content) {
    if (content == null || content.isBlank()) {
        return List.of();
    }
    return content.lines()
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .map(line -> line.split("[,，\\s]+")[0].trim())
            .filter(value -> !value.isBlank())
            .distinct()
            .toList();
}
```

- [ ] **Step 8: Implement create pairing logic**

Create only `min(accounts.size(), validMaterials.size())` items. A material is valid when `materialPhones(content)` is not empty. Use `groupSubject(prefix, taskName, index)`:

```java
private static String groupSubject(String prefix, String taskName, int index) {
    String base = StringUtils.hasText(prefix) ? prefix.trim() : taskName.trim();
    return base + "-" + (index + 1);
}
```

- [ ] **Step 9: Implement controller**

Expose:

```java
@RestController
@RequestMapping("/api/group-creation-marketing-tasks")
public class GroupCreationMarketingTaskController {
    private final GroupCreationMarketingTaskService service;

    public GroupCreationMarketingTaskController(GroupCreationMarketingTaskService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResult<GroupCreationMarketingTaskVO>> list(@ModelAttribute GroupCreationMarketingTaskQuery query) {
        return ApiResponse.ok(service.listTasks(query));
    }

    @PostMapping
    public ApiResponse<GroupCreationMarketingTaskDetailVO> create(@RequestBody CreateGroupCreationMarketingTaskDTO request) {
        return ApiResponse.ok(service.createTask(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<GroupCreationMarketingTaskDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.getDetail(id));
    }
}
```

- [ ] **Step 10: Run service and controller tests**

Run:

```bash
mvn -Dtest=GroupCreationMarketingTaskServiceImplTest,GroupCreationMarketingTaskControllerTest test
```

Expected: PASS.

---

### Task 3: Self-Built Group Registration

**Files:**
- Modify `armada-api/src/main/java/com/armada/group/service/GroupLinkRegistryService.java`
- Modify `armada-api/src/main/java/com/armada/group/service/impl/GroupLinkRegistryServiceImpl.java`
- Modify `armada-api/src/main/java/com/armada/group/mapper/GroupLinkMapper.java`
- Modify `armada-api/src/main/resources/mapper/group/GroupLinkMapper.xml`
- Test: `armada-api/src/test/java/com/armada/group/service/impl/GroupLinkRegistryServiceImplTest.java`

- [ ] **Step 1: Write failing test**

```java
@Test
void registerSelfBuiltGroupCreatesWaGroupLinkAndPreviewMembershipStateOwned() {
    Long groupLinkId = service.registerSelfBuiltGroup(
            "120363new@g.us",
            "任务群-1",
            7L,
            "8613900000000",
            51,
            1000L);

    assertThat(groupLinkId).isNotNull();
    assertThat(groupLinkMapper.selectAnyByUrl("wa://group/120363new@g.us").getOrigin())
            .isEqualTo(GroupLinkOrigin.SELF_BUILT.code());
}
```

- [ ] **Step 2: Run test and verify RED**

Run:

```bash
mvn -Dtest=GroupLinkRegistryServiceImplTest test
```

Expected: compile failure because `registerSelfBuiltGroup` does not exist.

- [ ] **Step 3: Add service method**

```java
Long registerSelfBuiltGroup(String groupJid,
                            String groupName,
                            Long ownerAccountId,
                            String ownerPhone,
                            Integer memberCount,
                            long now);
```

- [ ] **Step 4: Implement registration**

Implementation rules:

- `link_url` is `wa://group/{groupJid}`.
- `origin` is `GroupLinkOrigin.SELF_BUILT.code()`.
- `membership_state` is `GroupMembershipState.OWNED.code()`.
- Insert or revive `group_link`.
- Upsert `group_link_preview` with `group_jid`, subject and `member_size`.
- Upsert `account_group_membership` for the owner account with `is_admin=true`.

- [ ] **Step 5: Run test and verify GREEN**

Run:

```bash
mvn -Dtest=GroupLinkRegistryServiceImplTest test
```

Expected: PASS.

---

### Task 4: Worker Creates Group And Enqueues Marketing Message

**Files:**
- Create `armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingWorker.java`
- Modify `armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java` if private insert helpers are needed.
- Modify `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml` if private insert helpers are needed.
- Test: `armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingWorkerTest.java`

- [ ] **Step 1: Write failing worker tests**

Test cases:

```java
@Test
void processOnlineItemCreatesGroupRegistersGroupAndEnqueuesMarketingAttempt() {
    // given item PENDING, account ONLINE, template valid, groupCreatePort returns groupJid
    // expect item MARKETING_SENDING, groupLinkId set, marketingAttemptId set, outbox command written
}

@Test
void processOfflineItemIsAbandonedWithoutCallingProtocol() {
    // given item PENDING and account login_state OFFLINE
    // expect item ABANDONED with reasonCode ACCOUNT_OFFLINE
}

@Test
void processBannedItemIsAbandonedWithoutCallingProtocol() {
    // given account_state BANNED
    // expect item ABANDONED with reasonCode ACCOUNT_UNUSABLE
}

@Test
void processProtocolGroupCreateFailureMarksItemFailed() {
    // given GroupCreatePort throws ProtocolException
    // expect item FAILED with reason message
}
```

- [ ] **Step 2: Run worker tests and verify RED**

Run:

```bash
mvn -Dtest=GroupCreationMarketingWorkerTest test
```

Expected: compile failure because worker does not exist.

- [ ] **Step 3: Implement item claim mapper**

Add mapper methods:

```java
List<GroupCreationMarketingItem> selectDueItems(@Param("limit") int limit,
                                                @Param("now") long now);

int claimItem(@Param("id") Long id,
              @Param("fromStatus") int fromStatus,
              @Param("toStatus") int toStatus,
              @Param("now") long now);
```

- [ ] **Step 4: Implement account eligibility**

Worker must abandon when any condition is true:

```java
private static boolean unusable(GroupCreationMarketingAccountCandidate account) {
    return account.protocolAccountId() == null
            || account.protocolAccountId().isBlank()
            || Integer.valueOf(AccountStateCode.BANNED).equals(account.accountState())
            || Integer.valueOf(AccountStateCode.EXPORTED).equals(account.accountState())
            || Integer.valueOf(AccountStateCode.UNBOUND).equals(account.accountState())
            || (account.riskStatus() != null && account.riskStatus() > 1)
            || account.muteStatus() != null;
}
```

Offline condition is:

```java
!Integer.valueOf(AccountLoginStateCode.ONLINE).equals(account.loginState())
```

- [ ] **Step 5: Implement group create call**

Use existing port:

```java
GroupCreateResult result = groupCreatePort.create(
        account.getProtocolAccountId(),
        item.getGroupSubject(),
        item.getMaterialContent().lines().map(String::trim).filter(s -> !s.isBlank()).toList());
```

- [ ] **Step 6: Implement marketing outbox generation**

Do not call `MarketingRoundWorker` because it resolves existing task targets by rounds. Instead, reuse the same primitives:

- Load `MarketingTemplate` and optional `MarketingTemplateFile`.
- Call `MarketingMessageComposer.compose(template, imageFile)`.
- Insert a hidden/linked `marketing_task` row for this group-creation task if it does not exist.
- Insert one `marketing_task_target` for the new group.
- Insert one `marketing_task_send_attempt` with status `SUBMITTED`.
- Enqueue one `ProtocolMarketingMessageCommandRequest`.
- Store `marketingTaskId`, `marketingTargetId`, `marketingAttemptId`, and `commandId` on the item.

- [ ] **Step 7: Run worker tests and verify GREEN**

Run:

```bash
mvn -Dtest=GroupCreationMarketingWorkerTest test
```

Expected: PASS.

---

### Task 5: Marketing Result Updates Group-Creation Items

**Files:**
- Modify `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingSendResultServiceImpl.java`
- Modify `armada-api/src/main/java/com/armada/marketing/mapper/GroupCreationMarketingTaskMapper.java`
- Modify `armada-api/src/main/resources/mapper/marketing/GroupCreationMarketingTaskMapper.xml`
- Test: `armada-api/src/test/java/com/armada/marketing/service/MarketingSendResultServiceImplTest.java`

- [ ] **Step 1: Write failing tests**

```java
@Test
void successfulMarketingResultMarksGroupCreationItemSuccess() {
    // given item.marketing_attempt_id = event.attemptId
    // expect item SUCCESS and task success_count incremented once
}

@Test
void failedMarketingResultMarksGroupCreationItemFailed() {
    // given item.marketing_attempt_id = event.attemptId
    // expect item FAILED and task failed_count incremented once
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
mvn -Dtest=MarketingSendResultServiceImplTest test
```

Expected: FAIL because item status is not updated.

- [ ] **Step 3: Add mapper methods**

```java
int markItemSuccessByMarketingAttemptId(@Param("attemptId") Long attemptId,
                                        @Param("finishedAt") long finishedAt);

int markItemFailedByMarketingAttemptId(@Param("attemptId") Long attemptId,
                                       @Param("reasonCode") String reasonCode,
                                       @Param("reasonMessage") String reasonMessage,
                                       @Param("finishedAt") long finishedAt);
```

- [ ] **Step 4: Update send result service**

After the existing marketing attempt update returns `updated > 0`, call the group-creation mapper. Only increment group-creation task counters when the item row changed from `MARKETING_SENDING`.

- [ ] **Step 5: Run tests and verify GREEN**

Run:

```bash
mvn -Dtest=MarketingSendResultServiceImplTest test
```

Expected: PASS.

---

### Task 6: Scheduler And Controller Smoke

**Files:**
- Create `armada-api/src/main/java/com/armada/marketing/scheduler/GroupCreationMarketingScheduler.java`
- Test: `armada-api/src/test/java/com/armada/marketing/controller/GroupCreationMarketingTaskControllerTest.java`

- [ ] **Step 1: Add scheduler**

```java
@Component
@Profile("kafka")
public class GroupCreationMarketingScheduler {
    private final GroupCreationMarketingWorker worker;

    @Scheduled(fixedDelayString = "${armada.group-creation-marketing.scheduler.fixed-delay-ms:5000}")
    public void run() {
        worker.processDueItems(20);
    }
}
```

- [ ] **Step 2: Add controller smoke test**

```java
@Test
void createDelegatesToServiceAndReturnsDetail() throws Exception {
    mockMvc.perform(post("/api/group-creation-marketing-tasks")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                    {"taskName":"建群营销","accountGroupId":8,"marketingTemplateId":18,
                     "materials":[{"fileName":"a.txt","content":"8613900000000"}]}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.taskName").value("建群营销"));
}
```

- [ ] **Step 3: Run backend slice tests**

Run:

```bash
mvn -Dtest=GroupCreationMarketingMigrationDbTest,GroupCreationMarketingTaskServiceImplTest,GroupCreationMarketingWorkerTest,GroupCreationMarketingTaskControllerTest,MarketingSendResultServiceImplTest test
```

Expected: PASS.

---

### Task 7: Frontend API And Page State

**Files:**
- Create `wheel-saas-pure-web/src/api/group-creation-marketing.ts`
- Create `wheel-saas-pure-web/src/api/group-creation-marketing.test.ts`
- Create `wheel-saas-pure-web/src/views/task/group-creation-marketing/composables/useGroupCreationMarketingPage.ts`
- Create `wheel-saas-pure-web/src/views/task/group-creation-marketing/composables/useGroupCreationMarketingPage.test.ts`

- [ ] **Step 1: Write failing API test**

```ts
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { armadaCalls, resetArmadaMock } from "@/api/__tests__/armada-test-double";
import { createGroupCreationMarketingTask } from "./group-creation-marketing";

describe("group creation marketing API", () => {
  it("posts ordered material files", async () => {
    resetArmadaMock({ id: 1 });
    await createGroupCreationMarketingTask({
      taskName: "建群营销",
      accountGroupId: 8,
      accountGroupName: "A组",
      marketingTemplateId: 18,
      marketingTemplateName: "模板",
      groupNamePrefix: "活动群",
      remark: null,
      materials: [
        { fileName: "a.txt", content: "8613900000000" },
        { fileName: "b.txt", content: "8613911111111" }
      ]
    });
    assert.equal(armadaCalls()[0].url, "/api/group-creation-marketing-tasks");
  });
});
```

- [ ] **Step 2: Run API test and verify RED**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
node --import tsx src/api/group-creation-marketing.test.ts
```

Expected: FAIL because API file does not exist.

- [ ] **Step 3: Implement API file**

```ts
export interface GroupCreationMarketingMaterialPayload {
  fileName: string;
  content: string;
}

export interface CreateGroupCreationMarketingTaskPayload {
  taskName: string;
  accountGroupId: number;
  accountGroupName: string;
  marketingTemplateId: number;
  marketingTemplateName: string;
  groupNamePrefix?: string | null;
  remark?: string | null;
  materials: GroupCreationMarketingMaterialPayload[];
}

export function createGroupCreationMarketingTask(
  data: CreateGroupCreationMarketingTaskPayload
) {
  return armadaRequest("post", "/api/group-creation-marketing-tasks", { data });
}
```

- [ ] **Step 4: Write page-state matching test**

```ts
it("keeps upload order and marks extra files unmatched", async () => {
  const page = useGroupCreationMarketingPage();
  page.accountGroups.value = [{ id: 8, name: "A组", totalAccounts: 1, onlineAccounts: 1, abnormalAccounts: 0, bannedAccounts: 0, systemBuiltin: false }];
  page.accounts.value = [{ accountId: 10, wsPhone: "8613000000000", status: "ONLINE" }];
  await page.addMaterialFiles([
    new File(["8613900000000"], "a.txt"),
    new File(["8613911111111"], "b.txt")
  ]);
  assert.equal(page.matchRows.value[0].fileName, "a.txt");
  assert.equal(page.unmatchedFiles.value[0].fileName, "b.txt");
});
```

- [ ] **Step 5: Implement composable**

Composable responsibilities:

- Load account groups and marketing templates.
- Load account candidates for selected group from backend preview endpoint or reuse `/api/marketing-tasks/account-tree`.
- Read files using `file.text()`.
- Preserve upload order.
- Compute `matchRows = accounts.slice(0, files.length).map((account, index) => account + files[index])`.
- Compute `unmatchedFiles = files.slice(accounts.length)`.
- Submit only uploaded material payloads; backend remains source of truth for actual matched items.

- [ ] **Step 6: Run frontend tests**

Run:

```bash
node --import tsx src/api/group-creation-marketing.test.ts
node --import tsx src/views/task/group-creation-marketing/composables/useGroupCreationMarketingPage.test.ts
```

Expected: PASS.

---

### Task 8: Frontend Views And Menu

**Files:**
- Create `wheel-saas-pure-web/src/views/task/group-creation-marketing/index.vue`
- Create components listed in File Structure.
- Modify `wheel-saas-pure-web/mock/asyncRoutes.ts`

- [ ] **Step 1: Add mock route**

Add under `taskRouter.children`:

```ts
{
  path: "/task/group-creation-marketing",
  component: "task/group-creation-marketing/index",
  name: "TaskGroupCreationMarketing",
  meta: {
    title: "建群营销",
    roles: ["admin", "common"],
    showParent: true,
    module_key: "group_creation_marketing",
    perm_key: "tenant:group_creation_marketing:view"
  }
}
```

- [ ] **Step 2: Build page with Element Plus**

Use these controls:

- `ElForm` for search and create drawer.
- `ElSelect` for account group and template.
- `ElUpload` with `multiple` and `auto-upload=false`.
- `ElTable` for match preview and task list.
- `ElDrawer` for create/detail.
- `ElPagination` for list paging.

- [ ] **Step 3: Run typecheck**

Run:

```bash
pnpm typecheck
```

Expected: PASS.

---

## Final Verification

- [ ] **Backend focused tests**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=GroupCreationMarketingMigrationDbTest,GroupCreationMarketingTaskServiceImplTest,GroupCreationMarketingWorkerTest,GroupCreationMarketingTaskControllerTest,MarketingSendResultServiceImplTest,GroupLinkRegistryServiceImplTest,GroupOperationServiceImplTest test
```

- [ ] **Frontend focused tests**

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
node --import tsx src/api/group-creation-marketing.test.ts
node --import tsx src/views/task/group-creation-marketing/composables/useGroupCreationMarketingPage.test.ts
pnpm typecheck
```

- [ ] **Manual local smoke**

Run frontend dev server:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
pnpm dev
```

Open `/task/group-creation-marketing`, choose an account group, upload two small `.txt` files, verify the match preview preserves upload order and extra files show as unmatched.
