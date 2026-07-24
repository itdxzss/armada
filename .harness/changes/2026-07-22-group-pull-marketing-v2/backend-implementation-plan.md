# 拉群营销 Armada 后端 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Armada 新增独立的拉群营销任务后端，完成五张业务表、任务生命周期、并发建群状态机、营销引擎接入、分组整组锁和账号列表占用展示。

**Architecture:** 继续使用 `marketing_task` 作为公共营销任务和发送引擎入口，以 `business_type=2` 隔离拉群营销菜单；拉群特有配置与执行事实写入五张新表。业务调度使用“短事务分配资源 + `next_execute_at` 短租约 + 事务外协议调用”，建群成功后幂等写入固定营销目标并复用现有 round/attempt/outbox/结果回调链路。

**Tech Stack:** Java 17、Spring Boot 3.3、MyBatis/MyBatis-Plus、MySQL 8、Flyway、Spring Scheduling、Kafka outbox、JUnit 5、Mockito、AssertJ。

---

## 实施前提与执行顺序

- 需求真值以同目录 `summary.md` 为准；本计划不重新解释已经确认的业务口径。
- 先执行 `protocol-android-implementation-plan.md`，让 `GroupParticipantPort`、`GroupSettingsPort`、`GroupInvitePort` 和 `GroupLeavePort` 都能按 `ProtocolAccountRef` 路由 Web/Android。
- 当前工作树已有用户修改。每项提交只加入本项明确列出的文件，禁止 `git add .`。
- 当前最新迁移是 `V060`，本计划使用 `V061`。如果执行前主线已出现新的迁移，先把文件号顺延为当时下一个空闲版本，SQL 内容不变。
- 新代码集中到 `com.armada.marketing.grouppull`，公共营销表、账号分组及账号列表的兼容改动保留在现有包内。

## 文件结构

新增的主要文件：

- `armada-api/src/main/resources/db/migration/V061__group_pull_marketing.sql`：一次性建立字段、五张表及最小索引。
- `armada-api/src/main/java/com/armada/marketing/grouppull/model/**`：任务扩展、执行、料子、执行料子、营销账号统计实体和枚举。
- `armada-api/src/main/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingMapper.java`：五张表的事务写入、调度抢占和列表聚合。
- `armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml`：上述 Mapper 的 SQL。
- `armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingMaterialParser.java`：唯一 TXT/CSV 文件解析、号码清洗和去重。
- `armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingTaskService.java`：创建、列表、详情、启动、暂停、恢复、释放和删除的业务入口。
- `armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingAllocator.java`：每任务固定并发 5 的短事务资源分配。
- `armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingExecutionWorker.java`：按阶段推进单群执行，不持有跨协议调用数据库事务。
- `armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingFinalizer.java`：成功/失败结算、账号流转、营销目标创建和资源释放。
- `armada-api/src/main/java/com/armada/marketing/grouppull/scheduler/GroupPullMarketingScheduler.java`：跨租户扫描任务、执行租约及释放任务。
- `armada-api/src/main/java/com/armada/marketing/grouppull/controller/GroupPullMarketingTaskController.java`：独立 `/api/group-pull-marketing-tasks` API。
- `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingGroupOccupancyService.java`：所有营销类型共用的账号分组整组锁。

修改的公共文件：

- `MarketingTask.java`、`MarketingTaskMapper.java/xml`：加入 `business_type`，普通菜单固定筛选类型 1，发送调度允许类型 1/2。
- `MarketingTaskServiceImpl.java`、`MarketingTaskLifecycleWorker.java`、`MarketingRoundWorker.java`：普通营销锁组兼容和拉群营销安全结束分流。
- `MarketingNewGroupImmediateSendService.java/Impl.java`、`MarketingImmediateRetryService.java`：支持拉群营销固定目标第 0 轮首发和一次重试。
- `MarketingSendResultServiceImpl.java`：发送结果幂等回写后同步拉群群状态。
- `AccountGroup.java`、`AccountGroupMapper.java/xml`、`AccountGroupServiceImpl.java`：分组锁字段、条件抢锁/解锁及锁定期间操作限制。
- `AccountQuery.java`、`AccountListVoRow.java`、`AccountListVO.java`、`AccountMapper.java/xml`、`AccountServiceImpl.java`：轻量占用投影、筛选和单页批量补充。

### Task 1: 建立数据库事实模型

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V061__group_pull_marketing.sql`
- Create: `armada-api/src/test/java/com/armada/marketing/grouppull/GroupPullMarketingSchemaDbTest.java`

- [ ] **Step 1: 写迁移失败测试**

测试必须通过 `information_schema` 和真实写入覆盖以下事实：

```java
@Test
void migrationCreatesFiveTablesAndBusinessType() {
    assertThat(column("marketing_task", "business_type")).isPresent();
    assertThat(column("account_group", "marketing_occupancy_task_id")).isPresent();
    assertThat(table("group_pull_marketing_task")).isPresent();
    assertThat(table("group_pull_marketing_execution")).isPresent();
    assertThat(table("group_pull_marketing_material")).isPresent();
    assertThat(table("group_pull_marketing_execution_material")).isPresent();
    assertThat(table("group_pull_marketing_account_stat")).isPresent();
}

@Test
void activeBuilderUniqueKeyAllowsReuseOnlyAfterRelease() {
    long task1 = insertPullTask("任务1");
    long task2 = insertPullTask("任务2");
    long first = insertExecution(task1, 101L, null);
    assertThatThrownBy(() -> insertExecution(task2, 101L, null))
            .hasRootCauseInstanceOf(SQLIntegrityConstraintViolationException.class);
    jdbc.update("UPDATE group_pull_marketing_execution SET released_at=? WHERE id=?", 2L, first);
    assertThatCode(() -> insertExecution(task2, 101L, null)).doesNotThrowAnyException();
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dtest=GroupPullMarketingSchemaDbTest test`

Expected: FAIL，提示 `business_type` 或拉群营销表不存在。

- [ ] **Step 3: 写完整 Flyway 迁移**

迁移只创建已经确认的字段和索引：

```sql
ALTER TABLE marketing_task
    ADD COLUMN business_type TINYINT NOT NULL DEFAULT 1
        COMMENT '业务类型:1=普通营销 2=拉群营销' AFTER task_name,
    ADD KEY idx_marketing_task_business_page
        (tenant_id, business_type, deleted_at, id);

ALTER TABLE account_group
    ADD COLUMN marketing_occupancy_type TINYINT DEFAULT NULL
        COMMENT '营销占用类型:1单纯营销 2拉群营销 3拉群模式二 4拉群模式三 5其他营销',
    ADD COLUMN marketing_occupancy_task_id BIGINT DEFAULT NULL
        COMMENT '当前营销占用任务ID;NULL为空闲',
    ADD COLUMN marketing_locked_at BIGINT DEFAULT NULL
        COMMENT '营销分组锁定时间(epoch毫秒)',
    ADD KEY idx_account_group_marketing_occupancy
        (tenant_id, marketing_occupancy_type, marketing_occupancy_task_id);

ALTER TABLE marketing_account_occupancy
    COMMENT = '营销任务账号当前占用关系';

CREATE TABLE group_pull_marketing_task (
    marketing_task_id BIGINT NOT NULL COMMENT '统一营销任务ID',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    builder_group_id BIGINT NOT NULL COMMENT '建群账号分组ID',
    success_group_id BIGINT DEFAULT NULL COMMENT '建群成功转入分组ID',
    failure_group_id BIGINT DEFAULT NULL COMMENT '建群失败转入分组ID',
    marketing_account_group_limit INT NOT NULL DEFAULT 10 COMMENT '单营销账号当前任务最大群数',
    group_name_prefix VARCHAR(100) DEFAULT NULL COMMENT '群名前缀;NULL时使用任务名称',
    friend_retry_limit INT NOT NULL DEFAULT 3 COMMENT '加好友失败后的重试次数;不含首次',
    material_per_group INT NOT NULL DEFAULT 3 COMMENT '单群抽取料子数量',
    speak_permission TINYINT NOT NULL DEFAULT 1 COMMENT '发言权限:1不操作 2禁言 3不禁言',
    builder_exit_enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '建群账号是否退出:0否 1是',
    block_reason TINYINT NOT NULL DEFAULT 0 COMMENT '阻塞原因:0无 1建群账号 2营销账号 3料子 4系统异常 5人工处理',
    resource_status TINYINT NOT NULL DEFAULT 1 COMMENT '资源状态:1未锁定 2已锁定 3释放中 4已释放',
    marketing_account_total_count INT DEFAULT NULL COMMENT '启动锁组时营销账号总数',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (marketing_task_id),
    KEY idx_gpmt_tenant_resource (tenant_id, resource_status, marketing_task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='拉群营销任务扩展配置';

CREATE TABLE group_pull_marketing_execution (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    task_id BIGINT NOT NULL COMMENT '统一营销任务ID',
    builder_account_id BIGINT NOT NULL COMMENT '建群账号ID',
    marketing_account_id BIGINT DEFAULT NULL COMMENT '营销账号ID',
    group_name VARCHAR(100) DEFAULT NULL COMMENT '正式建群前冻结的群名称',
    group_jid VARCHAR(128) DEFAULT NULL COMMENT 'WhatsApp群JID',
    group_link_id BIGINT DEFAULT NULL COMMENT '统一群入口ID',
    group_invite_url VARCHAR(255) DEFAULT NULL COMMENT '群邀请链接',
    execution_status TINYINT NOT NULL DEFAULT 1 COMMENT '执行状态:1准备中 2执行中 3成功 4失败 5建群前跳过 6取消 7异常待处理',
    current_stage TINYINT NOT NULL DEFAULT 1 COMMENT '执行阶段:1资源 2好友 3建群 4营销号 5料子 6管理员 7权限 8群信息 9退群 10收口 11完成',
    stage_retry_count INT NOT NULL DEFAULT 0 COMMENT '当前阶段已发生的业务重试次数',
    next_execute_at BIGINT NOT NULL DEFAULT 0 COMMENT '下次业务推进时间及短期执行租约(epoch毫秒)',
    group_status TINYINT DEFAULT NULL COMMENT '群状态:1正常 2封禁;未创建为空',
    group_member_count INT DEFAULT NULL COMMENT '群成员总数',
    marketer_admin_status TINYINT NOT NULL DEFAULT 0 COMMENT '管理员状态:0不需要/未设置 1待设置 2已设置 3设置失败',
    builder_exit_status TINYINT NOT NULL DEFAULT 0 COMMENT '退群状态:0关闭/未退出 1待退出 2已退出 3退出失败',
    marketing_target_id BIGINT DEFAULT NULL COMMENT '现有营销固定目标ID',
    failure_reason VARCHAR(255) DEFAULT NULL COMMENT '非致命异常或最终失败原因;分号拼接',
    group_created_at BIGINT DEFAULT NULL COMMENT '群实际创建成功时间(epoch毫秒)',
    finished_at BIGINT DEFAULT NULL COMMENT '本次执行收口时间(epoch毫秒)',
    released_at BIGINT DEFAULT NULL COMMENT '建群账号任务占用释放时间(epoch毫秒)',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    active_builder_account_id BIGINT GENERATED ALWAYS AS (
        IF(released_at IS NULL, builder_account_id, NULL)
    ) STORED COMMENT '当前仍占用的建群账号',
    PRIMARY KEY (id),
    UNIQUE KEY uq_gpme_task_builder (tenant_id, task_id, builder_account_id),
    UNIQUE KEY uq_gpme_active_builder (tenant_id, active_builder_account_id),
    UNIQUE KEY uq_gpme_group_jid (tenant_id, group_jid),
    KEY idx_gpme_task_due (tenant_id, task_id, execution_status, next_execute_at, id),
    KEY idx_gpme_task_page (tenant_id, task_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='拉群营销单建群账号执行';

CREATE TABLE group_pull_marketing_material (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    task_id BIGINT NOT NULL COMMENT '统一营销任务ID',
    line_no INT NOT NULL COMMENT '有效料子稳定顺序',
    phone VARCHAR(32) NOT NULL COMMENT '清洗后手机号',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态:1可用 2已预留 3成功群已使用 4失败群已使用',
    current_execution_id BIGINT DEFAULT NULL COMMENT '当前预留或最终使用执行ID',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_gpmm_task_phone (tenant_id, task_id, phone),
    UNIQUE KEY uq_gpmm_task_line (tenant_id, task_id, line_no),
    KEY idx_gpmm_task_status_line (tenant_id, task_id, status, line_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='拉群营销料子池';

CREATE TABLE group_pull_marketing_execution_material (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    execution_id BIGINT NOT NULL COMMENT '建群执行ID',
    material_id BIGINT NOT NULL COMMENT '料子ID',
    allocation_no INT NOT NULL COMMENT '本群抽取顺序',
    friend_status TINYINT NOT NULL DEFAULT 1 COMMENT '好友状态:1待执行 2成功 3失败 4已存在',
    friend_failure_reason VARCHAR(255) DEFAULT NULL COMMENT '好友失败原因',
    entry_status TINYINT NOT NULL DEFAULT 1 COMMENT '进群状态:1待执行 2成功 3失败',
    entry_failure_reason VARCHAR(255) DEFAULT NULL COMMENT '进群失败原因',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_gpmem_execution_material (tenant_id, execution_id, material_id),
    UNIQUE KEY uq_gpmem_execution_order (tenant_id, execution_id, allocation_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='拉群执行料子历史';

CREATE TABLE group_pull_marketing_account_stat (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    task_id BIGINT NOT NULL COMMENT '统一营销任务ID',
    account_id BIGINT NOT NULL COMMENT '营销账号ID',
    reserved_group_count INT NOT NULL DEFAULT 0 COMMENT '已匹配尚未确认进群额度',
    joined_group_count INT NOT NULL DEFAULT 0 COMMENT '已成功进群永久消耗额度',
    created_at BIGINT NOT NULL COMMENT '首次实际调用时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '最近调用时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_gpmas_task_account (tenant_id, task_id, account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='拉群营销账号任务内额度';
```

- [ ] **Step 4: 运行迁移测试确认通过**

Run: `mvn -Dtest=GroupPullMarketingSchemaDbTest test`

Expected: PASS，且重复活动建群账号被唯一键拒绝、写入 `released_at` 后可被其他任务领取。

- [ ] **Step 5: 提交**

```bash
git add armada-api/src/main/resources/db/migration/V061__group_pull_marketing.sql armada-api/src/test/java/com/armada/marketing/grouppull/GroupPullMarketingSchemaDbTest.java
git commit -m "feat: add group pull marketing schema"
```

### Task 2: 建立枚举、实体与基础 Mapper

**Files:**
- Create: `armada-api/src/main/java/com/armada/marketing/model/enums/MarketingBusinessType.java`
- Create: `armada-api/src/main/java/com/armada/marketing/grouppull/model/enums/GroupPullBlockReason.java`
- Create: `armada-api/src/main/java/com/armada/marketing/grouppull/model/enums/GroupPullResourceStatus.java`
- Create: `armada-api/src/main/java/com/armada/marketing/grouppull/model/enums/GroupPullExecutionStatus.java`
- Create: `armada-api/src/main/java/com/armada/marketing/grouppull/model/enums/GroupPullExecutionStage.java`
- Create: `armada-api/src/main/java/com/armada/marketing/grouppull/model/enums/GroupPullMaterialStatus.java`
- Create: `armada-api/src/main/java/com/armada/marketing/grouppull/model/enums/GroupPullSpeakPermission.java`
- Create: `armada-api/src/main/java/com/armada/marketing/grouppull/model/entity/GroupPullMarketingTask.java`
- Create: `armada-api/src/main/java/com/armada/marketing/grouppull/model/entity/GroupPullMarketingExecution.java`
- Create: `armada-api/src/main/java/com/armada/marketing/grouppull/model/entity/GroupPullMarketingMaterial.java`
- Create: `armada-api/src/main/java/com/armada/marketing/grouppull/model/entity/GroupPullMarketingExecutionMaterial.java`
- Create: `armada-api/src/main/java/com/armada/marketing/grouppull/model/entity/GroupPullMarketingAccountStat.java`
- Create: `armada-api/src/main/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingMapper.java`
- Create: `armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml`
- Create: `armada-api/src/test/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingMapperDbTest.java`

- [ ] **Step 1: 写码值与基础读写失败测试**

```java
@Test
void codesRemainStable() {
    assertThat(MarketingBusinessType.ORDINARY.code()).isEqualTo(1);
    assertThat(MarketingBusinessType.GROUP_PULL.code()).isEqualTo(2);
    assertThat(GroupPullExecutionStage.RESOURCE_PREPARATION.code()).isEqualTo(1);
    assertThat(GroupPullExecutionStage.COMPLETED.code()).isEqualTo(11);
    assertThat(GroupPullResourceStatus.UNLOCKED.code()).isEqualTo(1);
    assertThat(GroupPullResourceStatus.RELEASE_FAILED.code()).isEqualTo(5);
}

@Test
void mapperRoundTripsTaskAndOrderedMaterials() {
    GroupPullMarketingTask task = task(2001L);
    assertThat(mapper.insertTask(task)).isEqualTo(1);
    mapper.insertMaterials(List.of(material(2001L, 2, "8613800000002"), material(2001L, 1, "8613800000001")));
    assertThat(mapper.selectAvailableMaterialsForUpdate(2001L, 2))
            .extracting(GroupPullMarketingMaterial::getLineNo)
            .containsExactly(1, 2);
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dtest=GroupPullMarketingMapperDbTest test`

Expected: FAIL，枚举、实体或 Mapper 尚不存在。

- [ ] **Step 3: 实现稳定码值枚举**

所有枚举采用显式整数，不使用 ordinal。核心定义必须是：

```java
public enum MarketingBusinessType {
    ORDINARY(1), GROUP_PULL(2);
    private final int code;
    MarketingBusinessType(int code) { this.code = code; }
    public int code() { return code; }
}

public enum GroupPullExecutionStage {
    RESOURCE_PREPARATION(1), FRIEND_PREPARATION(2), CREATE_GROUP(3),
    ADD_MARKETER(4), ADD_MATERIALS(5), SET_MARKETER_ADMIN(6),
    SET_SPEAK_PERMISSION(7), SAVE_GROUP_INFO(8), BUILDER_LEAVE(9),
    FINALIZE_RESULT(10), COMPLETED(11);
    private final int code;
    GroupPullExecutionStage(int code) { this.code = code; }
    public int code() { return code; }
}
```

其余枚举严格按 `summary.md` 第 22 节的码值建立；未知数据库码值必须抛 `IllegalArgumentException`，不能默认为成功或空闲。

- [ ] **Step 4: 实现五个裸 POJO 与 Mapper**

POJO 字段与迁移一一对应，不增加名称快照、JSON、`operationId`、`next_group_no` 或独立 `failure_stage`。Mapper 至少提供：

```java
@Mapper
public interface GroupPullMarketingMapper {
    int insertTask(GroupPullMarketingTask row);
    int insertMaterials(@Param("rows") List<GroupPullMarketingMaterial> rows);
    GroupPullMarketingTask selectTaskById(@Param("taskId") Long taskId);
    List<GroupPullMarketingMaterial> selectAvailableMaterialsForUpdate(
            @Param("taskId") Long taskId, @Param("limit") int limit);
    int reserveMaterials(@Param("ids") List<Long> ids,
                         @Param("executionId") Long executionId,
                         @Param("now") long now);
    int insertExecution(GroupPullMarketingExecution row);
    int insertExecutionMaterials(@Param("rows") List<GroupPullMarketingExecutionMaterial> rows);
    GroupPullMarketingExecution selectExecutionById(@Param("id") Long id);
    List<GroupPullMarketingExecutionMaterial> selectExecutionMaterials(@Param("executionId") Long executionId);
    GroupPullMarketingAccountStat selectAccountStatForUpdate(
            @Param("taskId") Long taskId, @Param("accountId") Long accountId);
    int insertAccountStat(GroupPullMarketingAccountStat row);
    int reserveMarketingQuota(@Param("taskId") Long taskId,
                              @Param("accountId") Long accountId,
                              @Param("limit") int limit,
                              @Param("now") long now);
}
```

`selectAvailableMaterialsForUpdate` 使用 `status=1 ORDER BY line_no ASC LIMIT #{limit} FOR UPDATE`；所有跨租户调度扫描方法才使用 `@InterceptorIgnore(tenantLine = "true")`，普通方法继续由租户拦截器注入租户条件。

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -Dtest=GroupPullMarketingMapperDbTest test`

Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add armada-api/src/main/java/com/armada/marketing/model/enums/MarketingBusinessType.java armada-api/src/main/java/com/armada/marketing/grouppull armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml armada-api/src/test/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingMapperDbTest.java
git commit -m "feat: add group pull marketing data access"
```

### Task 3: 给公共营销任务增加业务类型并实现整组锁

**Files:**
- Modify: `armada-api/src/main/java/com/armada/marketing/model/entity/MarketingTask.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/account/model/entity/AccountGroup.java`
- Modify: `armada-api/src/main/java/com/armada/account/mapper/AccountGroupMapper.java`
- Modify: `armada-api/src/main/resources/mapper/account/AccountGroupMapper.xml`
- Create: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingGroupOccupancyService.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTemplateServiceImpl.java`
- Test: `armada-api/src/test/java/com/armada/marketing/service/MarketingGroupOccupancyServiceDbTest.java`
- Test: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java`

- [ ] **Step 1: 写跨类型抢锁失败测试**

```java
@Test
void onlyFirstTaskCanLockWholeMarketingGroup() {
    assertThat(service.tryLock(groupId, MarketingBusinessType.GROUP_PULL, 101L, 1_000L)).isTrue();
    assertThat(service.tryLock(groupId, MarketingBusinessType.ORDINARY, 102L, 2_000L)).isFalse();
    assertThat(service.release(groupId, MarketingBusinessType.ORDINARY, 102L, 3_000L)).isFalse();
    assertThat(service.release(groupId, MarketingBusinessType.GROUP_PULL, 101L, 3_000L)).isTrue();
}

@Test
void ordinaryMarketingListNeverReturnsGroupPullRows() {
    insertMarketingTask(1, "普通");
    insertMarketingTask(2, "拉群");
    assertThat(marketingTaskMapper.selectPage(new MarketingTaskQuery()))
            .extracting(MarketingTask::getTaskName)
            .containsExactly("普通");
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dtest=MarketingGroupOccupancyServiceDbTest,MarketingTaskCreateReadDbTest test`

Expected: FAIL，公共实体与 SQL 尚无业务类型/分组锁。

- [ ] **Step 3: 增加公共任务类型字段和普通菜单隔离**

`MarketingTask` 增加 `Integer businessType`。`MarketingTaskMapper.xml` 的 `TaskColumns`、resultMap 和 insert 同步加入该列。普通营销的 `TaskFilter` 固定：

```xml
<where>
    business_type = 1
    AND deleted_at IS NULL
    <!-- 保留现有 id/keyword/status/time 条件 -->
</where>
```

`selectDueWaitingTasks`、`startDueWaitingTask` 和普通营销接口使用的 start/pause/resume/close SQL 都加 `business_type = 1`，避免待启动拉群任务被普通定时器自动启动。`selectDueSendingTasks` 保留类型 1 和 2，因为两类任务共用营销轮次发送。

- [ ] **Step 4: 实现账号分组原子抢锁/归属解锁**

`AccountGroup` 增加 `marketingOccupancyType`、`marketingOccupancyTaskId`、`marketingLockedAt`。Mapper SQL 必须是单条条件更新：

```xml
<update id="tryLockMarketingOccupancy">
  UPDATE account_group
  SET marketing_occupancy_type = #{occupancyType},
      marketing_occupancy_task_id = #{taskId},
      marketing_locked_at = #{now},
      updated_at = #{now}
  WHERE id = #{groupId}
    AND deleted_at IS NULL
    AND marketing_occupancy_task_id IS NULL
</update>

<update id="releaseMarketingOccupancy">
  UPDATE account_group
  SET marketing_occupancy_type = NULL,
      marketing_occupancy_task_id = NULL,
      marketing_locked_at = NULL,
      updated_at = #{now}
  WHERE id = #{groupId}
    AND marketing_occupancy_type = #{occupancyType}
    AND marketing_occupancy_task_id = #{taskId}
</update>
```

`MarketingGroupOccupancyService.tryLock/release` 仅把受影响行数 1 映射为 `true`，不做先查后改。

- [ ] **Step 5: 把普通营销纳入整组锁**

`MarketingTaskServiceImpl.createTask` 在同一创建事务中按以下顺序执行：写 `business_type=ORDINARY` → 原子锁营销分组 → 检查分组内不存在属于其他任务的上线前遗留 `marketing_account_occupancy` → 现有账号级 `lockTaskAccountsOrThrow`。任一步失败整单回滚。普通营销关闭、到期完成和模板异常完成时同时释放账号级占用和归属自己的分组锁；暂停不释放。对上线前创建、从未持有分组锁的旧任务，结束时若分组锁为空则只清账号级占用并正常完成；若分组锁属于其他任务则绝不能清除。

模板删除前新增查询：只要 `business_type=2 AND status IN (1,2,5)` 的拉群任务引用模板，就抛 `CONFLICT`；普通营销保留现有“异常完成并释放”行为。

- [ ] **Step 6: 运行回归测试**

Run: `mvn -Dtest=MarketingGroupOccupancyServiceDbTest,MarketingTaskCreateReadDbTest,MarketingTaskServiceImplLifecycleTest,MarketingTemplateServiceImplTest test`

Expected: PASS；普通营销现有生命周期断言不变，新增整组锁断言通过。

- [ ] **Step 7: 提交**

```bash
# 按本 Task 的 Files 清单逐文件暂存；对进入本任务前已修改的文件使用 git add -p。
git diff --cached --name-only
git commit -m "feat: add cross marketing group occupancy"
```

`git diff --cached --name-only` 中不得出现本 Task 清单之外的用户改动。

### Task 4: 实现单文件解析与待启动任务创建

**Files:**
- Create: `armada-api/src/main/java/com/armada/marketing/grouppull/model/dto/CreateGroupPullMarketingTaskDTO.java`
- Create: `armada-api/src/main/java/com/armada/marketing/grouppull/model/dto/GroupPullMarketingTaskQuery.java`
- Create: `armada-api/src/main/java/com/armada/marketing/grouppull/model/vo/GroupPullMarketingTaskVO.java`
- Create: `armada-api/src/main/java/com/armada/marketing/grouppull/model/vo/GroupPullMarketingTaskDetailVO.java`
- Create: `armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingMaterialParser.java`
- Reuse: `armada-api/src/main/java/com/armada/group/service/FileLinesExtractor.java`
- Create: `armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingTaskService.java`
- Create: `armada-api/src/main/java/com/armada/marketing/grouppull/service/impl/GroupPullMarketingTaskServiceImpl.java`
- Create: `armada-api/src/main/java/com/armada/marketing/grouppull/controller/GroupPullMarketingTaskController.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml`
- Test: `armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingMaterialParserTest.java`
- Test: `armada-api/src/test/java/com/armada/marketing/grouppull/controller/GroupPullMarketingTaskControllerDbTest.java`

- [ ] **Step 1: 写解析器失败测试**

```java
@Test
void txtNormalizesDeduplicatesAndKeepsFirstOrder() {
    MockMultipartFile file = new MockMultipartFile(
            "materialFile", "numbers.txt", "text/plain",
            "+86 138-0000-0001\n8613800000001\n(91) 98765 43210\nabc".getBytes(UTF_8));
    assertThat(parser.parse(file)).extracting(ParsedMaterial::phone)
            .containsExactly("8613800000001", "919876543210");
}

@Test
void csvReadsOnlyFirstColumn() {
    MockMultipartFile file = new MockMultipartFile(
            "materialFile", "numbers.csv", "text/csv",
            "8613800000001,name-a\n8613800000002,name-b".getBytes(UTF_8));
    assertThat(parser.parse(file)).extracting(ParsedMaterial::phone)
            .containsExactly("8613800000001", "8613800000002");
}

@Test
void rejectsUnsupportedOrEmptyFile() {
    assertThatThrownBy(() -> parser.parse(file("a.xlsx", "1"))).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> parser.parse(file("a.txt", "abc"))).isInstanceOf(BusinessException.class);
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dtest=GroupPullMarketingMaterialParserTest test`

Expected: FAIL，解析器不存在。

- [ ] **Step 3: 实现唯一 TXT/CSV 解析器**

先在 `GroupPullMarketingMaterialParser` 严格拒绝 TXT/CSV 以外的扩展名，再复用现有 `FileLinesExtractor.extract(file, null)` 完成 TXT 逐行和 CSV 第一列提取，不新加第二套文件读取依赖。每行先拒绝包含 `@` 的非手机号输入，再调用 `WhatsappJids.userJid` 复用现有展示字符清洗规则，并明确取返回 JID 中 `@` 前的数字部分作为 `phone`；不得把带 `@s.whatsapp.net` 的 JID 写入料子表。单行清洗异常只跳过该无效行，不能让整包解析失败。清洗不补国家码，最终只接受 7～15 位数字。使用 `LinkedHashSet` 保留首次出现顺序。输出记录：

```java
public record ParsedMaterial(int lineNo, String phone) {}
```

`lineNo` 是有效去重数据顺序，从 1 开始，不保存原始文件行号、无效数和重复数。

- [ ] **Step 4: 写创建接口失败测试**

使用 MockMvc 以 `multipart/form-data` 提交：

```java
mockMvc.perform(multipart("/api/group-pull-marketing-tasks")
        .file(new MockMultipartFile("config", "", "application/json", objectMapper.writeValueAsBytes(config)))
        .file(new MockMultipartFile("materialFile", "m.txt", "text/plain", "8613800000001".getBytes(UTF_8))))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.data.status").value(1))
    .andExpect(jsonPath("$.data.resourceStatus").value(1))
    .andExpect(jsonPath("$.data.totalDataCount").value(1));
```

同时覆盖：任务名空、建群分组无正常在线账号、营销分组无正常在线账号、模板不存在、结束时间不在未来、文件无有效数据时回滚且三张创建相关表均无残留。

- [ ] **Step 5: 实现 DTO、Service 和 Controller**

创建配置准确包含：

```java
public record CreateGroupPullMarketingTaskDTO(
        String taskName,
        Long builderGroupId,
        Long successGroupId,
        Long failureGroupId,
        Long marketingGroupId,
        Integer marketingAccountGroupLimit,
        Long marketingTemplateId,
        Integer sendIntervalSeconds,
        String groupNamePrefix,
        Integer friendRetryLimit,
        Integer materialPerGroup,
        Integer speakPermission,
        Boolean builderExitEnabled,
        String remark,
        Long taskEndAt) {}
```

Controller 方法：

```java
@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public ApiResponse<GroupPullMarketingTaskDetailVO> create(
        @RequestPart("config") CreateGroupPullMarketingTaskDTO config,
        @RequestPart("materialFile") MultipartFile materialFile) {
    return ApiResponse.ok(service.create(config, materialFile));
}
```

创建事务必须写一条 `marketing_task`（`business_type=2`、`account_group_id=marketingGroupId`、`status=1`、`account_group_send_interval_ms=0`、`auto_retry_enabled=1`、`retry_limit=1`、`next_round_at=NULL`），一条扩展任务和全部有效料子。公共表既有 `account_group_name`、`marketing_template_name` 是非空历史字段，按当前关联名称正常填充；它们不复制到五张新表，也不用于拉群业务判断。公共表的目标数、成功数和失败数等既有计数初始为 0。保存阶段不锁营销分组、不生成执行记录。建群分组与营销分组不能相同只由前端校验，后端不重复增加这一项校验。

- [ ] **Step 6: 实现列表和详情批量聚合**

列表 SQL 使用 `marketing_task JOIN group_pull_marketing_task`，统计通过按页任务 ID 的聚合子查询一次返回；不得在 Service 中逐任务查询。列表返回：三个状态维度、总/完成数据、成功/失败群数、营销账号总/占用数、创建/结束时间。详情只返回配置和汇总，不返回全部料子或群明细。

- [ ] **Step 7: 运行测试确认通过**

Run: `mvn -Dtest=GroupPullMarketingMaterialParserTest,GroupPullMarketingTaskControllerDbTest test`

Expected: PASS。

- [ ] **Step 8: 提交**

```bash
git add armada-api/src/main/java/com/armada/marketing/grouppull/model/dto/CreateGroupPullMarketingTaskDTO.java \
  armada-api/src/main/java/com/armada/marketing/grouppull/model/dto/GroupPullMarketingTaskQuery.java \
  armada-api/src/main/java/com/armada/marketing/grouppull/model/vo/GroupPullMarketingTaskVO.java \
  armada-api/src/main/java/com/armada/marketing/grouppull/model/vo/GroupPullMarketingTaskDetailVO.java \
  armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingMaterialParser.java \
  armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingTaskService.java \
  armada-api/src/main/java/com/armada/marketing/grouppull/service/impl/GroupPullMarketingTaskServiceImpl.java \
  armada-api/src/main/java/com/armada/marketing/grouppull/controller/GroupPullMarketingTaskController.java \
  armada-api/src/main/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingMapper.java \
  armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml \
  armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingMaterialParserTest.java \
  armada-api/src/test/java/com/armada/marketing/grouppull/controller/GroupPullMarketingTaskControllerDbTest.java
git commit -m "feat: create pending group pull marketing tasks"
```

### Task 5: 实现任务生命周期和安全释放入口

**Files:**
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/service/impl/GroupPullMarketingTaskServiceImpl.java`
- Create: `armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingReleaseService.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/controller/GroupPullMarketingTaskController.java`
- Test: `armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingLifecycleDbTest.java`

- [ ] **Step 1: 写五个状态操作的失败测试**

覆盖以下断言：待启动 start 成功后 `2/0/2`（执行中/无阻塞/已锁定）；锁冲突仍为 `1/0/1`；pause 保持锁；resume 重新进入执行；release 立刻写 `8/0/3`；待启动 delete 软删公共任务并物理删除扩展和料子；非待启动 delete 返回冲突。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dtest=GroupPullMarketingLifecycleDbTest test`

Expected: FAIL，生命周期 SQL 尚不存在。

- [ ] **Step 3: 实现启动事务**

启动按固定顺序：锁 `marketing_task` 行 → 验证 `business_type=2/status=1/endAt>now` → `MarketingGroupOccupancyService.tryLock` 原子抢营销分组锁 → 验证营销分组至少一个正常在线账号、复核不存在上线前遗留的旧 `marketing_account_occupancy` → 验证建群分组至少一个正常在线候选 → 保存营销分组当前账号总数 → 条件更新主状态为 2、资源为 2、阻塞为 0、`started_at` 首次赋值。任一步失败都抛 `CONFLICT` 并回滚整个事务，因此不会遗留分组锁。

- [ ] **Step 4: 实现暂停、恢复、释放和删除**

使用带预期状态的条件更新：

```text
pause:   status 2 -> 5，resource 保持 2，block_reason 保留
resume:  status 5 -> 2，resource 必须为 2，随后立即触发一次资源事实检查
release: status 2/5 -> 8，block_reason=0，resource 2 -> 3，next_round_at=NULL
delete:  status 1 -> deleted_at=now；同事务删除扩展和料子
```

`release` 只停止新分配；正式建群记录由 worker 收口，未正式建群记录由释放服务取消。接口在写入“已手动结束 + 释放中”后即可返回。

Controller 路径固定为：

```text
POST   /api/group-pull-marketing-tasks/{id}/start
POST   /api/group-pull-marketing-tasks/{id}/pause
POST   /api/group-pull-marketing-tasks/{id}/resume
POST   /api/group-pull-marketing-tasks/{id}/release
DELETE /api/group-pull-marketing-tasks/{id}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -Dtest=GroupPullMarketingLifecycleDbTest test`

Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add armada-api/src/main/java/com/armada/marketing/grouppull armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingLifecycleDbTest.java
git commit -m "feat: add group pull marketing lifecycle"
```

### Task 6: 实现固定并发 5 的原子资源分配

**Files:**
- Create: `armada-api/src/main/java/com/armada/marketing/grouppull/model/vo/GroupPullAccountRefRow.java`
- Create: `armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingAllocator.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/marketing/mapper/MarketingAccountOccupancyMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingAccountOccupancyMapper.xml`
- Test: `armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingAllocatorDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/mapper/MarketingAccountOccupancyMapperDbTest.java`

- [ ] **Step 1: 写并发分配失败测试**

测试必须验证：单任务最多 5 条在途；同一建群账号不能被普通营销、另一拉群任务或其他复用账号占用表的任务同时领取；建群账号所在分组已经被任一营销任务整组锁定时不领取；营销账号复用账号列表当前默认 `created_at DESC` 顺序；`reserved+joined` 达上限后换下一个；料子不足完整一组时不插执行；任一步不足事务无半占用。占用清理回归还要断言拉群任务主状态已经是完成/手动结束但资源为 2 或 3 时不清建群账号，资源为 4 后才允许清残留；按模板释放只处理普通营销，普通营销原有 1、2、5 保留规则不变。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dtest=GroupPullMarketingAllocatorDbTest,MarketingAccountOccupancyMapperDbTest test`

Expected: FAIL。

- [ ] **Step 3: 实现短事务 allocator**

每次 `allocateOne(taskId)` 在一个事务内：

1. `SELECT ... FOR UPDATE` 锁扩展任务并确认主状态 2、资源状态 2；
2. 统计 `execution_status IN (1,2)`，达到 5 直接返回 `CONCURRENCY_FULL`，不写阻塞原因；
3. 从建群分组选择正常在线、所在分组没有营销整组锁、未在活动执行且未在 `marketing_account_occupancy` 的账号；使用新增 `tryOccupyTaskAccount(taskId, accountId, now)` 通过 `INSERT ... SELECT marketing_task` 写入现有占用表，只有影响 1 行才算领取成功，唯一键冲突时在新事务选择下一账号；
4. 从营销分组选择正常在线且 `reserved_group_count + joined_group_count < limit` 的账号并预留 1；
5. 锁定并读取完整 `material_per_group` 条可用料子，不足时回滚；
6. 插执行、执行料子关系，更新料子为已预留后提交。

资源不足返回明确枚举 `WAIT_BUILDER/WAIT_MARKETER/WAIT_MATERIAL`，外层用独立短事务更新扩展表 `block_reason`。成功分配清除旧阻塞原因。建群账号占用插入或执行表唯一键冲突时重新开启一次分配事务选择下一账号，不吞掉其他约束异常。新增 `releaseByTaskAndAccount(taskId, accountId)`，只允许释放当前任务自己的账号占用；建群前跳过、成功、失败和释放流程都调用该方法，任务安全释放时再用既有 `releaseByTaskId` 清理异常残留。

同步把 `selectOwnersByTaskAccounts`、`selectOwnersByAccountIds` 和 `deleteStale` 共用的有效 owner 条件按业务类型分流：普通营销继续以主状态 `1/2/5` 为有效；拉群营销只要扩展资源状态为 `2/3` 就必须返回并保留，即使主状态已经是 `7/8`，避免安全收口期间提前释放或出现唯一键被占但查不到 owner。资源状态 4 后才可作为残留删除。现有 `releaseByTemplateIds` 固定只删除 `business_type=1` 普通营销占用，不能因模板删除绕过拉群安全释放。Mapper JavaDoc 从“普通营销账号占用”调整为“营销任务账号占用”；不增加占用类型字段，也不新建占用表。

- [ ] **Step 4: 实现营销额度的三种变更**

```text
匹配：reserved += 1
确认进群：reserved -= 1, joined += 1（条件 reserved > 0，且只执行一次）
建群前取消匹配：reserved -= 1（条件 reserved > 0）
```

营销账号已经进群后，后续建群失败不得减少 `joined_group_count`。

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -Dtest=GroupPullMarketingAllocatorDbTest,MarketingAccountOccupancyMapperDbTest test`

Expected: PASS，包括两个并发事务只有一个能领取同一建群账号、被分组整组锁或账号级占用的候选不会被领取。

- [ ] **Step 6: 提交**

```bash
git add armada-api/src/main/java/com/armada/marketing/grouppull \
  armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml \
  armada-api/src/main/java/com/armada/marketing/mapper/MarketingAccountOccupancyMapper.java \
  armada-api/src/main/resources/mapper/marketing/MarketingAccountOccupancyMapper.xml \
  armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingAllocatorDbTest.java \
  armada-api/src/test/java/com/armada/marketing/mapper/MarketingAccountOccupancyMapperDbTest.java
git commit -m "feat: allocate group pull marketing resources"
```

### Task 7: 实现执行租约、好友准备和幂等建群

**Files:**
- Create: `armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingExecutionWorker.java`
- Create: `armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullRetryPolicy.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml`
- Test: `armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingExecutionWorkerTest.java`

- [ ] **Step 1: 写阶段 2～4 的失败测试**

Mockito 测试覆盖：只有条件抢租约影响 1 行的 worker 调协议；每阶段前建群账号离线只延后且不增加重试/不释放占用，封禁立即失败收口并转失败分组；建群账号→营销账号、营销账号→建群账号双向保存联系人；建群账号对每条料子单向保存；料子好友失败仍进入建群；互加失败按配置重试并切下一个营销账号；全部营销账号失败时执行状态 5、不计建群失败，归还料子/营销额度并按任务+账号释放建群账号占用；建群使用营销账号作为唯一初始成员；初始成员成功直接跳到阶段 5，失败进入阶段 4 补加；Redis 幂等存储不可用只写系统异常并延后原阶段，不消耗固定业务重试；建群结果不明确进入人工处理且不重建。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dtest=GroupPullMarketingExecutionWorkerTest test`

Expected: FAIL。

- [ ] **Step 3: 实现 `next_execute_at` 短租约**

跨租户扫描只选 `execution_status IN (1,2)` 且 `next_execute_at<=now`。每次处理先读取建群账号最新账号/登录状态：账号离线时保持当前状态和阶段，把 `next_execute_at` 延后一个固定短轮询周期，不增加 `stage_retry_count`，也不释放账号、营销额度或料子；账号封禁时追加封禁阶段原因，进入失败 Finalizer 并按失败分组流转。封禁发生在正式建群前时仍保存失败执行结果，但聚合失败数量继续要求 `group_name IS NOT NULL`，不破坏统一统计起点。账号正常时再执行：

```sql
UPDATE group_pull_marketing_execution
SET next_execute_at = :leaseUntil,
    updated_at = :now
WHERE id = :id
  AND execution_status IN (1,2)
  AND current_stage = :expectedStage
  AND next_execute_at <= :now;
```

只有影响 1 行才调用协议。协议完成后仍以 `id + expectedStage + executionStatus` 条件推进下一阶段并重置 `stage_retry_count=0`。实例崩溃后租约到期可接手；创建结果不明确由协议计划定义的错误码转异常待处理。

租约按阶段固定：`CREATE_GROUP` 使用“Web/Android 最大协议 read timeout + 30 秒安全余量”，当前两个 backend 默认 60 秒，因此默认创建租约为 90 秒；测试断言首个创建请求仍在超时窗口内时第二实例抢不到租约。其他联系人、成员、管理员、权限和退群属于目标状态操作，继续使用较短固定租约。不得为此新增 `locked_by/locked_until` 字段。

- [ ] **Step 4: 实现好友阶段**

好友调用 operationId 使用确定性格式：

```text
group-pull:{executionId}:friend:builder-to-marketer:{marketerId}
group-pull:{executionId}:friend:marketer-to-builder:{marketerId}
group-pull:{executionId}:friend:builder-to-material:{materialId}
```

同一次 stage 调用内只重试失败方向；总尝试次数为 `1 + friend_retry_limit`。系统中断后重复保存联系人按目标状态操作处理。料子结果逐条写 `friend_status/reason`，失败不补位。

- [ ] **Step 5: 实现正式建群边界和名称生成**

进入阶段 3 前锁 `marketing_task` 行，统计当前任务 `group_name IS NOT NULL` 的执行记录数并生成 `prefix-(count+1)`。先生成 `-序号` 后缀，再把前缀截到 `100-后缀长度`，保证序号不会被截掉；先保存完整名称再调用协议。随后调用：

```java
groupCreatePort.create(new GroupCreateCommand(
        builderRef,
        execution.getGroupName(),
        List.of(marketerRef.wsPhone()),
        false,
        "group-pull:" + execution.getId() + ":create-group"));
```

固定两次重试只用于协议明确失败。`IDEMPOTENCY_STORE_UNAVAILABLE` 保持当前阶段和 `stage_retry_count`，写任务阻塞 4 并把 `next_execute_at` 延后，Redis 恢复后继续；`GROUP_CREATE_RESULT_UNCONFIRMED` 立即写执行状态 7、任务阻塞 5，不自动重建。成功时写 `group_jid/group_created_at/group_status=1` 并清除系统异常阻塞；创建返回已确认营销账号成功则原子转营销额度并跳阶段 5，否则进阶段 4。

- [ ] **Step 6: 运行测试确认通过**

Run: `mvn -Dtest=GroupPullMarketingExecutionWorkerTest test`

Expected: PASS。

- [ ] **Step 7: 提交**

```bash
git add armada-api/src/main/java/com/armada/marketing/grouppull armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingExecutionWorkerTest.java
git commit -m "feat: execute group pull preparation and create stages"
```

### Task 8: 实现加人、管理员、权限、群信息和退群阶段

**Files:**
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingExecutionWorker.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/group/service/GroupLinkRegistryService.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/GroupLinkRegistryServiceImpl.java`
- Test: `armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingGroupStagesTest.java`
- Test: `armada-api/src/test/java/com/armada/group/service/impl/GroupLinkRegistryServiceDbTest.java`

- [ ] **Step 1: 写阶段 4～9 的失败测试**

覆盖：营销账号补加成功才消耗 quota；料子批量结果逐项落库且不补位；实际进群小于配置最终失败；管理员只在禁言或退出开启时执行；不操作不调权限端口；禁言传 `false`、不禁言传 `true`；群成员只查询一次；群人数/链接失败只追加 `failure_reason`；退群关闭不调端口，开启失败最终建群失败；任一群操作明确返回群封禁/终止时更新 `group_status=2`，营销账号封禁不修改群状态。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dtest=GroupPullMarketingGroupStagesTest test`

Expected: FAIL。

- [ ] **Step 3: 实现添加营销账号和料子**

所有成员统一转 WhatsApp user JID。`GroupParticipantBatchResult` 只把状态 `OK`、`ALREADY_IN` 或原始 `200` 视为成功。营销账号阶段成功时用条件 SQL 把 reserved 转 joined；重复推进不得再次增加。料子阶段对尚未成功项最多固定重试 2 次，逐项更新 relation；不抽新料子。阶段统一异常分类器只在错误码/群状态证据明确为 `GROUP_BANNED/BANNED/CHAT_SUSPENDED/CHAT_TERMINATED` 时把已有真实群的 `group_status` 条件更新为 2；账号封禁、离线和未知异常不得误标群封禁。

- [ ] **Step 4: 实现管理员和发言权限联动**

```java
boolean adminRequired = permission == GroupPullSpeakPermission.MUTED
        || task.getBuilderExitEnabled() == 1;
```

不需要管理员时写 `marketer_admin_status=0` 并跳过协议；需要时使用 `PROMOTE`，成功写 2，重试耗尽写 3 并失败收口。权限不操作直接完成；禁言调用 `setSendMessagesAllowed(builderRef, groupJid, false)`；不禁言传 `true`。

- [ ] **Step 5: 实现一次性群信息查询与已知成员登记**

阶段 8 依次执行一次成员查询和一次邀请链接查询，分别捕获异常并通过“去重分号拼接、总长 255”方法写 `failure_reason`，两者都不单独否决成功。核心信息事务中：

1. 保存实际料子结果和群人数；
2. `registerSelfBuiltGroup` 登记 builder 为群主；
3. 新增 `GroupLinkRegistryService.registerKnownMembership(...)`，登记 marketer 的在群/admin 已知事实；
4. 回写 `group_link_id` 和可空 `group_invite_url`。

核心事务异常按原需求进入建群失败。

- [ ] **Step 6: 实现退群阶段**

关闭退出写 `builder_exit_status=0` 并进入收口；开启退出先确认营销账号进群、所需管理员已设置、权限步骤完成和核心信息已保存，再调用 `GroupLeavePort.leave(builderRef, groupJid)`。成功写 `builder_exit_status=2`；固定两次重试耗尽写 3 并失败收口。

- [ ] **Step 7: 运行测试确认通过**

Run: `mvn -Dtest=GroupPullMarketingGroupStagesTest,GroupLinkRegistryServiceDbTest test`

Expected: PASS。

- [ ] **Step 8: 提交**

```bash
git add armada-api/src/main/java/com/armada/marketing/grouppull armada-api/src/main/java/com/armada/group/service/GroupLinkRegistryService.java armada-api/src/main/java/com/armada/group/service/impl/GroupLinkRegistryServiceImpl.java armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml armada-api/src/test/java/com/armada/marketing/grouppull armada-api/src/test/java/com/armada/group/service/impl/GroupLinkRegistryServiceDbTest.java
git commit -m "feat: complete group pull protocol stages"
```

### Task 9: 实现结果收口和现有营销引擎接入

**Files:**
- Create: `armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingFinalizer.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/MarketingNewGroupImmediateSendService.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/MarketingMessageCommandFactory.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingNewGroupImmediateSendServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingImmediateRetryService.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundWorker.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingSendResultServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml`
- Test: `armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingFinalizerDbTest.java`
- Test: `armada-api/src/test/java/com/armada/marketing/service/MarketingMessageCommandFactoryTest.java`
- Test: `armada-api/src/test/java/com/armada/marketing/service/MarketingNewGroupImmediateSendServiceImplTest.java`
- Test: `armada-api/src/test/java/com/armada/marketing/service/MarketingImmediateRetryServiceTest.java`
- Test: `armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundWorkerTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingSendResultServiceImplTest.java`

- [ ] **Step 1: 写成功/失败结算失败测试**

验证：成功只统计 execution_status=3；成功料子状态 3；失败群已进群料子状态 4、未进群料子回 1；营销账号 joined 不退；成功、失败和建群前跳过都只按“任务+建群账号”释放现有账号级占用；建群结果转组失败不改结果且追加原因；成功只插一个固定 target；第一个成功群初始化 `next_round_at`，后续成功群不推迟；暂停期间完成建群插 target 并把首次正常轮次置为已到期，但不写 attempt/outbox，恢复后由正常轮次立即发送；营销账号离线时只保存 target、不首发，恢复后参加正常轮次；营销账号封禁时其历史 target 停止发送；明确群封禁的首次发送不触发即时重试，后续正常轮次也不再为该 target 建 attempt/outbox；命令 source 为 `group_pull_marketing` 且 `sendIntervalMs=0`，普通营销仍保持原 source 和配置间隔。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dtest=GroupPullMarketingFinalizerDbTest,MarketingNewGroupImmediateSendServiceImplTest,MarketingRoundWorkerTest test`

Expected: FAIL。

- [ ] **Step 3: 实现幂等结果结算**

Finalizer 使用 `execution_status IN (1,2)` 条件更新终态，只有首次影响 1 行才变更料子和统计。失败依据是阶段 3 以后关键动作耗尽或最终料子进群数不足。账号转组在收口时重查目标组；目标组不存在、等于当前组或被营销锁占用时不迁移，只追加原因。无论迁移成功与否都按任务 ID+建群账号 ID 删除 `marketing_account_occupancy`，删除成功或确认本任务已不持有该占用后才写 `released_at`；不得按账号 ID 误删其他任务的新占用。

- [ ] **Step 4: 幂等创建固定营销目标并首发**

成功事务插入 `target_scope=GROUP_FIXED` 的 `marketing_task_target`，现有唯一键作为重复闸门，并回写 `marketing_target_id`。接口增加：

```java
void enqueueFixedTarget(Long marketingTaskId, Long targetId, long detectedAt);
```

该方法只对 `business_type=2/status=2`、营销账号当前状态正常且在线的固定目标插入 `round_no=0` attempt 并立即写现有 outbox；账号离线或封禁时只保留 target，任务暂停或终态时同样不首发。无论任务当前为执行中还是暂停，第一个成功目标都执行条件更新；暂停时把 `next_round_at` 置为当前时间，恢复后正常轮次会立即命中；执行中但账号暂不可发送时沿用下方正常轮次间隔：

```sql
UPDATE marketing_task
SET next_round_at = CASE
        WHEN status = 5 THEN #{now}
        ELSE #{now} + send_interval_seconds * 1000
    END,
    updated_at = #{now}
WHERE id = #{taskId}
  AND business_type = 2
  AND status IN (2,5)
  AND next_round_at IS NULL;
```

- [ ] **Step 5: 让正常轮次和一次重试识别拉群类型**

普通营销继续使用账号级 occupancy；拉群营销验证营销分组锁仍由本任务持有后，把当前 target 账号视为任务合法账号，不向 `marketing_account_occupancy` 写拉群账号。每轮开始时，拉群类型使用批量查询同时取得 target 营销账号当前账号状态/登录状态，以及 `group_pull_marketing_execution.group_status=2` 对应的 `marketing_target_id`：账号离线的 target 本轮不创建 attempt/outbox，后续轮次重新判断；账号封禁或群封禁的 target 停止发送。不得逐 target 查询，也不得仅更新明细状态后继续发送。拉群任务同轮群命令 `notBeforeAt` 不递增；`MarketingMessageCommandFactory.accountGroupSendIntervalMs` 对 `business_type=2` 必须直接返回 0，不得被现有“小于 1 时兜底 500ms”的普通营销逻辑覆盖。

`MarketingMessageCommandFactory` 根据 `business_type` 设置内部 source：普通营销继续为 `marketing_task`，拉群营销固定为 `group_pull_marketing`。该 source 触发协议计划 Task 9 的 Web/Android 发送前群状态与发言权限闸门；`MarketingSendResultServiceImpl.supports` 同步接受该 source，仍走同一 attempt/target 回调链路，不另建结果处理器。

即时重试允许 `business_type=2 + GROUP_FIXED`，保留现有 `round_no=0/attempt_no=1→2` 幂等条件，并在重试前重新确认营销账号正常在线；但事件的 `reasonCode/groupStatus/groupStatusReason` 已明确为 `GROUP_BANNED/BANNED/CHAT_SUSPENDED/CHAT_TERMINATED` 时直接返回不重试，让结果处理器把首次 attempt 收口并更新群状态。

- [ ] **Step 6: 同步群封禁状态**

`MarketingSendResultServiceImpl` 在 attempt 首次成功/失败回写后，如果结果明确为 `GROUP_BANNED/BANNED/CHAT_SUSPENDED/CHAT_TERMINATED`，按 `marketing_target_id` 条件把执行 `group_status` 从 1 更新为 2；重复回调不重复累计发送数。营销账号封禁不修改群状态。该状态同时作为 Step 5 后续轮次的发送闸门。

- [ ] **Step 7: 运行测试确认通过**

Run: `mvn -Dtest=GroupPullMarketingFinalizerDbTest,MarketingMessageCommandFactoryTest,MarketingNewGroupImmediateSendServiceImplTest,MarketingImmediateRetryServiceTest,MarketingRoundWorkerTest,MarketingSendResultServiceImplTest test`

Expected: PASS。

- [ ] **Step 8: 提交**

```bash
# 按本 Task 的 Files 清单逐文件暂存；现有营销文件使用 git add -p，禁止暂存整个 marketing 目录。
git diff --cached --name-only
git commit -m "feat: connect group pull tasks to marketing rounds"
```

`git diff --cached --name-only` 中不得出现本 Task 清单之外的用户改动。

### Task 10: 实现调度、到期安全收口和资源释放完成

**Files:**
- Create: `armada-api/src/main/java/com/armada/marketing/grouppull/scheduler/GroupPullMarketingScheduler.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingReleaseService.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/scheduler/MarketingTaskLifecycleWorker.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundWorker.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/mapper/ProtocolCommandOutboxMapper.java`
- Modify: `armada-api/src/main/resources/mapper/platform/protocol/ProtocolCommandOutboxMapper.xml`
- Test: `armada-api/src/test/java/com/armada/marketing/grouppull/scheduler/GroupPullMarketingSchedulerTest.java`
- Test: `armada-api/src/test/java/com/armada/marketing/scheduler/MarketingTaskLifecycleWorkerTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundWorkerTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/mapper/ProtocolCommandOutboxMapperDbTest.java`

- [ ] **Step 1: 写释放边界失败测试**

覆盖：待启动到期直接 `7/0/1`；执行中到期先 `7/0/3`；暂停时未正式建群的准备记录保持原地且不推进、已正式建群记录继续收口；结束/释放时正式建群执行继续收口、准备中执行取消并归还 reservation；仍为 PENDING 的营销 outbox 被取消且对应 attempt 标记跳过；DEAD outbox 对应 attempt 标记失败；已 LOCKED 或 SENT 的当前消息不强行撤回并等待结果；没有在途执行/发送后条件清组锁并写资源 4；条件解锁未命中时保留资源 3 并记录错误日志。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dtest=GroupPullMarketingSchedulerTest,MarketingTaskLifecycleWorkerTest,ProtocolCommandOutboxMapperDbTest test`

Expected: FAIL。

- [ ] **Step 3: 实现拉群任务调度器**

每秒执行三个有界扫描：

```text
1. 执行中且阻塞原因为无/等待建群账号/等待营销账号/等待料子的任务：按每任务剩余槽位调用 allocator，资源恢复就清等待原因；系统异常和人工处理不分配新执行，分别等待原执行恢复清除或用户释放。
2. 到期 execution：任务执行中且阻塞原因为无或三种资源等待时允许推进准备/正式记录；阻塞原因为系统异常/人工处理，或任务已暂停、已完成、已手动结束时，只选择 `group_name IS NOT NULL` 的正式建群记录继续收口。暂停或异常阻塞的准备记录原地保留，释放中的准备记录交 release service 取消。选中记录提交固定大小线程池，worker 内部再抢 `next_execute_at` 租约。
3. resource_status=释放中：调用 release service 尝试安全收口。
```

线程切换前保存 tenantId，执行时恢复 `TenantContext`，finally 恢复原上下文；`@PreDestroy` 关闭线程池。

- [ ] **Step 4: 分流公共营销到期逻辑**

`MarketingTaskLifecycleWorker` 和 `MarketingRoundWorker.endExpiredTaskIfNeeded` 读取 `business_type`：普通营销沿用直接完成并释放账号/分组；拉群营销只把主状态置 7、阻塞清 0、扩展资源置 3、`next_round_at=NULL`，交释放服务等待在途操作。

- [ ] **Step 5: 实现释放完成判定**

释放事务按顺序：取消 `group_name IS NULL` 的准备记录并归还料子/营销 reserved/建群账号；确认不存在 `group_name IS NOT NULL AND execution_status IN (1,2)`；按本任务 attempt 的 `command_id` 条件取消仍为 `protocol_command_outbox.status=PENDING` 的消息并把对应 attempt 标记为 `SKIPPED`；把已为 `DEAD` 但 attempt 仍为 `SUBMITTED` 的消息标记 `FAILED` 并保留 outbox 错误；对已 `LOCKED/SENT` 且 attempt 仍为 `SUBMITTED` 的消息等待现有结果回调；确认不再存在本任务 `marketing_task_send_attempt.status=SUBMITTED` 后，按执行记录释放所有未释放 builder，并调用 `releaseByTaskId` 清除本任务可能遗留的账号级占用，再以分组 ID+类型2+任务 ID 条件解锁。全部完成写资源 4；数据库异常或归属不一致时保留资源 3 和现有锁并记录错误日志，由技术人员处理。

取消 SQL 必须同时带 tenant、commandId 和 `status=PENDING` 条件，不能取消其它任务或 publisher 已抢占的消息。释放流程不得删除 attempt/outbox 历史。

- [ ] **Step 6: 运行测试确认通过**

Run: `mvn -Dtest=GroupPullMarketingSchedulerTest,MarketingTaskLifecycleWorkerTest,MarketingRoundWorkerTest,ProtocolCommandOutboxMapperDbTest test`

Expected: PASS。

- [ ] **Step 7: 提交**

```bash
# 新增 grouppull 文件可逐文件暂存；现有 scheduler/mapper/test 文件使用 git add -p。
git diff --cached --name-only
git commit -m "feat: schedule and release group pull marketing tasks"
```

`git diff --cached --name-only` 中不得出现本 Task 清单之外的用户改动。

### Task 11: 实现群明细 API 和账号列表占用展示

**Files:**
- Create: `armada-api/src/main/java/com/armada/marketing/grouppull/model/dto/GroupPullMarketingGroupQuery.java`
- Create: `armada-api/src/main/java/com/armada/marketing/grouppull/model/vo/GroupPullMarketingGroupVO.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/controller/GroupPullMarketingTaskController.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/service/impl/GroupPullMarketingTaskServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/account/model/dto/AccountQuery.java`
- Modify: `armada-api/src/main/java/com/armada/account/model/vo/AccountListVoRow.java`
- Modify: `armada-api/src/main/java/com/armada/account/model/vo/AccountListVO.java`
- Modify: `armada-api/src/main/java/com/armada/account/model/vo/AccountGroupVoRow.java`
- Modify: `armada-api/src/main/java/com/armada/account/model/vo/AccountGroupVO.java`
- Create: `armada-api/src/main/java/com/armada/account/model/vo/AccountGroupMarketingOccupancyVO.java`
- Modify: `armada-api/src/main/java/com/armada/account/mapper/AccountMapper.java`
- Modify: `armada-api/src/main/resources/mapper/account/AccountMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/account/mapper/AccountGroupMapper.java`
- Modify: `armada-api/src/main/resources/mapper/account/AccountGroupMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/account/service/impl/AccountServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/account/service/impl/AccountGroupServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/account/controller/AccountGroupController.java`
- Test: `armada-api/src/test/java/com/armada/marketing/grouppull/controller/GroupPullMarketingGroupControllerDbTest.java`
- Test: `armada-api/src/test/java/com/armada/account/mapper/AccountListMapperDbTest.java`
- Test: `armada-api/src/test/java/com/armada/account/service/AccountGroupMarketingLockGuardTest.java`
- Modify: `armada-api/src/test/java/com/armada/account/service/impl/AccountServiceImplTest.java`
- Modify: `armada-api/src/test/java/com/armada/account/service/AccountGroupServiceImplTest.java`

- [ ] **Step 1: 写群明细、账号分页和迁移保护测试**

群明细断言正式失败记录也返回；发送条数从 target 聚合；成员数为空不转 0；权限/退出配置从任务扩展关联。账号列表 SQL shape 断言默认查询只继续 JOIN `account_group`，不出现 `group_pull_marketing_execution/material/account_stat` JOIN；Service 对当前页任务 ID 只调用一次批量状态查询。

账号迁移测试额外覆盖：资源已锁定或释放中的拉群任务，其 `builder_group_id` 允许迁入账号、禁止人工迁出账号；待启动或已释放任务不以分组引用阻止迁出；营销锁定分组仍双向禁止迁移；finalizer 的成功/失败系统转组不走人工迁移保护。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dtest=GroupPullMarketingGroupControllerDbTest,AccountListMapperDbTest,AccountGroupMarketingLockGuardTest test`

Expected: FAIL。

- [ ] **Step 3: 实现群明细分页**

`GET /api/group-pull-marketing-tasks/{id}/groups` 返回确认字段，并以 `execution.id ASC` 稳定分页。只返回该任务 execution；`marketing_target_id` 通过当前页一次批量关联 target/attempt 聚合发送状态、成功条数和最后发送时间。

- [ ] **Step 4: 扩展账号列表的轻量投影与筛选**

默认 `AccountMapper.selectPage` 在现有 `LEFT JOIN account_group g` 中只多读：

```sql
g.marketing_occupancy_type AS marketingOccupancyType,
g.marketing_occupancy_task_id AS marketingOccupancyTaskId,
g.marketing_locked_at AS marketingLockedAt
```

分页后 Service 收集去重 taskId，单条批量查询 `marketing_task` 及拉群扩展 resource_status，派生最终展示类型：空闲、单纯营销、拉群营销、暂停占用、待释放等。不得逐账号查任务。

`AccountListVoRow.marketingOccupancyType` 承接数据库中的持久化数字类型；Service 映射后的 `AccountListVO.marketingOccupancyType` 返回前端展示 key：`FREE`、`SIMPLE_MARKETING`、`GROUP_PULL_MARKETING`、`GROUP_PULL_MODE_2`、`GROUP_PULL_MODE_3`、`OTHER_MARKETING`、`PAUSED`、`RELEASING`。同时返回 `marketingOccupancyTaskId: Long` 和 `marketingLockedAt: Long`；前端负责把 epoch 毫秒格式化为展示时间。不要在同一个 VO 字段中混用数字锁类型和字符串展示类型。

现有账号分组列表 VO 同步返回 `marketingOccupancyType`、`marketingOccupancyTaskId`、`marketingLockedAt` 三个持久化字段，供创建页校验成功/失败转入分组；只返回锁事实，不在分组列表逐行补查任务名称或统计。当前被占用的营销分组仍允许保存待启动任务，真正互斥只在启动抢锁时判断。

高级筛选增加 `marketingOccupancyType`、`occupiedTaskKeyword`、`occupiedBusinessType`、`callable`。`FREE` 直接匹配锁任务 ID 为空；基础营销类型先按持久化 occupancy type 缩小分组集合，再排除任务状态派生为 `PAUSED/RELEASING` 的分组；`PAUSED/RELEASING` 则按锁中的任务类型和任务 ID 批量解析各业务任务状态，得到匹配 groupId。任务 ID、名称或业务类型筛选也先解析匹配 groupId，最终都通过 `account.account_group_id` 过滤，不把任务表 JOIN 到账号分页 SQL。可调用用账号状态/在线状态、组锁为空和 `NOT EXISTS marketing_account_occupancy`，不 JOIN 五张拉群表。

- [ ] **Step 5: 实现点击分组的占用详情接口**

新增 `GET /api/account-groups/{id}/marketing-occupancy`，只在点击时返回 `groupId`、`occupancyType`、`taskBusinessType`、`taskId`、`taskName`、`taskStatus`、`resourceStatus`、`lockedAt`、`marketingAccountTotalCount` 和 `marketingAccountUsedCount`。任务不存在但锁仍在时保留锁定字段并返回锁归属异常信息，不把分组伪装为空闲。

- [ ] **Step 6: 给人工迁移及营销分组结构操作加保护**

人工批量迁移在同一事务中读取全部账号当前来源组，按分组 ID 升序 `SELECT ... FOR UPDATE` 锁住涉及的来源组和目标组，再校验并使用带条件的批量更新保证全有或全无。这样迁移与任务启动的分组条件抢锁串行：迁移先提交时启动复核迁移后的账号集合，启动先提交时迁移看到锁后拒绝。

- 来源是营销锁定分组时禁止迁出，目标是营销锁定分组时禁止迁入；
- 来源分组被任一 `resource_status IN (2,3)` 的拉群任务作为 `builder_group_id` 使用时，禁止人工迁出，但仍允许其他账号迁入该建群分组；
- 待启动的资源未锁定任务和资源已释放任务不阻止建群分组迁出；
- 条件更新行数不等于请求中的有效账号数时整体回滚，避免校验后任务并发启动造成部分迁移；
- 系统成功/失败结果迁移走 finalizer 的内部方法并按第 7.3 节容错，不调用人工迁移入口。

当前代码实际已经存在 split/merge 后端入口，即使页面未展示也必须保护。营销锁定组禁止删除、split、merge；名称和备注修改仍允许。建群账号分组不做整组锁，本期不把营销分组的删除、拆分、合并限制扩展到建群分组。

- [ ] **Step 7: 运行测试确认通过**

Run: `mvn -Dtest=GroupPullMarketingGroupControllerDbTest,AccountListMapperDbTest,AccountServiceImplTest,AccountGroupServiceImplTest,AccountGroupMarketingLockGuardTest test`

Expected: PASS，账号默认分页无额外业务表 JOIN。

- [ ] **Step 8: 提交**

```bash
# 按本 Task 的 Files 清单逐文件暂存；现有 account 文件使用 git add -p，禁止暂存整个 account 目录。
git diff --cached --name-only
git commit -m "feat: expose group pull details and occupancy"
```

`git diff --cached --name-only` 中不得出现本 Task 清单之外的用户改动。

### Task 12: 完整回归、租户隔离和故障恢复验证

**Files:**
- Create: `armada-api/src/test/java/com/armada/marketing/grouppull/GroupPullMarketingTenantIsolationDbTest.java`
- Create: `armada-api/src/test/java/com/armada/marketing/grouppull/GroupPullMarketingRecoveryDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/config/ProtocolConfigurationTest.java`

- [ ] **Step 1: 写跨租户与恢复测试**

覆盖：同 taskId 不可跨租户读；跨租户 scheduler 恢复正确 TenantContext；租约过期可接手；创建成功重复恢复不建第二个群；营销 quota、料子状态、建群结果和发送回调重复执行不重复累计；暂停不新分配但正式群可收口；等待资源时已有群继续营销。

- [ ] **Step 2: 运行拉群营销定向测试**

Run: `mvn -Dtest='com.armada.marketing.grouppull.**,MarketingRoundWorkerTest,MarketingImmediateRetryServiceTest,MarketingSendResultServiceImplTest,ProtocolConfigurationTest' test`

Expected: PASS。

- [ ] **Step 3: 运行完整后端测试**

Run: `mvn test`

Expected: BUILD SUCCESS；无失败、无错误。

- [ ] **Step 4: 检查迁移与 SQL 形状**

Run: `rg -n "JOIN group_pull_marketing_(execution|material|account_stat)" armada-api/src/main/resources/mapper/account/AccountMapper.xml`

Expected: 无输出。

Run: `rg -n "business_type = 1" armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`

Expected: 普通列表和普通生命周期 SQL 均命中；公共轮次扫描不被错误限制为类型 1。

- [ ] **Step 5: 提交**

```bash
git add armada-api/src/test/java/com/armada/marketing/grouppull armada-api/src/test/java/com/armada/platform/protocol/config/ProtocolConfigurationTest.java
git commit -m "test: cover group pull recovery and isolation"
```

## 后端验收口径

- 保存只产生“待启动 + 未锁定”，且没有 execution。
- 启动才用 SQL 条件更新抢营销分组；暂停保持锁；释放/到期安全收口后条件解锁。
- 同任务最多 5 个不同建群账号并行；单群阶段串行；协议调用期间没有数据库长事务。
- 只有创建群组使用严格 Redis operationId 幂等；其他动作按目标状态重复安全。
- 料子不补位，进群不足即失败；成功/失败/完成数据统计全部来自明细状态聚合。
- 拉群成功群直接使用同一 `marketing_task` 的固定 target、round、attempt 和 outbox，不创建隐藏子任务。
- 普通营销列表不会出现拉群任务；普通营销现有生命周期、模板和发送行为不回归。
- 账号默认分页不新增业务表 JOIN，不出现 N+1。
