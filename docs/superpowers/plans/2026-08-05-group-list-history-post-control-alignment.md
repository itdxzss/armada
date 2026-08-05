# Group List Historical and Post-Control Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将租户群组列表对齐原型中的群组筛选和表格：固化历史群/上控后群标签，异步补齐历史群 metadata、邀请链接和完整成员快照，并支持大洲、国家、建群天数、成员数、可用管理员等统一查询。

**Architecture:** `armada-protocol` 保持账号群列表事件轻量，只扩充单群 metadata 稳定响应并发布带租户/账号引用的详情同步触发事件；`armada` 负责单调分类、幂等回填、持久化同步任务、逐群限流读取、原子快照和 SQL 下推查询；`wheel-saas-pure-web` 只消费统一列表接口，维护主筛选与历史筛选抽屉的草稿/已应用状态。所有读取按 tenant 隔离，历史与上控后允许重叠，分类一旦变为 true 永不回退。

**Tech Stack:** Java 17, Spring Boot, MyBatis XML, Flyway/MySQL 8, JUnit 5/AssertJ/Mockito/H2, TypeScript, Baileys 7.x, Fastify, Kafka, Jest, Vue 3, Element Plus, pure-admin, Node test runner, pnpm.

---

## 固定契约与执行边界

- 原型只对齐“历史群组筛选”和“群组列表”；原型中的其它未来按钮不实现。
- 统一列表仍使用 `GET /api/group-links`，已有参数和字段只做兼容保留，不改语义。
- 已有 `/api/historical-groups` 继续服务账号组维度的历史详情和成员操作；本次不删除、不改成统一列表别名。
- 分类筛选 `groupType` 的合法值为 `ALL/HISTORICAL/POST_CONTROL/BOTH`：
  - `HISTORICAL` 匹配 `is_historical=1`，包含同时为上控后的群。
  - `POST_CONTROL` 匹配 `is_post_control=1`，包含同时为历史群的群。
  - `BOTH` 只匹配两个字段都为 `1` 的群。
- `is_historical`、`is_post_control` 只能 `0 -> 1`；退群、被踢、快照缺失、任务失败和软删除都不能把标签写回 `0`。
- `group_created_at` 只接受 WhatsApp metadata 的 `creation` Unix 秒；任何本地成功时间、首次观察时间、导入时间、任务时间都不得兜底。
- 群主国家只由已确认的 PN/显式 `phoneNumber` 经过现有严格 libphonenumber 解析得到；不得从 LID 数字或执行账号国家推断。
- 大洲固定为 `ASIA/EUROPE/NORTH_AMERICA/SOUTH_AMERICA/AFRICA/OCEANIA`。`AQ/BV/HM/TF` 等特殊地区允许 `continentCode=null`，在“全部大洲”下仍可按国家选中。
- metadata 任务初次执行加三次重试，总尝试数最多 4 次；失败后的间隔依次为 1、5、30 分钟。没有可执行账号进入 `DEFERRED` 且不消耗尝试次数。
- 成员/资料变化触发采用数据库任务行上的 2 秒 trailing debounce；baseline、账号上线和手动刷新立即可执行。
- 默认并发为每租户 3、每执行账号 1；必须通过数据库中的 `RUNNING + execution_account_id + lease_until` 落实，不能只依赖单 JVM semaphore。
- 每次 metadata 成功才在同一事务内更新预览并完整替换成员快照；失败或不完整响应必须保留旧数据。
- 三个仓库分别提交，禁止把 `armada/.claude/worktrees/**`、凭据或其它用户改动加入提交。

### Task 0: 执行前基线与迁移号保护

**Files:**
- Read: `/Users/daishuaishuai/IdeaProjects/armada/AGENTS.md`
- Read: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/AGENTS.md`
- Read: `/Users/daishuaishuai/IdeaProjects/armada/docs/superpowers/specs/2026-08-05-group-list-history-post-control-alignment-design.md`

- [ ] **Step 1: 重新读取三个项目规则并检查工作树**

  Run:

  ```bash
  git -C /Users/daishuaishuai/IdeaProjects/armada status --short
  git -C /Users/daishuaishuai/IdeaProjects/armada-protocol status --short
  git -C /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web status --short
  ```

  Expected: 记录现有用户改动；后续仅暂存本计划列出的文件。`armada/.claude/worktrees/**` 即使仍显示修改也不处理。

- [ ] **Step 2: 确认 `V096` 仍未占用**

  Run:

  ```bash
  find /Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/db/migration -maxdepth 1 -type f -name 'V*.sql' -print | sort -V | tail -n 5
  ```

  Expected: 最新为 `V095__pull_task_standard_full_form_settings.sql`。若执行时 `V096` 已由并行工作占用，先把本计划内唯一迁移文件重命名为当时下一个空闲整数版本，并同步本计划引用后再写代码；不得复用已占用版本。

- [ ] **Step 3: 跑相关基线测试**

  Run:

  ```bash
  cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
  mvn -Dtest='GroupLinkMapperDbTest,AccountGroupMembershipReportServiceDbTest,GroupDetailServiceImplTest,ProtocolAccountEventConsumerTest,CountryMapperDbTest' test
  ```

  Run:

  ```bash
  cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
  npm test -- --runInBand src/routes/groups-detail.test.ts src/worker/account-manager.heartbeat.test.ts src/events/subjects.test.ts
  ```

  Run:

  ```bash
  cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
  node --test --experimental-strip-types --loader ./src/api/__tests__/node-test-loader.mjs src/api/group.test.ts src/views/group/list/GroupListFolderIntegration.test.ts src/views/group/list/components/GroupListTable.test.ts src/views/group/list/components/GroupMemberDrawer.test.ts
  ```

  Expected: 全部 PASS；若基线已失败，先记录失败，不把既有失败归因于本功能。

### Task 1: 建立数据库结构与迁移契约

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V096__group_list_history_metadata.sql`
- Create: `armada-api/src/test/java/com/armada/group/GroupListHistoryMetadataMigrationSqlTest.java`
- Modify: `armada-api/src/test/java/com/armada/boot/FlywayMigrationSqlContractTest.java`
- Create: `.harness/changes/group-list-history-metadata/summary.md`
- Create: `.harness/changes/group-list-history-metadata/db-migrations.sql`
- Create: `.harness/changes/group-list-history-metadata/rollback.sql`

- [ ] **Step 1: 先写迁移 SQL 契约测试并验证 RED**

  测试必须读取 `V096__group_list_history_metadata.sql` 并断言：

  ```java
  assertThat(sql).contains(
          "is_historical", "is_post_control", "continent_code",
          "wa_description", "admin_only_edit_info", "member_add_mode",
          "join_approval_mode", "ephemeral_duration_seconds",
          "creator_country_iso2", "creator_continent_code",
          "metadata_observed_at",
          "CREATE TABLE IF NOT EXISTS whatsapp_group_member_snapshot",
          "CREATE TABLE IF NOT EXISTS group_metadata_sync_task",
          "execution_account_id",
          "rerun_requested",
          "idx_group_link_historical", "idx_group_link_post_control",
          "idx_country_continent_sort",
          "UNIQUE KEY uq_group_metadata_sync_task (tenant_id, group_link_id)");
  assertThat(sql).doesNotContain("group_created_at = created_at");
  ```

  Run:

  ```bash
  cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
  mvn -Dtest='GroupListHistoryMetadataMigrationSqlTest,FlywayMigrationSqlContractTest' test
  ```

  Expected: RED，因为迁移文件尚不存在。

- [ ] **Step 2: 编写幂等 Flyway 迁移**

  `V096` 必须完成以下结构，所有业务时间使用 epoch 毫秒，唯独 `group_link_preview.group_created_at` 保持 Unix 秒：

  ```sql
  ALTER TABLE group_link
    ADD COLUMN is_historical TINYINT NOT NULL DEFAULT 0,
    ADD COLUMN is_post_control TINYINT NOT NULL DEFAULT 0;

  ALTER TABLE country
    ADD COLUMN continent_code VARCHAR(24) NULL;

  CREATE TABLE IF NOT EXISTS whatsapp_group_member_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    group_link_id BIGINT NOT NULL,
    group_jid VARCHAR(128) NOT NULL,
    participant_jid VARCHAR(128) NOT NULL,
    phone VARCHAR(32) NULL,
    role VARCHAR(32) NULL,
    is_admin TINYINT NOT NULL DEFAULT 0,
    is_owner TINYINT NOT NULL DEFAULT 0,
    snapshot_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_whatsapp_group_member (tenant_id, group_link_id, participant_jid),
    KEY idx_whatsapp_group_admin (tenant_id, group_link_id, is_admin)
  );

  CREATE TABLE IF NOT EXISTS group_metadata_sync_task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    group_link_id BIGINT NOT NULL,
    status TINYINT NOT NULL,
    trigger_source TINYINT NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_run_at BIGINT NULL,
    lease_until BIGINT NULL,
    execution_account_id BIGINT NULL,
    rerun_requested TINYINT NOT NULL DEFAULT 0,
    last_started_at BIGINT NULL,
    last_success_at BIGINT NULL,
    last_error_code VARCHAR(64) NULL,
    last_error_message VARCHAR(512) NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_group_metadata_sync_task (tenant_id, group_link_id),
    KEY idx_group_metadata_due (status, next_run_at, lease_until),
    KEY idx_group_metadata_running (tenant_id, execution_account_id, status, lease_until)
  );
  ```

  `group_link` 同时增加 `idx_group_link_historical (tenant_id, deleted_at, is_historical, created_at, id)` 与 `idx_group_link_post_control (tenant_id, deleted_at, is_post_control, created_at, id)`；`country` 增加 `idx_country_continent_sort (continent_code, is_enabled, sort_order, id)`。新增表和每个新增列都写明确 `COMMENT`，枚举列 comment 列出全部码值；DDL 继续沿用 InnoDB、utf8mb4 和项目现有 collation。

  使用项目现有 `information_schema + PREPARE` 模式保护对已有表加列/索引。扩展 `group_link_preview` 时字段定义严格为：

  ```text
  wa_description VARCHAR(1024) NULL
  admin_only_edit_info TINYINT NULL
  member_add_mode TINYINT NULL
  join_approval_mode TINYINT NULL
  ephemeral_duration_seconds INT NULL
  creator_country_iso2 VARCHAR(2) NULL
  creator_continent_code VARCHAR(24) NULL
  metadata_observed_at BIGINT NULL
  ```

  国家大洲使用显式 ISO2 列表更新；迁移结束时，除 `AQ/BV/HM/TF` 外的 active 实体国家必须都有六大洲代码，四个特殊地区保留 NULL。不得在 Flyway 中调用协议层或逐群远程接口，也不在 Flyway 中展开 baseline 做业务回填。

- [ ] **Step 3: 写变更记录与回滚说明**

  `db-migrations.sql` 记录正式 Flyway 文件、迁移前检查和迁移后只读验证 SQL；`rollback.sql` 只提供停 job、验证影响范围和在明确人工确认后删除新增结构的恢复指引。明确：回滚应用代码后可暂时保留新增列/表；若人工删除表会失去最后一次成员快照和任务状态；分类字段是历史事实，不提供把 `1` 批量改回 `0` 的数据回滚 SQL。

- [ ] **Step 4: 验证迁移 GREEN**

  Run:

  ```bash
  cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
  mvn -Dtest='GroupListHistoryMetadataMigrationSqlTest,FlywayMigrationSqlContractTest,FlywayMigrationVersionContractTest,FlywayMigrationHistoryContractTest' test
  ```

  Expected: PASS。

- [ ] **Step 5: 提交数据库骨架**

  ```bash
  git add armada-api/src/main/resources/db/migration/V096__group_list_history_metadata.sql armada-api/src/test/java/com/armada/group/GroupListHistoryMetadataMigrationSqlTest.java armada-api/src/test/java/com/armada/boot/FlywayMigrationSqlContractTest.java .harness/changes/group-list-history-metadata/summary.md .harness/changes/group-list-history-metadata/db-migrations.sql .harness/changes/group-list-history-metadata/rollback.sql
  git commit -m "feat: 建立群组分类与详情快照结构"
  ```

### Task 2: 建立分类、快照和任务持久化模型

**Files:**
- Modify: `armada-api/src/main/java/com/armada/group/model/entity/GroupLink.java`
- Modify: `armada-api/src/main/java/com/armada/group/model/entity/GroupLinkPreview.java`
- Create: `armada-api/src/main/java/com/armada/group/model/entity/WhatsappGroupMemberSnapshot.java`
- Create: `armada-api/src/main/java/com/armada/group/model/entity/GroupMetadataSyncTask.java`
- Create: `armada-api/src/main/java/com/armada/group/model/enums/GroupMetadataSyncStatus.java`
- Create: `armada-api/src/main/java/com/armada/group/model/enums/GroupMetadataSyncTrigger.java`
- Create: `armada-api/src/main/java/com/armada/group/mapper/WhatsappGroupMemberSnapshotMapper.java`
- Create: `armada-api/src/main/java/com/armada/group/mapper/GroupMetadataSyncTaskMapper.java`
- Create: `armada-api/src/main/resources/mapper/group/WhatsappGroupMemberSnapshotMapper.xml`
- Create: `armada-api/src/main/resources/mapper/group/GroupMetadataSyncTaskMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/group/mapper/GroupLinkPreviewMapper.java`
- Modify: `armada-api/src/main/resources/mapper/group/GroupLinkPreviewMapper.xml`
- Create: `armada-api/src/test/java/com/armada/group/mapper/WhatsappGroupMemberSnapshotMapperDbTest.java`
- Create: `armada-api/src/test/java/com/armada/group/mapper/GroupMetadataSyncTaskMapperDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/mapper/GroupLinkPreviewMapperDbTest.java`

- [ ] **Step 1: 先写 mapper DB 测试并验证 RED**

  覆盖：成员批量插入和按群完整删除、owner 自动满足 admin、同 tenant/group/participant 唯一；任务 enqueue 重复触发只保留一行；成功时间保留；重置 `FAILED/DEFERRED/SUCCEEDED` 为 `PENDING`；过期租约可恢复；另一租户同 group link id 不串行。

  Run:

  ```bash
  cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
  mvn -Dtest='WhatsappGroupMemberSnapshotMapperDbTest,GroupMetadataSyncTaskMapperDbTest,GroupLinkPreviewMapperDbTest' test
  ```

  Expected: RED，因为模型和 mapper 尚不存在。

- [ ] **Step 2: 用显式枚举码实现任务状态**

  状态码固定为：

  ```java
  public enum GroupMetadataSyncStatus {
      PENDING(1), RUNNING(2), RETRY_WAIT(3), SUCCEEDED(4), DEFERRED(5), FAILED(6);
  }
  ```

  触发码固定为：

  ```java
  public enum GroupMetadataSyncTrigger {
      BASELINE_CAPTURED(1), POST_CONTROL_DISCOVERED(2),
      PARTICIPANT_CHANGED(3), METADATA_CHANGED(4),
      ACCOUNT_ONLINE(5), MANUAL_REFRESH(6), BACKFILL(7);
  }
  ```

  Java 代码和 XML 通过枚举 `code()` 绑定，不出现散落魔法数字。

- [ ] **Step 3: 实现快照与任务 mapper**

  任务 enqueue 使用 `INSERT ... ON DUPLICATE KEY UPDATE`：更新 `trigger_source`、`updated_at`，将非 RUNNING 行推进到 `PENDING`、`attempt_count=0`、`next_run_at=now` 并清空旧错误；正在 RUNNING 的行保持当前租约，成功后由新触发再次 enqueue。为避免 RUNNING 期间丢触发，在表内增加 `rerun_requested TINYINT NOT NULL DEFAULT 0`，迁移和实体同步加入；RUNNING 重复触发只把该字段置 `1`，当前执行完成后转回 `PENDING`。

  `GroupLinkPreviewMapper.upsertMetadataSnapshot` 使用三态更新：metadata 明确观察到的 nullable 字段才覆盖；`group_created_at` 仅在新的 WhatsApp `creation` 合法且非空时更新；失败路径不调用该方法。持久化事务先锁定 preview 并比较 `metadata_observed_at`，只有本次请求开始观察时间不早于已保存值时才允许更新预览和替换成员；租约过期后晚到的旧执行视为 stale success，不覆盖新快照。

- [ ] **Step 4: 实现成员完整替换原语**

  service 层事务必须按顺序调用：

  ```java
  previewMapper.upsertMetadataSnapshot(preview);
  memberSnapshotMapper.deleteByGroupLinkId(groupLinkId);
  memberSnapshotMapper.insertBatch(members);
  ```

  空成员数组只有在协议明确返回完整的零成员快照时才允许替换；任何 participant 缺少规范化 JID 时，在进入事务前抛出“不完整响应”，旧数据不动。

- [ ] **Step 5: 验证并提交持久化层**

  Run: 重跑本 Task 的三个测试，Expected: PASS。

  ```bash
  git add armada-api/src/main/java/com/armada/group/model/entity/GroupLink.java armada-api/src/main/java/com/armada/group/model/entity/GroupLinkPreview.java armada-api/src/main/java/com/armada/group/model/entity/WhatsappGroupMemberSnapshot.java armada-api/src/main/java/com/armada/group/model/entity/GroupMetadataSyncTask.java armada-api/src/main/java/com/armada/group/model/enums/GroupMetadataSyncStatus.java armada-api/src/main/java/com/armada/group/model/enums/GroupMetadataSyncTrigger.java armada-api/src/main/java/com/armada/group/mapper/WhatsappGroupMemberSnapshotMapper.java armada-api/src/main/java/com/armada/group/mapper/GroupMetadataSyncTaskMapper.java armada-api/src/main/java/com/armada/group/mapper/GroupLinkPreviewMapper.java armada-api/src/main/resources/mapper/group/WhatsappGroupMemberSnapshotMapper.xml armada-api/src/main/resources/mapper/group/GroupMetadataSyncTaskMapper.xml armada-api/src/main/resources/mapper/group/GroupLinkPreviewMapper.xml armada-api/src/test/java/com/armada/group/mapper/WhatsappGroupMemberSnapshotMapperDbTest.java armada-api/src/test/java/com/armada/group/mapper/GroupMetadataSyncTaskMapperDbTest.java armada-api/src/test/java/com/armada/group/mapper/GroupLinkPreviewMapperDbTest.java armada-api/src/main/resources/db/migration/V096__group_list_history_metadata.sql
  git commit -m "feat: 增加群详情任务与成员快照持久化"
  ```

### Task 3: 固化历史群与上控后群分类，并幂等回填存量

**Files:**
- Create: `armada-api/src/main/java/com/armada/group/service/GroupClassificationService.java`
- Create: `armada-api/src/main/java/com/armada/group/service/impl/GroupClassificationServiceImpl.java`
- Create: `armada-api/src/main/java/com/armada/group/scheduler/GroupClassificationBackfillJob.java`
- Create: `armada-api/src/main/java/com/armada/group/scheduler/GroupClassificationBackfillProperties.java`
- Create: `armada-api/src/main/java/com/armada/group/model/vo/GroupClassificationCandidate.java`
- Modify: `armada-api/src/main/java/com/armada/group/mapper/GroupLinkMapper.java`
- Modify: `armada-api/src/main/resources/mapper/group/GroupLinkMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/AccountGroupMembershipReportServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/AccountGroupMembershipSnapshotServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/AccountGroupMembershipStatusServiceImpl.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/AccountGroupMembershipReportServiceDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/AccountGroupMembershipStatusServiceDbTest.java`
- Create: `armada-api/src/test/java/com/armada/group/service/GroupClassificationServiceDbTest.java`
- Create: `armada-api/src/test/java/com/armada/group/scheduler/GroupClassificationBackfillJobTest.java`

- [ ] **Step 1: 写分类真值表测试并验证 RED**

  测试至少覆盖：

  ```text
  PENDING + 首次完整快照 JID       -> historical=1, postControl=0
  CAPTURED + baseline 内 JID       -> historical=1, postControl 保留原值
  CAPTURED + baseline 外当前 JID   -> postControl=1, historical 保留原值
  CAPTURED + baseline 后 self add  -> baseline 外才 postControl=1
  PENDING/DISABLED + self add      -> 不分类
  remove/leave/快照缺失            -> 不清除任何标签
  同群被不同账号命中两类事实        -> historical=1, postControl=1
  软删除历史 link                  -> 回填不复活、不创建重复活动行
  ```

  Run:

  ```bash
  cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
  mvn -Dtest='GroupClassificationServiceDbTest,AccountGroupMembershipReportServiceDbTest,AccountGroupMembershipStatusServiceDbTest,GroupClassificationBackfillJobTest' test
  ```

  Expected: RED。

- [ ] **Step 2: 增加只升不降的 mapper 原语**

  必须使用定向 SQL，不允许把整个 `GroupLink` 实体回写：

  ```sql
  UPDATE group_link
  SET is_historical = 1, updated_at = GREATEST(updated_at, #{updatedAt})
  WHERE tenant_id = #{tenantId} AND id = #{groupLinkId}
    AND deleted_at IS NULL AND is_historical = 0;
  ```

  `markPostControl` 同理。另提供仅供历史事实回填使用的 `markHistoricalIncludingDeleted`，它可把已有软删除行的 `is_historical` 提升为 1，但不改 `deleted_at`、不排 metadata 任务。baseline 中完全不存在入口时，使用 `wa://group/{jid}`、baseline subject、`origin=ACCOUNT_SYNC` 登记；采用 `INSERT IGNORE`/唯一键冲突后只读取活动行，绝不走当前 `upsertAccountObservedGroup` 的 `deleted_at=NULL` 复活分支。

- [ ] **Step 3: 首次 baseline 同事务分类**

  `AccountGroupMembershipReportServiceImpl` 在成功固化 PENDING baseline 后，把本次去重的 JID/subject 交给 `GroupClassificationService.captureHistoricalBaseline`。先固定 `historical=1`，再建立当前 membership；该首次报告不能走 baseline 差集从而误标 `postControl=1`。

- [ ] **Step 4: CAPTURED 快照和精确事件分类**

  `AccountGroupMembershipSnapshotServiceImpl` 对 CAPTURED 账号逐 JID 做 baseline membership 判断：baseline 内保证 historical；baseline 外 mark post-control。`AccountGroupMembershipStatusServiceImpl` 只对 `add + CAPTURED + not in baseline + occurredAt > capturedAt` 标 post-control。比较使用协议事实时间，不使用消费时间。

- [ ] **Step 5: 实现无远程调用的批量回填 job**

  每次最多取 500 个尚未分类的候选：

  - 用 `JSON_TABLE(account_group_baseline.baseline_group_jids)` 展开 CAPTURED baseline，补活动 `group_link` 并 mark historical。
  - 用 `account_group_membership` 当前 `IN_GROUP` 关系连接 CAPTURED baseline，`JSON_CONTAINS(...)=0` 的 JID mark post-control。
  - DISABLED/PENDING 不参与上控后回填。
  - 查询必须 tenant scoped、keyset/limit 批量、按候选稳定排序；软删除候选先写历史标签再从后续候选中消失，但不创建任务；每轮完成后若仍有候选再由下一调度轮处理。
  - job 不调用协议层，不复活软删除，不修改已为 1 的标记。

- [ ] **Step 6: 验证并提交分类链路**

  Run: 重跑本 Task 四个测试，Expected: PASS。

  ```bash
  git add armada-api/src/main/java/com/armada/group/service/GroupClassificationService.java armada-api/src/main/java/com/armada/group/service/impl/GroupClassificationServiceImpl.java armada-api/src/main/java/com/armada/group/scheduler/GroupClassificationBackfillJob.java armada-api/src/main/java/com/armada/group/scheduler/GroupClassificationBackfillProperties.java armada-api/src/main/java/com/armada/group/model/vo/GroupClassificationCandidate.java armada-api/src/main/java/com/armada/group/mapper/GroupLinkMapper.java armada-api/src/main/resources/mapper/group/GroupLinkMapper.xml armada-api/src/main/java/com/armada/group/service/impl/AccountGroupMembershipReportServiceImpl.java armada-api/src/main/java/com/armada/group/service/impl/AccountGroupMembershipSnapshotServiceImpl.java armada-api/src/main/java/com/armada/group/service/impl/AccountGroupMembershipStatusServiceImpl.java armada-api/src/test/java/com/armada/group/service/AccountGroupMembershipReportServiceDbTest.java armada-api/src/test/java/com/armada/group/service/AccountGroupMembershipStatusServiceDbTest.java armada-api/src/test/java/com/armada/group/service/GroupClassificationServiceDbTest.java armada-api/src/test/java/com/armada/group/scheduler/GroupClassificationBackfillJobTest.java
  git commit -m "feat: 固化历史群与上控后群分类"
  ```

### Task 4: 增加国家大洲主数据与严格群主地区解析

**Files:**
- Modify: `armada-api/src/main/java/com/armada/platform/country/model/entity/Country.java`
- Modify: `armada-api/src/main/java/com/armada/platform/country/model/vo/CountryOptionVO.java`
- Modify: `armada-api/src/main/java/com/armada/platform/country/mapper/CountryMapper.java`
- Modify: `armada-api/src/main/resources/mapper/platform/country/CountryMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/platform/country/service/impl/CountryServiceImpl.java`
- Modify: `armada-api/src/test/java/com/armada/platform/country/mapper/CountryMapperDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/country/service/CountryServiceImplTest.java`
- Modify: `armada-api/src/test/java/com/armada/admin/controller/CountryControllerDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/promotion/channel/service/impl/PromotionChannelServiceImplTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/export/service/MarketingTaskExportServiceImplTest.java`

- [ ] **Step 1: 先扩展国家接口测试并验证 RED**

  断言 `/api/admin/countries/options` 返回 `continentCode`；`CN=ASIA`、`US=NORTH_AMERICA`、`BR=SOUTH_AMERICA`、`AQ=null`；所有 active 国家除 `AQ/BV/HM/TF` 外不允许 NULL；`CountryOptionVO` 所有构造点都显式传入 nullable 大洲。

- [ ] **Step 2: 实现主数据映射和严格解析复用**

  `CountryOptionVO` 字段顺序统一为下列定义；同时更新仓库内所有构造点，不增加隐藏 nullable 大洲的兼容构造器：

  ```java
  public record CountryOptionVO(
          String value, String iso2, String nameZh, String nameEn,
          String phonePrefix, String flag, boolean virtual,
          String continentCode) {}
  ```

  metadata 同步后续只调用 `CountryService.resolveActiveCountriesByPhoneNumbers(List.of(ownerPhone))`；解析不到、号码不严格有效或 identity 只包含 LID 时写 NULL，不猜测。

- [ ] **Step 3: 验证并提交**

  Run:

  ```bash
  cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
  mvn -Dtest='CountryMapperDbTest,CountryServiceImplTest,CountryControllerDbTest,PromotionChannelServiceImplTest,MarketingTaskExportServiceImplTest' test
  ```

  Expected: PASS。

  ```bash
  git add armada-api/src/main/java/com/armada/platform/country/model/entity/Country.java armada-api/src/main/java/com/armada/platform/country/model/vo/CountryOptionVO.java armada-api/src/main/java/com/armada/platform/country/mapper/CountryMapper.java armada-api/src/main/resources/mapper/platform/country/CountryMapper.xml armada-api/src/main/java/com/armada/platform/country/service/impl/CountryServiceImpl.java armada-api/src/test/java/com/armada/platform/country/mapper/CountryMapperDbTest.java armada-api/src/test/java/com/armada/platform/country/service/CountryServiceImplTest.java armada-api/src/test/java/com/armada/admin/controller/CountryControllerDbTest.java armada-api/src/test/java/com/armada/promotion/channel/service/impl/PromotionChannelServiceImplTest.java armada-api/src/test/java/com/armada/marketing/export/service/MarketingTaskExportServiceImplTest.java
  git commit -m "feat: 增加国家大洲主数据"
  ```

### Task 5: 补齐 Web metadata 到 Java 稳定模型的字段

**Files:**
- Modify: `armada-protocol/protocol-layer/src/routes/groups-detail.test.ts`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/model/result/GroupMetadataResult.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupMetadataAdapter.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeFixedAccountGroupMetadataAdapter.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/http/group/HttpGroupMetadataAdapterTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidNativeFixedAccountGroupMetadataAdapterTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/routing/RoutingFixedAccountGroupMetadataPortTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/impl/GroupDetailServiceImplTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/impl/HistoricalGroupServiceImplTest.java`

- [ ] **Step 1: 锁定现有 Web 路由契约**

  在 `groups-detail.test.ts` 断言 `/v1/groups/:jid/metadata` 返回：`id/subject/desc/owner/creation/participants/size/announce/restrict/memberAddMode/joinApprovalMode/ephemeralDuration`。不修改 `mapGroup()` 和 `account.groups_reported` 的 `{groupJid, subject}` 轻量结构。

  Run:

  ```bash
  cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
  npm test -- --runInBand src/routes/groups-detail.test.ts
  ```

  Expected: PASS；证明协议路由本身已有真实字段。

- [ ] **Step 2: 先写 Java adapter RED 测试**

  测试输入包含：

  ```json
  {
    "id":"120363001@g.us",
    "subject":"历史群",
    "desc":"群说明",
    "owner":"8613800000000@s.whatsapp.net",
    "creation":1722470400,
    "participants":[{"id":"8613800000000@s.whatsapp.net","admin":"superadmin"}]
  }
  ```

  断言稳定结果得到 `description`、`ownerJid`、`createdAtSeconds=1722470400`、`participantsComplete=true`，owner participant 同时为 owner/admin；响应缺少 participants 字段时 `participantsComplete=false`，不能和真实空群混淆。

- [ ] **Step 3: 一次性扩展稳定 record 并更新全部调用点**

  `GroupMetadataResult` 在 `subject` 后加入：

  ```java
  String description,
  String ownerJid,
  Long createdAtSeconds,
  boolean participantsComplete,
  ```

  不新增兼容构造器；编译器列出的所有调用点和测试都显式传值。Web adapter 从 `desc/owner/creation` 映射；Android 响应若有明确 creation/owner 则映射，否则传 NULL，不使用本地时间。

- [ ] **Step 4: 验证两个仓库并分别提交**

  Run:

  ```bash
  cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
  npm test -- --runInBand src/routes/groups-detail.test.ts
  npm run build
  ```

  ```bash
  git add protocol-layer/src/routes/groups-detail.test.ts
  git commit -m "test: 固定群 metadata 完整字段契约"
  ```

  Run:

  ```bash
  cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
  mvn -Dtest='HttpGroupMetadataAdapterTest,AndroidNativeFixedAccountGroupMetadataAdapterTest,RoutingFixedAccountGroupMetadataPortTest,GroupDetailServiceImplTest,HistoricalGroupServiceImplTest' test
  ```

  ```bash
  git add armada-api/src/main/java/com/armada/platform/protocol/model/result/GroupMetadataResult.java armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupMetadataAdapter.java armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeFixedAccountGroupMetadataAdapter.java armada-api/src/test/java/com/armada/platform/protocol/http/group/HttpGroupMetadataAdapterTest.java armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidNativeFixedAccountGroupMetadataAdapterTest.java armada-api/src/test/java/com/armada/platform/protocol/routing/RoutingFixedAccountGroupMetadataPortTest.java armada-api/src/test/java/com/armada/group/service/impl/GroupDetailServiceImplTest.java armada-api/src/test/java/com/armada/group/service/impl/HistoricalGroupServiceImplTest.java
  git commit -m "feat: 接收群 metadata 创建与群主字段"
  ```

### Task 6: 协议层发布可路由的群详情同步触发事件

**Files:**
- Modify: `armada-protocol/protocol-layer/src/events/subjects.ts`
- Modify: `armada-protocol/protocol-layer/src/events/subjects.test.ts`
- Modify: `armada-protocol/protocol-layer/src/worker/account-manager.ts`
- Modify: `armada-protocol/protocol-layer/src/worker/account-manager.heartbeat.test.ts`
- Modify: `armada-protocol/protocol-layer/src/routes/groups.ts`
- Modify: `armada-protocol/protocol-layer/src/routes/groups-test-harness.ts`
- Modify: `armada-protocol/protocol-layer/src/routes/groups-detail.test.ts`
- Modify: `armada-protocol/protocol-layer/src/routes/groups-metadata-summaries.test.ts`

- [ ] **Step 1: 写事件路由和行为 RED 测试**

  新事件名固定为 `account.group_metadata_sync_requested`，必须加入 `CRITICAL_EVENTS` 并路由到 `accountGroupSync` topic。账号上下文有业务引用时：

  - 任意 `group-participants.update` 的 `add/remove/promote/demote` 都发布一次触发；本人 add/remove 仍保留原有群列表同步行为。
  - `groups.update` 对每个合法 `@g.us` JID 发布触发。
  - 事件数据不携带 participants、群名或完整 metadata。
  - 缺少 businessRef 时不发布业务触发，只保留现有观测日志。

  事件数据固定为：

  ```json
  {
    "tenantId": 1,
    "accountId": 22,
    "protocolAccountId": "acc_web_22",
    "groupJid": "120363001@g.us",
    "trigger": "PARTICIPANT_CHANGED",
    "source": "wa_group_participants_update"
  }
  ```

  `groups.update` 使用 `trigger=METADATA_CHANGED`、`source=wa_groups_update`。

  增加反馈环测试：由 HTTP metadata/participants/metadata-summaries 主动执行的 `sock.groupMetadata()` 即使引发 Baileys `groups.update`，也不得发布同步触发；群资料 mutation 后自然到达的 `groups.update` 仍必须发布，避免“读取 -> 触发 -> 再读取”的无限任务循环。

- [ ] **Step 2: 实现发布并保持群列表事件轻量**

  抽取 `publishGroupMetadataSyncRequested(ctx, groupJid, trigger, source)`；内部复用 `businessEventData(ctx)`，以 `ctx.accountId` 为 Kafka key。任务表会去重，因此不在 Node 进程内做会丢事件的长时间缓存去重。

  为阻断反馈环，在 `AccountManager` 增加公开的 `readGroupMetadata(accountId, groupJid)`：调用前按 account+group 增加 in-flight 计数，调用结束后保留 2 秒 suppression window；`groupsUpdateHandler` 对仍在 read/suppression window 的同 JID 只记安全日志、不发布事件。`groups.ts` 的 metadata GET、participants GET 和 metadata-summaries 都必须通过该方法读取；群名称/权限/成员 mutation 仍直接使用 socket，不进入 suppression。并发读取用计数而不是 boolean，清理账号 context 时清除对应 suppression 条目。

  `group.participant_changed` 现有通用事件继续保留；新的业务事件专门供 Armada 可靠映射 tenant/account，不让后端从协议账号字符串猜租户。

- [ ] **Step 3: 验证并提交协议层事件**

  Run:

  ```bash
  cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
  npm test -- --runInBand src/events/subjects.test.ts src/worker/account-manager.heartbeat.test.ts src/worker/event-bridge.test.ts src/routes/groups-detail.test.ts src/routes/groups-metadata-summaries.test.ts
  npm run build
  ```

  Expected: PASS。

  ```bash
  git add protocol-layer/src/events/subjects.ts protocol-layer/src/events/subjects.test.ts protocol-layer/src/worker/account-manager.ts protocol-layer/src/worker/account-manager.heartbeat.test.ts protocol-layer/src/routes/groups.ts protocol-layer/src/routes/groups-test-harness.ts protocol-layer/src/routes/groups-detail.test.ts protocol-layer/src/routes/groups-metadata-summaries.test.ts
  git commit -m "feat: 发布群详情同步触发事件"
  ```

### Task 7: 后端消费触发事件并实现耐久任务调度

**Files:**
- Create: `armada-api/src/main/java/com/armada/platform/kafka/consumer/account/ProtocolGroupMetadataSyncRequestedEvent.java`
- Create: `armada-api/src/main/java/com/armada/platform/kafka/consumer/account/ProtocolGroupMetadataSyncRequestedSink.java`
- Modify: `armada-api/src/main/java/com/armada/platform/kafka/consumer/account/ProtocolAccountEventConsumer.java`
- Modify: `armada-api/src/test/java/com/armada/platform/kafka/consumer/account/ProtocolAccountEventConsumerTest.java`
- Create: `armada-api/src/main/java/com/armada/group/service/GroupMetadataSyncTaskService.java`
- Create: `armada-api/src/main/java/com/armada/group/service/GroupMetadataSyncExecutor.java`
- Create: `armada-api/src/main/java/com/armada/group/service/impl/GroupMetadataSyncTaskServiceImpl.java`
- Create: `armada-api/src/main/java/com/armada/group/service/impl/GroupMetadataSyncRequestedSinkAdapter.java`
- Create: `armada-api/src/main/java/com/armada/group/scheduler/GroupMetadataSyncJob.java`
- Create: `armada-api/src/main/java/com/armada/group/scheduler/GroupMetadataSyncJobProperties.java`
- Create: `armada-api/src/main/java/com/armada/group/observability/GroupMetadataSyncMetrics.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/GroupClassificationServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/group/scheduler/GroupClassificationBackfillJob.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/GroupClassificationServiceDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/scheduler/GroupClassificationBackfillJobTest.java`
- Modify: `armada-api/src/main/java/com/armada/account/service/impl/AccountStateChangedSinkAdapter.java`
- Modify: `armada-api/src/test/java/com/armada/account/service/impl/AccountStateChangedSinkAdapterTest.java`
- Create: `armada-api/src/test/java/com/armada/group/service/impl/GroupMetadataSyncTaskServiceImplTest.java`
- Create: `armada-api/src/test/java/com/armada/group/scheduler/GroupMetadataSyncJobTest.java`

- [ ] **Step 1: 写 consumer 校验 RED 测试**

  `onGroupSyncMessage` 接受新事件，并严格要求 `eventId/data.tenantId/data.accountId/data.protocolAccountId/data.groupJid/data.trigger/occurredAt`；顶层路由 `accountId` 必须等于 `data.protocolAccountId`。非法 tenant、非 `@g.us` JID、路由账号不一致必须抛 VALIDATION，不能入队。

- [ ] **Step 2: 写任务状态机 RED 测试**

  覆盖：重复触发去重、RUNNING 时设置 rerun、无账号 DEFERRED 不增 attempts、账号上线恢复 DEFERRED、租约过期恢复、失败 1/5/30 分钟、第四次失败 FAILED、成功 SUCCEEDED、成功期间 rerun 则 PENDING、每租户最多 3、同 account 最多 1。

- [ ] **Step 3: 实现数据库租约和调度**

  调度流程固定为：

  ```text
  select due candidates (small ordered page)
    -> selector.find(groupLinkId)
       -> no account: mark DEFERRED, attempts unchanged
       -> account: conditional claim with tenant/account running count + lease
          -> executor outside claim transaction
          -> complete/retry/fail in short transaction
  ```

  默认属性：`enabled=true`、`fixed-delay=5s`、`batch-size=20`、`lease=120s`、`change-debounce=2s`、`tenant-concurrency=3`、`account-concurrency=1`。job 同时使用 `@ConditionalOnProperty(enabled=true)` 和 `@ConditionalOnBean(GroupMetadataSyncExecutor.class)`；因此 Task 7 尚无真实 executor 时应用仍可启动，Task 8 注册实现后自动启用，也可在回滚/止损时只关闭领取。`PARTICIPANT_CHANGED/METADATA_CHANGED` 重复触发把非 RUNNING 任务的 `next_run_at` 推到最新触发时间后 2 秒；baseline、账号上线、手动刷新取当前时间。单个任务失败只记录安全错误码和截断到 512 字符的消息，不阻断同批其它任务。

  `GroupMetadataSyncJob` 依赖窄接口 `GroupMetadataSyncExecutor.execute(task, account)`；本 Task 的 job 测试使用 fake executor 验证状态机，Task 8 的 `GroupMetadataSnapshotService` 实现该接口。这样队列、租约和 consumer 可以先独立编译通过。

  `GroupMetadataSyncMetrics` 使用低基数标签，至少记录 pending gauge、`success/failed/deferred/retry` counter、metadata duration 和 snapshot member count；标签只使用结果/触发枚举，不放 tenant、account、JID、手机号或邀请链接。

- [ ] **Step 4: 接入所有触发点**

  - `GroupClassificationServiceImpl` 在 baseline 分类后：`BASELINE_CAPTURED`。
  - `GroupClassificationServiceImpl` 在新上控后群首次标记后：`POST_CONTROL_DISCOVERED`。
  - Kafka 触发：按 trigger 映射 `PARTICIPANT_CHANGED/METADATA_CHANGED`。
  - `AccountStateChangedSinkAdapter` 在已校验的账号状态真正进入 ONLINE 后，恢复该账号当前 `IN_GROUP` 群的 DEFERRED 任务，触发源 `ACCOUNT_ONLINE`；重复/乱序 ONLINE 不重复重置。
  - `GroupClassificationBackfillJob` 新建或首次分类活动群：`BACKFILL`；软删除群不入队。

  触发写入和本地分类写入处于同一数据库事务；协议远程调用绝不在该事务内。

- [ ] **Step 5: 验证并提交任务调度**

  Run:

  ```bash
  cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
  mvn -Dtest='ProtocolAccountEventConsumerTest,GroupMetadataSyncTaskServiceImplTest,GroupMetadataSyncJobTest,GroupClassificationServiceDbTest,GroupClassificationBackfillJobTest,AccountStateChangedSinkAdapterTest,AccountGroupMembershipReportServiceDbTest,AccountGroupMembershipStatusServiceDbTest' test
  ```

  Expected: PASS。

  ```bash
  git add armada-api/src/main/java/com/armada/platform/kafka/consumer/account/ProtocolGroupMetadataSyncRequestedEvent.java armada-api/src/main/java/com/armada/platform/kafka/consumer/account/ProtocolGroupMetadataSyncRequestedSink.java armada-api/src/main/java/com/armada/platform/kafka/consumer/account/ProtocolAccountEventConsumer.java armada-api/src/test/java/com/armada/platform/kafka/consumer/account/ProtocolAccountEventConsumerTest.java armada-api/src/main/java/com/armada/group/service/GroupMetadataSyncTaskService.java armada-api/src/main/java/com/armada/group/service/GroupMetadataSyncExecutor.java armada-api/src/main/java/com/armada/group/service/impl/GroupMetadataSyncTaskServiceImpl.java armada-api/src/main/java/com/armada/group/service/impl/GroupMetadataSyncRequestedSinkAdapter.java armada-api/src/main/java/com/armada/group/scheduler/GroupMetadataSyncJob.java armada-api/src/main/java/com/armada/group/scheduler/GroupMetadataSyncJobProperties.java armada-api/src/main/java/com/armada/group/observability/GroupMetadataSyncMetrics.java armada-api/src/test/java/com/armada/group/service/impl/GroupMetadataSyncTaskServiceImplTest.java armada-api/src/test/java/com/armada/group/scheduler/GroupMetadataSyncJobTest.java armada-api/src/main/java/com/armada/group/service/impl/GroupClassificationServiceImpl.java armada-api/src/main/java/com/armada/group/scheduler/GroupClassificationBackfillJob.java armada-api/src/test/java/com/armada/group/service/GroupClassificationServiceDbTest.java armada-api/src/test/java/com/armada/group/scheduler/GroupClassificationBackfillJobTest.java armada-api/src/main/java/com/armada/account/service/impl/AccountStateChangedSinkAdapter.java armada-api/src/test/java/com/armada/account/service/impl/AccountStateChangedSinkAdapterTest.java
  git commit -m "feat: 调度群 metadata 耐久同步任务"
  ```

### Task 8: 执行 metadata、邀请链接与原子成员快照

**Files:**
- Modify: `armada-api/src/main/java/com/armada/group/model/vo/GroupExecutionAccount.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/GroupExecutionAccountSelector.java`
- Modify: `armada-api/src/main/java/com/armada/group/mapper/AccountGroupMembershipMapper.java`
- Modify: `armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml`
- Create: `armada-api/src/main/java/com/armada/group/service/GroupMetadataSnapshotService.java`
- Create: `armada-api/src/main/java/com/armada/group/service/impl/GroupMetadataSnapshotServiceImpl.java`
- Create: `armada-api/src/main/java/com/armada/group/service/GroupMetadataSyncProtocolPorts.java`
- Create: `armada-api/src/test/java/com/armada/group/service/impl/GroupMetadataSnapshotServiceImplTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/GroupExecutionAccountSelectorDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/GroupExecutionAccountSelectorTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/HistoricalGroupExecutionAccountSelectorTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/impl/HistoricalGroupServiceImplTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/impl/GroupDetailServiceImplTest.java`

- [ ] **Step 1: 写选号和快照 RED 测试**

  选号必须只接受：membership=`IN_GROUP`、账号 NORMAL、login_state ONLINE、协议 binding/ref 完整；管理员优先，普通成员兜底。`GroupExecutionAccount` 增加 `boolean groupAdmin`，删除旧兼容构造器并更新全部构造点。

  快照测试覆盖：

  - 管理员账号读取 metadata 后再读取 invite code；普通成员不调用 invite port。
  - creation 原样保存 Unix 秒；NULL、非正数或晚于当前时间的值按未知且不覆盖旧值，绝不写 `System.currentTimeMillis()/1000`。
  - owner 只从明确 PN/participant.phone 解析；LID-only 为 NULL。
  - `participantsComplete=false` 或任一 participant 无稳定 JID 时拒绝整次成员快照；显式 complete 的空数组才表示真实零成员。
  - participant 重复时 OWNER > ADMIN > MEMBER，owner 必为 admin。
  - 任一 participant 无稳定 JID、协议超时、邀请读取失败时：metadata/成员成功仍可提交，但 invite 失败只保留旧 invite；metadata 不完整则整次不提交。
  - DB 事务中预览或成员任一步失败，旧快照完整保留。

- [ ] **Step 2: 实现统一执行器**

  `GroupMetadataSnapshotService` 实现 Task 7 的 `GroupMetadataSyncExecutor`；`GroupMetadataSyncProtocolPorts` 只组合 `FixedAccountGroupMetadataPort` 与 `GroupInvitePort`。执行顺序：

  ```java
  GroupMetadataResult metadata = ports.metadata().getMetadata(account.protocolRef(), groupJid);
  String inviteCode = account.groupAdmin()
          ? safeInviteCode(ports.invite().getInvite(account.protocolRef(), groupJid))
          : null;
  PreparedSnapshot snapshot = validateAndNormalize(metadata, inviteCode);
  persistAtomically(groupLinkId, snapshot);
  ```

  invite 失败记录专属错误但不让已完整的 metadata/成员回滚；旧 invite code 不被 NULL 覆盖。metadata 失败则任务按 Task 7 重试。

- [ ] **Step 3: 持久化列表需要的派生字段**

  `member_size` 取规范化成员数；`owner_phone`、`creator_country_iso2`、`creator_continent_code` 来自严格群主号码；说明、权限、限时消息、创建时间写入 preview。远程请求发出前固定 `metadataObservedAt`，持久化事务用它做新旧保护；`last_preview_at`、成员 `snapshot_at` 和任务成功时间使用本次成功完成的 epoch 毫秒。

- [ ] **Step 4: 验证并提交**

  Run:

  ```bash
  cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
  mvn -Dtest='GroupExecutionAccountSelectorDbTest,GroupExecutionAccountSelectorTest,HistoricalGroupExecutionAccountSelectorTest,HistoricalGroupServiceImplTest,GroupDetailServiceImplTest,GroupMetadataSnapshotServiceImplTest,WhatsappGroupMemberSnapshotMapperDbTest,GroupMetadataSyncJobTest' test
  ```

  Expected: PASS。

  ```bash
  git add armada-api/src/main/java/com/armada/group/model/vo/GroupExecutionAccount.java armada-api/src/main/java/com/armada/group/service/GroupExecutionAccountSelector.java armada-api/src/main/java/com/armada/group/mapper/AccountGroupMembershipMapper.java armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml armada-api/src/main/java/com/armada/group/service/GroupMetadataSnapshotService.java armada-api/src/main/java/com/armada/group/service/impl/GroupMetadataSnapshotServiceImpl.java armada-api/src/main/java/com/armada/group/service/GroupMetadataSyncProtocolPorts.java armada-api/src/test/java/com/armada/group/service/impl/GroupMetadataSnapshotServiceImplTest.java armada-api/src/test/java/com/armada/group/service/GroupExecutionAccountSelectorDbTest.java armada-api/src/test/java/com/armada/group/service/GroupExecutionAccountSelectorTest.java armada-api/src/test/java/com/armada/group/service/HistoricalGroupExecutionAccountSelectorTest.java armada-api/src/test/java/com/armada/group/service/impl/HistoricalGroupServiceImplTest.java armada-api/src/test/java/com/armada/group/service/impl/GroupDetailServiceImplTest.java
  git commit -m "feat: 持久化群 metadata 与完整成员快照"
  ```

### Task 9: 将详情抽屉读取改为本地快照并提供手动刷新

**Files:**
- Modify: `armada-api/src/main/java/com/armada/group/service/GroupDetailService.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/GroupDetailServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/group/model/vo/GroupDetailVO.java`
- Modify: `armada-api/src/main/java/com/armada/group/controller/GroupLinkController.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/impl/GroupDetailServiceImplTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/controller/GroupLinkControllerTest.java`

- [ ] **Step 1: 写本地读取和手动刷新 RED 测试**

  `GET /api/group-links/{id}/detail` 和 `/members` 不调用 metadata 端口；有快照时返回最后成功资料/成员，无快照时返回 `membersAvailable=false` 和同步状态，不把空数组伪装成“群无成员”。

  `POST /api/group-links/{id}/metadata-sync` 只校验 tenant 下活动 group link 并 enqueue `MANUAL_REFRESH`，响应：

  ```json
  {"accepted":true,"status":"PENDING"}
  ```

  GET/detail/members 和手动刷新继续使用现有 `tenant:group_link:view` 权限边界；写操作保留各自原权限。controller 测试要证明无权限请求被拒绝，不能因新增刷新入口绕过租户群查看权限。

- [ ] **Step 2: 保留写操作的实时确认语义**

  群名称、头像、权限、限时消息、升降管理员、踢人等已有写操作仍可实时选择账号和回读确认；写成功后 enqueue `METADATA_CHANGED`，不在当前 HTTP 请求里额外做第二次完整快照。只把详情 GET 从实时协议读取切到 DB，不破坏写端口。

- [ ] **Step 3: 扩展详情同步状态**

  `GroupDetailVO` 增加 `metadataSyncStatus/metadataSyncedAt/metadataSyncError`；error 只返回可展示的安全摘要。成员角色来自快照中的 OWNER/ADMIN/MEMBER。

- [ ] **Step 4: 验证并提交**

  Run:

  ```bash
  cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
  mvn -Dtest='GroupDetailServiceImplTest,GroupLinkControllerTest' test
  ```

  Expected: PASS。

  ```bash
  git add armada-api/src/main/java/com/armada/group/service/GroupDetailService.java armada-api/src/main/java/com/armada/group/service/impl/GroupDetailServiceImpl.java armada-api/src/main/java/com/armada/group/model/vo/GroupDetailVO.java armada-api/src/main/java/com/armada/group/controller/GroupLinkController.java armada-api/src/test/java/com/armada/group/service/impl/GroupDetailServiceImplTest.java armada-api/src/test/java/com/armada/group/controller/GroupLinkControllerTest.java
  git commit -m "feat: 群详情改读持久化快照"
  ```

### Task 10: 扩展统一群组列表 SQL、API 和筛选校验

**Files:**
- Create: `armada-api/src/main/java/com/armada/group/model/enums/GroupListType.java`
- Modify: `armada-api/src/main/java/com/armada/group/model/dto/GroupLinkQuery.java`
- Modify: `armada-api/src/main/java/com/armada/group/model/vo/GroupLinkVoRow.java`
- Modify: `armada-api/src/main/java/com/armada/group/model/vo/GroupLinkVO.java`
- Modify: `armada-api/src/main/java/com/armada/group/mapper/GroupLinkMapper.java`
- Modify: `armada-api/src/main/resources/mapper/group/GroupLinkMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/GroupLinkServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/group/converter/GroupConverter.java`
- Modify: `armada-api/src/main/java/com/armada/group/controller/GroupLinkController.java`
- Modify: `armada-api/src/test/java/com/armada/group/mapper/GroupLinkMapperDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/mapper/GroupLinkMapperSqlShapeTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/GroupLinkServiceImplTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/controller/GroupLinkControllerTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/converter/GroupConverterTest.java`

- [ ] **Step 1: 先写查询矩阵 RED 测试**

  `GroupLinkQuery` 增加并校验：

  ```java
  private Long folderId;
  private Boolean withoutFolder;
  private GroupListType groupType;
  private Boolean availableAdmin;
  private Integer memberCountMin;
  private Integer memberCountMax;
  private String continentCode;
  private String countryIso2;
  private Integer ageDaysMin;
  private Integer ageDaysMax;
  ```

  测试 `min <= max`、成员数非负、天数非负、ISO2 大写规范化、非法 groupType/continent 返回 VALIDATION。范围端点都包含；未知成员数/创建时间/地区在对应筛选激活时不匹配。

  DB 测试至少插入：纯历史、纯上控后、重叠、两者都不是、无 metadata、无可用管理员、另租户同 JID，并逐一断言 count 与 page list 完全同集。

- [ ] **Step 2: 重构共享 FROM 与 filter 片段**

  count/list 必须共用同一 `groupListFrom`、`groupListFilter`、`keywordFilter`。管理员聚合改读 `whatsapp_group_member_snapshot`；可用管理员独立用 `EXISTS(account_group_membership + account + account_state)`，严格要求 `IN_GROUP/is_admin=1/NORMAL/ONLINE/有效协议绑定`。

  建群天数 SQL 固定为：

  ```sql
  FLOOR((#{nowSeconds} - p.group_created_at) / 86400)
  ```

  `nowSeconds` 由 service 单次计算后同时传 count/list，避免跨秒边界不一致。激活群龄筛选时先要求 `p.group_created_at > 0 AND p.group_created_at <= #{nowSeconds}`，未来或非法 creation 按未知排除，不能夹成 0 天。

- [ ] **Step 3: 实现关键词、文件夹和分页稳定性**

  关键词匹配运营群名、WhatsApp subject、邀请 URL/code、群 JID、owner phone、最后成员快照内所有 owner/admin phone；不再从 `join_task_result` 拼管理员。文件夹 join `group_folder`，支持 `folderId/withoutFolder`。列表保持一租户一 group_link 一行，使用 `EXISTS` 或预聚合子查询，不能因成员/账号 join 产生重复；排序仍为 `g.created_at DESC,g.id DESC`。

- [ ] **Step 4: 扩展兼容 VO**

  `GroupLinkVO` 新增：

  ```text
  isHistorical, isPostControl,
  folderId, folderName,
  inviteUrl,
  adminPhones: List<String>,
  availableAdmin, availableAdminCount,
  creatorPhone,
  creatorCountryIso2, creatorCountryName, creatorCountryFlag, creatorContinentCode,
  groupCreatedAt,
  metadataSyncStatus, metadataSyncedAt, metadataSyncError
  ```

  现有 `admin` 字符串保留，值由 `adminPhones` 以 `, ` 连接，供旧前端兼容。现有 status/statusLabel/source/origin/membership 字段继续返回。

- [ ] **Step 5: 验证并提交列表后端**

  Run:

  ```bash
  cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
  mvn -Dtest='GroupLinkMapperDbTest,GroupLinkMapperSqlShapeTest,GroupLinkServiceImplTest,GroupLinkControllerTest,GroupConverterTest' test
  ```

  Expected: PASS，且 SQL shape 测试确认 count/list 共享筛选、不含跨租户裸 join、管理员来源不再是 `join_task_result`。

  ```bash
  git add armada-api/src/main/java/com/armada/group/model/enums/GroupListType.java armada-api/src/main/java/com/armada/group/model/dto/GroupLinkQuery.java armada-api/src/main/java/com/armada/group/model/vo/GroupLinkVoRow.java armada-api/src/main/java/com/armada/group/model/vo/GroupLinkVO.java armada-api/src/main/java/com/armada/group/mapper/GroupLinkMapper.java armada-api/src/main/resources/mapper/group/GroupLinkMapper.xml armada-api/src/main/java/com/armada/group/service/impl/GroupLinkServiceImpl.java armada-api/src/main/java/com/armada/group/converter/GroupConverter.java armada-api/src/main/java/com/armada/group/controller/GroupLinkController.java armada-api/src/test/java/com/armada/group/mapper/GroupLinkMapperDbTest.java armada-api/src/test/java/com/armada/group/mapper/GroupLinkMapperSqlShapeTest.java armada-api/src/test/java/com/armada/group/service/GroupLinkServiceImplTest.java armada-api/src/test/java/com/armada/group/controller/GroupLinkControllerTest.java armada-api/src/test/java/com/armada/group/converter/GroupConverterTest.java
  git commit -m "feat: 扩展群组列表分类与组合筛选"
  ```

### Task 11: 扩展前端 API 类型与可测试筛选状态

**Files:**
- Modify: `wheel-saas-pure-web/src/api/group.ts`
- Modify: `wheel-saas-pure-web/src/api/group.test.ts`
- Modify: `wheel-saas-pure-web/src/api/resource-ip.ts`
- Modify: `wheel-saas-pure-web/src/api/resource-ip.test.ts`
- Create: `wheel-saas-pure-web/src/views/group/list/group-list-filters.ts`
- Create: `wheel-saas-pure-web/src/views/group/list/group-list-filters.test.ts`
- Modify: `wheel-saas-pure-web/src/views/group/list/composables/useGroupListPage.ts`
- Create: `wheel-saas-pure-web/src/views/group/list/composables/useGroupListPage.test.ts`

- [ ] **Step 1: 先写 API 参数与筛选状态 RED 测试**

  `GroupListQuery` 增加后端全部新增参数；`GroupListRow` 增加 Task 10 字段；`GroupDetail` 增加 metadata 状态；新增 `requestGroupMetadataSync(id)` POST。

  `IpCountryOption` 增加 `continentCode?: string | null`，继续复用 `/api/admin/countries/options`，不再创建第二套国家接口。

  筛选状态测试固定以下行为：

  ```text
  打开历史筛选 -> draft 从 applied 克隆
  修改 draft 后关闭 -> applied/query 不变
  清空 -> 只清 draft
  应用 -> 保存 applied，不请求
  抽屉查询 -> 保存 applied、groupType=HISTORICAL、page=1、请求一次
  主区查询 -> 使用当前 applied 请求
  主区重置 -> 清主筛选和 applied/draft，page=1、请求一次
  主区成员范围与抽屉成员范围 -> 同一个状态源
  ```

- [ ] **Step 2: 实现纯函数筛选模型**

  `group-list-filters.ts` 导出：

  ```ts
  export type GroupType = "" | "HISTORICAL" | "POST_CONTROL" | "BOTH";
  export interface HistoricalFilterValue {
    continentCode: string;
    countryIso2: string;
    ageDaysMin: number | undefined;
    ageDaysMax: number | undefined;
    memberCountMin: number | undefined;
    memberCountMax: number | undefined;
  }
  export function toGroupListQuery(main, applied, page, pageSize): GroupListQuery;
  export function countriesForContinent(rows, continentCode): IpCountryOption[];
  ```

  `toGroupListQuery` 去掉空字符串/undefined，不发送 NaN；国家改变大洲时若已选国家不属于新大洲，清空 countryIso2。

  查询组合规则必须显式测试：主群类型为空且已应用历史地区/群龄时，有效 `groupType=HISTORICAL`；主群类型为 `POST_CONTROL/BOTH` 时保留历史草稿但不发送 continent/country/age；成员范围因与主筛选共用，任何群类型下都继续发送；Drawer“查询”显式把主群类型切回 `HISTORICAL`。

- [ ] **Step 3: 重构 composable**

  主筛选只保留：群信息 keyword、分组、群类型、状态、可用管理员、成员最小/最大。`sourceFileName/origin/membershipState` 从页面隐藏，但 API 类型和兼容参数保留。composable 暴露 drawer open/draft/applied、country options loading 和 apply/query/clear/close 方法。

- [ ] **Step 4: 验证并提交前端数据层**

  Run:

  ```bash
  cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
  node --test --experimental-strip-types --loader ./src/api/__tests__/node-test-loader.mjs src/api/group.test.ts src/api/resource-ip.test.ts src/views/group/list/group-list-filters.test.ts src/views/group/list/composables/useGroupListPage.test.ts
  ```

  Expected: PASS。

  ```bash
  git add src/api/group.ts src/api/group.test.ts src/api/resource-ip.ts src/api/resource-ip.test.ts src/views/group/list/group-list-filters.ts src/views/group/list/group-list-filters.test.ts src/views/group/list/composables/useGroupListPage.ts src/views/group/list/composables/useGroupListPage.test.ts
  git commit -m "feat: 增加群组历史筛选状态与接口"
  ```

### Task 12: 实现历史群组筛选 Drawer 和主筛选区

**Files:**
- Create: `wheel-saas-pure-web/src/views/group/list/components/HistoricalGroupFilterDrawer.vue`
- Create: `wheel-saas-pure-web/src/views/group/list/components/HistoricalGroupFilterDrawer.test.ts`
- Modify: `wheel-saas-pure-web/src/views/group/list/constants.ts`
- Modify: `wheel-saas-pure-web/src/views/group/list/index.vue`
- Create: `wheel-saas-pure-web/src/views/group/list/GroupListPageContract.test.ts`

- [ ] **Step 1: 写页面契约 RED 测试**

  断言主区存在“群信息、群组分组、群类型、状态、可用管理员、群成员数量、历史群组筛选、查询、重置”，不再渲染“来源文件、来源、关系”。抽屉必须使用 `ElDrawer`，包含大洲、国家、建群天数、成员数、快捷范围、清空、应用、查询。

- [ ] **Step 2: 实现主筛选布局**

  群类型选项：全部、历史群、上控后群、同时属于两类。可用管理员：全部、有、无。成员数最小/最大共享 applied 历史筛选值。点击“历史群组筛选”打开 Drawer；若 applied 非空，在按钮旁展示已应用条件数。

- [ ] **Step 3: 实现 Drawer 草稿交互**

  大洲只提供六个稳定选项；国家选项按 draft continent 联动。快捷天数固定为 `0-7/8-30/31-90/91-180/181-365/365+`；成员快捷范围固定为 `0-50/51-100/101-200/201-500/500+`。快捷项只是填充输入，不额外请求。

  - 关闭/X/遮罩：丢弃 draft。
  - 清空：清 draft，Drawer 保持打开。
  - 应用：保存 applied 并关闭，不请求。
  - 查询：保存 applied、强制 groupType=HISTORICAL、page=1、请求后关闭。

- [ ] **Step 4: 验证并提交筛选 UI**

  Run:

  ```bash
  cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
  node --test --experimental-strip-types --loader ./src/api/__tests__/node-test-loader.mjs src/views/group/list/components/HistoricalGroupFilterDrawer.test.ts src/views/group/list/GroupListPageContract.test.ts src/views/group/list/group-list-filters.test.ts src/views/group/list/composables/useGroupListPage.test.ts
  pnpm typecheck
  ```

  Expected: PASS。

  ```bash
  git add src/views/group/list/components/HistoricalGroupFilterDrawer.vue src/views/group/list/components/HistoricalGroupFilterDrawer.test.ts src/views/group/list/constants.ts src/views/group/list/index.vue src/views/group/list/GroupListPageContract.test.ts
  git commit -m "feat: 对齐历史群组筛选抽屉"
  ```

### Task 13: 对齐群组表格与持久化成员详情

**Files:**
- Modify: `wheel-saas-pure-web/src/views/group/list/components/GroupListTable.vue`
- Modify: `wheel-saas-pure-web/src/views/group/list/components/GroupListTable.test.ts`
- Modify: `wheel-saas-pure-web/src/views/group/list/components/GroupMemberDrawer.vue`
- Modify: `wheel-saas-pure-web/src/views/group/list/components/GroupMemberDrawer.test.ts`
- Modify: `wheel-saas-pure-web/src/views/group/list/constants.ts`

- [ ] **Step 1: 写列表展示 RED 测试**

  表格列按原型核心信息包含：WS 群名称+标签、群组分组、成员数、邀请链接、全部管理员号码、状态、可用管理员、创建信息（国家/创建者/WhatsApp 建群时间）、群 JID、现有操作。保留选择、批量分组、分组管理、删除、详情、跳转进群任务；不实现原型其它未来按钮。

- [ ] **Step 2: 实现分类和未知值展示**

  `isHistorical` 显示“历史群”，`isPostControl` 显示“上控后”；重叠时两枚 tag 都显示。创建时间为空显示 `-`，不得回退 `createdAt`。国家为空、管理员为空、邀请链接为空都显示 `-`。`adminPhones` 每个号码可换行/Tag 展示；`availableAdminCount>0` 显示可用数量，否则显示不可用。

- [ ] **Step 3: 改造成员 Drawer**

  打开 Drawer 只调用本地 detail GET。显示 metadata 状态和最后同步时间；无快照时显示“详情待同步”，不显示“0 人”。“刷新群信息”调用 `requestGroupMetadataSync`，成功提示“已加入同步队列”，然后重新读取一次任务状态；不轮询到完成，不伪造实时成功。

  成员写操作成功后继续刷新详情；后端会另行 enqueue metadata，前端不直接修改成员数组冒充服务端快照。

- [ ] **Step 4: 验证并提交表格与 Drawer**

  Run:

  ```bash
  cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
  node --test --experimental-strip-types --loader ./src/api/__tests__/node-test-loader.mjs src/api/group.test.ts src/views/group/list/GroupListFolderIntegration.test.ts src/views/group/list/components/GroupListTable.test.ts src/views/group/list/components/GroupMemberDrawer.test.ts src/views/group/list/GroupListPageContract.test.ts
  pnpm typecheck
  pnpm exec eslint --max-warnings 0 src/api/group.ts src/api/group.test.ts src/api/resource-ip.ts src/api/resource-ip.test.ts src/views/group/list
  pnpm exec stylelint src/views/group/list/index.vue src/views/group/list/components/HistoricalGroupFilterDrawer.vue src/views/group/list/components/GroupListTable.vue src/views/group/list/components/GroupMemberDrawer.vue
  pnpm build
  ```

  Expected: 全部 PASS。

  ```bash
  git add src/views/group/list/components/GroupListTable.vue src/views/group/list/components/GroupListTable.test.ts src/views/group/list/components/GroupMemberDrawer.vue src/views/group/list/components/GroupMemberDrawer.test.ts src/views/group/list/constants.ts
  git commit -m "feat: 对齐群组列表与成员快照展示"
  ```

### Task 14: 全链路回归、文档生成与交付门禁

**Files:**
- Modify: `.harness/changes/group-list-history-metadata/summary.md`
- Modify: `.harness/changes/group-list-history-metadata/db-migrations.sql`
- Modify: `.harness/changes/group-list-history-metadata/rollback.sql`
- Modify: `.harness/wiki/数据模型.md`（仅通过项目规定生成器更新）
- Create: `armada-api/src/test/java/com/armada/boot/DataModelWikiExportMySqlTest.java`

- [ ] **Step 1: 后端完整验证**

  Run:

  ```bash
  cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
  mvn test
  ```

  Expected: BUILD SUCCESS。额外检查测试明确证明：历史/上控后重叠、tenant 隔离、单调标签、无本地建群时间兜底、任务重试/租约、成员快照失败保旧、count/list 同筛选。

- [ ] **Step 2: 协议层完整验证**

  Run:

  ```bash
  cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
  npm test -- --runInBand
  npm run lint
  npm run build
  ```

  Expected: 全部 PASS；确认 `account.groups_reported` 仍不含 participants，新增事件只含定位和触发字段。

- [ ] **Step 3: 前端完整验证**

  Run:

  ```bash
  cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
  node --test --experimental-strip-types --loader ./src/api/__tests__/node-test-loader.mjs src/api/group.test.ts src/api/resource-ip.test.ts src/views/group/list/group-list-filters.test.ts src/views/group/list/composables/useGroupListPage.test.ts src/views/group/list/components/HistoricalGroupFilterDrawer.test.ts src/views/group/list/GroupListPageContract.test.ts src/views/group/list/GroupListFolderIntegration.test.ts src/views/group/list/components/GroupListTable.test.ts src/views/group/list/components/GroupMemberDrawer.test.ts
  pnpm typecheck
  pnpm build
  ```

  Expected: 全部 PASS。

- [ ] **Step 4: 从一次性 MySQL 生成数据模型文档并检查敏感/临时内容**

  先实现 `DataModelWikiExportMySqlTest`：使用项目现有 Testcontainers MySQL 8.4.8，在一次性库执行全部 Flyway 后，通过 JDBC 查询 `information_schema.COLUMNS/STATISTICS/TABLES`，分别按生成器要求的字段顺序写 `/tmp/wheel_columns.tsv`、`/tmp/wheel_indexes.tsv`、`/tmp/wheel_tables.tsv`；测试输出不得含表数据或凭据。该类加 `@EnabledIfSystemProperty(named="armada.wiki.export", matches="true")`，避免普通 `mvn test` 强制依赖 Docker。然后执行：

  ```bash
  cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
  mvn -Darmada.wiki.export=true -Dtest='DataModelWikiExportMySqlTest' test
  cd /Users/daishuaishuai/IdeaProjects/armada
  python3 .harness/wiki/gen_datamodel.py
  cp /tmp/datamodel_tables.md .harness/wiki/数据模型.md
  ```

  Expected: 生成文档含 `group_link.is_historical/is_post_control`、`group_link_preview.metadata_observed_at`、`country.continent_code`、`whatsapp_group_member_snapshot`、`group_metadata_sync_task`。若本机 Docker 不可用或镜像无法取得，停止此步骤并请求用户确认一个已经应用本分支 Flyway 的测试 MySQL；不得擅自连接共享库，也不得手改自动生成文档。

  然后执行新增 diff 检查：

  ```bash
  git diff -U0 -- armada-api/src .harness/changes/group-list-history-metadata | rg -n '^\+.*(TODO|TBD|临时兜底|created_at.*group_created_at|dev-1\.pem|xieyi\.pem)'
  git -C /Users/daishuaishuai/IdeaProjects/armada-protocol diff -U0 -- protocol-layer/src | rg -n '^\+.*(TODO|TBD|dev-1\.pem|xieyi\.pem)'
  git -C /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web diff -U0 -- src/views/group/list src/api/group.ts src/api/resource-ip.ts | rg -n '^\+.*(TODO|TBD|dev-1\.pem|xieyi\.pem)'
  ```

  Expected: 三条命令都无输出（`rg` 退出码 1 表示没有匹配），不出现新增的未处理占位符、建群时间本地兜底或凭据引用。

- [ ] **Step 5: 人工验收清单**

  在明确的测试环境、获得用户确认后再连接真实服务；验收：

  1. 首次上线账号的既有群进入统一列表并显示历史群标签，详情异步从“待同步”变为成功。
  2. baseline 后新加入群显示上控后标签；同一群满足两类事实时显示两枚标签。
  3. 退群/被踢后分类标签保留，可用管理员按当前事实变为不可用。
  4. 历史筛选 Drawer 的关闭、清空、应用、查询语义与设计一致。
  5. 大洲联动国家；特殊地区仅在全部大洲下可选；未知国家不命中地区筛选。
  6. 建群时间只显示 WhatsApp creation；无 creation 显示 `-`。
  7. metadata 失败不清空旧管理员/成员/邀请链接，手动刷新只入队。
  8. 文件夹、批量分组、详情、删除等现有功能不回归。

- [ ] **Step 6: 最终只提交文档更新并审查三个仓库 diff**

  ```bash
  git add .harness/changes/group-list-history-metadata/summary.md .harness/changes/group-list-history-metadata/db-migrations.sql .harness/changes/group-list-history-metadata/rollback.sql .harness/wiki/数据模型.md armada-api/src/test/java/com/armada/boot/DataModelWikiExportMySqlTest.java
  git commit -m "docs: 完善群组列表对齐交付说明"
  ```

  Run:

  ```bash
  git -C /Users/daishuaishuai/IdeaProjects/armada status --short
  git -C /Users/daishuaishuai/IdeaProjects/armada-protocol status --short
  git -C /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web status --short
  ```

  Expected: 只剩执行前已记录的用户改动；没有未跟踪的业务文件，没有 `.claude/worktrees/**` 或凭据进入任何 commit。
