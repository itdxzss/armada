# Pull Task Standard Full Form Persistence Implementation Plan

> 执行状态（2026-08-04）：本地代码步骤已完成；所有 commit 步骤按用户要求跳过；完整 Maven DB 回归及手工端到端验收待一次性本地 MySQL/Docker 环境。实际验证证据以 `.harness/changes/pull-task-standard-full-form/summary.md` 为准。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the normal-link standard pull-task page submit, validate, persist, and read back every approved field in one large-form save, including local group-avatar storage and group-folder link sourcing.

**Architecture:** Keep `pull_task` as the lifecycle root, extend `pull_task_standard_setting` for frozen execution policy, add exactly one `pull_task_standard_group_setting` row for desired group profile/permission settings, and keep frozen links/materials in the existing execution tables. The UI performs one save action: upload a newly selected avatar first, then submit the complete JSON form; the backend database write is one transaction, while local files use an explicit pending/bound lifecycle.

**Tech Stack:** Java 17, Spring Boot 3.3.5, Spring MVC/Security/Transactions/Scheduling, MyBatis-Plus/MySQL/Flyway, JUnit 5/AssertJ/Mockito/H2; Vue 3, TypeScript, Element Plus, Axios wrapper, Node test runner, pnpm/Vite.

---

## Scope and fixed decisions

- Applies only to `pull_task.task_type=STANDARD` and `mode=NORMAL_LINK`.
- Excludes the red “模板与内容/营销规则” block, every field marked “后期”, and WhatsApp protocol application of the new group settings.
- The page has one save action and one complete create request. There is no independent group-setting save endpoint.
- `avatar_file_key` exists only in `pull_task_standard_group_setting`; do not add `pull_task_group_avatar_file` or any other avatar table.
- Avatar files are JPG/JPEG/PNG, at most `512000` bytes, and live under `/app/data/pull-task-avatars/{tenantId}/{key}`.
- `group_link_label` remains the import-source category. `group_folder` is the group-list operational folder. Never merge the semantics.
- A selected folder and pasted links may both contribute links. Normalize and deduplicate before freezing execution rows.
- New standard tasks keep `pull_task.config_json='{}'` and do not write `pull_task.group_name`; legacy task modes keep their current behavior.
- Do not modify or deploy to a real database, remote host, or server while executing this plan.

## Execution preflight: protect the in-flight migration reorder

The backend worktree currently contains an uncommitted V090–V094 reorder. Before Task 1, run these commands and compare the output with the design document:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git status --short
git diff -- armada-api/src/main/resources/db/migration
git diff -- armada-api/src/test/java/com/armada/boot/FlywayAppliedMigrationCompatibilityTest.java
git diff -- armada-api/src/test/java/com/armada/task/PullTaskNormalLinkMigrationSqlTest.java
git diff -- armada-api/src/test/java/com/armada/task/mapper/PullTaskNormalLinkSchema.java
```

Expected at plan-writing time:

- `V090__group_folder.sql` and `V091__whatsapp_group_departed_member.sql` through `V094__pull_task_group_account_membership_result.sql` are in flight.
- The old V090/V091 normal-link filenames are deleted because those migrations moved to V093/V094.
- The compatibility and H2 schema tests are already modified by that migration work.

Rules while implementing:

1. Re-read status immediately before every commit.
2. Do not overwrite V091–V094 or their associated test hunks.
3. Reconcile only the `group_folder` part of V090: change `name VARCHAR(64)` to the approved `VARCHAR(100)` if that file is still the active unapplied migration.
4. Allocate the settings migration as `V095__pull_task_standard_full_form_settings.sql` only if V095 is still unused. If another session takes V095, use the next free number and update every test/reference in the same task.
5. Stage exact paths or exact hunks. Never use `git add .` in this dirty worktree.

## Request-to-storage contract

| Request field | Canonical storage |
|---|---|
| `taskName`, `remark` | `pull_task` |
| `autoStart`, `groupFolderId` + backend name snapshot | `pull_task_standard_setting` |
| `pullerSyncMode`, `materialAdminTiming`, `clearExistingMembers` | `pull_task_standard_setting` |
| pull ranges, intervals, puller/station/concurrency/risk counts | `pull_task_standard_setting` |
| manager/puller/station group IDs + backend name snapshots | `pull_task_standard_setting` |
| manager/puller finish group IDs + backend name snapshots | `pull_task_standard_setting` |
| `groupSetting.*` | `pull_task_standard_group_setting` |
| normalized group URLs, TXT names and order | `pull_task_group_execution` |
| normalized material phones and `A/a` admin flags | `pull_task_material_member` |

## Task 1: Finish the schema contract and migration coverage

**Files:**

- Modify: `armada-api/src/main/resources/db/migration/V090__group_folder.sql`
- Create: `armada-api/src/main/resources/db/migration/V095__pull_task_standard_full_form_settings.sql` (or the verified next free version)
- Modify: `armada-api/src/test/java/com/armada/task/PullTaskNormalLinkMigrationSqlTest.java`
- Modify: `armada-api/src/test/java/com/armada/boot/FlywayAppliedMigrationCompatibilityTest.java`
- Modify: `armada-api/src/test/java/com/armada/task/mapper/PullTaskNormalLinkSchema.java`
- Modify: `.harness/wiki/数据模型.md`
- Create: `.harness/changes/pull-task-standard-full-form/db-migrations.sql`
- Create: `.harness/changes/pull-task-standard-full-form/rollback.sql`
- Create: `.harness/changes/pull-task-standard-full-form/summary.md`

- [ ] **Step 1: Write failing migration structure tests**

Add assertions that the new migration:

- adds the eight execution-setting columns;
- makes both station snapshot columns nullable;
- creates `pull_task_standard_group_setting` with one row per `(tenant_id, task_id)`;
- creates the non-null avatar uniqueness constraint `(tenant_id, avatar_file_key)`;
- does not create an avatar file table or add `avatar_file_key` to any other table.

The core assertions should be explicit strings/regexes, for example:

```java
assertThat(sql).contains("ADD COLUMN source_group_folder_id BIGINT NULL");
assertThat(sql).contains("MODIFY COLUMN station_group_id BIGINT NULL");
assertThat(sql).contains("CREATE TABLE pull_task_standard_group_setting");
assertThat(sql).contains("UNIQUE KEY uq_pull_task_standard_group_setting_task (tenant_id, task_id)");
assertThat(sql).contains("UNIQUE KEY uq_pull_task_standard_group_setting_avatar (tenant_id, avatar_file_key)");
assertThat(sql).doesNotContain("pull_task_group_avatar_file");
```

- [ ] **Step 2: Run the focused tests and confirm RED**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest='PullTaskNormalLinkMigrationSqlTest,FlywayAppliedMigrationCompatibilityTest' test
```

Expected: failure because the settings migration/schema assertions are not implemented yet.

- [ ] **Step 3: Implement the Flyway migration**

Use this exact data contract (with the verified migration version):

```sql
ALTER TABLE pull_task_standard_setting
    ADD COLUMN source_group_folder_id BIGINT NULL COMMENT '群组运营分组ID' AFTER auto_start,
    ADD COLUMN source_group_folder_name VARCHAR(100) NULL COMMENT '群组运营分组名称快照' AFTER source_group_folder_id,
    ADD COLUMN puller_sync_mode TINYINT NOT NULL DEFAULT 1 COMMENT '拉手同步模式:1单个,2批量' AFTER material_admin_timing,
    ADD COLUMN is_clear_existing_members TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否先清空群原成员' AFTER puller_sync_mode,
    ADD COLUMN manager_finish_group_id BIGINT NULL COMMENT '管理员完成分组ID' AFTER station_group_id,
    ADD COLUMN manager_finish_group_name VARCHAR(100) NULL COMMENT '管理员完成分组名称快照' AFTER station_group_name,
    ADD COLUMN puller_finish_group_id BIGINT NULL COMMENT '拉手完成分组ID' AFTER manager_finish_group_id,
    ADD COLUMN puller_finish_group_name VARCHAR(100) NULL COMMENT '拉手完成分组名称快照' AFTER manager_finish_group_name,
    MODIFY COLUMN station_group_id BIGINT NULL COMMENT '站台账号分组ID',
    MODIFY COLUMN station_group_name VARCHAR(100) NULL COMMENT '站台账号分组名称快照';

CREATE TABLE pull_task_standard_group_setting (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    task_id BIGINT NOT NULL COMMENT '拉群任务ID',
    setting_timing TINYINT NOT NULL DEFAULT 2 COMMENT '设置顺序:1拉人前,2拉完后',
    group_name VARCHAR(128) NULL COMMENT '手工群名;使用TXT文件名时为空',
    is_material_filename_as_group_name TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否使用TXT文件名为群名',
    avatar_file_key VARCHAR(512) NULL COMMENT '当前租户头像目录内安全文件Key',
    group_description VARCHAR(1024) NULL COMMENT '群描述',
    is_auto_unmute_after_task TINYINT(1) NOT NULL DEFAULT 0 COMMENT '任务完成后自动解除禁言',
    is_auto_close_invite_after_task TINYINT(1) NOT NULL DEFAULT 0 COMMENT '任务完成后关闭拉人权限',
    edit_permission_mode TINYINT NOT NULL DEFAULT 0 COMMENT '编辑权限:0不操作,1允许,2不允许',
    mute_mode TINYINT NOT NULL DEFAULT 0 COMMENT '禁言:0不操作,1禁言,2不禁言',
    link_permission_mode TINYINT NOT NULL DEFAULT 2 COMMENT '群链接权限:1所有人,2仅管理员',
    disappearing_message_mode TINYINT NOT NULL DEFAULT 0 COMMENT '限时消息:0不操作,1一天,2七天,3九十天,4关闭',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_pull_task_standard_group_setting_task (tenant_id, task_id),
    UNIQUE KEY uq_pull_task_standard_group_setting_avatar (tenant_id, avatar_file_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='普通群链接任务群资料与权限设置';
```

Also change V090 `group_folder.name` to `VARCHAR(100)` and keep `created_by`/soft deletion. Do not change the link-label columns.

- [ ] **Step 4: Mirror the schema in H2**

Extend `PullTaskNormalLinkSchema.STANDARD_SETTING`, add `STANDARD_GROUP_SETTING`, add it to `all()`, and add the minimal `GROUP_FOLDER`, `GROUP_LINK`, `GROUP_LINK_HEALTH` test DDL needed by later mapper/service tests. Keep the generated-column constraints already present in this file.

- [ ] **Step 5: Write migration and rollback artifacts**

Copy the exact forward DDL into `db-migrations.sql`. `rollback.sql` must:

1. drop `pull_task_standard_group_setting`;
2. drop the eight added setting columns;
3. restore station columns to `NOT NULL` only after documenting that NULL rows must be remediated first;
4. not delete avatar files automatically;
5. not drop legacy `config_json` or `group_name`.

- [ ] **Step 6: Run the focused tests and confirm GREEN**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest='PullTaskNormalLinkMigrationSqlTest,FlywayAppliedMigrationCompatibilityTest,PullTaskNormalLinkSchemaSelfTest' test
```

Expected: all listed tests pass.

- [ ] **Step 7: Commit only the schema slice**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/main/resources/db/migration/V090__group_folder.sql
git add armada-api/src/main/resources/db/migration/V095__pull_task_standard_full_form_settings.sql
git add -p armada-api/src/test/java/com/armada/task/PullTaskNormalLinkMigrationSqlTest.java
git add -p armada-api/src/test/java/com/armada/boot/FlywayAppliedMigrationCompatibilityTest.java
git add -p armada-api/src/test/java/com/armada/task/mapper/PullTaskNormalLinkSchema.java
git add .harness/wiki/数据模型.md .harness/changes/pull-task-standard-full-form
git diff --cached --check
git commit -m "feat: add standard pull task full form schema"
```

If the verified migration number is not V095, replace that exact path in the staging command.

## Task 2: Implement the group-folder backend contract

**Files:**

- Create: `armada-api/src/main/java/com/armada/group/model/entity/GroupFolder.java`
- Create: `armada-api/src/main/java/com/armada/group/model/dto/GroupFolderQuery.java`
- Create: `armada-api/src/main/java/com/armada/group/model/dto/GroupFolderWriteDTO.java`
- Create: `armada-api/src/main/java/com/armada/group/model/vo/GroupFolderVO.java`
- Create: `armada-api/src/main/java/com/armada/group/model/vo/GroupFolderOptionVO.java`
- Create: `armada-api/src/main/java/com/armada/group/model/vo/GroupFolderDeleteVO.java`
- Create: `armada-api/src/main/java/com/armada/group/mapper/GroupFolderMapper.java`
- Create: `armada-api/src/main/resources/mapper/group/GroupFolderMapper.xml`
- Create: `armada-api/src/main/java/com/armada/group/service/GroupFolderService.java`
- Create: `armada-api/src/main/java/com/armada/group/service/impl/GroupFolderServiceImpl.java`
- Create: `armada-api/src/main/java/com/armada/group/controller/GroupFolderController.java`
- Modify: `armada-api/src/main/java/com/armada/group/model/entity/GroupLink.java`
- Create: `armada-api/src/test/java/com/armada/group/mapper/GroupFolderMapperInMemoryTest.java`
- Create: `armada-api/src/test/java/com/armada/group/service/GroupFolderServiceImplTest.java`
- Create: `armada-api/src/test/java/com/armada/group/controller/GroupFolderControllerTest.java`

- [ ] **Step 1: Write failing mapper tests**

Cover insert/list/options/update, active-name uniqueness, soft-delete/revive, and batch deletion. Seed links with all health combinations and assert `groupCount`/planning links count only rows satisfying:

```sql
g.deleted_at IS NULL
AND g.folder_id = #{folderId}
AND NULLIF(TRIM(g.link_url), '') IS NOT NULL
AND h.health_status = 1
AND COALESCE(h.is_banned, 0) = 0
```

Assert batch delete first sets matching active `group_link.folder_id=NULL`, then soft-deletes folders, returning both affected counts.

- [ ] **Step 2: Confirm RED**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest='GroupFolderMapperInMemoryTest' test
```

Expected: compilation failure because the mapper/domain do not exist.

- [ ] **Step 3: Implement the mapper and domain objects**

Use these public contracts:

```java
public record GroupFolderWriteDTO(String name) {}

public record GroupFolderVO(long id, String name, long groupCount,
                            long createdAt, long updatedAt) {}

public record GroupFolderOptionVO(long id, String name) {}

public record GroupFolderDeleteVO(int deletedFolderCount,
                                  int ungroupedGroupCount) {}
```

Add `folderId` to `GroupLink`. Keep all group-folder SQL tenant-interceptor compatible: production CRUD SQL does not manually substitute a request tenant ID.

- [ ] **Step 4: Write failing service/controller tests**

Cover:

- trimmed names, length `1..100`, create/revive, rename collision;
- `requireExisting(id)` returning the active row or `NOT_FOUND`;
- `usableLinks(id)` returning only the SQL-defined effective links;
- batch size `1..100`, deduplicated IDs, unlink-before-soft-delete transaction;
- response shapes matching `src/api/group-folder.ts` exactly;
- query permission accepts `tenant:group_link:view` or `tenant:pull_task:view`; write endpoints require `tenant:group_link:view`.

- [ ] **Step 5: Implement Service and Controller**

The service boundary must include both UI management and the cross-domain task dependency:

```java
public interface GroupFolderService {
    PageResult<GroupFolderVO> list(GroupFolderQuery query);
    List<GroupFolderOptionVO> options();
    GroupFolderVO create(GroupFolderWriteDTO request, long userId);
    void update(long id, GroupFolderWriteDTO request);
    GroupFolderDeleteVO batchDelete(List<Long> ids);
    GroupFolder requireExisting(long id);
    List<String> usableLinks(long id);
}
```

Expose exactly:

```text
GET    /api/group-folders
GET    /api/group-folders/options
POST   /api/group-folders
PATCH  /api/group-folders/{id}
POST   /api/group-folders/batch-delete
```

Use existing `GroupIdsDTO` for batch delete. Return `GroupFolderDeleteVO`, not a bare count.

- [ ] **Step 6: Run all group-folder tests**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest='GroupFolderMapperInMemoryTest,GroupFolderServiceImplTest,GroupFolderControllerTest' test
```

Expected: all pass, with no real database connection.

- [ ] **Step 7: Commit**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/main/java/com/armada/group armada-api/src/main/resources/mapper/group/GroupFolderMapper.xml armada-api/src/test/java/com/armada/group
git diff --cached --check
git commit -m "feat: implement group folder backend"
```

Review the staged file list before committing so unrelated group-domain changes are not included.

## Task 3: Let draft planning merge folder links with pasted links

**Files:**

- Modify: `armada-api/src/main/java/com/armada/task/controller/PullTaskStandardController.java`
- Modify: `armada-api/src/main/java/com/armada/task/service/PullTaskStandardDraftService.java`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardDraftServiceImpl.java`
- Modify: `armada-api/src/test/java/com/armada/task/controller/PullTaskStandardControllerTest.java`
- Modify: `armada-api/src/test/java/com/armada/task/service/PullTaskStandardDraftServicePlanTest.java`

- [ ] **Step 1: Add failing merge tests**

Test four paths:

1. pasted links only remains backward compatible;
2. folder links only are accepted;
3. folder + pasted links merge in deterministic source order and normalized duplicates occur once;
4. missing/cross-tenant folder produces `NOT_FOUND` and writes no draft rows.

The controller contract becomes:

```java
public ApiResponse<PullTaskStandardDraftVO> plan(
        @RequestParam(required = false) Long groupFolderId,
        @RequestParam(required = false) String linksText,
        @RequestPart(required = false) MultipartFile[] files,
        Principal principal)
```

- [ ] **Step 2: Confirm RED**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest='PullTaskStandardDraftServicePlanTest,PullTaskStandardControllerTest' test
```

- [ ] **Step 3: Implement through the group-domain Service**

Inject `GroupFolderService`, never `GroupFolderMapper`, into the task service. Compose source text without changing the existing probe/matcher rules:

```java
List<String> folderLinks = groupFolderId == null
        ? List.of()
        : groupFolderService.usableLinks(groupFolderId);
String mergedLinks = Stream.concat(folderLinks.stream(), lines(linksText).stream())
        .filter(StringUtils::hasText)
        .collect(Collectors.joining("\n"));
```

Feed `mergedLinks` into the existing link probe so invite-code normalization and duplicate classification remain single-sourced. Reject when both sources resolve to zero links. Once rows are written, later folder moves/deletion must not alter them.

- [ ] **Step 4: Confirm GREEN and commit**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest='PullTaskStandardDraftServicePlanTest,PullTaskStandardControllerTest' test
```

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/main/java/com/armada/task/controller/PullTaskStandardController.java armada-api/src/main/java/com/armada/task/service/PullTaskStandardDraftService.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardDraftServiceImpl.java armada-api/src/test/java/com/armada/task/controller/PullTaskStandardControllerTest.java armada-api/src/test/java/com/armada/task/service/PullTaskStandardDraftServicePlanTest.java
git diff --cached --check
git commit -m "feat: plan pull tasks from group folders"
```

## Task 4: Define the complete backend DTO and enum contract

**Files:**

- Modify: `armada-api/src/main/java/com/armada/task/model/dto/PullTaskStandardCreateDTO.java`
- Create: `armada-api/src/main/java/com/armada/task/model/dto/PullTaskStandardGroupSettingDTO.java`
- Create: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskPullerSyncMode.java`
- Create: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskGroupSettingTiming.java`
- Create: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskEditPermissionMode.java`
- Create: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskMuteMode.java`
- Create: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskLinkPermissionMode.java`
- Create: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskDisappearingMessageMode.java`
- Create: `armada-api/src/test/java/com/armada/task/model/dto/PullTaskStandardCreateDTOTest.java`
- Create: `armada-api/src/test/java/com/armada/task/model/enums/PullTaskStandardSettingEnumsTest.java`
- Modify: `armada-api/src/test/java/com/armada/task/controller/PullTaskStandardControllerTest.java`

- [ ] **Step 1: Write failing serialization and mapping tests**

Assert the exact JSON strings and database codes:

```text
PullerSyncMode: SINGLE=1, BATCH=2
GroupSettingTiming: BEFORE_PULL=1, AFTER_PULL=2
EditPermissionMode: UNCHANGED=0, ALLOW=1, DISALLOW=2
MuteMode: UNCHANGED=0, MUTE=1, UNMUTE=2
LinkPermissionMode: ALL=1, ADMIN_ONLY=2
DisappearingMessageMode: UNCHANGED=0, ONE_DAY=1, SEVEN_DAYS=2, NINETY_DAYS=3, OFF=4
```

Every enum needs `code()` and `fromCode(int)`, with an explicit exception for unknown codes. Add a controller test proving an unknown top-level or nested JSON field is rejected rather than ignored.

- [ ] **Step 2: Confirm RED**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest='PullTaskStandardCreateDTOTest,PullTaskStandardSettingEnumsTest,PullTaskStandardControllerTest' test
```

- [ ] **Step 3: Implement the complete request records**

Use this signature so field ownership is reviewable at the HTTP boundary:

```java
public record PullTaskStandardCreateDTO(
        Long draftTaskId,
        Integer version,
        String taskName,
        String remark,
        Integer autoStart,
        Long groupFolderId,
        PullTaskPullerSyncMode pullerSyncMode,
        Integer materialAdminTiming,
        Boolean clearExistingMembers,
        Integer pullCountMin,
        Integer pullCountMax,
        Integer pullIntervalSeconds,
        Integer pullerCountPerGroup,
        Integer stationCountPerCall,
        Integer concurrentGroupCount,
        Long managerGroupId,
        Long pullerGroupId,
        Long stationGroupId,
        Long managerFinishGroupId,
        Long pullerFinishGroupId,
        PullTaskStandardGroupSettingDTO groupSetting) {}
```

```java
public record PullTaskStandardGroupSettingDTO(
        PullTaskGroupSettingTiming settingTiming,
        String groupName,
        Boolean useMaterialFileNameAsGroupName,
        String avatarFileKey,
        String groupDescription,
        Boolean autoCloseMuteAfterTask,
        Boolean autoCloseInviteAfterTask,
        PullTaskEditPermissionMode editPermission,
        PullTaskMuteMode muteMode,
        PullTaskLinkPermissionMode linkPermission,
        PullTaskDisappearingMessageMode disappearingMessage) {}
```

Do not add marketing or “后期” fields.

- [ ] **Step 4: Confirm GREEN and commit**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest='PullTaskStandardCreateDTOTest,PullTaskStandardSettingEnumsTest,PullTaskStandardControllerTest' test
```

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/main/java/com/armada/task/model/dto armada-api/src/main/java/com/armada/task/model/enums armada-api/src/test/java/com/armada/task/model/dto armada-api/src/test/java/com/armada/task/model/enums armada-api/src/test/java/com/armada/task/controller/PullTaskStandardControllerTest.java
git diff --cached --check
git commit -m "feat: define standard pull task full form contract"
```

## Task 5: Persist both normalized setting aggregates

**Files:**

- Modify: `armada-api/src/main/java/com/armada/task/model/entity/PullTaskStandardSetting.java`
- Modify: `armada-api/src/main/java/com/armada/task/mapper/PullTaskStandardSettingMapper.java`
- Modify: `armada-api/src/main/resources/mapper/task/PullTaskStandardSettingMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardSettingWriter.java`
- Create: `armada-api/src/main/java/com/armada/task/model/entity/PullTaskStandardGroupSetting.java`
- Create: `armada-api/src/main/java/com/armada/task/mapper/PullTaskStandardGroupSettingMapper.java`
- Create: `armada-api/src/main/resources/mapper/task/PullTaskStandardGroupSettingMapper.xml`
- Create: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardGroupSettingWriter.java`
- Modify: `armada-api/src/test/java/com/armada/task/mapper/PullTaskStandardSettingMapperInMemoryTest.java`
- Create: `armada-api/src/test/java/com/armada/task/mapper/PullTaskStandardGroupSettingMapperInMemoryTest.java`
- Create: `armada-api/src/test/java/com/armada/task/service/PullTaskStandardSettingWriterTest.java`
- Create: `armada-api/src/test/java/com/armada/task/service/PullTaskStandardGroupSettingWriterTest.java`

- [ ] **Step 1: Add failing mapper round-trip tests**

Insert every new column with non-default values, read by task ID, and assert exact equality. Add separate cases for:

- nullable station group when station count is zero;
- nullable finish groups;
- multiple NULL avatar keys allowed;
- the same non-null avatar key rejected for a second task;
- tenant isolation for both setting tables.

- [ ] **Step 2: Confirm RED**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest='PullTaskStandardSettingMapperInMemoryTest,PullTaskStandardGroupSettingMapperInMemoryTest' test
```

- [ ] **Step 3: Implement entity and mapper parity**

Keep Java field names mechanically aligned to columns. The group writer must normalize text once:

```java
String groupName = trimToNull(request.groupName());
if (Boolean.TRUE.equals(request.useMaterialFileNameAsGroupName())) {
    groupName = null;
}
String description = trimToNull(request.groupDescription());
```

Map API names deliberately:

```text
autoCloseMuteAfterTask -> is_auto_unmute_after_task
autoCloseInviteAfterTask -> is_auto_close_invite_after_task
```

- [ ] **Step 4: Write failing writer validation tests**

Assert:

- manager and puller groups are always looked up through `AccountGroupService.requireExisting`;
- station is not looked up when count is zero and ID is null;
- station is required/looked up when count is positive;
- source folder is looked up through `GroupFolderService.requireExisting` and name is snapshotted;
- optional finish groups are looked up only when non-null;
- request-provided names cannot enter snapshots;
- group setting is required; text lengths are 128/1024 after trimming;
- all booleans/enums are non-null.

- [ ] **Step 5: Implement writers and confirm GREEN**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest='PullTaskStandardSettingMapperInMemoryTest,PullTaskStandardGroupSettingMapperInMemoryTest,PullTaskStandardSettingWriterTest,PullTaskStandardGroupSettingWriterTest' test
```

- [ ] **Step 6: Commit**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/main/java/com/armada/task/model/entity/PullTaskStandardSetting.java armada-api/src/main/java/com/armada/task/model/entity/PullTaskStandardGroupSetting.java armada-api/src/main/java/com/armada/task/mapper/PullTaskStandardSettingMapper.java armada-api/src/main/java/com/armada/task/mapper/PullTaskStandardGroupSettingMapper.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardSettingWriter.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardGroupSettingWriter.java armada-api/src/main/resources/mapper/task/PullTaskStandardSettingMapper.xml armada-api/src/main/resources/mapper/task/PullTaskStandardGroupSettingMapper.xml armada-api/src/test/java/com/armada/task/mapper/PullTaskStandardSettingMapperInMemoryTest.java armada-api/src/test/java/com/armada/task/mapper/PullTaskStandardGroupSettingMapperInMemoryTest.java armada-api/src/test/java/com/armada/task/service/PullTaskStandardSettingWriterTest.java armada-api/src/test/java/com/armada/task/service/PullTaskStandardGroupSettingWriterTest.java
git diff --cached --check
git commit -m "feat: persist standard task settings"
```

## Task 6: Add secure local avatar upload and preview

**Files:**

- Create: `armada-api/src/main/java/com/armada/task/service/PullTaskGroupAvatarService.java`
- Create: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskGroupAvatarServiceImpl.java`
- Create: `armada-api/src/main/java/com/armada/task/controller/PullTaskGroupAvatarController.java`
- Create: `armada-api/src/main/java/com/armada/task/model/vo/PullTaskGroupAvatarUploadVO.java`
- Create: `armada-api/src/main/java/com/armada/task/model/vo/PullTaskGroupAvatarContent.java`
- Create: `armada-api/src/test/java/com/armada/task/service/PullTaskGroupAvatarServiceTest.java`
- Create: `armada-api/src/test/java/com/armada/task/controller/PullTaskGroupAvatarControllerTest.java`
- Modify: `armada-api/src/main/resources/application.yml`

- [ ] **Step 1: Write failing file validation tests using a temp directory**

Use JUnit `@TempDir`; do not write to `/app` in tests. Cover:

- valid small `.jpg`, `.jpeg`, `.png` uploads;
- empty content and `512001` bytes rejected;
- GIF/WebP rejected;
- accepted extension + wrong MIME rejected;
- accepted extension/MIME + wrong signature rejected;
- JPEG signature `FF D8 FF` and the eight-byte PNG signature accepted only with matching extension/MIME;
- generated key is a basename, contains no slash/backslash/`..`, and preserves canonical `.jpg` or `.png`;
- tenant 7 cannot read/delete tenant 8’s key;
- delete succeeds only for an unbound file.

- [ ] **Step 2: Confirm RED**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest='PullTaskGroupAvatarServiceTest,PullTaskGroupAvatarControllerTest' test
```

- [ ] **Step 3: Implement storage with normalized paths and atomic moves**

Configure:

```yaml
armada:
  pull-task:
    avatar:
      storage-dir: ${ARMADA_PULL_TASK_AVATAR_STORAGE_DIR:/app/data/pull-task-avatars}
```

The service must resolve files like this:

```java
Path tenantDir = storageRoot.resolve(Long.toString(tenantId)).normalize();
Path target = tenantDir.resolve(fileKey).normalize();
if (!target.getParent().equals(tenantDir)) {
    throw new BusinessException(ErrorCode.NOT_FOUND, "群头像不存在");
}
```

Read bytes once to enforce the true byte limit and signature. Write a random temporary file in `tenantDir`, then `Files.move(temp, target, ATOMIC_MOVE)` with a same-filesystem non-atomic fallback only when `AtomicMoveNotSupportedException` occurs. Never use `MultipartFile.getOriginalFilename()` as the physical filename.

Before allowing DELETE, call `PullTaskStandardGroupSettingMapper.countActiveTaskBindings(fileKey)`; active means joined parent `pull_task.deleted_at IS NULL`.

- [ ] **Step 4: Implement HTTP endpoints and permissions**

```text
POST   /api/pull-tasks/standard/group-avatars          tenant:pull_task:create
GET    /api/pull-tasks/standard/group-avatars/{key}    tenant:pull_task:view
DELETE /api/pull-tasks/standard/group-avatars/{key}    tenant:pull_task:create
```

Return upload JSON:

```json
{
  "avatarFileKey": "<random>.png",
  "originalFileName": "头像.png",
  "previewUrl": "/api/pull-tasks/standard/group-avatars/<random>.png"
}
```

GET returns the actual `image/jpeg` or `image/png` content type. Cross-tenant lookup returns the same `NOT_FOUND` shape as a missing key.

- [ ] **Step 5: Confirm GREEN and commit**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest='PullTaskGroupAvatarServiceTest,PullTaskGroupAvatarControllerTest' test
```

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/main/java/com/armada/task/service/PullTaskGroupAvatarService.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskGroupAvatarServiceImpl.java armada-api/src/main/java/com/armada/task/controller/PullTaskGroupAvatarController.java armada-api/src/main/java/com/armada/task/model/vo/PullTaskGroupAvatarUploadVO.java armada-api/src/main/java/com/armada/task/model/vo/PullTaskGroupAvatarContent.java armada-api/src/test/java/com/armada/task/service/PullTaskGroupAvatarServiceTest.java armada-api/src/test/java/com/armada/task/controller/PullTaskGroupAvatarControllerTest.java armada-api/src/main/resources/application.yml
git diff --cached --check
git commit -m "feat: store pull task avatars locally"
```

## Task 7: Make complete task creation transactional and idempotent

**Files:**

- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardCreateServiceImpl.java`
- Create: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardCreateTransactionService.java`
- Modify: `armada-api/src/main/resources/mapper/task/PullTaskMapper.xml`
- Modify: `armada-api/src/test/java/com/armada/task/service/PullTaskStandardCreateServiceTest.java`
- Modify: `armada-api/src/test/java/com/armada/task/mapper/PullTaskMapperInMemoryTest.java`

- [ ] **Step 1: Expand the failing create integration tests**

Use one `validRequest` builder/factory instead of repeating the enlarged record constructor. Assert:

- all root fields and all nested group-setting fields persist and round-trip;
- `stationCountPerCall=0` accepts `stationGroupId=null`;
- positive station count rejects a null station group;
- TXT filename naming forces stored `group_name=NULL`;
- missing folder/account/avatar is rejected before task state changes;
- injected failure after either setting insert rolls back both setting rows and leaves the task `DRAFT`;
- repeat submission returns the same task without inserting more settings;
- `pull_task.config_json` remains exactly `{}` and `pull_task.group_name` remains NULL;
- a key already bound to another active task produces `CONFLICT`;
- auto-start runs only after the submit transaction commits.

- [ ] **Step 2: Confirm RED**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest='PullTaskStandardCreateServiceTest,PullTaskMapperInMemoryTest' test
```

- [ ] **Step 3: Split transaction completion from auto-start**

Do not call `startService.start()` while the submit transaction is still open. Use this control flow:

```java
public PullTaskStandardCreatedVO create(PullTaskStandardCreateDTO request, long userId) {
    SubmissionResult result = transactionService.submit(request, userId);
    if (!result.newlySubmitted() || request.autoStart() != 1) {
        return result.created();
    }
    startService.start(result.created().id());
    return currentCreated(result.created().id());
}
```

`PullTaskStandardCreateTransactionService.submit` carries `@Transactional(rollbackFor = Exception.class)` and performs, in order:

1. validate the whole DTO;
2. load/validate draft owner, status, version and non-empty rows;
3. validate/freeze folder and account-group snapshots;
4. validate the avatar belongs to the tenant, exists, and is not bound;
5. insert execution setting;
6. insert group setting;
7. register links/freeze rows;
8. atomically submit `DRAFT -> WAIT_START`.

Keep the already-submitted branch before any insert. Return `newlySubmitted=false` for idempotent reads.

- [ ] **Step 4: Stop writing the DTO JSON**

Remove the private `ObjectMapper`, `toConfigJson`, and DTO serialization. `submitDraft` must not overwrite the draft’s existing `{}`. Do not change the legacy `/api/pull-tasks` create/update paths.

- [ ] **Step 5: Confirm GREEN and commit**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest='PullTaskStandardCreateServiceTest,PullTaskMapperInMemoryTest' test
```

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardCreateServiceImpl.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardCreateTransactionService.java armada-api/src/main/resources/mapper/task/PullTaskMapper.xml armada-api/src/test/java/com/armada/task/service/PullTaskStandardCreateServiceTest.java armada-api/src/test/java/com/armada/task/mapper/PullTaskMapperInMemoryTest.java
git diff --cached --check
git commit -m "feat: save complete standard pull task form"
```

## Task 8: Read normalized settings back and show the standard group name in lists

**Files:**

- Create: `armada-api/src/main/java/com/armada/task/model/vo/PullTaskStandardSettingVO.java`
- Create: `armada-api/src/main/java/com/armada/task/model/vo/PullTaskStandardGroupSettingVO.java`
- Modify: `armada-api/src/main/java/com/armada/task/model/vo/PullTaskStandardTaskDetailVO.java`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardReadResources.java`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardReadServiceImpl.java`
- Modify: `armada-api/src/main/resources/mapper/task/PullTaskMapper.xml`
- Modify: `armada-api/src/test/java/com/armada/task/service/PullTaskStandardReadServiceTest.java`
- Modify: `armada-api/src/test/java/com/armada/task/mapper/PullTaskMapperBusinessConditionTest.java`
- Modify: `armada-api/src/test/java/com/armada/task/mapper/PullTaskStandardReadMapperInMemoryTest.java`

- [ ] **Step 1: Write failing readback tests**

Assert `GET /api/pull-tasks/standard/{taskId}` has two non-null nested objects:

```java
public record PullTaskStandardTaskDetailVO(
        long taskId,
        String taskName,
        String status,
        int groupCount,
        int expectedPullCount,
        Long startedAt,
        Long finishedAt,
        Long createdAt,
        String remark,
        List<PullTaskStandardExecutionSummaryVO> executions,
        PullTaskStandardTaskSummaryVO summary,
        PullTaskStandardSettingVO standardSetting,
        PullTaskStandardGroupSettingVO groupSetting) {}
```

Assert DB codes are converted back to API enum names, booleans are booleans, and `avatarPreviewUrl` is null iff `avatarFileKey` is null.

Add list tests proving:

- STANDARD/NORMAL_LINK returns the hand-entered group setting name when present;
- TXT filename mode does not invent one task-level group name;
- legacy modes still return `pull_task.group_name`;
- keyword count and list use the same effective-name expression.

- [ ] **Step 2: Confirm RED**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest='PullTaskStandardReadServiceTest,PullTaskMapperBusinessConditionTest,PullTaskStandardReadMapperInMemoryTest' test
```

- [ ] **Step 3: Implement normalized read assembly**

Read both setting mappers in `PullTaskStandardReadResources`; never parse `config_json`. Build preview URLs only from validated stored keys:

```java
String avatarPreviewUrl = setting.getAvatarFileKey() == null
        ? null
        : "/api/pull-tasks/standard/group-avatars/" + setting.getAvatarFileKey();
```

For generic task list SQL, left join the group-setting table and use:

```sql
CASE
  WHEN task_row.task_type = 'STANDARD' AND task_row.mode = 'NORMAL_LINK'
    THEN standard_group_setting.group_name
  ELSE task_row.group_name
END AS group_name
```

Use the same expression in keyword filtering. Do not copy the setting name into `pull_task`.

- [ ] **Step 4: Confirm GREEN and commit**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest='PullTaskStandardReadServiceTest,PullTaskMapperBusinessConditionTest,PullTaskStandardReadMapperInMemoryTest' test
```

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/main/java/com/armada/task/model/vo/PullTaskStandardSettingVO.java armada-api/src/main/java/com/armada/task/model/vo/PullTaskStandardGroupSettingVO.java armada-api/src/main/java/com/armada/task/model/vo/PullTaskStandardTaskDetailVO.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardReadResources.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardReadServiceImpl.java armada-api/src/main/resources/mapper/task/PullTaskMapper.xml armada-api/src/test/java/com/armada/task/service/PullTaskStandardReadServiceTest.java armada-api/src/test/java/com/armada/task/mapper/PullTaskMapperBusinessConditionTest.java armada-api/src/test/java/com/armada/task/mapper/PullTaskStandardReadMapperInMemoryTest.java
git diff --cached --check
git commit -m "feat: read standard pull task settings"
```

## Task 9: Close the avatar lifecycle on task deletion and orphan cleanup

**Files:**

- Create: `armada-api/src/main/java/com/armada/task/model/vo/PullTaskAvatarReference.java`
- Modify: `armada-api/src/main/java/com/armada/task/mapper/PullTaskStandardGroupSettingMapper.java`
- Modify: `armada-api/src/main/resources/mapper/task/PullTaskStandardGroupSettingMapper.xml`
- Create: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskGroupAvatarCleanupJob.java`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskMutationServiceImpl.java`
- Create: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskGroupAvatarCleanupJobTest.java`
- Modify: `armada-api/src/test/java/com/armada/task/service/PullTaskMutationServiceTest.java`
- Modify: `armada-deploy/docker-compose.rds.yml`
- Modify: `armada-deploy/prod/app/docker-compose.yml`
- Modify: `armada-deploy/.env.example`
- Modify: `armada-deploy/prod/app/.env.example`

- [ ] **Step 1: Write failing lifecycle tests**

Cover:

- successful task soft-delete schedules deletion only after commit;
- rolled-back task deletion keeps the file;
- one task without an avatar does nothing;
- file deletion failure does not roll back the already-committed task delete and is retried by cleanup;
- files younger than 24 hours remain;
- old unbound files are deleted;
- old files referenced only by soft-deleted parents are deleted;
- old files referenced by active parents remain;
- cleanup never follows symlinks or escapes the configured root.

- [ ] **Step 2: Confirm RED**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest='PullTaskGroupAvatarCleanupJobTest,PullTaskMutationServiceTest' test
```

- [ ] **Step 3: Implement safe cross-tenant cleanup**

The normal create/read queries stay tenant-intercepted. The scheduler may use one narrowly scoped mapper method annotated `@InterceptorIgnore(tenantLine = "true")` to return active references as `(tenantId, avatarFileKey)`; it must not expose general cross-tenant CRUD.

Scan only immediate tenant directories and immediate regular files, compare `Files.getLastModifiedTime`, and delete when age is at least 24 hours and the `(tenantId,key)` pair is not active. Use a scheduled property such as:

```text
armada.pull-task.avatar.cleanup-fixed-delay-ms=3600000
armada.pull-task.avatar.pending-ttl-ms=86400000
```

For task deletion, capture avatar references before the soft delete transaction, register an `afterCommit` callback, and log only tenant/task/key—not absolute paths or file bytes.

- [ ] **Step 4: Add persistent deploy mounts without deploying**

Add:

```yaml
environment:
  ARMADA_PULL_TASK_AVATAR_STORAGE_DIR: ${ARMADA_PULL_TASK_AVATAR_STORAGE_DIR:-/app/data/pull-task-avatars}
volumes:
  - ${ARMADA_PULL_TASK_AVATAR_HOST_DIR:-./data/pull-task-avatars}:/app/data/pull-task-avatars
```

Adapt the exact YAML indentation/service name already present in each compose file. Add both variables to `.env.example`. Do not run compose or SSH.

- [ ] **Step 5: Confirm GREEN plus deploy-script static tests**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest='PullTaskGroupAvatarCleanupJobTest,PullTaskMutationServiceTest' test
```

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
bash armada-deploy/deploy-test.test.sh
bash armada-deploy/package-prod.test.sh
```

- [ ] **Step 6: Commit**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/main/java/com/armada/task/model/vo/PullTaskAvatarReference.java armada-api/src/main/java/com/armada/task/mapper/PullTaskStandardGroupSettingMapper.java armada-api/src/main/resources/mapper/task/PullTaskStandardGroupSettingMapper.xml armada-api/src/main/java/com/armada/task/scheduler/PullTaskGroupAvatarCleanupJob.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskMutationServiceImpl.java armada-api/src/test/java/com/armada/task/scheduler/PullTaskGroupAvatarCleanupJobTest.java armada-api/src/test/java/com/armada/task/service/PullTaskMutationServiceTest.java armada-deploy/docker-compose.rds.yml armada-deploy/prod/app/docker-compose.yml armada-deploy/.env.example armada-deploy/prod/app/.env.example
git diff --cached --check
git commit -m "feat: manage pull task avatar lifecycle"
```

## Task 10: Extend the frontend API contract

**Files:**

- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/api/pull-task.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/api/pull-task.test.ts`
- Verify unchanged: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/api/group-folder.ts`
- Verify unchanged: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/api/group-folder.test.ts`

- [ ] **Step 1: Write failing request-contract tests**

Assert:

- draft plan multipart data contains `groupFolderId` when selected;
- avatar upload sends the real `File` under `file`;
- create sends every approved root field and nested `groupSetting` field;
- no marketing or “后期” field is present;
- detail types expose `standardSetting`, `groupSetting`, `avatarFileKey`, and `avatarPreviewUrl`.

- [ ] **Step 2: Confirm RED**

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
node --test --experimental-strip-types --loader ./src/api/__tests__/node-test-loader.mjs src/api/pull-task.test.ts src/api/group-folder.test.ts
```

- [ ] **Step 3: Implement exact TypeScript contracts**

Add:

```ts
export interface PullTaskStandardGroupSettingRequest {
  settingTiming: "BEFORE_PULL" | "AFTER_PULL";
  groupName: string | null;
  useMaterialFileNameAsGroupName: boolean;
  avatarFileKey: string | null;
  groupDescription: string | null;
  autoCloseMuteAfterTask: boolean;
  autoCloseInviteAfterTask: boolean;
  editPermission: "UNCHANGED" | "ALLOW" | "DISALLOW";
  muteMode: "UNCHANGED" | "MUTE" | "UNMUTE";
  linkPermission: "ALL" | "ADMIN_ONLY";
  disappearingMessage:
    | "UNCHANGED"
    | "ONE_DAY"
    | "SEVEN_DAYS"
    | "NINETY_DAYS"
    | "OFF";
}
```

Extend `PullTaskStandardCreateRequest` with `groupFolderId`, sync/clear fields, nullable station, both finish group IDs, and required `groupSetting`. Add:

```ts
export interface PullTaskStandardGroupAvatarUpload {
  avatarFileKey: string;
  originalFileName: string;
  previewUrl: string;
}

export function uploadPullTaskStandardGroupAvatar(
  file: File
): Promise<PullTaskStandardGroupAvatarUpload>;

export function deletePullTaskStandardGroupAvatar(
  avatarFileKey: string
): Promise<void>;
```

Change `planPullTaskStandardDraft` to accept `groupFolderId: number | null` and append it to `FormData` only when non-null.

- [ ] **Step 4: Confirm GREEN and commit in the frontend repository**

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
node --test --experimental-strip-types --loader ./src/api/__tests__/node-test-loader.mjs src/api/pull-task.test.ts src/api/group-folder.test.ts
git add src/api/pull-task.ts src/api/pull-task.test.ts
git diff --cached --check
git commit -m "feat: add standard pull task full form API"
```

## Task 11: Wire the real avatar file and whole-form save flow

**Files:**

- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/task/pull-task/composables/useStandardPullTaskCreate.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/task/pull-task/composables/useStandardPullTaskCreate.test.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/task/pull-task/components/PullTaskStandardGroupSettings.vue`
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/task/pull-task/components/PullTaskStandardCreateLayout.test.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/task/pull-task/index.vue`
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/.harness/changes/pull-task-normal-link-create/summary.md`

- [ ] **Step 1: Write failing composable tests**

Cover the exact save state machine:

1. no avatar: create full form directly;
2. new valid avatar: upload once, then create with returned key;
3. upload failure: do not call create;
4. create failure: retain the form, selected file, and uploaded key; retry creates without re-uploading;
5. selecting/replacing/clearing an already uploaded-but-unbound avatar calls DELETE and clears the cached key;
6. successful create resets the form, avatar file/key, links, files, and draft;
7. JPG/JPEG/PNG are accepted case-insensitively at `<=512000`; wrong extension/type or `>512000` is blocked locally;
8. station group is optional only when `stationCountPerCall===0`;
9. changing `groupFolderId`, links, or pending TXT files after planning forces re-plan;
10. a selected folder counts as a link source, but a TXT plan is still required before create.

- [ ] **Step 2: Confirm RED**

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
node --test --experimental-strip-types --loader ./src/api/__tests__/node-test-loader.mjs src/views/task/pull-task/composables/useStandardPullTaskCreate.test.ts src/views/task/pull-task/components/PullTaskStandardCreateLayout.test.ts
```

- [ ] **Step 3: Store the actual File, not only its name**

Replace `groupAvatarFileName` with explicit save state:

```ts
const groupAvatarFile = ref<File | null>(null);
const uploadedAvatar = ref<PullTaskStandardGroupAvatarUpload | null>(null);
```

Expose the file through the composable state. In `PullTaskStandardGroupSettings.vue`, bind Element Plus upload with `accept=".jpg,.jpeg,.png,image/jpeg,image/png"`, take `uploadFile.raw`, and emit/set the actual `File`. Keep only UI display derived from `file.name`.

- [ ] **Step 4: Track the planned folder snapshot**

Add `plannedGroupFolderId: number | null`; set it only after a successful plan. Include it in the stale-plan comparison beside `plannedLinksText` and pending TXT names. Pass `positiveId(form.groupFolderId) ? form.groupFolderId : null` to the API.

- [ ] **Step 5: Build and submit the complete payload**

The save handler should have this order:

```ts
const avatarFileKey = await ensureAvatarUploaded();
const payload = createPayload(avatarFileKey);
if (!payload) return;
await createPullTaskStandard(payload);
resetSuccessfulCreateState();
```

`createPayload` must include:

```ts
{
  groupFolderId: positiveId(form.groupFolderId) ? form.groupFolderId : null,
  pullerSyncMode: form.pullerSyncMode,
  clearExistingMembers: form.clearExistingMembers,
  stationGroupId: positiveId(form.stationGroupId) ? form.stationGroupId : null,
  managerFinishGroupId: positiveId(form.managerFinishGroupId)
    ? form.managerFinishGroupId
    : null,
  pullerFinishGroupId: positiveId(form.pullerFinishGroupId)
    ? form.pullerFinishGroupId
    : null,
  groupSetting: {
    settingTiming: form.groupSettingTiming,
    groupName: form.useMaterialFileNameAsGroupName
      ? null
      : form.groupName.trim() || null,
    useMaterialFileNameAsGroupName: form.useMaterialFileNameAsGroupName,
    avatarFileKey,
    groupDescription: form.groupDescription.trim() || null,
    autoCloseMuteAfterTask: form.autoCloseMuteAfterTask,
    autoCloseInviteAfterTask: form.autoCloseInviteAfterTask,
    editPermission: form.editPermission,
    muteMode: form.muteMode,
    linkPermission: form.linkPermission,
    disappearingMessage: form.disappearingMessage
  }
}
```

Retain the existing execution fields in the same payload. Remove the warning that says the newly aligned settings are not persisted.

- [ ] **Step 6: Confirm GREEN, type safety, and formatting**

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
node --test --experimental-strip-types --loader ./src/api/__tests__/node-test-loader.mjs src/api/pull-task.test.ts src/api/group-folder.test.ts src/views/task/pull-task/composables/useStandardPullTaskCreate.test.ts src/views/task/pull-task/components/PullTaskStandardCreateLayout.test.ts
pnpm typecheck
pnpm lint:eslint
pnpm lint:prettier
```

Because lint commands can rewrite files, inspect `git diff` and revert no unrelated user changes.

- [ ] **Step 7: Commit**

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
git add src/views/task/pull-task/composables/useStandardPullTaskCreate.ts src/views/task/pull-task/composables/useStandardPullTaskCreate.test.ts src/views/task/pull-task/components/PullTaskStandardGroupSettings.vue src/views/task/pull-task/components/PullTaskStandardCreateLayout.test.ts src/views/task/pull-task/index.vue .harness/changes/pull-task-normal-link-create/summary.md
git diff --cached --check
git commit -m "feat: save complete standard pull task form"
```

## Task 12: Add detail readback typing/UI and run full verification

**Files:**

- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/task/pull-task/components/PullTaskDetailDrawer.vue`
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/task/pull-task/composables/usePullTaskExecutionDetail.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/task/pull-task/composables/usePullTaskExecutionDetail.test.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/.harness/changes/pull-task-standard-full-form/summary.md`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/docs/superpowers/specs/2026-08-04-pull-task-standard-full-form-persistence-design.md`

- [ ] **Step 1: Add failing frontend detail tests**

Assert the drawer/composable can read `standardSetting` and `groupSetting`, renders the saved names/values, and uses `avatarPreviewUrl` for the image. It may show enum labels, but must not claim that protocol application is active; label the section as saved task configuration.

- [ ] **Step 2: Implement minimal readback display**

Keep rendering inside existing detail components and Element Plus primitives. Do not add another page or a second request path. If the current drawer has no editing behavior, readback remains read-only.

- [ ] **Step 3: Run the complete backend regression**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn test
```

Expected: `BUILD SUCCESS`. Record the Maven test count and elapsed time in the backend change summary.

- [ ] **Step 4: Run the complete frontend regression and build**

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
node --test --experimental-strip-types --loader ./src/api/__tests__/node-test-loader.mjs src/api/pull-task.test.ts src/api/group-folder.test.ts src/views/task/pull-task/composables/useStandardPullTaskCreate.test.ts src/views/task/pull-task/composables/usePullTaskExecutionDetail.test.ts src/views/task/pull-task/components/PullTaskStandardCreateLayout.test.ts
pnpm typecheck
pnpm lint:eslint
pnpm lint:prettier
pnpm build
```

Expected: all Node tests pass, both type checkers pass, ESLint/Prettier finish cleanly, and Vite build succeeds. Record exact outputs in the frontend change summary.

- [ ] **Step 5: Run static safety searches in both repositories**

Backend:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
rg -n "pull_task_group_avatar_file|avatar_file_key" armada-api/src/main armada-api/src/test .harness docs
rg -n "toConfigJson|writeValueAsString\(request\)" armada-api/src/main/java/com/armada/task
git diff --check
git status --short
```

Expected:

- no avatar table exists;
- production `avatar_file_key` storage occurs only in the group-setting table/mapper;
- standard create no longer serializes its DTO;
- only intended files are changed.

Frontend:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
rg -n "groupAvatarFileName|新增配置待后端接入" src
git diff --check
git status --short
```

Expected: both obsolete strings are absent and only intended files are changed.

- [ ] **Step 6: Update completion documentation**

Change design status to implemented only after the real verification outputs exist. The change summary must list:

- final Flyway migration number;
- exact field-to-table mapping;
- local avatar configuration and operational backup requirement;
- tests/build commands and actual outcomes;
- explicit remaining non-goal: WhatsApp protocol application of group settings.

- [ ] **Step 7: Commit final readback/documentation changes per repository**

Backend:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add .harness/changes/pull-task-standard-full-form/summary.md docs/superpowers/specs/2026-08-04-pull-task-standard-full-form-persistence-design.md
git diff --cached --check
git commit -m "docs: record standard pull task persistence verification"
```

Frontend:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
git add src/views/task/pull-task/components/PullTaskDetailDrawer.vue src/views/task/pull-task/composables/usePullTaskExecutionDetail.ts src/views/task/pull-task/composables/usePullTaskExecutionDetail.test.ts .harness/changes/pull-task-normal-link-create/summary.md
git diff --cached --check
git commit -m "feat: show saved standard pull task settings"
```

## Final manual acceptance checklist

Run locally against a disposable database only; do not point this checklist at test/production without separate user confirmation.

- [ ] Create a task with pasted links only and no avatar; detail matches every submitted field.
- [ ] Create a task with folder links + pasted duplicates; the frozen plan contains each normalized link once.
- [ ] Create with `stationCountPerCall=0` and no station group; save succeeds.
- [ ] Create with a positive station count and no station group; both frontend and backend reject it.
- [ ] Upload a 500KB JPG and PNG; both preview and bind successfully.
- [ ] Upload a 500001-byte file, GIF, or forged image; upload is rejected and no task request is sent.
- [ ] Force task creation failure after avatar upload; retry uses the same key without re-uploading.
- [ ] Enable TXT filename naming; stored/read-back manual group name is NULL.
- [ ] Delete a task; database soft delete commits before the avatar is removed.
- [ ] Verify legacy OLD_LINK/CREATE_NEW tasks still read/write their existing `config_json`/`group_name` behavior.
- [ ] Confirm no protocol command is emitted for the newly saved group profile/permission values in this phase.
