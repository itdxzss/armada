# 普通群链接拉群任务 · 数据层实施计划（M1）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为普通群链接拉群任务落地 M1 最小真实闭环所需的持久化层——Flyway `V090` 迁移（6 张新表 + `pull_task` 加 3 列）、实体、租户隔离 Mapper 及其测试。

**Architecture:** 复用 `pull_task` 作为任务主表，新增 `status='DRAFT'` 承载创建页预览计划（ADR-0007）。执行行 `pull_task_group_execution` 是"群链接 ↔ TXT"的一对一冻结配对，向下挂料子成员、角色账号、账号动作和批量拉人调用。拉手跨任务互斥与群链接跨任务占用都由**生成列 + 部分唯一索引**在数据库层保证（ADR-0008），不依赖应用层锁。本计划只做数据层，不含 Service、Controller、调度器和协议编排。

**Tech Stack:** Java 17 · Spring Boot 3.3.5 · MyBatis-Plus（`TenantLineInnerInterceptor` 自动注入 `tenant_id`）· Flyway · MySQL 8（`utf8mb4_0900_ai_ci`）· 测试用 H2 `MODE=MySQL` + JUnit 5 + AssertJ

## Global Constraints

以下约束适用于本计划的每一个任务，来源为 `AGENTS.md`、`.harness/rules/编码规范.md`、`.harness/rules/数据模型规范.md` 和设计规格 §3。

- **包根** `com.armada`，业务域 `task`。实体放 `com/armada/task/model/entity/`，枚举放 `com/armada/task/model/enums/`，Mapper 接口放 `com/armada/task/mapper/`，Mapper XML 放 `src/main/resources/mapper/task/`。
- **JDK 17 语法**，缩进 4 空格，禁止 tab。
- **实体是纯 POJO**：无 MyBatis-Plus 注解，字段 + getter/setter，每个字段带 Javadoc。参照 `com/armada/task/model/entity/PullTaskGroupMarketingGroupOccupancy.java`。
- **Mapper 接口方法必须有 Javadoc**，说明业务含义、`@param`、`@return`。
- **INSERT 语句的列清单里不写 `tenant_id`**，由 `TenantLineInnerInterceptor` 注入。参照 `src/main/resources/mapper/task/PullTaskGroupMarketingGroupOccupancyMapper.xml` 的 `insertWaiting`。
- **需要跨租户扫描的方法**加 `@InterceptorIgnore(tenantLine = "true")`（`com.baomidou.mybatisplus.annotation.InterceptorIgnore`），参照 `com/armada/platform/protocol/mapper/ProtocolCommandOutboxMapper.java:41`。
- **枚举列一律 `TINYINT`**，DDL 里逐值 `COMMENT`，Java 侧映射 enum 常量，禁魔法值。
- **所有列必须带中文 `COMMENT`**——`.harness/wiki/数据模型.md` 的自动生成靠它。
- **时间列一律 `BIGINT` epoch 毫秒**，命名 `xxx_at`，禁 `create_time`/`update_time`。
- **唯一键或需精确匹配的字符串列必须 `CHARACTER SET ascii COLLATE ascii_bin`**：`normalized_link`、`invite_code`、`group_jid`、`normalized_phone`、`account_phone`、`command_id`、`idempotency_key`、`wa_jid`、`lock_owner`。表默认 `utf8mb4_0900_ai_ci` 大小写不敏感，漏声明会把仅大小写不同的邀请码判为重复。
- **生成列的 else 分支必须是 `NULL`**：`CASE WHEN <有效条件> THEN <值> ELSE NULL END`。写成 `0` 会让唯一索引把已释放记录也纳入约束。
- **建表尾缀统一**：`ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='<中文表注释>'`。
- **不建外键**，引用完整性由 Service 保证。
- **测试门禁**（规格 §10）：H2 `MODE=MySQL` 覆盖生成列、部分唯一索引、租户隔离和 Mapper XML 真实执行；排序规则 H2 不支持，改为对 `V090` 脚本做结构断言；真库 DbTest 只作可选补充，**执行前必须确认目标环境**，不是本地完成门禁。
- **每个任务结束时提交**，commit message 用 `<type>: <描述>` 格式。

### 已知的规范例外（需在评审时确认）

`pull_task_group_execution` 有 33 列，超过 `.harness/rules/数据模型规范.md` 第三条的 ~30 列经验阈值。不拆的理由：这张表就是"一条群链接 ↔ 一个 TXT"这一个聚合，TXT 元数据与群链接是严格 1:1 且同生共死，拆成两张表会制造一个永远只能 JOIN 使用的空壳表。**实施时保留此结构，并在 PR 描述里写明该例外及理由。**

---

## 文件结构

**新建（迁移与回滚）**
- `armada-api/src/main/resources/db/migration/V090__pull_task_normal_link_execution.sql` — 6 张新表 + `pull_task` 3 列
- `.harness/changes/pull-task-normal-link/db-migrations.sql` — 迁移副本
- `.harness/changes/pull-task-normal-link/rollback.sql` — 逆序回滚

**新建（枚举，`com/armada/task/model/enums/`）**
- `PullTaskExecutionStatus.java`、`PullTaskExecutionStage.java`、`PullTaskWaitResourceType.java`
- `PullTaskMaterialPullStatus.java`、`PullTaskMaterialAdminStatus.java`
- `PullTaskGroupAccountRole.java`、`PullTaskGroupAccountMembershipStatus.java`、`PullTaskGroupAccountAdminStatus.java`、`PullTaskGroupAccountAvailability.java`
- `PullTaskAccountActionType.java`、`PullTaskActionStatus.java`
- `PullTaskPullCallStatus.java`

**新建（实体，`com/armada/task/model/entity/`）**
- `PullTaskStandardSetting.java`、`PullTaskGroupExecution.java`、`PullTaskMaterialMember.java`、`PullTaskGroupAccount.java`、`PullTaskAccountAction.java`、`PullTaskPullCall.java`

**新建（Mapper 接口 + XML）**
- `com/armada/task/mapper/PullTaskStandardSettingMapper.java` + `mapper/task/PullTaskStandardSettingMapper.xml`
- `PullTaskGroupExecutionMapper` / `PullTaskMaterialMemberMapper` / `PullTaskGroupAccountMapper` / `PullTaskAccountActionMapper` / `PullTaskPullCallMapper` 同构

**修改**
- `com/armada/task/model/entity/PullTask.java` — 加 `startedAt` / `finishedAt` / `version`
- `com/armada/task/mapper/PullTaskMapper.java` + `mapper/task/PullTaskMapper.xml` — 加生命周期乐观锁方法

**新建（测试，`src/test/java/com/armada/task/`）**
- `PullTaskNormalLinkMigrationSqlTest.java` — 迁移脚本结构断言（排序规则门禁）
- `mapper/PullTaskNormalLinkSchema.java` — H2 建表 DDL 共享常量
- `mapper/PullTaskNormalLinkH2Support.java` — H2 DataSource / SqlSessionFactory 工厂方法
- `mapper/PullTaskLifecycleMapperInMemoryTest.java`
- `mapper/PullTaskGroupExecutionMapperInMemoryTest.java`
- `mapper/PullTaskMaterialMemberMapperInMemoryTest.java`
- `mapper/PullTaskGroupAccountMapperInMemoryTest.java`
- `mapper/PullTaskAccountActionMapperInMemoryTest.java`
- `mapper/PullTaskPullCallMapperInMemoryTest.java`
- `mapper/PullTaskStandardSettingMapperInMemoryTest.java`

---

## 任务清单

计划共 10 个任务。Task 1 建 schema，Task 2 建 H2 测试基座，两者是所有后续任务的前置。Task 4–9 六张表之间没有依赖，可并行执行。

| # | 交付物 | 依赖 |
|---|---|---|
| 1 | `V090` 迁移 + 脚本结构测试 + 回滚脚本 | — |
| 2 | H2 测试基座：共享 DDL 常量 + 工厂方法 | 1 |
| 3 | `pull_task` 3 列 + DRAFT 可见性 + 生命周期乐观锁 Mapper | 1 |
| 4 | `pull_task_standard_setting` 实体 + Mapper | 2 |
| 5 | `pull_task_group_execution` 实体 + Mapper（含链接占用） | 2 |
| 6 | `pull_task_material_member` 实体 + Mapper | 2 |
| 7 | `pull_task_group_account` 实体 + Mapper（含拉手互斥） | 2 |
| 8 | `pull_task_account_action` 实体 + Mapper | 2 |
| 9 | `pull_task_pull_call` 实体 + Mapper | 2 |
| 10 | 数据模型文档重跑 + 可选真库 DbTest + change 记录 | 3–9 |

各任务的完整步骤在下方分节展开。

---

## Task 1: Flyway `V090` 迁移与脚本结构测试

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V090__pull_task_normal_link_execution.sql`
- Create: `armada-api/src/test/java/com/armada/task/PullTaskNormalLinkMigrationSqlTest.java`
- Create: `.harness/changes/pull-task-normal-link/db-migrations.sql`
- Create: `.harness/changes/pull-task-normal-link/rollback.sql`

**Interfaces:**
- Consumes: 无（首个任务）
- Produces: 6 张表和 `pull_task` 的 3 个新列。后续任务的实体字段名 = 这里的列名去下划线转驼峰（`map-underscore-to-camel-case: true`）。表名与列名以本任务的 DDL 为唯一事实源。

**先决检查：** `V089__pull_task_group_marketing_group_occupancy.sql` 是当前最高版本，`V090` 未被占用。动手前执行 `ls armada-api/src/main/resources/db/migration/ | tail -3` 确认，若已有他人提交的 `V090` 则顺延并同步更新本计划所有引用。

- [ ] **Step 1: 写失败的迁移脚本结构测试**

创建 `armada-api/src/test/java/com/armada/task/PullTaskNormalLinkMigrationSqlTest.java`。这个测试是**排序规则的唯一门禁**——H2 不支持列级 `COLLATE`，所以只能断言脚本文本。

```java
package com.armada.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 普通群链接执行域 Flyway 脚本契约测试。
 *
 * <p>列级排序规则和 MySQL 专有的生成列写法无法在 H2 MySQL 模式下验证
 * （H2 不支持列级 CHARACTER SET / COLLATE，且默认大小写敏感，会让
 * utf8mb4_0900_ai_ci 造成的"仅大小写不同即判重复"问题静默通过），
 * 因此改为对脚本文本做结构断言。</p>
 */
class PullTaskNormalLinkMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V090__pull_task_normal_link_execution.sql");

    private String sql() throws IOException {
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }

    @Test
    void migrationCreatesSixExecutionTables() throws IOException {
        assertThat(sql())
                .contains("CREATE TABLE IF NOT EXISTS pull_task_standard_setting")
                .contains("CREATE TABLE IF NOT EXISTS pull_task_group_execution")
                .contains("CREATE TABLE IF NOT EXISTS pull_task_material_member")
                .contains("CREATE TABLE IF NOT EXISTS pull_task_group_account")
                .contains("CREATE TABLE IF NOT EXISTS pull_task_account_action")
                .contains("CREATE TABLE IF NOT EXISTS pull_task_pull_call");
    }

    @Test
    void migrationAddsPullTaskLifecycleColumnsIdempotently() throws IOException {
        String sql = sql();
        assertThat(sql)
                .contains("information_schema.columns")
                .contains("column_name = 'started_at'")
                .contains("ADD COLUMN started_at BIGINT DEFAULT NULL")
                .contains("column_name = 'finished_at'")
                .contains("ADD COLUMN finished_at BIGINT DEFAULT NULL")
                .contains("column_name = 'version'")
                .contains("ADD COLUMN version INT NOT NULL DEFAULT 1");
    }

    @Test
    void exactMatchColumnsDeclareAsciiBinCollation() throws IOException {
        String sql = sql();
        // 表默认 utf8mb4_0900_ai_ci 大小写不敏感；WhatsApp 邀请码大小写敏感，
        // 漏声明会让仅大小写不同的两条链接被唯一键判为重复。
        assertThat(sql)
                .contains("normalized_link VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin")
                .contains("invite_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
                .contains("group_jid VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin")
                .contains("normalized_phone VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin")
                .contains("account_phone VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin")
                .contains("command_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
                .contains("idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
                .contains("wa_jid VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin");
    }

    @Test
    void generatedColumnsUseNullElseBranch() throws IOException {
        String sql = sql();
        // else 分支写成 0 会让唯一索引把已释放记录也纳入约束，
        // 导致一个账号一生只能有一条释放记录。
        assertThat(sql)
                .contains("CASE WHEN execution_status IN (1, 2, 3) THEN normalized_link ELSE NULL END")
                .contains("CASE WHEN role_type = 2 AND released_at IS NULL THEN account_id ELSE NULL END");
        assertThat(sql).doesNotContain("ELSE 0 END");
    }

    @Test
    void schedulerIndexHasNoTenantPrefix() throws IOException {
        // 后台调度器无租户上下文（MyBatisConfig fail-closed 回退 -1），
        // 必须有一条不以 tenant_id 打头的索引供跨租户扫描。
        assertThat(sql())
                .contains("KEY idx_pull_task_execution_dispatch "
                        + "(execution_status, manual_paused, next_run_at, id)");
    }

    @Test
    void callbackLookupIndexesExist() throws IOException {
        assertThat(sql())
                .contains("UNIQUE KEY uq_pull_task_action_command (tenant_id, command_id)")
                .contains("UNIQUE KEY uq_pull_task_call_command (tenant_id, command_id)")
                .contains("KEY idx_pull_task_material_admin_command (tenant_id, admin_command_id)");
    }

    @Test
    void occupancyUniqueKeysExist() throws IOException {
        assertThat(sql())
                .contains("UNIQUE KEY uq_pull_task_execution_link_occupancy "
                        + "(tenant_id, link_occupancy_key)")
                .contains("UNIQUE KEY uq_pull_task_group_account_occupancy "
                        + "(tenant_id, occupancy_key)");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd armada-api && mvn -q -Dtest='PullTaskNormalLinkMigrationSqlTest' -DfailIfNoTests=false test
```

Expected: FAIL — `NoSuchFileException: src/main/resources/db/migration/V090__pull_task_normal_link_execution.sql`

- [ ] **Step 3: 写迁移脚本**

创建 `armada-api/src/main/resources/db/migration/V090__pull_task_normal_link_execution.sql`。

注意 `.harness/rules/编码规范.md` 的 SQL 注释规范：独立的 `--` 注释后必须有空格。

```sql
-- 普通群链接拉群任务执行域:6 张新表 + pull_task 3 个生命周期列。
-- 不修改拉群营销表,不迁移历史营销任务。设计见
-- docs/superpowers/specs/2026-08-02-pull-task-normal-link-data-model-design.md。

-- pull_task 增加生命周期列;ADD COLUMN 用 information_schema 守卫保证幂等。
SET @pull_task_started_at_sql := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task'
       AND column_name = 'started_at') = 0,
    'ALTER TABLE pull_task ADD COLUMN started_at BIGINT DEFAULT NULL COMMENT ''首次真实启动时间(epoch毫秒)'' AFTER status',
    'SELECT 1'
);
PREPARE pull_task_started_at_stmt FROM @pull_task_started_at_sql;
EXECUTE pull_task_started_at_stmt;
DEALLOCATE PREPARE pull_task_started_at_stmt;

SET @pull_task_finished_at_sql := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task'
       AND column_name = 'finished_at') = 0,
    'ALTER TABLE pull_task ADD COLUMN finished_at BIGINT DEFAULT NULL COMMENT ''进入COMPLETED或ENDED的时间(epoch毫秒)'' AFTER started_at',
    'SELECT 1'
);
PREPARE pull_task_finished_at_stmt FROM @pull_task_finished_at_sql;
EXECUTE pull_task_finished_at_stmt;
DEALLOCATE PREPARE pull_task_finished_at_stmt;

SET @pull_task_version_sql := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task'
       AND column_name = 'version') = 0,
    'ALTER TABLE pull_task ADD COLUMN version INT NOT NULL DEFAULT 1 COMMENT ''生命周期更新乐观锁版本号''',
    'SELECT 1'
);
PREPARE pull_task_version_stmt FROM @pull_task_version_sql;
EXECUTE pull_task_version_stmt;
DEALLOCATE PREPARE pull_task_version_stmt;

-- 普通群链接任务冻结执行配置;一条任务一行。
CREATE TABLE IF NOT EXISTS pull_task_standard_setting (
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    task_id BIGINT NOT NULL COMMENT '拉群任务ID(→pull_task.id)',
    auto_start TINYINT(1) NOT NULL DEFAULT 0 COMMENT '创建后是否自动启动:0否 1是',
    material_admin_timing TINYINT NOT NULL COMMENT '料子内管理员设置时点:1=成员入群后立即 2=本群料子全部终态后',
    pull_count_min INT NOT NULL COMMENT '单次拉人料子人数下限(闭区间,不含站台)',
    pull_count_max INT NOT NULL COMMENT '单次拉人料子人数上限(闭区间,不含站台)',
    pull_interval_seconds INT NOT NULL COMMENT '同一拉手账号连续拉人调用的最小间隔(秒)',
    puller_count_per_group INT NOT NULL COMMENT '每条执行行的计划拉手数',
    station_count_per_call INT NOT NULL COMMENT '每一次拉人调用叠加的站台数',
    concurrent_group_count INT NOT NULL COMMENT '同一父任务最大同时运行执行行数',
    puller_risk_minutes INT NOT NULL DEFAULT 0 COMMENT '拉手风控冷却分钟;0=不建立定时恢复',
    required_manager_count INT NOT NULL DEFAULT 0 COMMENT '任务启动时按管理分组可用账号数冻结的要求管理员人数N',
    manager_group_id BIGINT NOT NULL COMMENT '管理账号分组ID(→account_group.id)',
    puller_group_id BIGINT NOT NULL COMMENT '拉手账号分组ID(→account_group.id)',
    station_group_id BIGINT NOT NULL COMMENT '站台账号分组ID(→account_group.id)',
    manager_group_name VARCHAR(100) NOT NULL COMMENT '管理分组名称快照',
    puller_group_name VARCHAR(100) NOT NULL COMMENT '拉手分组名称快照',
    station_group_name VARCHAR(100) NOT NULL COMMENT '站台分组名称快照',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (tenant_id, task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='普通群链接任务冻结执行配置';

-- 群链接与TXT的一对一冻结配对;一行就是一条可独立调度的执行行。
CREATE TABLE IF NOT EXISTS pull_task_group_execution (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '执行行主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    task_id BIGINT NOT NULL COMMENT '拉群任务ID(→pull_task.id);草稿期也非空',
    seq INT NOT NULL COMMENT '任务内展示与执行顺序',
    group_link_id BIGINT DEFAULT NULL COMMENT '群入口ID(→group_link.id);最终创建时回填',
    normalized_link VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '归一化群链接chat.whatsapp.com/<邀请码>',
    invite_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '群邀请码;大小写敏感',
    source_link_line_no INT NOT NULL COMMENT '粘贴内容中的原始行号',
    group_jid VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT 'WhatsApp群JID;启动校验时回填',
    source_file_index INT NOT NULL COMMENT '上传TXT的序号',
    source_file_name VARCHAR(255) NOT NULL COMMENT 'TXT原始文件名',
    total_line_count INT NOT NULL DEFAULT 0 COMMENT 'TXT总行数',
    valid_member_count INT NOT NULL DEFAULT 0 COMMENT '去重后有效料子数',
    invalid_line_count INT NOT NULL DEFAULT 0 COMMENT '非法号码行数',
    duplicate_line_count INT NOT NULL DEFAULT 0 COMMENT '文件内重复号码行数',
    execution_status TINYINT NOT NULL DEFAULT 0 COMMENT '执行状态:0=草稿 1=待启动 2=执行中 3=等待资源 4=已完成 5=失败终态 6=已放弃',
    stage TINYINT NOT NULL DEFAULT 1 COMMENT '业务阶段:1=链接校验 2=管理入群 3=管理拉手联系人 4=管理邀请拉手 5=拉人执行 6=料子提权 7=收口',
    manual_paused TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否人工暂停:0否 1是;与资源等待独立',
    wait_resource_type TINYINT DEFAULT NULL COMMENT '资源等待类型:1=管理员 2=拉手 3=站台;NULL=非资源等待',
    reason_code VARCHAR(64) DEFAULT NULL COMMENT '当前状态原因码',
    reason_message VARCHAR(255) DEFAULT NULL COMMENT '当前状态原因描述(已脱敏)',
    next_manager_index INT NOT NULL DEFAULT 0 COMMENT '管理账号轮询游标',
    next_puller_index INT NOT NULL DEFAULT 0 COMMENT '拉手轮询游标',
    next_run_at BIGINT NOT NULL DEFAULT 0 COMMENT '下次可调度时间(epoch毫秒);0=立即可调度',
    lock_owner VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '抢占调度的实例标识',
    lock_expires_at BIGINT DEFAULT NULL COMMENT '调度锁过期时间(epoch毫秒);过期可被抢占回收',
    version INT NOT NULL DEFAULT 1 COMMENT '执行行更新乐观锁版本号',
    started_at BIGINT DEFAULT NULL COMMENT '本行首次启动时间(epoch毫秒)',
    finished_at BIGINT DEFAULT NULL COMMENT '本行进入终态时间(epoch毫秒)',
    last_business_executed_at BIGINT DEFAULT NULL COMMENT '最近一次真实业务动作时间(epoch毫秒)',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    link_occupancy_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (
            CASE WHEN execution_status IN (1, 2, 3) THEN normalized_link ELSE NULL END
        ) STORED COMMENT '群链接跨任务占用唯一键辅助列;草稿与终态为NULL不占用',
    PRIMARY KEY (id),
    UNIQUE KEY uq_pull_task_execution_seq (tenant_id, task_id, seq),
    UNIQUE KEY uq_pull_task_execution_link (tenant_id, task_id, normalized_link),
    UNIQUE KEY uq_pull_task_execution_file (tenant_id, task_id, source_file_index),
    UNIQUE KEY uq_pull_task_execution_link_occupancy (tenant_id, link_occupancy_key),
    KEY idx_pull_task_execution_page (tenant_id, task_id, id),
    KEY idx_pull_task_execution_dispatch (execution_status, manual_paused, next_run_at, id),
    KEY idx_pull_task_execution_group (tenant_id, group_link_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='普通群链接执行行(群链接与TXT一对一冻结配对)';

-- TXT有效料子号码及其入群、提权结果。
CREATE TABLE IF NOT EXISTS pull_task_material_member (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '料子成员主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    group_execution_id BIGINT NOT NULL COMMENT '所属执行行ID(→pull_task_group_execution.id)',
    member_seq INT NOT NULL COMMENT '文件内去重后稳定顺序',
    source_line_no INT NOT NULL COMMENT '首次有效出现的原始行号',
    normalized_phone VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '归一化号码(7-15位含国家码纯数字)',
    admin_required TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否带A/a需设群管理员标识:0否 1是',
    pull_call_id BIGINT DEFAULT NULL COMMENT '消费本料子的拉人调用ID;NULL=尚未消费',
    pull_status TINYINT NOT NULL DEFAULT 0 COMMENT '入群结果:0=未消费 1=已提交 2=成功 3=失败 4=结果未知 5=取消',
    pull_reason_code VARCHAR(64) DEFAULT NULL COMMENT '入群失败原因码',
    pull_reason_message VARCHAR(255) DEFAULT NULL COMMENT '入群失败原因描述(已脱敏)',
    wa_jid VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '成功入群后的成员JID',
    pull_result_at BIGINT DEFAULT NULL COMMENT '入群结果回写时间(epoch毫秒)',
    admin_status TINYINT NOT NULL DEFAULT 0 COMMENT '提权结果:0=不需要 1=待执行 2=已提交 3=成功 4=失败 5=结果未知 6=取消',
    admin_command_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '提权协议命令ID',
    admin_reason_code VARCHAR(64) DEFAULT NULL COMMENT '提权失败原因码',
    admin_result_at BIGINT DEFAULT NULL COMMENT '提权结果回写时间(epoch毫秒)',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_pull_task_material_seq (tenant_id, group_execution_id, member_seq),
    UNIQUE KEY uq_pull_task_material_phone (tenant_id, group_execution_id, normalized_phone),
    KEY idx_pull_task_material_pending (tenant_id, group_execution_id, pull_status, member_seq),
    KEY idx_pull_task_material_admin (tenant_id, group_execution_id, admin_required, admin_status, id),
    KEY idx_pull_task_material_admin_command (tenant_id, admin_command_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='普通群链接执行行的TXT料子号码与逐号码结果';

-- 管理、拉手、站台在某条执行行中的选择、在群状态与拉手占用。
CREATE TABLE IF NOT EXISTS pull_task_group_account (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '角色账号主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    task_id BIGINT NOT NULL COMMENT '拉群任务ID(→pull_task.id)',
    group_execution_id BIGINT NOT NULL COMMENT '所属执行行ID(→pull_task_group_execution.id)',
    account_id BIGINT NOT NULL COMMENT '账号ID(→account.id)',
    account_phone VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '账号号码展示快照',
    role_type TINYINT NOT NULL COMMENT '角色:1=管理 2=拉手 3=站台',
    role_seq INT NOT NULL COMMENT '同角色内顺序;补充时递增',
    source_type TINYINT NOT NULL DEFAULT 1 COMMENT '来源:1=初始选择 2=人工补充',
    selection_mode TINYINT NOT NULL DEFAULT 1 COMMENT '选号方式:1=自动 2=手动',
    entry_mode TINYINT DEFAULT NULL COMMENT '进群方式:1=踩链接 2=管理员邀请 3=拉手拉入;站台补充为NULL',
    membership_status TINYINT NOT NULL DEFAULT 0 COMMENT '在群状态:0=未入群 1=入群中 2=在群 3=入群失败 4=结果未知',
    joined_at BIGINT DEFAULT NULL COMMENT '确认在群时间(epoch毫秒)',
    pull_call_id BIGINT DEFAULT NULL COMMENT '站台由哪次拉人调用拉入(→pull_task_pull_call.id)',
    admin_status TINYINT NOT NULL DEFAULT 0 COMMENT '群管理员权限:0=不适用 1=待设置 2=已提交 3=成功 4=失败 5=结果未知',
    availability_status TINYINT NOT NULL DEFAULT 1 COMMENT '可用性:1=可用 2=风控冷却 3=离线或不可用 4=已移出本行',
    unavailable_reason_code VARCHAR(64) DEFAULT NULL COMMENT '不可用原因码',
    cooldown_until BIGINT DEFAULT NULL COMMENT '风控冷却到期时间(epoch毫秒)',
    occupied_at BIGINT DEFAULT NULL COMMENT '拉手占用开始时间(epoch毫秒)',
    released_at BIGINT DEFAULT NULL COMMENT '拉手占用释放时间(epoch毫秒);NULL=当前占用中',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    occupancy_key BIGINT
        GENERATED ALWAYS AS (
            CASE WHEN role_type = 2 AND released_at IS NULL THEN account_id ELSE NULL END
        ) STORED COMMENT '拉手跨任务互斥唯一键辅助列;非拉手或已释放为NULL',
    PRIMARY KEY (id),
    UNIQUE KEY uq_pull_task_group_account_occupancy (tenant_id, occupancy_key),
    UNIQUE KEY uq_pull_task_group_account_role (tenant_id, group_execution_id, role_type, account_id),
    UNIQUE KEY uq_pull_task_group_account_seq (tenant_id, group_execution_id, role_type, role_seq),
    KEY idx_pull_task_group_account_pick (tenant_id, group_execution_id, role_type, availability_status, id),
    KEY idx_pull_task_group_account_account (tenant_id, account_id, role_type, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='普通群链接执行行的角色账号、在群状态与拉手占用';

-- 账号之间的真实协议动作:保存联系人、邀请入群、踩链接入群。
CREATE TABLE IF NOT EXISTS pull_task_account_action (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '账号动作主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    task_id BIGINT NOT NULL COMMENT '拉群任务ID(→pull_task.id)',
    group_execution_id BIGINT NOT NULL COMMENT '所属执行行ID(→pull_task_group_execution.id)',
    action_type TINYINT NOT NULL COMMENT '动作类型:1=保存联系人 2=邀请入群 3=踩链接入群',
    actor_group_account_id BIGINT NOT NULL COMMENT '动作发起方角色行ID;踩链接入群时为目标账号自身ID(MySQL唯一索引中NULL互不相等,留空会让幂等键失效)',
    target_group_account_id BIGINT NOT NULL COMMENT '动作对象角色行ID(→pull_task_group_account.id)',
    action_status TINYINT NOT NULL DEFAULT 1 COMMENT '动作结果:1=待执行 2=已提交 3=成功 4=失败 5=结果未知 6=取消',
    command_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '协议命令ID;回调按此定位',
    reason_code VARCHAR(64) DEFAULT NULL COMMENT '失败原因码',
    reason_message VARCHAR(255) DEFAULT NULL COMMENT '失败原因描述(已脱敏)',
    submitted_at BIGINT DEFAULT NULL COMMENT '命令提交时间(epoch毫秒)',
    result_at BIGINT DEFAULT NULL COMMENT '结果回写时间(epoch毫秒)',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_pull_task_action_pair
        (tenant_id, group_execution_id, action_type, actor_group_account_id, target_group_account_id),
    UNIQUE KEY uq_pull_task_action_command (tenant_id, command_id),
    KEY idx_pull_task_action_pending (tenant_id, group_execution_id, action_status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='普通群链接执行行的账号动作(联系人/邀请/踩链接)';

-- 一个拉手对同一群JID的一次真实批量加成员请求。
CREATE TABLE IF NOT EXISTS pull_task_pull_call (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '拉人调用主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    task_id BIGINT NOT NULL COMMENT '拉群任务ID(→pull_task.id)',
    group_execution_id BIGINT NOT NULL COMMENT '所属执行行ID(→pull_task_group_execution.id)',
    call_seq INT NOT NULL COMMENT '本执行行内调用序号',
    puller_group_account_id BIGINT NOT NULL COMMENT '执行本次调用的拉手角色行ID',
    puller_account_id BIGINT NOT NULL COMMENT '执行本次调用的拉手账号ID(→account.id)',
    planned_material_count INT NOT NULL COMMENT '本次计划料子人数(闭区间随机结果)',
    planned_station_count INT NOT NULL COMMENT '本次计划站台数',
    call_status TINYINT NOT NULL DEFAULT 1 COMMENT '调用状态:1=计划 2=已提交 3=已回写 4=结果未知 5=取消',
    command_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '协议命令ID;回调按此定位',
    idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '计划阶段生成的幂等键;崩溃恢复用原键重投',
    reason_code VARCHAR(64) DEFAULT NULL COMMENT '失败原因码',
    reason_message VARCHAR(255) DEFAULT NULL COMMENT '失败原因描述(已脱敏)',
    submitted_at BIGINT DEFAULT NULL COMMENT '命令提交时间(epoch毫秒)',
    result_at BIGINT DEFAULT NULL COMMENT '结果回写时间(epoch毫秒)',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_pull_task_call_seq (tenant_id, group_execution_id, call_seq),
    UNIQUE KEY uq_pull_task_call_idempotency (tenant_id, idempotency_key),
    UNIQUE KEY uq_pull_task_call_command (tenant_id, command_id),
    KEY idx_pull_task_call_puller_time (tenant_id, puller_account_id, submitted_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='普通群链接执行行的单次批量加成员调用';
```

- [ ] **Step 4: 运行测试确认通过**

```bash
cd armada-api && mvn -q -Dtest='PullTaskNormalLinkMigrationSqlTest' -DfailIfNoTests=false test
```

Expected: PASS，7 个测试全绿。

- [ ] **Step 5: 运行既有迁移契约测试确认没撞号**

```bash
cd armada-api && mvn -q -Dtest='FlywayMigration*Test,FlywayAppliedMigrationCompatibilityTest' -DfailIfNoTests=false test
```

Expected: PASS。`FlywayMigrationVersionContractTest` 会校验版本号唯一，`FlywayMigrationSqlContractTest` 会校验 `--` 注释后有空格。如果注释测试失败，检查脚本里所有独立 `--` 行是否都写成 `-- ` 带空格。

- [ ] **Step 6: 写 change 记录的迁移与回滚脚本**

创建 `.harness/changes/pull-task-normal-link/db-migrations.sql`，内容为上面 `V090` 的完整副本（规范要求 change 目录留一份）。

创建 `.harness/changes/pull-task-normal-link/rollback.sql`：

```sql
-- 普通群链接执行域回滚:按依赖逆序删表,再删 pull_task 新增列。
-- 共享库或生产执行前必须单独确认目标环境。

DROP TABLE IF EXISTS pull_task_pull_call;
DROP TABLE IF EXISTS pull_task_account_action;
DROP TABLE IF EXISTS pull_task_group_account;
DROP TABLE IF EXISTS pull_task_material_member;
DROP TABLE IF EXISTS pull_task_group_execution;
DROP TABLE IF EXISTS pull_task_standard_setting;

ALTER TABLE pull_task DROP COLUMN version;
ALTER TABLE pull_task DROP COLUMN finished_at;
ALTER TABLE pull_task DROP COLUMN started_at;

-- 回滚后必须手工删除 flyway_schema_history 中 version='090' 的记录,
-- 否则重新迁移会因 checksum 校验失败导致启动 crash-loop。
DELETE FROM flyway_schema_history WHERE version = '090';
```

- [ ] **Step 7: 提交**

```bash
git add armada-api/src/main/resources/db/migration/V090__pull_task_normal_link_execution.sql \
        armada-api/src/test/java/com/armada/task/PullTaskNormalLinkMigrationSqlTest.java \
        .harness/changes/pull-task-normal-link/
git commit -m "feat: 新增普通群链接执行域 Flyway V090 迁移

6 张新表 + pull_task 3 个生命周期列。唯一键字符串列声明 ascii_bin,
生成列 else 分支为 NULL,调度索引不带租户前缀。排序规则由迁移脚本
结构测试断言(H2 不支持列级 COLLATE)。"
```

---

## Task 2: H2 测试基座（共享 DDL 常量与工厂方法）

**Files:**
- Create: `armada-api/src/test/java/com/armada/task/mapper/PullTaskNormalLinkSchema.java`
- Create: `armada-api/src/test/java/com/armada/task/mapper/PullTaskNormalLinkH2Support.java`

**Interfaces:**
- Consumes: Task 1 的表结构（H2 DDL 是它的等价翻译，去掉 H2 不支持的列级 `CHARACTER SET` / `COLLATE`）
- Produces：Task 3–9 的每个测试类都消费这两个类。
  - `PullTaskNormalLinkSchema.PULL_TASK` / `.STANDARD_SETTING` / `.GROUP_EXECUTION` / `.MATERIAL_MEMBER` / `.GROUP_ACCOUNT` / `.ACCOUNT_ACTION` / `.PULL_CALL` — 均为 `String` 常量，单条 `CREATE TABLE` 语句
  - `PullTaskNormalLinkSchema.all()` → `String[]`，按依赖顺序返回全部 7 条建表语句
  - `PullTaskNormalLinkH2Support.dataSource(String dbName)` → `DataSource`
  - `PullTaskNormalLinkH2Support.sqlSessionFactory(DataSource ds, MybatisPlusInterceptor interceptor, String... mapperXmlPaths)` → `SqlSessionFactory`
  - `PullTaskNormalLinkH2Support.resetSchema(DataSource ds, String... extraStatements)` → `void`，先 `DROP ALL OBJECTS`，再建全部表，最后执行 `extraStatements`

**为什么 H2 DDL 要手工维护：** Flyway 脚本不在 H2 上执行（仓库现状，见规格 §10）。H2 也不支持列级 `CHARACTER SET ascii COLLATE ascii_bin`，所以这里的 DDL 省略排序规则；排序规则由 Task 1 的脚本结构测试保证。**生成列和"NULL 不参与唯一键"的部分唯一索引语义 H2 能复现**，必须原样保留——`PullTaskGroupMarketingGroupMapperInMemoryTest` 已有同款先例。

- [ ] **Step 1: 写 `PullTaskNormalLinkSchema`**

创建 `armada-api/src/test/java/com/armada/task/mapper/PullTaskNormalLinkSchema.java`。

```java
package com.armada.task.mapper;

/**
 * 普通群链接执行域在 H2 MySQL 模式下的建表语句。
 *
 * <p>Flyway 脚本不在 H2 上执行，因此这里手工维护与
 * {@code V090__pull_task_normal_link_execution.sql} 等价的 DDL。两点差异是刻意的：</p>
 * <ul>
 *   <li>省略列级 {@code CHARACTER SET ascii COLLATE ascii_bin}——H2 不支持列级排序规则，
 *       该约束由 {@code PullTaskNormalLinkMigrationSqlTest} 对迁移脚本做结构断言来保证。</li>
 *   <li>原样保留生成列与部分唯一索引——H2 MySQL 模式能正确复现
 *       "生成列为 NULL 时不参与唯一约束"的语义，这是拉手互斥和群链接占用的核心机制。</li>
 * </ul>
 *
 * <p>改动 V090 的列时必须同步改这里，否则 Mapper 测试会以过期结构通过。</p>
 */
final class PullTaskNormalLinkSchema {

    private PullTaskNormalLinkSchema() {
    }

    /** 拉群任务主表；只含 Mapper 测试用得到的列。 */
    static final String PULL_TASK = """
            CREATE TABLE pull_task (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                tenant_id BIGINT NOT NULL,
                task_type VARCHAR(32) NOT NULL,
                group_source VARCHAR(32),
                task_name VARCHAR(128) NOT NULL,
                group_name VARCHAR(128),
                mode VARCHAR(32) NOT NULL,
                status VARCHAR(32) NOT NULL,
                primary_stage VARCHAR(64),
                blocking_reason VARCHAR(255),
                started_at BIGINT,
                finished_at BIGINT,
                version INT NOT NULL DEFAULT 1,
                group_count INT NOT NULL DEFAULT 0,
                expected_pull_count INT NOT NULL DEFAULT 0,
                config_json VARCHAR(4000) NOT NULL,
                operator_name VARCHAR(64),
                created_by BIGINT,
                remark VARCHAR(500),
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                last_business_executed_at BIGINT,
                deleted_at BIGINT
            )
            """;

    /** 普通群链接任务冻结执行配置。 */
    static final String STANDARD_SETTING = """
            CREATE TABLE pull_task_standard_setting (
                tenant_id BIGINT NOT NULL,
                task_id BIGINT NOT NULL,
                auto_start TINYINT NOT NULL DEFAULT 0,
                material_admin_timing TINYINT NOT NULL,
                pull_count_min INT NOT NULL,
                pull_count_max INT NOT NULL,
                pull_interval_seconds INT NOT NULL,
                puller_count_per_group INT NOT NULL,
                station_count_per_call INT NOT NULL,
                concurrent_group_count INT NOT NULL,
                puller_risk_minutes INT NOT NULL DEFAULT 0,
                required_manager_count INT NOT NULL DEFAULT 0,
                manager_group_id BIGINT NOT NULL,
                puller_group_id BIGINT NOT NULL,
                station_group_id BIGINT NOT NULL,
                manager_group_name VARCHAR(100) NOT NULL,
                puller_group_name VARCHAR(100) NOT NULL,
                station_group_name VARCHAR(100) NOT NULL,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                PRIMARY KEY (tenant_id, task_id)
            )
            """;

    /** 群链接与 TXT 一对一冻结配对的执行行；含链接跨任务占用生成列。 */
    static final String GROUP_EXECUTION = """
            CREATE TABLE pull_task_group_execution (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                tenant_id BIGINT NOT NULL,
                task_id BIGINT NOT NULL,
                seq INT NOT NULL,
                group_link_id BIGINT,
                normalized_link VARCHAR(255) NOT NULL,
                invite_code VARCHAR(64) NOT NULL,
                source_link_line_no INT NOT NULL,
                group_jid VARCHAR(128),
                source_file_index INT NOT NULL,
                source_file_name VARCHAR(255) NOT NULL,
                total_line_count INT NOT NULL DEFAULT 0,
                valid_member_count INT NOT NULL DEFAULT 0,
                invalid_line_count INT NOT NULL DEFAULT 0,
                duplicate_line_count INT NOT NULL DEFAULT 0,
                execution_status TINYINT NOT NULL DEFAULT 0,
                stage TINYINT NOT NULL DEFAULT 1,
                manual_paused TINYINT NOT NULL DEFAULT 0,
                wait_resource_type TINYINT,
                reason_code VARCHAR(64),
                reason_message VARCHAR(255),
                next_manager_index INT NOT NULL DEFAULT 0,
                next_puller_index INT NOT NULL DEFAULT 0,
                next_run_at BIGINT NOT NULL DEFAULT 0,
                lock_owner VARCHAR(64),
                lock_expires_at BIGINT,
                version INT NOT NULL DEFAULT 1,
                started_at BIGINT,
                finished_at BIGINT,
                last_business_executed_at BIGINT,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                link_occupancy_key VARCHAR(255) GENERATED ALWAYS AS (
                    CASE WHEN execution_status IN (1, 2, 3) THEN normalized_link ELSE NULL END
                ),
                CONSTRAINT uq_pull_task_execution_seq UNIQUE (tenant_id, task_id, seq),
                CONSTRAINT uq_pull_task_execution_link UNIQUE (tenant_id, task_id, normalized_link),
                CONSTRAINT uq_pull_task_execution_file UNIQUE (tenant_id, task_id, source_file_index),
                CONSTRAINT uq_pull_task_execution_link_occupancy
                    UNIQUE (tenant_id, link_occupancy_key)
            )
            """;

    /** TXT 料子号码与逐号码入群、提权结果。 */
    static final String MATERIAL_MEMBER = """
            CREATE TABLE pull_task_material_member (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                tenant_id BIGINT NOT NULL,
                group_execution_id BIGINT NOT NULL,
                member_seq INT NOT NULL,
                source_line_no INT NOT NULL,
                normalized_phone VARCHAR(32) NOT NULL,
                admin_required TINYINT NOT NULL DEFAULT 0,
                pull_call_id BIGINT,
                pull_status TINYINT NOT NULL DEFAULT 0,
                pull_reason_code VARCHAR(64),
                pull_reason_message VARCHAR(255),
                wa_jid VARCHAR(128),
                pull_result_at BIGINT,
                admin_status TINYINT NOT NULL DEFAULT 0,
                admin_command_id VARCHAR(64),
                admin_reason_code VARCHAR(64),
                admin_result_at BIGINT,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                CONSTRAINT uq_pull_task_material_seq UNIQUE (tenant_id, group_execution_id, member_seq),
                CONSTRAINT uq_pull_task_material_phone
                    UNIQUE (tenant_id, group_execution_id, normalized_phone)
            )
            """;

    /** 角色账号、在群状态与拉手跨任务占用生成列。 */
    static final String GROUP_ACCOUNT = """
            CREATE TABLE pull_task_group_account (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                tenant_id BIGINT NOT NULL,
                task_id BIGINT NOT NULL,
                group_execution_id BIGINT NOT NULL,
                account_id BIGINT NOT NULL,
                account_phone VARCHAR(32) NOT NULL,
                role_type TINYINT NOT NULL,
                role_seq INT NOT NULL,
                source_type TINYINT NOT NULL DEFAULT 1,
                selection_mode TINYINT NOT NULL DEFAULT 1,
                entry_mode TINYINT,
                membership_status TINYINT NOT NULL DEFAULT 0,
                joined_at BIGINT,
                pull_call_id BIGINT,
                admin_status TINYINT NOT NULL DEFAULT 0,
                availability_status TINYINT NOT NULL DEFAULT 1,
                unavailable_reason_code VARCHAR(64),
                cooldown_until BIGINT,
                occupied_at BIGINT,
                released_at BIGINT,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                occupancy_key BIGINT GENERATED ALWAYS AS (
                    CASE WHEN role_type = 2 AND released_at IS NULL THEN account_id ELSE NULL END
                ),
                CONSTRAINT uq_pull_task_group_account_occupancy UNIQUE (tenant_id, occupancy_key),
                CONSTRAINT uq_pull_task_group_account_role
                    UNIQUE (tenant_id, group_execution_id, role_type, account_id),
                CONSTRAINT uq_pull_task_group_account_seq
                    UNIQUE (tenant_id, group_execution_id, role_type, role_seq)
            )
            """;

    /** 账号动作：保存联系人、邀请入群、踩链接入群。 */
    static final String ACCOUNT_ACTION = """
            CREATE TABLE pull_task_account_action (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                tenant_id BIGINT NOT NULL,
                task_id BIGINT NOT NULL,
                group_execution_id BIGINT NOT NULL,
                action_type TINYINT NOT NULL,
                actor_group_account_id BIGINT NOT NULL,
                target_group_account_id BIGINT NOT NULL,
                action_status TINYINT NOT NULL DEFAULT 1,
                command_id VARCHAR(64),
                reason_code VARCHAR(64),
                reason_message VARCHAR(255),
                submitted_at BIGINT,
                result_at BIGINT,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                CONSTRAINT uq_pull_task_action_pair UNIQUE
                    (tenant_id, group_execution_id, action_type,
                     actor_group_account_id, target_group_account_id),
                CONSTRAINT uq_pull_task_action_command UNIQUE (tenant_id, command_id)
            )
            """;

    /** 单次批量加成员调用。 */
    static final String PULL_CALL = """
            CREATE TABLE pull_task_pull_call (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                tenant_id BIGINT NOT NULL,
                task_id BIGINT NOT NULL,
                group_execution_id BIGINT NOT NULL,
                call_seq INT NOT NULL,
                puller_group_account_id BIGINT NOT NULL,
                puller_account_id BIGINT NOT NULL,
                planned_material_count INT NOT NULL,
                planned_station_count INT NOT NULL,
                call_status TINYINT NOT NULL DEFAULT 1,
                command_id VARCHAR(64),
                idempotency_key VARCHAR(64) NOT NULL,
                reason_code VARCHAR(64),
                reason_message VARCHAR(255),
                submitted_at BIGINT,
                result_at BIGINT,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                CONSTRAINT uq_pull_task_call_seq UNIQUE (tenant_id, group_execution_id, call_seq),
                CONSTRAINT uq_pull_task_call_idempotency UNIQUE (tenant_id, idempotency_key),
                CONSTRAINT uq_pull_task_call_command UNIQUE (tenant_id, command_id)
            )
            """;

    /**
     * 按依赖顺序返回全部建表语句。
     *
     * @return 建表语句数组
     */
    static String[] all() {
        return new String[] {
            PULL_TASK, STANDARD_SETTING, GROUP_EXECUTION,
            MATERIAL_MEMBER, GROUP_ACCOUNT, ACCOUNT_ACTION, PULL_CALL,
        };
    }
}
```

- [ ] **Step 2: 写 `PullTaskNormalLinkH2Support`**

创建 `armada-api/src/test/java/com/armada/task/mapper/PullTaskNormalLinkH2Support.java`。

```java
package com.armada.task.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * 普通群链接执行域 Mapper 测试的 H2 基座。
 *
 * <p>用 H2 MySQL 模式加载真实 Mapper XML 和生产 {@code MyBatisConfig} 的租户拦截器，
 * 让 Mapper SQL、租户隔离、生成列和部分唯一索引在本地就能验证。</p>
 */
final class PullTaskNormalLinkH2Support {

    private PullTaskNormalLinkH2Support() {
    }

    /**
     * 构造隔离的 H2 内存库。
     *
     * @param dbName 库名；每个测试类用不同的名字避免互相污染
     * @return H2 数据源
     */
    static DataSource dataSource(String dbName) {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:" + dbName
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        h2.setUser("sa");
        h2.setPassword("");
        return h2;
    }

    /**
     * 用生产租户拦截器和真实 Mapper XML 构造 SqlSessionFactory。
     *
     * @param dataSource H2 数据源
     * @param interceptor 生产 MyBatis-Plus 拦截器（含租户行隔离）
     * @param mapperXmlPaths classpath 下的 Mapper XML 路径
     * @return SqlSessionFactory
     * @throws Exception 构造失败时抛出
     */
    static SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                               MybatisPlusInterceptor interceptor,
                                               String... mapperXmlPaths) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setUseGeneratedKeys(true);

        Resource[] resources = Arrays.stream(mapperXmlPaths)
                .map(ClassPathResource::new)
                .toArray(Resource[]::new);

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        factoryBean.setPlugins(interceptor);
        factoryBean.setMapperLocations(resources);
        return factoryBean.getObject();
    }

    /**
     * 清库、重建全部表，再执行调用方给的 fixture 语句。
     *
     * @param dataSource H2 数据源
     * @param extraStatements 建表后要执行的 fixture 语句
     * @throws SQLException 执行失败时抛出
     */
    static void resetSchema(DataSource dataSource, String... extraStatements) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
            for (String ddl : PullTaskNormalLinkSchema.all()) {
                statement.execute(ddl);
            }
            for (String sql : extraStatements) {
                statement.execute(sql);
            }
        }
    }
}
```

- [ ] **Step 3: 写一个自检测试证明基座能建表且生成列语义正确**

创建 `armada-api/src/test/java/com/armada/task/mapper/PullTaskNormalLinkSchemaSelfTest.java`。这个测试不依赖任何 Mapper，是基座本身的门禁。

```java
package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** H2 测试基座自检：建表可用，且生成列 + 部分唯一索引语义与 MySQL 一致。 */
class PullTaskNormalLinkSchemaSelfTest {

    private final DataSource dataSource =
            PullTaskNormalLinkH2Support.dataSource("pull_task_schema_self_test");

    @BeforeEach
    void setUp() throws SQLException {
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
    }

    @Test
    void allSevenTablesAreCreated() throws SQLException {
        assertThat(PullTaskNormalLinkSchema.all()).hasSize(7);
        for (String table : new String[] {
                "pull_task", "pull_task_standard_setting", "pull_task_group_execution",
                "pull_task_material_member", "pull_task_group_account",
                "pull_task_account_action", "pull_task_pull_call"}) {
            assertThat(countRows("SELECT COUNT(*) FROM " + table)).isZero();
        }
    }

    @Test
    void releasedPullerRowsDoNotBlockTheOccupancyUniqueKey() throws SQLException {
        // 已释放的拉手行 occupancy_key 为 NULL，不参与唯一约束，
        // 因此同一账号可以留下任意多条历史释放记录。
        insertGroupAccount(1, 10, 2, 500L, 900L);
        insertGroupAccount(2, 10, 2, 501L, 901L);

        assertThat(countRows(
                "SELECT COUNT(*) FROM pull_task_group_account WHERE occupancy_key IS NULL"))
                .isEqualTo(2);
    }

    @Test
    void secondActivePullerOccupancyOnSameAccountIsRejected() throws SQLException {
        insertGroupAccount(1, 10, 2, 500L, null);

        assertThatThrownBy(() -> insertGroupAccount(2, 10, 2, 501L, null))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void nonPullerRolesNeverOccupy() throws SQLException {
        // role_type=1 管理、role_type=3 站台的 occupancy_key 恒为 NULL，
        // 同一账号在不同执行行担任站台不受互斥限制。
        insertGroupAccount(1, 20, 3, 500L, null);
        insertGroupAccount(2, 20, 3, 500L, null);

        assertThat(countRows(
                "SELECT COUNT(*) FROM pull_task_group_account WHERE account_id = 20"))
                .isEqualTo(2);
    }

    @Test
    void draftExecutionRowsDoNotOccupyTheGroupLink() throws SQLException {
        // execution_status=0 草稿的 link_occupancy_key 为 NULL，
        // 两个用户的草稿可以同时贴同一条链接。
        insertExecution(1, 100, 0, "chat.whatsapp.com/AAA");
        insertExecution(2, 200, 0, "chat.whatsapp.com/AAA");

        assertThat(countRows(
                "SELECT COUNT(*) FROM pull_task_group_execution WHERE link_occupancy_key IS NULL"))
                .isEqualTo(2);
    }

    @Test
    void twoLiveTasksCannotHoldTheSameGroupLink() throws SQLException {
        insertExecution(1, 100, 1, "chat.whatsapp.com/AAA");

        assertThatThrownBy(() -> insertExecution(2, 200, 1, "chat.whatsapp.com/AAA"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void terminalExecutionRowReleasesTheGroupLink() throws SQLException {
        insertExecution(1, 100, 4, "chat.whatsapp.com/AAA");

        // execution_status=4 已完成，占用已释放，另一个任务可以接手同一条链接。
        insertExecution(2, 200, 1, "chat.whatsapp.com/AAA");
        assertThat(countRows("SELECT COUNT(*) FROM pull_task_group_execution")).isEqualTo(2);
    }

    private void insertGroupAccount(long id, long accountId, int roleType,
                                    long groupExecutionId, Long releasedAt) throws SQLException {
        execute("INSERT INTO pull_task_group_account "
                + "(id, tenant_id, task_id, group_execution_id, account_id, account_phone, "
                + " role_type, role_seq, created_at, updated_at, released_at) VALUES ("
                + id + ", 7, 1, " + groupExecutionId + ", " + accountId + ", '8613800000000', "
                + roleType + ", " + id + ", 100, 100, "
                + (releasedAt == null ? "NULL" : releasedAt) + ")");
    }

    private void insertExecution(long id, long taskId, int status, String link)
            throws SQLException {
        execute("INSERT INTO pull_task_group_execution "
                + "(id, tenant_id, task_id, seq, normalized_link, invite_code, "
                + " source_link_line_no, source_file_index, source_file_name, "
                + " execution_status, created_at, updated_at) VALUES ("
                + id + ", 7, " + taskId + ", 1, '" + link + "', 'AAA', 1, 1, 'a.txt', "
                + status + ", 100, 100)");
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long countRows(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
```

- [ ] **Step 4: 运行自检测试**

```bash
cd armada-api && mvn -q -Dtest='PullTaskNormalLinkSchemaSelfTest' -DfailIfNoTests=false test
```

Expected: PASS，7 个测试全绿。

若 `secondActivePullerOccupancyOnSameAccountIsRejected` 或 `twoLiveTasksCannotHoldTheSameGroupLink` 失败（没抛异常），说明 H2 没把生成列纳入唯一约束——检查生成列表达式的 `ELSE NULL` 是否写对，以及 `CONSTRAINT ... UNIQUE` 是否落在生成列上。**这两个测试是拉手互斥与链接占用的唯一本地门禁，不能跳过。**

- [ ] **Step 5: 提交**

```bash
git add armada-api/src/test/java/com/armada/task/mapper/PullTaskNormalLinkSchema.java \
        armada-api/src/test/java/com/armada/task/mapper/PullTaskNormalLinkH2Support.java \
        armada-api/src/test/java/com/armada/task/mapper/PullTaskNormalLinkSchemaSelfTest.java
git commit -m "test: 新增普通群链接执行域 H2 测试基座

共享建表 DDL 与 DataSource/SqlSessionFactory 工厂方法,并自检生成列
与部分唯一索引语义:草稿不占用链接、终态释放链接、拉手同账号只能有
一条未释放占用、非拉手角色不占用。"
```

---

## Task 3: `pull_task` 生命周期列、DRAFT 可见性与乐观锁 Mapper

**Files:**
- Modify: `armada-api/src/main/java/com/armada/task/model/entity/PullTask.java`
- Modify: `armada-api/src/main/java/com/armada/task/mapper/PullTaskMapper.java`
- Modify: `armada-api/src/main/resources/mapper/task/PullTaskMapper.xml`
- Modify: `armada-api/src/test/java/com/armada/task/mapper/PullTaskMapperInMemoryTest.java`
- Create: `armada-api/src/test/java/com/armada/task/mapper/PullTaskLifecycleMapperInMemoryTest.java`

**Interfaces:**
- Consumes: Task 1 的 `pull_task.started_at` / `finished_at` / `version`；Task 2 的 `PullTaskNormalLinkH2Support` 与 `PullTaskNormalLinkSchema`
- Produces:
  - `PullTask#getStartedAt()` / `setStartedAt(Long)`、`getFinishedAt()` / `setFinishedAt(Long)`、`getVersion()` / `setVersion(Integer)`
  - `PullTaskMapper#updateStatusWithVersion(long id, String fromStatus, String toStatus, Integer expectedVersion, Long startedAt, Long finishedAt, long now)` → `int`（0 = 前置状态或版本不匹配）
  - `PullTaskMapper#selectLifecycle(long id)` → `PullTask`

**关键前置事实（实施前必读）：** `pull_task.status` **已经**在用 `DRAFT`——`PullTaskMapper.xml` 的 `batchSoftDeleteAllowed` 里有 `task_type = 'GROUP_MARKETING' AND status = 'DRAFT'`，说明 DRAFT 对拉群营销是**可见**状态。ADR-0007 只要求 STANDARD 的草稿不可见。因此列表过滤必须写成 `NOT (task_type = 'STANDARD' AND status = 'DRAFT')`，**不能**写成一刀切的 `status <> 'DRAFT'`，否则会隐藏营销草稿、打破既有功能。

- [ ] **Step 1: 写失败的测试**

创建 `armada-api/src/test/java/com/armada/task/mapper/PullTaskLifecycleMapperInMemoryTest.java`。

```java
package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.dto.PullTaskQuery;
import com.armada.task.model.entity.PullTask;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 拉群任务生命周期乐观锁与 STANDARD 草稿可见性的 H2 MySQL 模式测试。 */
@SpringJUnitConfig(PullTaskLifecycleMapperInMemoryTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskLifecycleMapperInMemoryTest {

    private static final long TENANT = 7L;

    private static final String FIXTURES = """
            INSERT INTO pull_task
              (id, tenant_id, task_type, task_name, mode, status, version,
               config_json, created_at, updated_at)
            VALUES
              (1, 7, 'STANDARD', '普通草稿', 'GROUP_LINK', 'DRAFT', 1, '{}', 100, 100),
              (2, 7, 'STANDARD', '待启动任务', 'GROUP_LINK', 'WAIT_START', 1, '{}', 100, 100),
              (3, 7, 'GROUP_MARKETING', '营销草稿', 'OLD_LINK', 'DRAFT', 1, '{}', 100, 100),
              (4, 8, 'STANDARD', '他租户任务', 'GROUP_LINK', 'WAIT_START', 1, '{}', 100, 100)
            """;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PullTaskMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(TENANT);
        PullTaskNormalLinkH2Support.resetSchema(dataSource, FIXTURES);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void standardDraftIsHiddenFromListButMarketingDraftStaysVisible() {
        List<PullTask> rows = mapper.selectPage(new PullTaskQuery(), 50, 0);

        // STANDARD 草稿是创建页未提交的计划,不进列表(ADR-0007);
        // GROUP_MARKETING 的 DRAFT 是既有可见状态,不能被一起隐藏。
        assertThat(rows).extracting(PullTask::getId).containsExactlyInAnyOrder(2L, 3L);
    }

    @Test
    void listExcludesOtherTenants() {
        List<PullTask> rows = mapper.selectPage(new PullTaskQuery(), 50, 0);

        assertThat(rows).extracting(PullTask::getId).doesNotContain(4L);
    }

    @Test
    void freezeDraftToWaitStartSucceedsOnceAndIsRejectedOnRepeat() {
        assertThat(mapper.updateStatusWithVersion(1L, "DRAFT", "WAIT_START", 1, null, null, 500L))
                .isEqualTo(1);

        // 重复提交:前置状态已不满足,返回 0 行,不产生第二次副作用。
        assertThat(mapper.updateStatusWithVersion(1L, "DRAFT", "WAIT_START", 1, null, null, 600L))
                .isZero();

        PullTask task = mapper.selectLifecycle(1L);
        assertThat(task.getStatus()).isEqualTo("WAIT_START");
        assertThat(task.getVersion()).isEqualTo(2);
    }

    @Test
    void staleVersionIsRejected() {
        assertThat(mapper.updateStatusWithVersion(2L, "WAIT_START", "EXECUTING", 1, 700L, null, 700L))
                .isEqualTo(1);

        // 另一个会话拿着旧版本号提交,必须被乐观锁挡掉。
        assertThat(mapper.updateStatusWithVersion(2L, "EXECUTING", "PAUSED", 1, null, null, 800L))
                .isZero();
    }

    @Test
    void startedAtIsNotOverwrittenByLaterTransitions() {
        mapper.updateStatusWithVersion(2L, "WAIT_START", "EXECUTING", 1, 700L, null, 700L);
        assertThat(mapper.selectLifecycle(2L).getStartedAt()).isEqualTo(700L);
        assertThat(mapper.selectLifecycle(2L).getFinishedAt()).isNull();

        mapper.updateStatusWithVersion(2L, "EXECUTING", "COMPLETED", 2, null, 900L, 900L);
        PullTask done = mapper.selectLifecycle(2L);
        assertThat(done.getStartedAt()).isEqualTo(700L);
        assertThat(done.getFinishedAt()).isEqualTo(900L);
    }

    @Test
    void lifecycleUpdateCannotCrossTenant() {
        assertThat(mapper.updateStatusWithVersion(4L, "WAIT_START", "EXECUTING", 1, 700L, null, 700L))
                .isZero();
        assertThat(mapper.selectLifecycle(4L)).isNull();
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_lifecycle_test");
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(
                    dataSource, interceptor, "mapper/task/PullTaskMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        PullTaskMapper pullTaskMapper(SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(PullTaskMapper.class);
        }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd armada-api && mvn -q -Dtest='PullTaskLifecycleMapperInMemoryTest' -DfailIfNoTests=false test
```

Expected: 编译失败 —— `PullTask` 没有 `getStartedAt()`，`PullTaskMapper` 没有 `updateStatusWithVersion` / `selectLifecycle`。

- [ ] **Step 3: 给 `PullTask` 加三个字段**

修改 `armada-api/src/main/java/com/armada/task/model/entity/PullTask.java`，在 `blockingReason` 字段声明之后插入：

```java
    /** 首次真实启动时间(epoch 毫秒)。 */
    private Long startedAt;

    /** 进入 COMPLETED 或 ENDED 的时间(epoch 毫秒)。 */
    private Long finishedAt;

    /** 生命周期更新乐观锁版本号。 */
    private Integer version;
```

在 `setBlockingReason` 方法之后插入访问器：

```java
    public Long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Long startedAt) {
        this.startedAt = startedAt;
    }

    public Long getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Long finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
```

- [ ] **Step 4: 给 `PullTaskMapper` 加两个方法**

修改 `armada-api/src/main/java/com/armada/task/mapper/PullTaskMapper.java`，在接口内追加（若缺 `org.apache.ibatis.annotations.Param` 的 import 则补上）：

```java
    /**
     * 按状态前置条件与乐观锁版本推进任务生命周期。
     *
     * <p>返回 0 表示任务不在允许的前置状态或版本已过期。这是人工操作幂等的落点
     * （ADR-0009）：重复提交因前置状态不满足返回 0，Service 据此把当前状态当作
     * 成功结果返回，不得原样重试。</p>
     *
     * @param id 任务 ID
     * @param fromStatus 允许的当前状态
     * @param toStatus 目标状态
     * @param expectedVersion 读取时拿到的版本号
     * @param startedAt 首次启动时间；传 null 时不写该列，已有值不被覆盖
     * @param finishedAt 终态时间；传 null 时不写该列，已有值不被覆盖
     * @param now 本次更新时间(epoch 毫秒)
     * @return 实际更新行数；1 表示成功推进，0 表示前置校验或乐观锁失败
     */
    int updateStatusWithVersion(@Param("id") long id,
                                @Param("fromStatus") String fromStatus,
                                @Param("toStatus") String toStatus,
                                @Param("expectedVersion") Integer expectedVersion,
                                @Param("startedAt") Long startedAt,
                                @Param("finishedAt") Long finishedAt,
                                @Param("now") long now);

    /**
     * 读取任务生命周期字段，供 Service 在推进状态前取当前状态与版本号。
     *
     * @param id 任务 ID
     * @return 生命周期视图；任务不存在、已软删或不属于当前租户时为 null
     */
    PullTask selectLifecycle(@Param("id") long id);
```

- [ ] **Step 5: 改 `PullTaskMapper.xml`**

修改 `armada-api/src/main/resources/mapper/task/PullTaskMapper.xml`，共 4 处。

改动 A —— `<sql id="filter">` 开头补 STANDARD 草稿过滤：

```xml
  <sql id="filter">
    deleted_at IS NULL
    <!-- STANDARD 草稿是创建页未提交的计划，不进列表(ADR-0007)；
         GROUP_MARKETING 的 DRAFT 是既有可见状态，不能一起隐藏。 -->
    AND NOT (task_type = 'STANDARD' AND status = 'DRAFT')
    <if test="filter.id != null">AND id = #{filter.id}</if>
```

改动 B —— `selectPage` 列清单补三列：

```xml
    SELECT
      id, tenant_id, task_type, group_source, task_name, group_name, mode, status,
      primary_stage, blocking_reason, started_at, finished_at, version,
      group_count, expected_pull_count, operator_name,
      created_at, updated_at, last_business_executed_at, remark, deleted_at
    FROM pull_task
```

改动 C —— `batchSoftDeleteAllowed` 允许删 STANDARD 草稿（创建页"清除全部"和放弃草稿需要）：

```xml
      AND (
        (task_type = 'GROUP_MARKETING' AND status = 'DRAFT')
        OR
        (task_type = 'STANDARD' AND status IN ('DRAFT', 'WAIT_START', 'COMPLETED', 'ENDED'))
      )
```

改动 D —— 在 `</mapper>` 之前追加：

```xml
  <!-- 状态前置条件与乐观锁版本在数据库层复核，重复提交返回 0 行而不是产生第二次副作用。 -->
  <update id="updateStatusWithVersion">
    UPDATE pull_task
    SET status = #{toStatus},
        version = version + 1,
        updated_at = #{now}
        <if test="startedAt != null">, started_at = #{startedAt}</if>
        <if test="finishedAt != null">, finished_at = #{finishedAt}</if>
    WHERE id = #{id}
      AND deleted_at IS NULL
      AND status = #{fromStatus}
      AND version = #{expectedVersion}
  </update>

  <select id="selectLifecycle" resultType="com.armada.task.model.entity.PullTask">
    SELECT id, tenant_id, task_type, task_name, mode, status,
           primary_stage, blocking_reason, started_at, finished_at, version,
           created_at, updated_at
    FROM pull_task
    WHERE id = #{id}
      AND deleted_at IS NULL
  </select>
```

- [ ] **Step 6: 校验 XML 语法**

```bash
cd armada-api && xmllint --noout src/main/resources/mapper/task/PullTaskMapper.xml
```

Expected: 无输出即通过。若环境没有 `xmllint`，跳过，由 Step 8 的 MyBatis 加载兜底。

- [ ] **Step 7: 修既有 `PullTaskMapperInMemoryTest` 的建表语句**

`selectPage` 现在要查 `started_at` / `finished_at` / `version`，而 `PullTaskMapperInMemoryTest` 里手写的 `CREATE TABLE pull_task` 没有这三列，**必定报 `Column not found`**。打开该文件，在其 `pull_task` 建表语句的 `blocking_reason` 之后补：

```sql
                    started_at BIGINT,
                    finished_at BIGINT,
                    version INT NOT NULL DEFAULT 1,
```

同时检查该测试里是否有断言"草稿任务出现在列表中"的用例——若有 STANDARD + DRAFT 的 fixture 行，其期望要按新的过滤规则调整。

- [ ] **Step 8: 运行测试确认通过**

```bash
cd armada-api && mvn -q -Dtest='PullTaskLifecycleMapperInMemoryTest,PullTaskMapperInMemoryTest' -DfailIfNoTests=false test
```

Expected: PASS。

- [ ] **Step 9: 跑全部 pull_task 相关测试**

```bash
cd armada-api && mvn -q -Dtest='PullTask*Test' -DfailIfNoTests=false test
```

Expected: PASS。任何失败都要修到绿，不得跳过。

- [ ] **Step 10: 提交**

```bash
git add armada-api/src/main/java/com/armada/task/model/entity/PullTask.java \
        armada-api/src/main/java/com/armada/task/mapper/PullTaskMapper.java \
        armada-api/src/main/resources/mapper/task/PullTaskMapper.xml \
        armada-api/src/test/java/com/armada/task/mapper/
git commit -m "feat: pull_task 生命周期乐观锁与 STANDARD 草稿可见性

- 实体补 startedAt/finishedAt/version
- 列表过滤 STANDARD 草稿,保留 GROUP_MARKETING 草稿既有可见性
- 批量软删允许 STANDARD 草稿,支撑创建页清除全部
- 新增状态前置校验 + 乐观锁的生命周期推进方法,作为人工操作幂等落点"
```

---

## 关于 Task 4–9 的实体写法（一次说明，六个任务共用）

六张表的实体都是纯 POJO：字段声明 + 标准 getter/setter，无 MyBatis-Plus 注解。下面每个任务只给出**字段声明段**（含 Javadoc），访问器按 `com/armada/task/model/entity/PullTaskGroupMarketingGroupOccupancy.java` 的写法逐字段生成 `getXxx()` / `setXxx(T)`，不写额外逻辑、不写 `toString`、不写 `equals`。

枚举列在实体里用 `Integer` 承载（与既有 `PullTaskGroupMarketingGroupOccupancy` 一致），语义由同名 enum 常量类提供给 Service 使用，避免 Mapper 层做类型转换。

---

## Task 4: `pull_task_standard_setting` 实体与 Mapper

**Files:**
- Create: `armada-api/src/main/java/com/armada/task/model/entity/PullTaskStandardSetting.java`
- Create: `armada-api/src/main/java/com/armada/task/mapper/PullTaskStandardSettingMapper.java`
- Create: `armada-api/src/main/resources/mapper/task/PullTaskStandardSettingMapper.xml`
- Create: `armada-api/src/test/java/com/armada/task/mapper/PullTaskStandardSettingMapperInMemoryTest.java`

**Interfaces:**
- Consumes: Task 2 的 `PullTaskNormalLinkH2Support` / `PullTaskNormalLinkSchema`
- Produces:
  - `PullTaskStandardSetting`（POJO，字段见 Step 1）
  - `PullTaskStandardSettingMapper#insert(PullTaskStandardSetting row)` → `int`
  - `PullTaskStandardSettingMapper#selectByTaskId(long taskId)` → `PullTaskStandardSetting`
  - `PullTaskStandardSettingMapper#freezeRequiredManagerCount(long taskId, int requiredManagerCount, long now)` → `int`

- [ ] **Step 1: 写实体**

创建 `armada-api/src/main/java/com/armada/task/model/entity/PullTaskStandardSetting.java`，包 `com.armada.task.model.entity`，类注释 `/** 普通群链接任务冻结执行配置，映射 {@code pull_task_standard_setting}。 */`。字段声明：

```java
    /** 所属租户 ID。 */
    private Long tenantId;

    /** 拉群任务 ID(→pull_task.id)。 */
    private Long taskId;

    /** 创建后是否自动启动：0 否 1 是。 */
    private Integer autoStart;

    /** 料子内管理员设置时点：1=成员入群后立即 2=本群料子全部终态后。 */
    private Integer materialAdminTiming;

    /** 单次拉人料子人数下限(闭区间，不含站台)。 */
    private Integer pullCountMin;

    /** 单次拉人料子人数上限(闭区间，不含站台)。 */
    private Integer pullCountMax;

    /** 同一拉手账号连续拉人调用的最小间隔(秒)。 */
    private Integer pullIntervalSeconds;

    /** 每条执行行的计划拉手数。 */
    private Integer pullerCountPerGroup;

    /** 每一次拉人调用叠加的站台数。 */
    private Integer stationCountPerCall;

    /** 同一父任务最大同时运行执行行数。 */
    private Integer concurrentGroupCount;

    /** 拉手风控冷却分钟；0 表示不建立定时恢复。 */
    private Integer pullerRiskMinutes;

    /** 任务启动时按管理分组可用账号数冻结的要求管理员人数 N。 */
    private Integer requiredManagerCount;

    /** 管理账号分组 ID(→account_group.id)。 */
    private Long managerGroupId;

    /** 拉手账号分组 ID(→account_group.id)。 */
    private Long pullerGroupId;

    /** 站台账号分组 ID(→account_group.id)。 */
    private Long stationGroupId;

    /** 管理分组名称快照。 */
    private String managerGroupName;

    /** 拉手分组名称快照。 */
    private String pullerGroupName;

    /** 站台分组名称快照。 */
    private String stationGroupName;

    /** 创建时间(epoch 毫秒)。 */
    private Long createdAt;

    /** 更新时间(epoch 毫秒)。 */
    private Long updatedAt;
```

- [ ] **Step 2: 写失败的测试**

创建 `armada-api/src/test/java/com/armada/task/mapper/PullTaskStandardSettingMapperInMemoryTest.java`。

```java
package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 普通群链接冻结配置 Mapper 的 H2 MySQL 模式测试。 */
@SpringJUnitConfig(PullTaskStandardSettingMapperInMemoryTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskStandardSettingMapperInMemoryTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PullTaskStandardSettingMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void insertAndReadBackAllFrozenConfig() {
        mapper.insert(sample(1L));

        PullTaskStandardSetting saved = mapper.selectByTaskId(1L);
        assertThat(saved.getTenantId()).isEqualTo(7L);
        assertThat(saved.getPullCountMin()).isEqualTo(3);
        assertThat(saved.getPullCountMax()).isEqualTo(8);
        assertThat(saved.getStationCountPerCall()).isEqualTo(2);
        assertThat(saved.getConcurrentGroupCount()).isEqualTo(1);
        assertThat(saved.getMaterialAdminTiming()).isEqualTo(1);
        assertThat(saved.getManagerGroupName()).isEqualTo("管理组");
        // 启动前 N 尚未冻结。
        assertThat(saved.getRequiredManagerCount()).isZero();
    }

    @Test
    void freezeRequiredManagerCountWritesTaskLevelN() {
        mapper.insert(sample(1L));

        assertThat(mapper.freezeRequiredManagerCount(1L, 4, 900L)).isEqualTo(1);

        PullTaskStandardSetting saved = mapper.selectByTaskId(1L);
        // N 冻结在任务级而不是执行行级:执行行受并发槽位控制、启动时刻不同,
        // 逐行冻结会得到互不相同的 N,导致各群缺口口径不一致。
        assertThat(saved.getRequiredManagerCount()).isEqualTo(4);
        assertThat(saved.getUpdatedAt()).isEqualTo(900L);
    }

    @Test
    void otherTenantSettingIsInvisibleAndUnwritable() {
        mapper.insert(sample(1L));

        TenantContext.set(8L);
        assertThat(mapper.selectByTaskId(1L)).isNull();
        assertThat(mapper.freezeRequiredManagerCount(1L, 9, 900L)).isZero();

        TenantContext.set(7L);
        assertThat(mapper.selectByTaskId(1L).getRequiredManagerCount()).isZero();
    }

    private PullTaskStandardSetting sample(long taskId) {
        PullTaskStandardSetting row = new PullTaskStandardSetting();
        row.setTaskId(taskId);
        row.setAutoStart(0);
        row.setMaterialAdminTiming(1);
        row.setPullCountMin(3);
        row.setPullCountMax(8);
        row.setPullIntervalSeconds(30);
        row.setPullerCountPerGroup(2);
        row.setStationCountPerCall(2);
        row.setConcurrentGroupCount(1);
        row.setPullerRiskMinutes(0);
        row.setRequiredManagerCount(0);
        row.setManagerGroupId(11L);
        row.setPullerGroupId(12L);
        row.setStationGroupId(13L);
        row.setManagerGroupName("管理组");
        row.setPullerGroupName("拉手组");
        row.setStationGroupName("站台组");
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_standard_setting_test");
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(
                    dataSource, interceptor, "mapper/task/PullTaskStandardSettingMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        PullTaskStandardSettingMapper pullTaskStandardSettingMapper(
                SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(PullTaskStandardSettingMapper.class);
        }
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

```bash
cd armada-api && mvn -q -Dtest='PullTaskStandardSettingMapperInMemoryTest' -DfailIfNoTests=false test
```

Expected: 编译失败 —— `PullTaskStandardSettingMapper` 不存在。

- [ ] **Step 4: 写 Mapper 接口**

创建 `armada-api/src/main/java/com/armada/task/mapper/PullTaskStandardSettingMapper.java`。

```java
package com.armada.task.mapper;

import com.armada.task.model.entity.PullTaskStandardSetting;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 普通群链接任务冻结执行配置数据访问层。 */
@Mapper
public interface PullTaskStandardSettingMapper {

    /**
     * 在任务由草稿冻结为待启动的同一事务中写入执行配置。
     *
     * @param row 冻结配置
     * @return 新增行数
     */
    int insert(PullTaskStandardSetting row);

    /**
     * 读取任务的冻结执行配置。
     *
     * @param taskId 拉群任务 ID
     * @return 冻结配置；不存在或不属于当前租户时为 null
     */
    PullTaskStandardSetting selectByTaskId(@Param("taskId") long taskId);

    /**
     * 任务启动时冻结要求管理员人数 N。
     *
     * <p>N 必须落在任务级：执行行受并发槽位控制、启动时刻不同，逐行冻结会得到
     * 互不相同的 N，导致各群的"缺少管理员人数"口径不一致。</p>
     *
     * @param taskId 拉群任务 ID
     * @param requiredManagerCount 按管理分组可用账号数算出的 N
     * @param now 更新时间(epoch 毫秒)
     * @return 实际更新行数
     */
    int freezeRequiredManagerCount(@Param("taskId") long taskId,
                                   @Param("requiredManagerCount") int requiredManagerCount,
                                   @Param("now") long now);
}
```

- [ ] **Step 5: 写 Mapper XML**

创建 `armada-api/src/main/resources/mapper/task/PullTaskStandardSettingMapper.xml`。注意 `INSERT` 列清单**不写 `tenant_id`**，由租户拦截器注入。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.armada.task.mapper.PullTaskStandardSettingMapper">

  <!-- tenant_id 由租户拦截器注入，列清单里不写。 -->
  <insert id="insert">
    INSERT INTO pull_task_standard_setting (
      task_id, auto_start, material_admin_timing, pull_count_min, pull_count_max,
      pull_interval_seconds, puller_count_per_group, station_count_per_call,
      concurrent_group_count, puller_risk_minutes, required_manager_count,
      manager_group_id, puller_group_id, station_group_id,
      manager_group_name, puller_group_name, station_group_name,
      created_at, updated_at
    ) VALUES (
      #{taskId}, #{autoStart}, #{materialAdminTiming}, #{pullCountMin}, #{pullCountMax},
      #{pullIntervalSeconds}, #{pullerCountPerGroup}, #{stationCountPerCall},
      #{concurrentGroupCount}, #{pullerRiskMinutes}, #{requiredManagerCount},
      #{managerGroupId}, #{pullerGroupId}, #{stationGroupId},
      #{managerGroupName}, #{pullerGroupName}, #{stationGroupName},
      #{createdAt}, #{updatedAt}
    )
  </insert>

  <select id="selectByTaskId" resultType="com.armada.task.model.entity.PullTaskStandardSetting">
    SELECT *
    FROM pull_task_standard_setting
    WHERE task_id = #{taskId}
  </select>

  <update id="freezeRequiredManagerCount">
    UPDATE pull_task_standard_setting
    SET required_manager_count = #{requiredManagerCount},
        updated_at = #{now}
    WHERE task_id = #{taskId}
  </update>

</mapper>
```

- [ ] **Step 6: 校验 XML 并运行测试**

```bash
cd armada-api && xmllint --noout src/main/resources/mapper/task/PullTaskStandardSettingMapper.xml \
  && mvn -q -Dtest='PullTaskStandardSettingMapperInMemoryTest' -DfailIfNoTests=false test
```

Expected: PASS，3 个测试全绿。

- [ ] **Step 7: 提交**

```bash
git add armada-api/src/main/java/com/armada/task/model/entity/PullTaskStandardSetting.java \
        armada-api/src/main/java/com/armada/task/mapper/PullTaskStandardSettingMapper.java \
        armada-api/src/main/resources/mapper/task/PullTaskStandardSettingMapper.xml \
        armada-api/src/test/java/com/armada/task/mapper/PullTaskStandardSettingMapperInMemoryTest.java
git commit -m "feat: 新增普通群链接冻结执行配置 Mapper

要求管理员人数 N 冻结在任务级而非执行行级,避免不同启动时刻的执行行
得到互不相同的 N。"
```

---

## Task 5: `pull_task_group_execution` 实体、枚举与 Mapper

这是本计划最核心的一张表：链接跨任务占用、跨租户调度扫描和调度锁都在这里。

**Files:**
- Create: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskExecutionStatus.java`
- Create: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskExecutionStage.java`
- Create: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskWaitResourceType.java`
- Create: `armada-api/src/main/java/com/armada/task/model/entity/PullTaskGroupExecution.java`
- Create: `armada-api/src/main/java/com/armada/task/mapper/PullTaskGroupExecutionMapper.java`
- Create: `armada-api/src/main/resources/mapper/task/PullTaskGroupExecutionMapper.xml`
- Create: `armada-api/src/test/java/com/armada/task/mapper/PullTaskGroupExecutionMapperInMemoryTest.java`

**Interfaces:**
- Consumes: Task 2 的 `PullTaskNormalLinkH2Support` / `PullTaskNormalLinkSchema`
- Produces:
  - `PullTaskExecutionStatus.DRAFT/WAIT_START/EXECUTING/WAIT_RESOURCE/COMPLETED/FAILED/ABANDONED`，各有 `int code()`
  - `PullTaskGroupExecution`（POJO）
  - `PullTaskGroupExecutionMapper#insertDraft(PullTaskGroupExecution row)` → `int`（`useGeneratedKeys`，回填 `id`）
  - `#selectByTaskId(long taskId)` → `List<PullTaskGroupExecution>`
  - `#deleteDraftByTaskId(long taskId)` → `int`
  - `#freezeDraftRows(long taskId, long now)` → `int`
  - `#claimDue(int limit, long now, String lockOwner, long lockExpiresAt)` → `int`（跨租户）
  - `#selectClaimed(String lockOwner)` → `List<PullTaskGroupExecution>`（跨租户）
  - `#updateCheckpoint(long id, int expectedVersion, Integer nextManagerIndex, Integer nextPullerIndex, Integer stage, long nextRunAt, long now)` → `int`
  - `#releaseLock(long id, String lockOwner)` → `int`（跨租户）

- [ ] **Step 1: 写三个枚举**

创建 `armada-api/src/main/java/com/armada/task/model/enums/PullTaskExecutionStatus.java`：

```java
package com.armada.task.model.enums;

/** 群链接执行行状态；与 pull_task_group_execution.execution_status 的 TINYINT 取值一一对应。 */
public enum PullTaskExecutionStatus {

    /** 草稿：创建页未提交的计划行，不参与调度，不占用群链接。 */
    DRAFT(0),
    /** 待启动：已随任务冻结，等待调度取走。 */
    WAIT_START(1),
    /** 执行中：已被调度器抢占，正在推进业务阶段。 */
    EXECUTING(2),
    /** 等待资源：管理员、拉手或站台不足，暂停本行等待补充。 */
    WAIT_RESOURCE(3),
    /** 已完成：本行全部料子进入终态并收口。 */
    COMPLETED(4),
    /** 失败终态：链接失效等不可恢复原因。 */
    FAILED(5),
    /** 已放弃：人工放弃该群，不可恢复。 */
    ABANDONED(6);

    private final int code;

    PullTaskExecutionStatus(int code) {
        this.code = code;
    }

    /**
     * 数据库存储值。
     *
     * @return TINYINT 取值
     */
    public int code() {
        return code;
    }
}
```

创建 `PullTaskExecutionStage.java`：

```java
package com.armada.task.model.enums;

/** 群链接执行行业务阶段；与 pull_task_group_execution.stage 的 TINYINT 取值一一对应。 */
public enum PullTaskExecutionStage {

    /** 链接校验：确认链接有效并解析群 JID。 */
    LINK_VALIDATION(1),
    /** 管理入群：管理分组账号踩链接进入目标群。 */
    MANAGER_JOIN(2),
    /** 管理—拉手联系人：双向保存联系人，失败不阻断。 */
    MANAGER_PULLER_CONTACT(3),
    /** 管理邀请拉手：管理账号轮询单人邀请，全局固定 1 秒间隔。 */
    PULLER_INVITE(4),
    /** 拉人执行：含拉手—站台联系人与站台、料子同批加入。 */
    PULL_EXECUTION(5),
    /** 料子提权：给带 A/a 标识且已成功入群的料子设置群管理员。 */
    MATERIAL_ADMIN(6),
    /** 收口：本行全部动作终态，写入完成状态。 */
    CLOSING(7);

    private final int code;

    PullTaskExecutionStage(int code) {
        this.code = code;
    }

    /**
     * 数据库存储值。
     *
     * @return TINYINT 取值
     */
    public int code() {
        return code;
    }
}
```

创建 `PullTaskWaitResourceType.java`：

```java
package com.armada.task.model.enums;

/** 执行行资源等待类型；与 pull_task_group_execution.wait_resource_type 一一对应，非资源等待时为 null。 */
public enum PullTaskWaitResourceType {

    /** 等待管理员：本行可用管理账号降为 0。 */
    MANAGER(1),
    /** 等待拉手：本行可用拉手降为 0。 */
    PULLER(2),
    /** 等待站台：本次调用可分配站台不足配置数量。 */
    STATION(3);

    private final int code;

    PullTaskWaitResourceType(int code) {
        this.code = code;
    }

    /**
     * 数据库存储值。
     *
     * @return TINYINT 取值
     */
    public int code() {
        return code;
    }
}
```

- [ ] **Step 2: 写实体**

创建 `armada-api/src/main/java/com/armada/task/model/entity/PullTaskGroupExecution.java`，类注释 `/** 群链接与 TXT 一对一冻结配对的执行行，映射 {@code pull_task_group_execution}。 */`。字段声明：

```java
    /** 执行行主键。 */
    private Long id;

    /** 所属租户 ID。 */
    private Long tenantId;

    /** 拉群任务 ID(→pull_task.id)；草稿期也非空。 */
    private Long taskId;

    /** 任务内展示与执行顺序。 */
    private Integer seq;

    /** 群入口 ID(→group_link.id)；最终创建时回填。 */
    private Long groupLinkId;

    /** 归一化群链接 chat.whatsapp.com/&lt;邀请码&gt;。 */
    private String normalizedLink;

    /** 群邀请码；大小写敏感。 */
    private String inviteCode;

    /** 粘贴内容中的原始行号。 */
    private Integer sourceLinkLineNo;

    /** WhatsApp 群 JID；启动校验时回填。 */
    private String groupJid;

    /** 上传 TXT 的序号。 */
    private Integer sourceFileIndex;

    /** TXT 原始文件名。 */
    private String sourceFileName;

    /** TXT 总行数。 */
    private Integer totalLineCount;

    /** 去重后有效料子数。 */
    private Integer validMemberCount;

    /** 非法号码行数。 */
    private Integer invalidLineCount;

    /** 文件内重复号码行数。 */
    private Integer duplicateLineCount;

    /** 执行状态，取值见 PullTaskExecutionStatus。 */
    private Integer executionStatus;

    /** 业务阶段，取值见 PullTaskExecutionStage。 */
    private Integer stage;

    /** 是否人工暂停：0 否 1 是；与资源等待独立。 */
    private Integer manualPaused;

    /** 资源等待类型，取值见 PullTaskWaitResourceType；非资源等待为 null。 */
    private Integer waitResourceType;

    /** 当前状态原因码。 */
    private String reasonCode;

    /** 当前状态原因描述(已脱敏)。 */
    private String reasonMessage;

    /** 管理账号轮询游标。 */
    private Integer nextManagerIndex;

    /** 拉手轮询游标。 */
    private Integer nextPullerIndex;

    /** 下次可调度时间(epoch 毫秒)；0 表示立即可调度。 */
    private Long nextRunAt;

    /** 抢占调度的实例标识。 */
    private String lockOwner;

    /** 调度锁过期时间(epoch 毫秒)；过期可被抢占回收。 */
    private Long lockExpiresAt;

    /** 执行行更新乐观锁版本号。 */
    private Integer version;

    /** 本行首次启动时间(epoch 毫秒)。 */
    private Long startedAt;

    /** 本行进入终态时间(epoch 毫秒)。 */
    private Long finishedAt;

    /** 最近一次真实业务动作时间(epoch 毫秒)。 */
    private Long lastBusinessExecutedAt;

    /** 创建时间(epoch 毫秒)。 */
    private Long createdAt;

    /** 更新时间(epoch 毫秒)。 */
    private Long updatedAt;
```

`link_occupancy_key` 是数据库生成列，**实体不映射**——它只服务于唯一索引，应用层不读不写。

- [ ] **Step 3: 写失败的测试**

创建 `armada-api/src/test/java/com/armada/task/mapper/PullTaskGroupExecutionMapperInMemoryTest.java`。

```java
package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 群链接执行行 Mapper 的 H2 MySQL 模式测试：链接占用、跨租户调度扫描与调度锁。 */
@SpringJUnitConfig(PullTaskGroupExecutionMapperInMemoryTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskGroupExecutionMapperInMemoryTest {

    private static final String LINK = "chat.whatsapp.com/AbCdEfGhIjKlMnOpQrStUv";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PullTaskGroupExecutionMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void insertDraftFillsGeneratedId() {
        PullTaskGroupExecution row = draft(100L, 1, LINK, 1);
        mapper.insertDraft(row);

        assertThat(row.getId()).isNotNull();
        assertThat(mapper.selectByTaskId(100L)).hasSize(1);
    }

    @Test
    void twoDraftsMayHoldTheSameLinkBecauseDraftsDoNotOccupy() {
        mapper.insertDraft(draft(100L, 1, LINK, 1));
        mapper.insertDraft(draft(200L, 1, LINK, 1));

        // 草稿 execution_status=0，link_occupancy_key 为 NULL，不参与唯一约束。
        assertThat(mapper.selectByTaskId(100L)).hasSize(1);
        assertThat(mapper.selectByTaskId(200L)).hasSize(1);
    }

    @Test
    void freezingTheSecondTaskOnTheSameLinkIsRejected() {
        mapper.insertDraft(draft(100L, 1, LINK, 1));
        mapper.insertDraft(draft(200L, 1, LINK, 1));

        assertThat(mapper.freezeDraftRows(100L, 500L)).isEqualTo(1);

        // 第一个任务已占用该链接，第二个任务冻结时唯一键冲突。
        assertThatThrownBy(() -> mapper.freezeDraftRows(200L, 600L))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void inviteCodesDifferingOnlyByCaseCoexistWithinOneTask() {
        // H2 默认大小写敏感，这里只验证唯一键维度正确；
        // MySQL 侧的 ai_ci 风险由 PullTaskNormalLinkMigrationSqlTest 的
        // ascii_bin 断言兜住。
        mapper.insertDraft(draft(100L, 1, "chat.whatsapp.com/AAAA", 1));
        mapper.insertDraft(draft(100L, 2, "chat.whatsapp.com/aaaa", 2));

        assertThat(mapper.selectByTaskId(100L)).hasSize(2);
    }

    @Test
    void duplicateLinkWithinOneTaskIsRejected() {
        mapper.insertDraft(draft(100L, 1, LINK, 1));

        assertThatThrownBy(() -> mapper.insertDraft(draft(100L, 2, LINK, 2)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void deleteDraftRemovesOnlyDraftRowsOfThatTask() {
        mapper.insertDraft(draft(100L, 1, LINK, 1));
        mapper.insertDraft(draft(100L, 2, "chat.whatsapp.com/BBBB", 2));
        mapper.freezeDraftRows(100L, 500L);
        mapper.insertDraft(draft(100L, 3, "chat.whatsapp.com/CCCC", 3));

        // 只清未冻结的草稿行，已冻结的执行行不受影响。
        assertThat(mapper.deleteDraftByTaskId(100L)).isEqualTo(1);
        assertThat(mapper.selectByTaskId(100L)).hasSize(2);
    }

    @Test
    void claimDueScansAcrossTenantsWithoutTenantContext() {
        mapper.insertDraft(draft(100L, 1, LINK, 1));
        mapper.freezeDraftRows(100L, 500L);

        TenantContext.set(8L);
        mapper.insertDraft(draft(300L, 1, "chat.whatsapp.com/DDDD", 1));
        mapper.freezeDraftRows(300L, 500L);

        // 调度器没有租户上下文；@InterceptorIgnore 让它能看到全部租户的待执行行。
        TenantContext.clear();
        assertThat(mapper.claimDue(10, 600L, "worker-1", 660L)).isEqualTo(2);
        assertThat(mapper.selectClaimed("worker-1"))
                .extracting(PullTaskGroupExecution::getTaskId)
                .containsExactlyInAnyOrder(100L, 300L);
    }

    @Test
    void claimDueSkipsManuallyPausedAndFutureRows() throws SQLException {
        mapper.insertDraft(draft(100L, 1, LINK, 1));
        mapper.insertDraft(draft(100L, 2, "chat.whatsapp.com/BBBB", 2));
        mapper.freezeDraftRows(100L, 500L);
        executeRaw("UPDATE pull_task_group_execution SET manual_paused = 1 WHERE seq = 1");
        executeRaw("UPDATE pull_task_group_execution SET next_run_at = 9999 WHERE seq = 2");

        TenantContext.clear();
        // 人工暂停优先于资源自动恢复；未到调度时间的行也不取。
        assertThat(mapper.claimDue(10, 600L, "worker-1", 660L)).isZero();
    }

    @Test
    void expiredLockCanBeReclaimedByAnotherWorker() {
        mapper.insertDraft(draft(100L, 1, LINK, 1));
        mapper.freezeDraftRows(100L, 500L);

        TenantContext.clear();
        assertThat(mapper.claimDue(10, 600L, "worker-1", 660L)).isEqualTo(1);
        // 锁未过期时别的实例抢不到。
        assertThat(mapper.claimDue(10, 610L, "worker-2", 670L)).isZero();
        // 锁过期后可被回收，避免实例崩溃导致执行行永久卡死。
        assertThat(mapper.claimDue(10, 700L, "worker-2", 760L)).isEqualTo(1);
        assertThat(mapper.selectClaimed("worker-1")).isEmpty();
    }

    @Test
    void updateCheckpointRespectsOptimisticLock() {
        PullTaskGroupExecution row = draft(100L, 1, LINK, 1);
        mapper.insertDraft(row);

        assertThat(mapper.updateCheckpoint(row.getId(), 1, 2, 3, 4, 800L, 800L)).isEqualTo(1);
        // 拿旧版本号再提交必须被挡掉。
        assertThat(mapper.updateCheckpoint(row.getId(), 1, 5, 6, 5, 900L, 900L)).isZero();

        PullTaskGroupExecution saved = mapper.selectByTaskId(100L).get(0);
        assertThat(saved.getNextManagerIndex()).isEqualTo(2);
        assertThat(saved.getNextPullerIndex()).isEqualTo(3);
        assertThat(saved.getStage()).isEqualTo(4);
        assertThat(saved.getVersion()).isEqualTo(2);
    }

    @Test
    void otherTenantExecutionRowsAreInvisible() {
        mapper.insertDraft(draft(100L, 1, LINK, 1));

        TenantContext.set(8L);
        assertThat(mapper.selectByTaskId(100L)).isEmpty();
        assertThat(mapper.deleteDraftByTaskId(100L)).isZero();
    }

    private PullTaskGroupExecution draft(long taskId, int seq, String link, int fileIndex) {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setTaskId(taskId);
        row.setSeq(seq);
        row.setNormalizedLink(link);
        row.setInviteCode(link.substring(link.lastIndexOf('/') + 1));
        row.setSourceLinkLineNo(seq);
        row.setSourceFileIndex(fileIndex);
        row.setSourceFileName("material-" + fileIndex + ".txt");
        row.setTotalLineCount(10);
        row.setValidMemberCount(8);
        row.setInvalidLineCount(1);
        row.setDuplicateLineCount(1);
        row.setExecutionStatus(PullTaskExecutionStatus.DRAFT.code());
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    private void executeRaw(String sql) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_group_execution_test");
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(
                    dataSource, interceptor, "mapper/task/PullTaskGroupExecutionMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        PullTaskGroupExecutionMapper pullTaskGroupExecutionMapper(
                SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(PullTaskGroupExecutionMapper.class);
        }
    }
}
```

- [ ] **Step 4: 运行测试确认失败**

```bash
cd armada-api && mvn -q -Dtest='PullTaskGroupExecutionMapperInMemoryTest' -DfailIfNoTests=false test
```

Expected: 编译失败 —— `PullTaskGroupExecutionMapper` 不存在。

- [ ] **Step 5: 写 Mapper 接口**

创建 `armada-api/src/main/java/com/armada/task/mapper/PullTaskGroupExecutionMapper.java`。

```java
package com.armada.task.mapper;

import com.armada.task.model.entity.PullTaskGroupExecution;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 群链接执行行数据访问层。 */
@Mapper
public interface PullTaskGroupExecutionMapper {

    /**
     * 创建页生成匹配计划时写入草稿执行行。
     *
     * <p>草稿行 {@code execution_status=0}，生成列 {@code link_occupancy_key} 为 NULL，
     * 因此不同用户的草稿可以同时持有同一条群链接（ADR-0007）。</p>
     *
     * @param row 草稿执行行；写入后回填 id
     * @return 新增行数
     */
    int insertDraft(PullTaskGroupExecution row);

    /**
     * 读取任务的全部执行行，按 seq 升序。
     *
     * @param taskId 拉群任务 ID
     * @return 执行行列表
     */
    List<PullTaskGroupExecution> selectByTaskId(@Param("taskId") long taskId);

    /**
     * 删除任务下尚未冻结的草稿执行行，支撑创建页的"清除全部"。
     *
     * @param taskId 拉群任务 ID
     * @return 删除行数
     */
    int deleteDraftByTaskId(@Param("taskId") long taskId);

    /**
     * 任务由草稿冻结为待启动时，把本任务的草稿执行行整体推进为待启动。
     *
     * <p>推进后生成列 {@code link_occupancy_key} 取到链接值，占用随之生效；
     * 若同一链接已被另一个在跑的任务占用，数据库唯一键会抛
     * {@link org.springframework.dao.DuplicateKeyException}，调用方应把它翻译成
     * 面向运营的"群链接已被占用"业务异常。</p>
     *
     * @param taskId 拉群任务 ID
     * @param now 冻结时间(epoch 毫秒)
     * @return 实际冻结行数
     */
    int freezeDraftRows(@Param("taskId") long taskId, @Param("now") long now);

    /**
     * 调度器跨租户抢占到期的执行行。
     *
     * <p>后台调度线程没有租户上下文（{@code MyBatisConfig} 无上下文时 fail-closed
     * 回退 -1），因此这里忽略租户拦截，并只走不带租户前缀的
     * {@code idx_pull_task_execution_dispatch} 索引。</p>
     *
     * @param limit 单批最多抢占行数
     * @param now 当前时间(epoch 毫秒)
     * @param lockOwner 抢占实例标识
     * @param lockExpiresAt 本次锁过期时间(epoch 毫秒)
     * @return 实际抢占行数
     */
    @InterceptorIgnore(tenantLine = "true")
    int claimDue(@Param("limit") int limit,
                 @Param("now") long now,
                 @Param("lockOwner") String lockOwner,
                 @Param("lockExpiresAt") long lockExpiresAt);

    /**
     * 读取本实例当前持有的执行行。
     *
     * @param lockOwner 抢占实例标识
     * @return 该实例持有的执行行
     */
    @InterceptorIgnore(tenantLine = "true")
    List<PullTaskGroupExecution> selectClaimed(@Param("lockOwner") String lockOwner);

    /**
     * 用乐观锁推进执行行的检查点。
     *
     * @param id 执行行 ID
     * @param expectedVersion 读取时拿到的版本号
     * @param nextManagerIndex 新的管理账号轮询游标
     * @param nextPullerIndex 新的拉手轮询游标
     * @param stage 新的业务阶段
     * @param nextRunAt 下次可调度时间(epoch 毫秒)
     * @param now 更新时间(epoch 毫秒)
     * @return 实际更新行数；0 表示版本已过期
     */
    int updateCheckpoint(@Param("id") long id,
                         @Param("expectedVersion") int expectedVersion,
                         @Param("nextManagerIndex") Integer nextManagerIndex,
                         @Param("nextPullerIndex") Integer nextPullerIndex,
                         @Param("stage") Integer stage,
                         @Param("nextRunAt") long nextRunAt,
                         @Param("now") long now);

    /**
     * 释放本实例持有的调度锁。
     *
     * @param id 执行行 ID
     * @param lockOwner 抢占实例标识
     * @return 实际释放行数
     */
    @InterceptorIgnore(tenantLine = "true")
    int releaseLock(@Param("id") long id, @Param("lockOwner") String lockOwner);
}
```

- [ ] **Step 6: 写 Mapper XML**

创建 `armada-api/src/main/resources/mapper/task/PullTaskGroupExecutionMapper.xml`。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.armada.task.mapper.PullTaskGroupExecutionMapper">

  <!-- tenant_id 由租户拦截器注入；link_occupancy_key 是生成列，永不显式写入。 -->
  <insert id="insertDraft" useGeneratedKeys="true" keyProperty="id">
    INSERT INTO pull_task_group_execution (
      task_id, seq, group_link_id, normalized_link, invite_code, source_link_line_no, group_jid,
      source_file_index, source_file_name, total_line_count, valid_member_count,
      invalid_line_count, duplicate_line_count, execution_status, stage, manual_paused,
      next_manager_index, next_puller_index, next_run_at, version, created_at, updated_at
    ) VALUES (
      #{taskId}, #{seq}, #{groupLinkId}, #{normalizedLink}, #{inviteCode},
      #{sourceLinkLineNo}, #{groupJid},
      #{sourceFileIndex}, #{sourceFileName}, #{totalLineCount}, #{validMemberCount},
      #{invalidLineCount}, #{duplicateLineCount}, 0, 1, 0,
      0, 0, 0, 1, #{createdAt}, #{updatedAt}
    )
  </insert>

  <select id="selectByTaskId" resultType="com.armada.task.model.entity.PullTaskGroupExecution">
    SELECT *
    FROM pull_task_group_execution
    WHERE task_id = #{taskId}
    ORDER BY seq ASC
  </select>

  <delete id="deleteDraftByTaskId">
    DELETE FROM pull_task_group_execution
    WHERE task_id = #{taskId}
      AND execution_status = 0
  </delete>

  <!-- 推进为待启动后生成列取到链接值，跨任务占用随之生效；冲突由唯一键抛出。 -->
  <update id="freezeDraftRows">
    UPDATE pull_task_group_execution
    SET execution_status = 1,
        updated_at = #{now}
    WHERE task_id = #{taskId}
      AND execution_status = 0
  </update>

  <!-- 调度器跨租户扫描：条件顺序对齐 idx_pull_task_execution_dispatch
       (execution_status, manual_paused, next_run_at, id)。 -->
  <update id="claimDue">
    UPDATE pull_task_group_execution
    SET lock_owner = #{lockOwner},
        lock_expires_at = #{lockExpiresAt},
        updated_at = #{now}
    WHERE execution_status IN (1, 2)
      AND manual_paused = 0
      AND next_run_at &lt;= #{now}
      AND (lock_owner IS NULL OR lock_expires_at &lt;= #{now})
    ORDER BY next_run_at ASC, id ASC
    LIMIT #{limit}
  </update>

  <select id="selectClaimed" resultType="com.armada.task.model.entity.PullTaskGroupExecution">
    SELECT *
    FROM pull_task_group_execution
    WHERE lock_owner = #{lockOwner}
    ORDER BY id ASC
  </select>

  <update id="updateCheckpoint">
    UPDATE pull_task_group_execution
    SET next_manager_index = #{nextManagerIndex},
        next_puller_index = #{nextPullerIndex},
        stage = #{stage},
        next_run_at = #{nextRunAt},
        last_business_executed_at = #{now},
        version = version + 1,
        updated_at = #{now}
    WHERE id = #{id}
      AND version = #{expectedVersion}
  </update>

  <update id="releaseLock">
    UPDATE pull_task_group_execution
    SET lock_owner = NULL,
        lock_expires_at = NULL
    WHERE id = #{id}
      AND lock_owner = #{lockOwner}
  </update>

</mapper>
```

- [ ] **Step 7: 校验 XML 并运行测试**

```bash
cd armada-api && xmllint --noout src/main/resources/mapper/task/PullTaskGroupExecutionMapper.xml \
  && mvn -q -Dtest='PullTaskGroupExecutionMapperInMemoryTest' -DfailIfNoTests=false test
```

Expected: PASS，11 个测试全绿。

若 `claimDue` 在 H2 报 `UPDATE ... ORDER BY ... LIMIT` 语法错，把该语句改成两步：先 `SELECT id ... ORDER BY ... LIMIT` 取出候选 id，再 `UPDATE ... WHERE id IN (...)`，并把接口拆成 `selectDueIds` + `claimByIds` 两个方法，两者都保留 `@InterceptorIgnore`。改动后同步更新测试对返回值的断言。

- [ ] **Step 8: 提交**

```bash
git add armada-api/src/main/java/com/armada/task/model/enums/PullTaskExecutionStatus.java \
        armada-api/src/main/java/com/armada/task/model/enums/PullTaskExecutionStage.java \
        armada-api/src/main/java/com/armada/task/model/enums/PullTaskWaitResourceType.java \
        armada-api/src/main/java/com/armada/task/model/entity/PullTaskGroupExecution.java \
        armada-api/src/main/java/com/armada/task/mapper/PullTaskGroupExecutionMapper.java \
        armada-api/src/main/resources/mapper/task/PullTaskGroupExecutionMapper.xml \
        armada-api/src/test/java/com/armada/task/mapper/PullTaskGroupExecutionMapperInMemoryTest.java
git commit -m "feat: 新增群链接执行行 Mapper

草稿不占用链接、冻结后跨任务占用由生成列唯一索引保证;调度扫描忽略
租户拦截并走无租户前缀索引;调度锁过期可被其他实例回收。"
```

---

## Task 6: `pull_task_material_member` 实体、枚举与 Mapper

**Files:**
- Create: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskMaterialPullStatus.java`
- Create: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskMaterialAdminStatus.java`
- Create: `armada-api/src/main/java/com/armada/task/model/entity/PullTaskMaterialMember.java`
- Create: `armada-api/src/main/java/com/armada/task/mapper/PullTaskMaterialMemberMapper.java`
- Create: `armada-api/src/main/resources/mapper/task/PullTaskMaterialMemberMapper.xml`
- Create: `armada-api/src/test/java/com/armada/task/mapper/PullTaskMaterialMemberMapperInMemoryTest.java`

**Interfaces:**
- Consumes: Task 2 的 H2 基座
- Produces:
  - `PullTaskMaterialPullStatus.UNCONSUMED(0)/SUBMITTED(1)/SUCCESS(2)/FAILED(3)/UNKNOWN(4)/CANCELED(5)`
  - `PullTaskMaterialAdminStatus.NOT_REQUIRED(0)/PENDING(1)/SUBMITTED(2)/SUCCESS(3)/FAILED(4)/UNKNOWN(5)/CANCELED(6)`
  - `PullTaskMaterialMember`（POJO）
  - `PullTaskMaterialMemberMapper#batchInsert(List<PullTaskMaterialMember> rows)` → `int`
  - `#selectByExecution(long groupExecutionId)` → `List<PullTaskMaterialMember>`
  - `#selectUnconsumed(long groupExecutionId, int limit)` → `List<PullTaskMaterialMember>`
  - `#assignToCall(List<Long> ids, long pullCallId, long now)` → `int`
  - `#writeBackPullResult(long id, int pullStatus, String reasonCode, String reasonMessage, String waJid, long now)` → `int`
  - `#selectPendingAdmin(long groupExecutionId)` → `List<PullTaskMaterialMember>`
  - `#markAdminSubmitted(long id, String adminCommandId, long now)` → `int`
  - `#selectByAdminCommandId(String adminCommandId)` → `PullTaskMaterialMember`

  不设料子进度汇总方法：详情页的料子进度按 `pull_status` 现算，M1 数据层没有消费方，加了就是死代码。

- [ ] **Step 1: 写两个枚举**

创建 `PullTaskMaterialPullStatus.java`：

```java
package com.armada.task.model.enums;

/** 料子号码的入群结果；与 pull_task_material_member.pull_status 一一对应。 */
public enum PullTaskMaterialPullStatus {

    /** 未消费：尚未被任何一次拉人调用取走，是料子游标的判定依据。 */
    UNCONSUMED(0),
    /** 已提交：已随批量加成员命令发出，等待协议结果。 */
    SUBMITTED(1),
    /** 成功：协议确认已入群。 */
    SUCCESS(2),
    /** 失败：协议明确失败，终态，不重试、不换拉手。 */
    FAILED(3),
    /** 结果未知：只能由状态查询或协议回调收敛，不得伪装成成功或失败。 */
    UNKNOWN(4),
    /** 取消：任务结束时尚未发出的动作。 */
    CANCELED(5);

    private final int code;

    PullTaskMaterialPullStatus(int code) {
        this.code = code;
    }

    /**
     * 数据库存储值。
     *
     * @return TINYINT 取值
     */
    public int code() {
        return code;
    }
}
```

创建 `PullTaskMaterialAdminStatus.java`：

```java
package com.armada.task.model.enums;

/** 带 A/a 标识料子的提权结果；与 pull_task_material_member.admin_status 一一对应。 */
public enum PullTaskMaterialAdminStatus {

    /** 不需要：号码未带 A/a 标识。 */
    NOT_REQUIRED(0),
    /** 待执行：已成功入群且校验在群，等待按设置时机提权。 */
    PENDING(1),
    /** 已提交：提权命令已发出。 */
    SUBMITTED(2),
    /** 成功：已确认取得群管理员权限。 */
    SUCCESS(3),
    /** 失败：提权失败，不反向修改该号码的入群成功结果。 */
    FAILED(4),
    /** 结果未知：由查询或回调收敛。 */
    UNKNOWN(5),
    /** 取消：任务结束时尚未发出的提权动作。 */
    CANCELED(6);

    private final int code;

    PullTaskMaterialAdminStatus(int code) {
        this.code = code;
    }

    /**
     * 数据库存储值。
     *
     * @return TINYINT 取值
     */
    public int code() {
        return code;
    }
}
```

- [ ] **Step 2: 写实体**

创建 `armada-api/src/main/java/com/armada/task/model/entity/PullTaskMaterialMember.java`，类注释 `/** TXT 料子号码及其入群、提权结果，映射 {@code pull_task_material_member}。 */`。字段声明：

```java
    /** 料子成员主键。 */
    private Long id;

    /** 所属租户 ID。 */
    private Long tenantId;

    /** 所属执行行 ID(→pull_task_group_execution.id)。 */
    private Long groupExecutionId;

    /** 文件内去重后稳定顺序。 */
    private Integer memberSeq;

    /** 首次有效出现的原始行号。 */
    private Integer sourceLineNo;

    /** 归一化号码(7-15 位含国家码纯数字)。 */
    private String normalizedPhone;

    /** 是否带 A/a 需设群管理员标识：0 否 1 是。 */
    private Integer adminRequired;

    /** 消费本料子的拉人调用 ID；null 表示尚未消费。 */
    private Long pullCallId;

    /** 入群结果，取值见 PullTaskMaterialPullStatus。 */
    private Integer pullStatus;

    /** 入群失败原因码。 */
    private String pullReasonCode;

    /** 入群失败原因描述(已脱敏)。 */
    private String pullReasonMessage;

    /** 成功入群后的成员 JID。 */
    private String waJid;

    /** 入群结果回写时间(epoch 毫秒)。 */
    private Long pullResultAt;

    /** 提权结果，取值见 PullTaskMaterialAdminStatus。 */
    private Integer adminStatus;

    /** 提权协议命令 ID。 */
    private String adminCommandId;

    /** 提权失败原因码。 */
    private String adminReasonCode;

    /** 提权结果回写时间(epoch 毫秒)。 */
    private Long adminResultAt;

    /** 创建时间(epoch 毫秒)。 */
    private Long createdAt;

    /** 更新时间(epoch 毫秒)。 */
    private Long updatedAt;
```

- [ ] **Step 3: 写失败的测试**

创建 `armada-api/src/test/java/com/armada/task/mapper/PullTaskMaterialMemberMapperInMemoryTest.java`。

```java
package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.enums.PullTaskMaterialAdminStatus;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 料子成员 Mapper 的 H2 MySQL 模式测试：游标语义、单文件去重与回调定位。 */
@SpringJUnitConfig(PullTaskMaterialMemberMapperInMemoryTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskMaterialMemberMapperInMemoryTest {

    private static final long EXECUTION = 500L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PullTaskMaterialMemberMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void batchInsertPersistsAllMembersWithStableOrder() {
        mapper.batchInsert(List.of(
                member(1, "8613800000001", 0),
                member(2, "8613800000002", 1),
                member(3, "8613800000003", 0)));

        List<PullTaskMaterialMember> unconsumed = mapper.selectUnconsumed(EXECUTION, 10);
        assertThat(unconsumed).extracting(PullTaskMaterialMember::getMemberSeq)
                .containsExactly(1, 2, 3);
        assertThat(unconsumed.get(1).getAdminRequired()).isEqualTo(1);
    }

    @Test
    void duplicatePhoneWithinOneExecutionIsRejected() {
        mapper.batchInsert(List.of(member(1, "8613800000001", 0)));

        assertThatThrownBy(() -> mapper.batchInsert(List.of(member(2, "8613800000001", 0))))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void assignToCallConsumesMembersAndAdvancesTheCursor() {
        mapper.batchInsert(List.of(
                member(1, "8613800000001", 0),
                member(2, "8613800000002", 0),
                member(3, "8613800000003", 0)));

        List<Long> firstBatch = mapper.selectUnconsumed(EXECUTION, 2).stream()
                .map(PullTaskMaterialMember::getId).toList();
        assertThat(mapper.assignToCall(firstBatch, 900L, 900L)).isEqualTo(2);

        // pull_call_id 非空即"已消费"，游标自然前移，不需要单独的游标列。
        assertThat(mapper.selectUnconsumed(EXECUTION, 10))
                .extracting(PullTaskMaterialMember::getMemberSeq)
                .containsExactly(3);
    }

    @Test
    void alreadyConsumedMembersAreNotReassigned() {
        mapper.batchInsert(List.of(member(1, "8613800000001", 0)));
        Long id = mapper.selectUnconsumed(EXECUTION, 1).get(0).getId();

        assertThat(mapper.assignToCall(List.of(id), 900L, 900L)).isEqualTo(1);
        // 重复分配必须是 0 行：一个料子一生只属于一次调用。
        assertThat(mapper.assignToCall(List.of(id), 901L, 901L)).isZero();
    }

    @Test
    void pullResultWriteBackKeepsUnknownDistinctFromFailure() {
        mapper.batchInsert(List.of(
                member(1, "8613800000001", 0),
                member(2, "8613800000002", 0)));
        List<PullTaskMaterialMember> rows = mapper.selectUnconsumed(EXECUTION, 10);
        mapper.assignToCall(rows.stream().map(PullTaskMaterialMember::getId).toList(), 900L, 900L);

        mapper.writeBackPullResult(rows.get(0).getId(),
                PullTaskMaterialPullStatus.SUCCESS.code(), null, null, "8613800000001@s.whatsapp.net", 950L);
        mapper.writeBackPullResult(rows.get(1).getId(),
                PullTaskMaterialPullStatus.UNKNOWN.code(), "TIMEOUT", "协议超时", null, 950L);

        List<PullTaskMaterialMember> after = mapper.selectByExecution(EXECUTION);
        assertThat(after.get(0).getPullStatus()).isEqualTo(PullTaskMaterialPullStatus.SUCCESS.code());
        assertThat(after.get(0).getWaJid()).isEqualTo("8613800000001@s.whatsapp.net");
        assertThat(after.get(1).getPullStatus()).isEqualTo(PullTaskMaterialPullStatus.UNKNOWN.code());
        assertThat(after.get(1).getPullReasonCode()).isEqualTo("TIMEOUT");
    }

    @Test
    void pendingAdminOnlyIncludesFlaggedMembersThatJoinedSuccessfully() {
        mapper.batchInsert(List.of(
                member(1, "8613800000001", 1),
                member(2, "8613800000002", 1),
                member(3, "8613800000003", 0)));
        List<PullTaskMaterialMember> rows = mapper.selectUnconsumed(EXECUTION, 10);
        mapper.assignToCall(rows.stream().map(PullTaskMaterialMember::getId).toList(), 900L, 900L);

        mapper.writeBackPullResult(rows.get(0).getId(),
                PullTaskMaterialPullStatus.SUCCESS.code(), null, null, "jid1", 950L);
        mapper.writeBackPullResult(rows.get(1).getId(),
                PullTaskMaterialPullStatus.FAILED.code(), "PRIVACY", "隐私限制", null, 950L);
        mapper.writeBackPullResult(rows.get(2).getId(),
                PullTaskMaterialPullStatus.SUCCESS.code(), null, null, "jid3", 950L);

        // 入群失败或结果未知的标记料子不提权；未标记的成功料子也不提权。
        assertThat(mapper.selectPendingAdmin(EXECUTION))
                .extracting(PullTaskMaterialMember::getNormalizedPhone)
                .containsExactly("8613800000001");
    }

    @Test
    void adminCallbackIsLocatedByCommandId() {
        mapper.batchInsert(List.of(member(1, "8613800000001", 1)));
        Long id = mapper.selectUnconsumed(EXECUTION, 1).get(0).getId();
        mapper.assignToCall(List.of(id), 900L, 900L);
        mapper.writeBackPullResult(id, PullTaskMaterialPullStatus.SUCCESS.code(), null, null, "jid1", 950L);

        mapper.markAdminSubmitted(id, "cmd-admin-1", 960L);

        PullTaskMaterialMember found = mapper.selectByAdminCommandId("cmd-admin-1");
        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(id);
        assertThat(found.getAdminStatus())
                .isEqualTo(PullTaskMaterialAdminStatus.SUBMITTED.code());
    }

    @Test
    void otherTenantMembersAreInvisible() {
        mapper.batchInsert(List.of(member(1, "8613800000001", 0)));

        TenantContext.set(8L);
        assertThat(mapper.selectUnconsumed(EXECUTION, 10)).isEmpty();
        assertThat(mapper.selectByAdminCommandId("cmd-admin-1")).isNull();
    }

    private PullTaskMaterialMember member(int seq, String phone, int adminRequired) {
        PullTaskMaterialMember row = new PullTaskMaterialMember();
        row.setGroupExecutionId(EXECUTION);
        row.setMemberSeq(seq);
        row.setSourceLineNo(seq);
        row.setNormalizedPhone(phone);
        row.setAdminRequired(adminRequired);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_material_member_test");
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(
                    dataSource, interceptor, "mapper/task/PullTaskMaterialMemberMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        PullTaskMaterialMemberMapper pullTaskMaterialMemberMapper(
                SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(PullTaskMaterialMemberMapper.class);
        }
    }
}
```

- [ ] **Step 4: 运行测试确认失败**

```bash
cd armada-api && mvn -q -Dtest='PullTaskMaterialMemberMapperInMemoryTest' -DfailIfNoTests=false test
```

Expected: 编译失败 —— `PullTaskMaterialMemberMapper` 不存在。

- [ ] **Step 5: 写 Mapper 接口**

创建 `armada-api/src/main/java/com/armada/task/mapper/PullTaskMaterialMemberMapper.java`。

```java
package com.armada.task.mapper;

import com.armada.task.model.entity.PullTaskMaterialMember;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 料子号码与逐号码结果数据访问层。 */
@Mapper
public interface PullTaskMaterialMemberMapper {

    /**
     * 解析 TXT 后批量写入去重后的有效号码。
     *
     * @param rows 料子成员，按 memberSeq 升序
     * @return 新增行数
     */
    int batchInsert(@Param("rows") List<PullTaskMaterialMember> rows);

    /**
     * 读取执行行的全部料子，按 memberSeq 升序。
     *
     * @param groupExecutionId 执行行 ID
     * @return 料子列表
     */
    List<PullTaskMaterialMember> selectByExecution(@Param("groupExecutionId") long groupExecutionId);

    /**
     * 取下一批尚未消费的料子。
     *
     * <p>{@code pull_call_id IS NULL} 即"未消费"，这就是料子游标本身，
     * 执行行上不再单独存游标列。</p>
     *
     * @param groupExecutionId 执行行 ID
     * @param limit 本次调用需要的料子人数
     * @return 未消费料子，按 memberSeq 升序
     */
    List<PullTaskMaterialMember> selectUnconsumed(@Param("groupExecutionId") long groupExecutionId,
                                                  @Param("limit") int limit);

    /**
     * 把选中的料子绑定到一次拉人调用。
     *
     * <p>只更新仍未消费的行；返回行数小于入参数量说明有并发消费，调用方必须放弃
     * 本次调用并重新取料，不得按原数量提交协议命令。</p>
     *
     * @param ids 料子 ID
     * @param pullCallId 拉人调用 ID
     * @param now 更新时间(epoch 毫秒)
     * @return 实际绑定行数
     */
    int assignToCall(@Param("ids") List<Long> ids,
                     @Param("pullCallId") long pullCallId,
                     @Param("now") long now);

    /**
     * 回写单个号码的入群结果。
     *
     * @param id 料子 ID
     * @param pullStatus 入群结果，取值见 PullTaskMaterialPullStatus
     * @param reasonCode 失败原因码
     * @param reasonMessage 失败原因描述(已脱敏)
     * @param waJid 成功入群后的成员 JID
     * @param now 回写时间(epoch 毫秒)
     * @return 实际更新行数
     */
    int writeBackPullResult(@Param("id") long id,
                            @Param("pullStatus") int pullStatus,
                            @Param("reasonCode") String reasonCode,
                            @Param("reasonMessage") String reasonMessage,
                            @Param("waJid") String waJid,
                            @Param("now") long now);

    /**
     * 取本执行行待提权的料子：带 A/a 标识、已成功入群、尚未提交提权。
     *
     * @param groupExecutionId 执行行 ID
     * @return 待提权料子，按 memberSeq 升序
     */
    List<PullTaskMaterialMember> selectPendingAdmin(
            @Param("groupExecutionId") long groupExecutionId);

    /**
     * 标记提权命令已提交。
     *
     * @param id 料子 ID
     * @param adminCommandId 提权协议命令 ID
     * @param now 提交时间(epoch 毫秒)
     * @return 实际更新行数
     */
    int markAdminSubmitted(@Param("id") long id,
                           @Param("adminCommandId") String adminCommandId,
                           @Param("now") long now);

    /**
     * 提权回调按命令 ID 定位料子行。
     *
     * @param adminCommandId 提权协议命令 ID
     * @return 料子行；不存在或不属于当前租户时为 null
     */
    PullTaskMaterialMember selectByAdminCommandId(
            @Param("adminCommandId") String adminCommandId);
}
```

- [ ] **Step 6: 写 Mapper XML**

创建 `armada-api/src/main/resources/mapper/task/PullTaskMaterialMemberMapper.xml`。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.armada.task.mapper.PullTaskMaterialMemberMapper">

  <!-- tenant_id 由租户拦截器注入。 -->
  <insert id="batchInsert">
    INSERT INTO pull_task_material_member (
      group_execution_id, member_seq, source_line_no, normalized_phone, admin_required,
      pull_status, admin_status, created_at, updated_at
    ) VALUES
    <foreach collection="rows" item="row" separator=",">
      (#{row.groupExecutionId}, #{row.memberSeq}, #{row.sourceLineNo},
       #{row.normalizedPhone}, #{row.adminRequired},
       0, <choose><when test="row.adminRequired == 1">1</when><otherwise>0</otherwise></choose>,
       #{row.createdAt}, #{row.updatedAt})
    </foreach>
  </insert>

  <select id="selectByExecution" resultType="com.armada.task.model.entity.PullTaskMaterialMember">
    SELECT *
    FROM pull_task_material_member
    WHERE group_execution_id = #{groupExecutionId}
    ORDER BY member_seq ASC
  </select>

  <!-- pull_call_id IS NULL 即未消费，这就是料子游标；走
       idx_pull_task_material_pending (tenant_id, group_execution_id, pull_status, member_seq)。 -->
  <select id="selectUnconsumed" resultType="com.armada.task.model.entity.PullTaskMaterialMember">
    SELECT *
    FROM pull_task_material_member
    WHERE group_execution_id = #{groupExecutionId}
      AND pull_status = 0
      AND pull_call_id IS NULL
    ORDER BY member_seq ASC
    LIMIT #{limit}
  </select>

  <!-- 只绑定仍未消费的行；返回行数不足时调用方必须放弃本次调用重新取料。 -->
  <update id="assignToCall">
    UPDATE pull_task_material_member
    SET pull_call_id = #{pullCallId},
        pull_status = 1,
        updated_at = #{now}
    WHERE pull_call_id IS NULL
      AND pull_status = 0
      AND id IN
      <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
  </update>

  <update id="writeBackPullResult">
    UPDATE pull_task_material_member
    SET pull_status = #{pullStatus},
        pull_reason_code = #{reasonCode},
        pull_reason_message = #{reasonMessage},
        wa_jid = #{waJid},
        pull_result_at = #{now},
        updated_at = #{now}
    WHERE id = #{id}
  </update>

  <!-- 入群失败或结果未知的标记料子不提权(pull_status = 2 才进)。 -->
  <select id="selectPendingAdmin" resultType="com.armada.task.model.entity.PullTaskMaterialMember">
    SELECT *
    FROM pull_task_material_member
    WHERE group_execution_id = #{groupExecutionId}
      AND admin_required = 1
      AND pull_status = 2
      AND admin_status = 1
    ORDER BY member_seq ASC
  </select>

  <update id="markAdminSubmitted">
    UPDATE pull_task_material_member
    SET admin_status = 2,
        admin_command_id = #{adminCommandId},
        updated_at = #{now}
    WHERE id = #{id}
      AND admin_status = 1
  </update>

  <select id="selectByAdminCommandId"
          resultType="com.armada.task.model.entity.PullTaskMaterialMember">
    SELECT *
    FROM pull_task_material_member
    WHERE admin_command_id = #{adminCommandId}
  </select>

</mapper>
```

- [ ] **Step 7: 校验 XML 并运行测试**

```bash
cd armada-api && xmllint --noout src/main/resources/mapper/task/PullTaskMaterialMemberMapper.xml \
  && mvn -q -Dtest='PullTaskMaterialMemberMapperInMemoryTest' -DfailIfNoTests=false test
```

Expected: PASS，8 个测试全绿。

- [ ] **Step 8: 提交**

```bash
git add armada-api/src/main/java/com/armada/task/model/enums/PullTaskMaterial*.java \
        armada-api/src/main/java/com/armada/task/model/entity/PullTaskMaterialMember.java \
        armada-api/src/main/java/com/armada/task/mapper/PullTaskMaterialMemberMapper.java \
        armada-api/src/main/resources/mapper/task/PullTaskMaterialMemberMapper.xml \
        armada-api/src/test/java/com/armada/task/mapper/PullTaskMaterialMemberMapperInMemoryTest.java
git commit -m "feat: 新增料子成员 Mapper

pull_call_id 为空即未消费,充当料子游标;入群结果与提权结果落在号码
本体行上,不另设参与者表。UNKNOWN 保持独立状态。"
```

---

## Task 7: `pull_task_group_account` 实体、枚举与 Mapper

第二张核心表：拉手跨任务互斥（ADR-0008）落在这里。

**Files:**
- Create: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskGroupAccountRole.java`
- Create: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskGroupAccountMembershipStatus.java`
- Create: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskGroupAccountAvailability.java`
- Create: `armada-api/src/main/java/com/armada/task/model/entity/PullTaskGroupAccount.java`
- Create: `armada-api/src/main/java/com/armada/task/mapper/PullTaskGroupAccountMapper.java`
- Create: `armada-api/src/main/resources/mapper/task/PullTaskGroupAccountMapper.xml`
- Create: `armada-api/src/test/java/com/armada/task/mapper/PullTaskGroupAccountMapperInMemoryTest.java`

**Interfaces:**
- Consumes: Task 2 的 H2 基座
- Produces:
  - `PullTaskGroupAccountRole.MANAGER(1)/PULLER(2)/STATION(3)`
  - `PullTaskGroupAccountMembershipStatus.NOT_JOINED(0)/JOINING(1)/IN_GROUP(2)/JOIN_FAILED(3)/UNKNOWN(4)`
  - `PullTaskGroupAccountAvailability.AVAILABLE(1)/RISK_COOLDOWN(2)/OFFLINE(3)/REMOVED(4)`
  - `PullTaskGroupAccount`（POJO）
  - `PullTaskGroupAccountMapper#insert(PullTaskGroupAccount row)` → `int`（`useGeneratedKeys`）
  - `#selectByExecutionAndRole(long groupExecutionId, int roleType)` → `List<PullTaskGroupAccount>`
  - `#countAvailableByRole(long groupExecutionId)` → `List<PullTaskGroupAccountRoleCount>`
  - `#releasePuller(long id, long now)` → `int`
  - `#reoccupyPuller(long id, long now)` → `int`
  - `#releaseAllPullersOfExecution(long groupExecutionId, long now)` → `int`
  - `#markUnavailable(long id, int availabilityStatus, String reasonCode, Long cooldownUntil, long now)` → `int`
  - `#updateMembership(long id, int membershipStatus, Long joinedAt, long now)` → `int`
- 附带产出一个只读投影：`com/armada/task/model/vo/PullTaskGroupAccountRoleCount.java`，字段 `roleType`(Integer) / `availableCount`(Integer)

- [ ] **Step 1: 写三个枚举**

创建 `PullTaskGroupAccountRole.java`：

```java
package com.armada.task.model.enums;

/** 执行行内的账号角色；与 pull_task_group_account.role_type 一一对应。 */
public enum PullTaskGroupAccountRole {

    /** 管理账号：踩链接进群后负责邀请拉手；数量由任务级 N 冻结。 */
    MANAGER(1),
    /** 拉手：负责批量把站台和料子加入群；跨任务互斥。 */
    PULLER(2),
    /** 站台：每次拉人调用叠加的陪跑账号；同群只入一次，可跨执行行复用。 */
    STATION(3);

    private final int code;

    PullTaskGroupAccountRole(int code) {
        this.code = code;
    }

    /**
     * 数据库存储值。
     *
     * @return TINYINT 取值
     */
    public int code() {
        return code;
    }
}
```

创建 `PullTaskGroupAccountMembershipStatus.java`：

```java
package com.armada.task.model.enums;

/** 角色账号在目标群中的在群状态；与 pull_task_group_account.membership_status 一一对应。 */
public enum PullTaskGroupAccountMembershipStatus {

    /** 未入群：尚未发起入群动作。 */
    NOT_JOINED(0),
    /** 入群中：入群命令已发出，等待结果。 */
    JOINING(1),
    /** 在群：已确认在群，可承担后续职责。 */
    IN_GROUP(2),
    /** 入群失败：明确失败，终态。 */
    JOIN_FAILED(3),
    /** 结果未知：由查询或回调收敛。 */
    UNKNOWN(4);

    private final int code;

    PullTaskGroupAccountMembershipStatus(int code) {
        this.code = code;
    }

    /**
     * 数据库存储值。
     *
     * @return TINYINT 取值
     */
    public int code() {
        return code;
    }
}
```

创建 `PullTaskGroupAccountAvailability.java`：

```java
package com.armada.task.model.enums;

/** 角色账号在本执行行中的可用性；与 pull_task_group_account.availability_status 一一对应。 */
public enum PullTaskGroupAccountAvailability {

    /** 可用：可参与调度。 */
    AVAILABLE(1),
    /** 风控冷却：到期后必须先通过真实可用性校验才能重新可用，到期本身不代表健康。 */
    RISK_COOLDOWN(2),
    /** 离线或不可用：账号级异常，跳过后继续轮询本行其他账号。 */
    OFFLINE(3),
    /** 已移出本行：不再参与本执行行的任何动作。 */
    REMOVED(4);

    private final int code;

    PullTaskGroupAccountAvailability(int code) {
        this.code = code;
    }

    /**
     * 数据库存储值。
     *
     * @return TINYINT 取值
     */
    public int code() {
        return code;
    }
}
```

- [ ] **Step 2: 写实体与投影**

创建 `armada-api/src/main/java/com/armada/task/model/entity/PullTaskGroupAccount.java`，类注释 `/** 执行行内的角色账号、在群状态与拉手占用，映射 {@code pull_task_group_account}。 */`。字段声明：

```java
    /** 角色账号主键。 */
    private Long id;

    /** 所属租户 ID。 */
    private Long tenantId;

    /** 拉群任务 ID(→pull_task.id)。 */
    private Long taskId;

    /** 所属执行行 ID(→pull_task_group_execution.id)。 */
    private Long groupExecutionId;

    /** 账号 ID(→account.id)。 */
    private Long accountId;

    /** 账号号码展示快照。 */
    private String accountPhone;

    /** 角色，取值见 PullTaskGroupAccountRole。 */
    private Integer roleType;

    /** 同角色内顺序；人工补充时递增。 */
    private Integer roleSeq;

    /** 来源：1=初始选择 2=人工补充。 */
    private Integer sourceType;

    /** 选号方式：1=自动 2=手动。 */
    private Integer selectionMode;

    /** 进群方式：1=踩链接 2=管理员邀请 3=拉手拉入；站台补充为 null。 */
    private Integer entryMode;

    /** 在群状态，取值见 PullTaskGroupAccountMembershipStatus。 */
    private Integer membershipStatus;

    /** 确认在群时间(epoch 毫秒)。 */
    private Long joinedAt;

    /** 站台由哪次拉人调用拉入(→pull_task_pull_call.id)。 */
    private Long pullCallId;

    /** 群管理员权限状态：0=不适用 1=待设置 2=已提交 3=成功 4=失败 5=结果未知；仅管理角色有意义。 */
    private Integer adminStatus;

    /** 可用性，取值见 PullTaskGroupAccountAvailability。 */
    private Integer availabilityStatus;

    /** 不可用原因码。 */
    private String unavailableReasonCode;

    /** 风控冷却到期时间(epoch 毫秒)。 */
    private Long cooldownUntil;

    /** 拉手占用开始时间(epoch 毫秒)。 */
    private Long occupiedAt;

    /** 拉手占用释放时间(epoch 毫秒)；null 表示当前占用中。 */
    private Long releasedAt;

    /** 创建时间(epoch 毫秒)。 */
    private Long createdAt;

    /** 更新时间(epoch 毫秒)。 */
    private Long updatedAt;
```

`occupancy_key` 是数据库生成列，**实体不映射**。

创建 `armada-api/src/main/java/com/armada/task/model/vo/PullTaskGroupAccountRoleCount.java`：

```java
package com.armada.task.model.vo;

/**
 * 执行行内按角色统计的当前可用账号数。
 *
 * <p>详情页的"当前可用拉手数 / 计划拉手数"由本投影现算，执行行上不存资源快照列——
 * 快照列要在六个写路径上同步计数器，且随时可能与明细不一致。</p>
 */
public class PullTaskGroupAccountRoleCount {

    /** 角色，取值见 PullTaskGroupAccountRole。 */
    private Integer roleType;

    /** 该角色当前可用账号数。 */
    private Integer availableCount;

    public Integer getRoleType() {
        return roleType;
    }

    public void setRoleType(Integer roleType) {
        this.roleType = roleType;
    }

    public Integer getAvailableCount() {
        return availableCount;
    }

    public void setAvailableCount(Integer availableCount) {
        this.availableCount = availableCount;
    }
}
```

- [ ] **Step 3: 写失败的测试**

创建 `armada-api/src/test/java/com/armada/task/mapper/PullTaskGroupAccountMapperInMemoryTest.java`。

```java
package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.vo.PullTaskGroupAccountRoleCount;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 角色账号 Mapper 的 H2 MySQL 模式测试：拉手跨任务互斥、释放与重新占用。 */
@SpringJUnitConfig(PullTaskGroupAccountMapperInMemoryTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskGroupAccountMapperInMemoryTest {

    private static final long EXEC_A = 501L;
    private static final long EXEC_B = 502L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PullTaskGroupAccountMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void samePullerCannotServeTwoExecutionRowsAtOnce() {
        mapper.insert(role(100L, EXEC_A, 900L, PullTaskGroupAccountRole.PULLER, 1));

        // 另一个父任务的执行行想占同一个拉手账号：唯一键直接拒绝。
        assertThatThrownBy(() ->
                mapper.insert(role(200L, EXEC_B, 900L, PullTaskGroupAccountRole.PULLER, 1)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void releasingAPullerLetsAnotherTaskTakeIt() {
        PullTaskGroupAccount first = role(100L, EXEC_A, 900L, PullTaskGroupAccountRole.PULLER, 1);
        mapper.insert(first);

        assertThat(mapper.releasePuller(first.getId(), 800L)).isEqualTo(1);

        // 释放后 occupancy_key 变 NULL，不再参与唯一约束。
        mapper.insert(role(200L, EXEC_B, 900L, PullTaskGroupAccountRole.PULLER, 1));
        assertThat(mapper.selectByExecutionAndRole(EXEC_B, PullTaskGroupAccountRole.PULLER.code()))
                .hasSize(1);
    }

    @Test
    void reoccupyFailsWhenAnotherTaskAlreadyTookTheAccount() {
        PullTaskGroupAccount first = role(100L, EXEC_A, 900L, PullTaskGroupAccountRole.PULLER, 1);
        mapper.insert(first);
        mapper.releasePuller(first.getId(), 800L);
        mapper.insert(role(200L, EXEC_B, 900L, PullTaskGroupAccountRole.PULLER, 1));

        // 恢复执行时重新竞争拉手；已被别人占走就必须失败，让本行进入等待拉手。
        assertThatThrownBy(() -> mapper.reoccupyPuller(first.getId(), 850L))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void reoccupySucceedsWhenAccountIsStillFree() {
        PullTaskGroupAccount first = role(100L, EXEC_A, 900L, PullTaskGroupAccountRole.PULLER, 1);
        mapper.insert(first);
        mapper.releasePuller(first.getId(), 800L);

        assertThat(mapper.reoccupyPuller(first.getId(), 850L)).isEqualTo(1);
        assertThatThrownBy(() ->
                mapper.insert(role(200L, EXEC_B, 900L, PullTaskGroupAccountRole.PULLER, 1)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void managersAndStationsNeverOccupyAcrossExecutions() {
        mapper.insert(role(100L, EXEC_A, 910L, PullTaskGroupAccountRole.MANAGER, 1));
        mapper.insert(role(100L, EXEC_B, 910L, PullTaskGroupAccountRole.MANAGER, 1));
        mapper.insert(role(100L, EXEC_A, 920L, PullTaskGroupAccountRole.STATION, 1));
        mapper.insert(role(100L, EXEC_B, 920L, PullTaskGroupAccountRole.STATION, 1));

        // 管理账号要进每一条执行行；站台允许跨执行行复用。
        assertThat(mapper.selectByExecutionAndRole(EXEC_A, PullTaskGroupAccountRole.MANAGER.code()))
                .hasSize(1);
        assertThat(mapper.selectByExecutionAndRole(EXEC_B, PullTaskGroupAccountRole.STATION.code()))
                .hasSize(1);
    }

    @Test
    void sameStationCannotEnterTheSameExecutionTwice() {
        mapper.insert(role(100L, EXEC_A, 920L, PullTaskGroupAccountRole.STATION, 1));

        assertThatThrownBy(() ->
                mapper.insert(role(100L, EXEC_A, 920L, PullTaskGroupAccountRole.STATION, 2)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void releaseAllPullersOfExecutionFreesEveryActiveOccupation() {
        mapper.insert(role(100L, EXEC_A, 900L, PullTaskGroupAccountRole.PULLER, 1));
        mapper.insert(role(100L, EXEC_A, 901L, PullTaskGroupAccountRole.PULLER, 2));
        mapper.insert(role(100L, EXEC_A, 910L, PullTaskGroupAccountRole.MANAGER, 1));

        // 执行行暂停或进入资源等待时释放全部拉手，管理角色不受影响。
        assertThat(mapper.releaseAllPullersOfExecution(EXEC_A, 800L)).isEqualTo(2);
        mapper.insert(role(200L, EXEC_B, 900L, PullTaskGroupAccountRole.PULLER, 1));
        assertThat(mapper.selectByExecutionAndRole(EXEC_B, PullTaskGroupAccountRole.PULLER.code()))
                .hasSize(1);
    }

    @Test
    void availableCountIsComputedPerRole() {
        mapper.insert(role(100L, EXEC_A, 900L, PullTaskGroupAccountRole.PULLER, 1));
        PullTaskGroupAccount cooled = role(100L, EXEC_A, 901L, PullTaskGroupAccountRole.PULLER, 2);
        mapper.insert(cooled);
        mapper.insert(role(100L, EXEC_A, 910L, PullTaskGroupAccountRole.MANAGER, 1));

        mapper.markUnavailable(cooled.getId(),
                PullTaskGroupAccountAvailability.RISK_COOLDOWN.code(), "RISK", 5000L, 800L);

        List<PullTaskGroupAccountRoleCount> counts = mapper.countAvailableByRole(EXEC_A);
        assertThat(counts)
                .extracting(PullTaskGroupAccountRoleCount::getRoleType,
                            PullTaskGroupAccountRoleCount::getAvailableCount)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(1, 1),
                        org.assertj.core.groups.Tuple.tuple(2, 1));
    }

    @Test
    void updateMembershipRecordsJoinResult() {
        PullTaskGroupAccount manager = role(100L, EXEC_A, 910L, PullTaskGroupAccountRole.MANAGER, 1);
        mapper.insert(manager);

        mapper.updateMembership(manager.getId(),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code(), 950L, 950L);

        PullTaskGroupAccount saved =
                mapper.selectByExecutionAndRole(EXEC_A, PullTaskGroupAccountRole.MANAGER.code()).get(0);
        assertThat(saved.getMembershipStatus())
                .isEqualTo(PullTaskGroupAccountMembershipStatus.IN_GROUP.code());
        assertThat(saved.getJoinedAt()).isEqualTo(950L);
    }

    @Test
    void otherTenantRoleRowsAreInvisible() {
        mapper.insert(role(100L, EXEC_A, 900L, PullTaskGroupAccountRole.PULLER, 1));

        TenantContext.set(8L);
        assertThat(mapper.selectByExecutionAndRole(EXEC_A, PullTaskGroupAccountRole.PULLER.code()))
                .isEmpty();
        assertThat(mapper.releaseAllPullersOfExecution(EXEC_A, 800L)).isZero();
    }

    private PullTaskGroupAccount role(long taskId, long executionId, long accountId,
                                      PullTaskGroupAccountRole roleType, int roleSeq) {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setTaskId(taskId);
        row.setGroupExecutionId(executionId);
        row.setAccountId(accountId);
        row.setAccountPhone("86138" + accountId);
        row.setRoleType(roleType.code());
        row.setRoleSeq(roleSeq);
        row.setSourceType(1);
        row.setSelectionMode(1);
        row.setEntryMode(roleType == PullTaskGroupAccountRole.STATION ? null : 1);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        if (roleType == PullTaskGroupAccountRole.PULLER) {
            row.setOccupiedAt(100L);
        }
        return row;
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_group_account_test");
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(
                    dataSource, interceptor, "mapper/task/PullTaskGroupAccountMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        PullTaskGroupAccountMapper pullTaskGroupAccountMapper(
                SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(PullTaskGroupAccountMapper.class);
        }
    }
}
```

- [ ] **Step 4: 运行测试确认失败**

```bash
cd armada-api && mvn -q -Dtest='PullTaskGroupAccountMapperInMemoryTest' -DfailIfNoTests=false test
```

Expected: 编译失败 —— `PullTaskGroupAccountMapper` 不存在。

- [ ] **Step 5: 写 Mapper 接口**

创建 `armada-api/src/main/java/com/armada/task/mapper/PullTaskGroupAccountMapper.java`。

```java
package com.armada.task.mapper;

import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.vo.PullTaskGroupAccountRoleCount;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 执行行角色账号数据访问层。 */
@Mapper
public interface PullTaskGroupAccountMapper {

    /**
     * 为执行行选中一个角色账号。
     *
     * <p>拉手行插入即取得跨任务占用：生成列 {@code occupancy_key} 在
     * {@code role_type=2 且 released_at IS NULL} 时取账号 ID，唯一键保证同一账号同时
     * 只服务一条执行行（ADR-0008）。冲突时抛
     * {@link org.springframework.dao.DuplicateKeyException}，调用方应把它当作
     * "该拉手已被占用"的预期路径处理，让本行进入等待拉手，不得当系统错误上抛。</p>
     *
     * @param row 角色账号；写入后回填 id
     * @return 新增行数
     */
    int insert(PullTaskGroupAccount row);

    /**
     * 读取执行行内某个角色的全部账号，按 roleSeq 升序。
     *
     * @param groupExecutionId 执行行 ID
     * @param roleType 角色，取值见 PullTaskGroupAccountRole
     * @return 角色账号列表
     */
    List<PullTaskGroupAccount> selectByExecutionAndRole(
            @Param("groupExecutionId") long groupExecutionId,
            @Param("roleType") int roleType);

    /**
     * 按角色统计执行行当前可用账号数，供详情页现算"当前数 / 计划数"。
     *
     * @param groupExecutionId 执行行 ID
     * @return 每个角色一行；没有可用账号的角色不出现在结果里
     */
    List<PullTaskGroupAccountRoleCount> countAvailableByRole(
            @Param("groupExecutionId") long groupExecutionId);

    /**
     * 释放单个拉手的跨任务占用。
     *
     * @param id 角色账号行 ID
     * @param now 释放时间(epoch 毫秒)
     * @return 实际释放行数
     */
    int releasePuller(@Param("id") long id, @Param("now") long now);

    /**
     * 执行行恢复时重新占用原拉手。
     *
     * <p>该账号已被其他执行行占走时抛
     * {@link org.springframework.dao.DuplicateKeyException}，这是"恢复时重新竞争拉手"
     * 的预期结果，调用方据此让本行继续等待。</p>
     *
     * @param id 角色账号行 ID
     * @param now 重新占用时间(epoch 毫秒)
     * @return 实际更新行数
     */
    int reoccupyPuller(@Param("id") long id, @Param("now") long now);

    /**
     * 释放执行行下全部仍在占用中的拉手。
     *
     * <p>执行行完成、失败、被人工暂停或进入资源等待时调用。管理与站台角色不参与占用，
     * 不受影响。</p>
     *
     * @param groupExecutionId 执行行 ID
     * @param now 释放时间(epoch 毫秒)
     * @return 实际释放行数
     */
    int releaseAllPullersOfExecution(@Param("groupExecutionId") long groupExecutionId,
                                     @Param("now") long now);

    /**
     * 标记账号在本执行行不可用。
     *
     * @param id 角色账号行 ID
     * @param availabilityStatus 可用性，取值见 PullTaskGroupAccountAvailability
     * @param reasonCode 不可用原因码
     * @param cooldownUntil 风控冷却到期时间(epoch 毫秒)；非冷却场景传 null
     * @param now 更新时间(epoch 毫秒)
     * @return 实际更新行数
     */
    int markUnavailable(@Param("id") long id,
                        @Param("availabilityStatus") int availabilityStatus,
                        @Param("reasonCode") String reasonCode,
                        @Param("cooldownUntil") Long cooldownUntil,
                        @Param("now") long now);

    /**
     * 回写账号的在群状态。
     *
     * @param id 角色账号行 ID
     * @param membershipStatus 在群状态，取值见 PullTaskGroupAccountMembershipStatus
     * @param joinedAt 确认在群时间(epoch 毫秒)；非成功场景传 null
     * @param now 更新时间(epoch 毫秒)
     * @return 实际更新行数
     */
    int updateMembership(@Param("id") long id,
                         @Param("membershipStatus") int membershipStatus,
                         @Param("joinedAt") Long joinedAt,
                         @Param("now") long now);
}
```

- [ ] **Step 6: 写 Mapper XML**

创建 `armada-api/src/main/resources/mapper/task/PullTaskGroupAccountMapper.xml`。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.armada.task.mapper.PullTaskGroupAccountMapper">

  <!-- tenant_id 由租户拦截器注入；occupancy_key 是生成列，永不显式写入。 -->
  <insert id="insert" useGeneratedKeys="true" keyProperty="id">
    INSERT INTO pull_task_group_account (
      task_id, group_execution_id, account_id, account_phone, role_type, role_seq,
      source_type, selection_mode, entry_mode, membership_status, admin_status,
      availability_status, occupied_at, created_at, updated_at
    ) VALUES (
      #{taskId}, #{groupExecutionId}, #{accountId}, #{accountPhone}, #{roleType}, #{roleSeq},
      #{sourceType}, #{selectionMode}, #{entryMode}, 0,
      <choose><when test="roleType == 1">1</when><otherwise>0</otherwise></choose>,
      1, #{occupiedAt}, #{createdAt}, #{updatedAt}
    )
  </insert>

  <select id="selectByExecutionAndRole"
          resultType="com.armada.task.model.entity.PullTaskGroupAccount">
    SELECT *
    FROM pull_task_group_account
    WHERE group_execution_id = #{groupExecutionId}
      AND role_type = #{roleType}
    ORDER BY role_seq ASC
  </select>

  <!-- 详情页的"当前可用数 / 计划数"现算，不在执行行上存资源快照列。 -->
  <select id="countAvailableByRole"
          resultType="com.armada.task.model.vo.PullTaskGroupAccountRoleCount">
    SELECT role_type AS roleType, COUNT(*) AS availableCount
    FROM pull_task_group_account
    WHERE group_execution_id = #{groupExecutionId}
      AND availability_status = 1
    GROUP BY role_type
  </select>

  <update id="releasePuller">
    UPDATE pull_task_group_account
    SET released_at = #{now},
        updated_at = #{now}
    WHERE id = #{id}
      AND role_type = 2
      AND released_at IS NULL
  </update>

  <!-- released_at 置回 NULL 会让 occupancy_key 重新取值；账号已被别人占走时唯一键抛错，
       这是"恢复时重新竞争拉手"的预期路径。 -->
  <update id="reoccupyPuller">
    UPDATE pull_task_group_account
    SET released_at = NULL,
        occupied_at = #{now},
        updated_at = #{now}
    WHERE id = #{id}
      AND role_type = 2
      AND released_at IS NOT NULL
  </update>

  <update id="releaseAllPullersOfExecution">
    UPDATE pull_task_group_account
    SET released_at = #{now},
        updated_at = #{now}
    WHERE group_execution_id = #{groupExecutionId}
      AND role_type = 2
      AND released_at IS NULL
  </update>

  <update id="markUnavailable">
    UPDATE pull_task_group_account
    SET availability_status = #{availabilityStatus},
        unavailable_reason_code = #{reasonCode},
        cooldown_until = #{cooldownUntil},
        updated_at = #{now}
    WHERE id = #{id}
  </update>

  <update id="updateMembership">
    UPDATE pull_task_group_account
    SET membership_status = #{membershipStatus},
        joined_at = #{joinedAt},
        updated_at = #{now}
    WHERE id = #{id}
  </update>

</mapper>
```

- [ ] **Step 7: 校验 XML 并运行测试**

```bash
cd armada-api && xmllint --noout src/main/resources/mapper/task/PullTaskGroupAccountMapper.xml \
  && mvn -q -Dtest='PullTaskGroupAccountMapperInMemoryTest' -DfailIfNoTests=false test
```

Expected: PASS，10 个测试全绿。

`samePullerCannotServeTwoExecutionRowsAtOnce` 和 `reoccupyFailsWhenAnotherTaskAlreadyTookTheAccount` 是 ADR-0008 的唯一本地门禁，失败必须查生成列表达式和唯一键，**不得改测试绕过**。

- [ ] **Step 8: 提交**

```bash
git add armada-api/src/main/java/com/armada/task/model/enums/PullTaskGroupAccount*.java \
        armada-api/src/main/java/com/armada/task/model/entity/PullTaskGroupAccount.java \
        armada-api/src/main/java/com/armada/task/model/vo/PullTaskGroupAccountRoleCount.java \
        armada-api/src/main/java/com/armada/task/mapper/PullTaskGroupAccountMapper.java \
        armada-api/src/main/resources/mapper/task/PullTaskGroupAccountMapper.xml \
        armada-api/src/test/java/com/armada/task/mapper/PullTaskGroupAccountMapperInMemoryTest.java
git commit -m "feat: 新增执行行角色账号 Mapper

拉手跨任务互斥由 occupancy_key 生成列 + 部分唯一索引保证,不另设租约表;
释放后可被其他任务占走,恢复时重新竞争失败即进入等待拉手。可用账号数
现算,不在执行行上存资源快照列。"
```

---

## Task 8: `pull_task_account_action` 实体、枚举与 Mapper

**Files:**
- Create: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskAccountActionType.java`
- Create: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskActionStatus.java`
- Create: `armada-api/src/main/java/com/armada/task/model/entity/PullTaskAccountAction.java`
- Create: `armada-api/src/main/java/com/armada/task/mapper/PullTaskAccountActionMapper.java`
- Create: `armada-api/src/main/resources/mapper/task/PullTaskAccountActionMapper.xml`
- Create: `armada-api/src/test/java/com/armada/task/mapper/PullTaskAccountActionMapperInMemoryTest.java`

**Interfaces:**
- Consumes: Task 2 的 H2 基座
- Produces:
  - `PullTaskAccountActionType.SAVE_CONTACT(1)/INVITE_TO_GROUP(2)/JOIN_BY_LINK(3)`
  - `PullTaskActionStatus.PENDING(1)/SUBMITTED(2)/SUCCESS(3)/FAILED(4)/UNKNOWN(5)/CANCELED(6)`（Task 9 的 `pull_call` 不复用它，见 Task 9）
  - `PullTaskAccountAction`（POJO）
  - `PullTaskAccountActionMapper#insertIfAbsent(PullTaskAccountAction row)` → `int`（0 = 已存在，幂等）
  - `#selectPending(long groupExecutionId)` → `List<PullTaskAccountAction>`
  - `#selectByExecutionAndType(long groupExecutionId, int actionType)` → `List<PullTaskAccountAction>`
  - `#markSubmitted(long id, String commandId, long now)` → `int`
  - `#writeBackResult(long id, int actionStatus, String reasonCode, String reasonMessage, long now)` → `int`
  - `#selectByCommandId(String commandId)` → `PullTaskAccountAction`

- [ ] **Step 1: 写两个枚举**

创建 `PullTaskAccountActionType.java`：

```java
package com.armada.task.model.enums;

/** 执行行内的账号动作类型；与 pull_task_account_action.action_type 一一对应。 */
public enum PullTaskAccountActionType {

    /** 保存联系人：单方向动作，双向加好友由 actor/target 互换的两行表达。 */
    SAVE_CONTACT(1),
    /** 邀请入群：管理账号邀请拉手，或补充管理员时由现有管理员邀请。 */
    INVITE_TO_GROUP(2),
    /** 踩链接入群：账号自行通过群链接进入，actor 写目标账号自身 ID。 */
    JOIN_BY_LINK(3);

    private final int code;

    PullTaskAccountActionType(int code) {
        this.code = code;
    }

    /**
     * 数据库存储值。
     *
     * @return TINYINT 取值
     */
    public int code() {
        return code;
    }
}
```

创建 `PullTaskActionStatus.java`：

```java
package com.armada.task.model.enums;

/** 账号动作结果；与 pull_task_account_action.action_status 一一对应。 */
public enum PullTaskActionStatus {

    /** 待执行：动作行已建，命令尚未发出。 */
    PENDING(1),
    /** 已提交：协议命令已发出，等待结果。 */
    SUBMITTED(2),
    /** 成功：协议确认成功。 */
    SUCCESS(3),
    /** 失败：明确失败，终态；加好友失败不阻断后续邀请或拉人。 */
    FAILED(4),
    /** 结果未知：由查询或回调收敛，不得伪装成成功或失败。 */
    UNKNOWN(5),
    /** 取消：任务结束时尚未发出的动作。 */
    CANCELED(6);

    private final int code;

    PullTaskActionStatus(int code) {
        this.code = code;
    }

    /**
     * 数据库存储值。
     *
     * @return TINYINT 取值
     */
    public int code() {
        return code;
    }
}
```

- [ ] **Step 2: 写实体**

创建 `armada-api/src/main/java/com/armada/task/model/entity/PullTaskAccountAction.java`，类注释 `/** 执行行内的账号动作（联系人、邀请、踩链接），映射 {@code pull_task_account_action}。 */`。字段声明：

```java
    /** 账号动作主键。 */
    private Long id;

    /** 所属租户 ID。 */
    private Long tenantId;

    /** 拉群任务 ID(→pull_task.id)。 */
    private Long taskId;

    /** 所属执行行 ID(→pull_task_group_execution.id)。 */
    private Long groupExecutionId;

    /** 动作类型，取值见 PullTaskAccountActionType。 */
    private Integer actionType;

    /** 动作发起方角色行 ID；踩链接入群时为目标账号自身 ID(MySQL 唯一索引中 NULL 互不相等，留空会让幂等键失效)。 */
    private Long actorGroupAccountId;

    /** 动作对象角色行 ID(→pull_task_group_account.id)。 */
    private Long targetGroupAccountId;

    /** 动作结果，取值见 PullTaskActionStatus。 */
    private Integer actionStatus;

    /** 协议命令 ID；回调按此定位。 */
    private String commandId;

    /** 失败原因码。 */
    private String reasonCode;

    /** 失败原因描述(已脱敏)。 */
    private String reasonMessage;

    /** 命令提交时间(epoch 毫秒)。 */
    private Long submittedAt;

    /** 结果回写时间(epoch 毫秒)。 */
    private Long resultAt;

    /** 创建时间(epoch 毫秒)。 */
    private Long createdAt;

    /** 更新时间(epoch 毫秒)。 */
    private Long updatedAt;
```

- [ ] **Step 3: 写失败的测试**

创建 `armada-api/src/test/java/com/armada/task/mapper/PullTaskAccountActionMapperInMemoryTest.java`。测试配置块与 Task 7 同构，只是 Mapper 类型和库名不同。

```java
package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 账号动作 Mapper 的 H2 MySQL 模式测试：动作幂等、双向独立与回调定位。 */
@SpringJUnitConfig(PullTaskAccountActionMapperInMemoryTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskAccountActionMapperInMemoryTest {

    private static final long EXECUTION = 501L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PullTaskAccountActionMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void repeatedInsertOfTheSameActionIsAbsorbed() {
        PullTaskAccountAction action =
                action(PullTaskAccountActionType.SAVE_CONTACT, 11L, 22L);
        assertThat(mapper.insertIfAbsent(action)).isEqualTo(1);

        // 服务重启后重放同一步：唯一键吸收，不产生第二行也不发第二次命令。
        assertThat(mapper.insertIfAbsent(action(PullTaskAccountActionType.SAVE_CONTACT, 11L, 22L)))
                .isZero();
        assertThat(mapper.selectByExecutionAndType(
                EXECUTION, PullTaskAccountActionType.SAVE_CONTACT.code())).hasSize(1);
    }

    @Test
    void twoDirectionsOfTheSameContactPairAreIndependentRows() {
        mapper.insertIfAbsent(action(PullTaskAccountActionType.SAVE_CONTACT, 11L, 22L));
        mapper.insertIfAbsent(action(PullTaskAccountActionType.SAVE_CONTACT, 22L, 11L));

        // 双向加好友是 actor/target 互换的两行，各自独立记录结果。
        assertThat(mapper.selectByExecutionAndType(
                EXECUTION, PullTaskAccountActionType.SAVE_CONTACT.code())).hasSize(2);
    }

    @Test
    void joinByLinkUsesSelfAsActorSoTheIdempotencyKeyWorks() {
        // 踩链接没有真正的发起方，但 actor 必须写目标自身 ID：
        // MySQL 唯一索引中 NULL 互不相等，留空会让同一账号可以无限重复插入。
        assertThat(mapper.insertIfAbsent(action(PullTaskAccountActionType.JOIN_BY_LINK, 33L, 33L)))
                .isEqualTo(1);
        assertThat(mapper.insertIfAbsent(action(PullTaskAccountActionType.JOIN_BY_LINK, 33L, 33L)))
                .isZero();
    }

    @Test
    void pendingActionsExcludeFinishedOnes() {
        PullTaskAccountAction first =
                action(PullTaskAccountActionType.SAVE_CONTACT, 11L, 22L);
        mapper.insertIfAbsent(first);
        mapper.insertIfAbsent(action(PullTaskAccountActionType.INVITE_TO_GROUP, 11L, 22L));

        Long firstId = mapper.selectByExecutionAndType(
                EXECUTION, PullTaskAccountActionType.SAVE_CONTACT.code()).get(0).getId();
        mapper.markSubmitted(firstId, "cmd-1", 800L);
        mapper.writeBackResult(firstId, PullTaskActionStatus.FAILED.code(),
                "PRIVACY", "对方隐私设置", 850L);

        assertThat(mapper.selectPending(EXECUTION))
                .extracting(PullTaskAccountAction::getActionType)
                .containsExactly(PullTaskAccountActionType.INVITE_TO_GROUP.code());
    }

    @Test
    void callbackIsLocatedByCommandId() {
        mapper.insertIfAbsent(action(PullTaskAccountActionType.INVITE_TO_GROUP, 11L, 22L));
        Long id = mapper.selectPending(EXECUTION).get(0).getId();
        mapper.markSubmitted(id, "cmd-invite-1", 800L);

        PullTaskAccountAction found = mapper.selectByCommandId("cmd-invite-1");
        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(id);
        assertThat(found.getActionStatus()).isEqualTo(PullTaskActionStatus.SUBMITTED.code());
        assertThat(found.getSubmittedAt()).isEqualTo(800L);
    }

    @Test
    void unknownResultIsStoredDistinctFromFailure() {
        mapper.insertIfAbsent(action(PullTaskAccountActionType.SAVE_CONTACT, 11L, 22L));
        Long id = mapper.selectPending(EXECUTION).get(0).getId();
        mapper.markSubmitted(id, "cmd-2", 800L);

        mapper.writeBackResult(id, PullTaskActionStatus.UNKNOWN.code(), "TIMEOUT", "协议超时", 850L);

        assertThat(mapper.selectByCommandId("cmd-2").getActionStatus())
                .isEqualTo(PullTaskActionStatus.UNKNOWN.code());
    }

    @Test
    void otherTenantActionsAreInvisible() {
        mapper.insertIfAbsent(action(PullTaskAccountActionType.SAVE_CONTACT, 11L, 22L));

        TenantContext.set(8L);
        assertThat(mapper.selectPending(EXECUTION)).isEmpty();
        assertThat(mapper.selectByCommandId("cmd-1")).isNull();
    }

    private PullTaskAccountAction action(PullTaskAccountActionType type, long actor, long target) {
        PullTaskAccountAction row = new PullTaskAccountAction();
        row.setTaskId(100L);
        row.setGroupExecutionId(EXECUTION);
        row.setActionType(type.code());
        row.setActorGroupAccountId(actor);
        row.setTargetGroupAccountId(target);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_account_action_test");
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(
                    dataSource, interceptor, "mapper/task/PullTaskAccountActionMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        PullTaskAccountActionMapper pullTaskAccountActionMapper(
                SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(PullTaskAccountActionMapper.class);
        }
    }
}
```

- [ ] **Step 4: 运行测试确认失败**

```bash
cd armada-api && mvn -q -Dtest='PullTaskAccountActionMapperInMemoryTest' -DfailIfNoTests=false test
```

Expected: 编译失败 —— `PullTaskAccountActionMapper` 不存在。

- [ ] **Step 5: 写 Mapper 接口**

创建 `armada-api/src/main/java/com/armada/task/mapper/PullTaskAccountActionMapper.java`。

```java
package com.armada.task.mapper;

import com.armada.task.model.entity.PullTaskAccountAction;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 执行行账号动作数据访问层。 */
@Mapper
public interface PullTaskAccountActionMapper {

    /**
     * 幂等地建立一个账号动作行。
     *
     * <p>唯一键 {@code (tenant_id, group_execution_id, action_type,
     * actor_group_account_id, target_group_account_id)} 本身就是幂等键，因此不设
     * requestId 列。服务重启后重放同一步会返回 0，调用方据此跳过，不重复发命令。</p>
     *
     * <p>踩链接入群没有真正的发起方，但 {@code actor_group_account_id} 仍必须写
     * 目标账号自身 ID：MySQL 唯一索引中 NULL 之间互不相等，留空会让同一账号的
     * 踩链接动作可以无限重复插入，幂等键形同虚设。</p>
     *
     * @param row 动作行；写入后回填 id
     * @return 新增行数；0 表示该动作已存在
     */
    int insertIfAbsent(PullTaskAccountAction row);

    /**
     * 取执行行内待执行的动作，按 id 升序。
     *
     * @param groupExecutionId 执行行 ID
     * @return 待执行动作
     */
    List<PullTaskAccountAction> selectPending(@Param("groupExecutionId") long groupExecutionId);

    /**
     * 读取执行行内某一类动作的全部记录。
     *
     * @param groupExecutionId 执行行 ID
     * @param actionType 动作类型，取值见 PullTaskAccountActionType
     * @return 动作记录，按 id 升序
     */
    List<PullTaskAccountAction> selectByExecutionAndType(
            @Param("groupExecutionId") long groupExecutionId,
            @Param("actionType") int actionType);

    /**
     * 标记动作命令已提交。
     *
     * @param id 动作行 ID
     * @param commandId 协议命令 ID
     * @param now 提交时间(epoch 毫秒)
     * @return 实际更新行数；0 表示该动作已不在待执行状态
     */
    int markSubmitted(@Param("id") long id,
                      @Param("commandId") String commandId,
                      @Param("now") long now);

    /**
     * 回写动作结果。
     *
     * @param id 动作行 ID
     * @param actionStatus 动作结果，取值见 PullTaskActionStatus
     * @param reasonCode 失败原因码
     * @param reasonMessage 失败原因描述(已脱敏)
     * @param now 回写时间(epoch 毫秒)
     * @return 实际更新行数
     */
    int writeBackResult(@Param("id") long id,
                        @Param("actionStatus") int actionStatus,
                        @Param("reasonCode") String reasonCode,
                        @Param("reasonMessage") String reasonMessage,
                        @Param("now") long now);

    /**
     * 协议回调按命令 ID 定位动作行。
     *
     * @param commandId 协议命令 ID
     * @return 动作行；不存在或不属于当前租户时为 null
     */
    PullTaskAccountAction selectByCommandId(@Param("commandId") String commandId);
}
```

- [ ] **Step 6: 写 Mapper XML**

创建 `armada-api/src/main/resources/mapper/task/PullTaskAccountActionMapper.xml`。

`INSERT IGNORE` 在 H2 MySQL 模式下受支持；若实测不支持，改用 `INSERT ... SELECT ... WHERE NOT EXISTS` 并同步保留唯一键兜底。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.armada.task.mapper.PullTaskAccountActionMapper">

  <!-- tenant_id 由租户拦截器注入；唯一键即幂等键，重放返回 0 行。 -->
  <insert id="insertIfAbsent" useGeneratedKeys="true" keyProperty="id">
    INSERT IGNORE INTO pull_task_account_action (
      task_id, group_execution_id, action_type,
      actor_group_account_id, target_group_account_id,
      action_status, created_at, updated_at
    ) VALUES (
      #{taskId}, #{groupExecutionId}, #{actionType},
      #{actorGroupAccountId}, #{targetGroupAccountId},
      1, #{createdAt}, #{updatedAt}
    )
  </insert>

  <select id="selectPending" resultType="com.armada.task.model.entity.PullTaskAccountAction">
    SELECT *
    FROM pull_task_account_action
    WHERE group_execution_id = #{groupExecutionId}
      AND action_status = 1
    ORDER BY id ASC
  </select>

  <select id="selectByExecutionAndType"
          resultType="com.armada.task.model.entity.PullTaskAccountAction">
    SELECT *
    FROM pull_task_account_action
    WHERE group_execution_id = #{groupExecutionId}
      AND action_type = #{actionType}
    ORDER BY id ASC
  </select>

  <update id="markSubmitted">
    UPDATE pull_task_account_action
    SET action_status = 2,
        command_id = #{commandId},
        submitted_at = #{now},
        updated_at = #{now}
    WHERE id = #{id}
      AND action_status = 1
  </update>

  <update id="writeBackResult">
    UPDATE pull_task_account_action
    SET action_status = #{actionStatus},
        reason_code = #{reasonCode},
        reason_message = #{reasonMessage},
        result_at = #{now},
        updated_at = #{now}
    WHERE id = #{id}
  </update>

  <select id="selectByCommandId" resultType="com.armada.task.model.entity.PullTaskAccountAction">
    SELECT *
    FROM pull_task_account_action
    WHERE command_id = #{commandId}
  </select>

</mapper>
```

- [ ] **Step 7: 校验 XML 并运行测试**

```bash
cd armada-api && xmllint --noout src/main/resources/mapper/task/PullTaskAccountActionMapper.xml \
  && mvn -q -Dtest='PullTaskAccountActionMapperInMemoryTest' -DfailIfNoTests=false test
```

Expected: PASS，7 个测试全绿。

`joinByLinkUsesSelfAsActorSoTheIdempotencyKeyWorks` 验的是已经定死的口径：`actor_group_account_id` 是 `NOT NULL`，踩链接入群写目标账号自身 ID。**不要**把它改回 NULL——MySQL 唯一索引中 NULL 之间互不相等，留空会让同一账号的踩链接动作无限重复插入，幂等键形同虚设，而 H2 上这个缺陷同样测不出来。

- [ ] **Step 8: 提交**

```bash
git add armada-api/src/main/java/com/armada/task/model/enums/PullTaskAccountActionType.java \
        armada-api/src/main/java/com/armada/task/model/enums/PullTaskActionStatus.java \
        armada-api/src/main/java/com/armada/task/model/entity/PullTaskAccountAction.java \
        armada-api/src/main/java/com/armada/task/mapper/PullTaskAccountActionMapper.java \
        armada-api/src/main/resources/mapper/task/PullTaskAccountActionMapper.xml \
        armada-api/src/test/java/com/armada/task/mapper/PullTaskAccountActionMapperInMemoryTest.java
git commit -m "feat: 新增账号动作 Mapper

合并原联系人表与邀请表,并覆盖踩链接入群。唯一键即幂等键,重启重放
不产生第二行;command_id 唯一键供协议回调定位。"
```

---

## Task 9: `pull_task_pull_call` 实体、枚举与 Mapper

**Files:**
- Create: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskPullCallStatus.java`
- Create: `armada-api/src/main/java/com/armada/task/model/entity/PullTaskPullCall.java`
- Create: `armada-api/src/main/java/com/armada/task/mapper/PullTaskPullCallMapper.java`
- Create: `armada-api/src/main/resources/mapper/task/PullTaskPullCallMapper.xml`
- Create: `armada-api/src/test/java/com/armada/task/mapper/PullTaskPullCallMapperInMemoryTest.java`

**Interfaces:**
- Consumes: Task 2 的 H2 基座
- Produces:
  - `PullTaskPullCallStatus.PLANNED(1)/SUBMITTED(2)/WRITTEN_BACK(3)/UNKNOWN(4)/CANCELED(5)`
  - `PullTaskPullCall`（POJO）
  - `PullTaskPullCallMapper#insertPlanned(PullTaskPullCall row)` → `int`（`useGeneratedKeys`）
  - `#selectPlannedByExecution(long groupExecutionId)` → `List<PullTaskPullCall>`
  - `#selectLastSubmittedAtByPuller(long pullerAccountId)` → `Long`
  - `#markSubmitted(long id, String commandId, long now)` → `int`
  - `#writeBackResult(long id, int callStatus, String reasonCode, String reasonMessage, long now)` → `int`
  - `#selectByCommandId(String commandId)` → `PullTaskPullCall`

- [ ] **Step 1: 写枚举**

创建 `armada-api/src/main/java/com/armada/task/model/enums/PullTaskPullCallStatus.java`：

```java
package com.armada.task.model.enums;

/** 单次批量加成员调用的状态；与 pull_task_pull_call.call_status 一一对应。 */
public enum PullTaskPullCallStatus {

    /** 计划：调用行、料子绑定和站台绑定已在同一事务内写入，协议命令尚未发出。
     *  崩溃恢复时看到这个状态要用原 idempotency_key 重投，不得重新分配料子。 */
    PLANNED(1),
    /** 已提交：批量加成员命令已发出。 */
    SUBMITTED(2),
    /** 已回写：逐参与者结果已落到料子行和站台行。 */
    WRITTEN_BACK(3),
    /** 结果未知：由查询或回调收敛。 */
    UNKNOWN(4),
    /** 取消：任务结束时尚未发出的调用。 */
    CANCELED(5);

    private final int code;

    PullTaskPullCallStatus(int code) {
        this.code = code;
    }

    /**
     * 数据库存储值。
     *
     * @return TINYINT 取值
     */
    public int code() {
        return code;
    }
}
```

- [ ] **Step 2: 写实体**

创建 `armada-api/src/main/java/com/armada/task/model/entity/PullTaskPullCall.java`，类注释 `/** 一个拉手对同一群 JID 的一次批量加成员请求，映射 {@code pull_task_pull_call}。 */`。字段声明：

```java
    /** 拉人调用主键。 */
    private Long id;

    /** 所属租户 ID。 */
    private Long tenantId;

    /** 拉群任务 ID(→pull_task.id)。 */
    private Long taskId;

    /** 所属执行行 ID(→pull_task_group_execution.id)。 */
    private Long groupExecutionId;

    /** 本执行行内调用序号。 */
    private Integer callSeq;

    /** 执行本次调用的拉手角色行 ID(→pull_task_group_account.id)。 */
    private Long pullerGroupAccountId;

    /** 执行本次调用的拉手账号 ID(→account.id)。 */
    private Long pullerAccountId;

    /** 本次计划料子人数(闭区间随机结果，不含站台)。 */
    private Integer plannedMaterialCount;

    /** 本次计划站台数。 */
    private Integer plannedStationCount;

    /** 调用状态，取值见 PullTaskPullCallStatus。 */
    private Integer callStatus;

    /** 协议命令 ID；回调按此定位。 */
    private String commandId;

    /** 计划阶段生成的幂等键；崩溃恢复用原键重投。 */
    private String idempotencyKey;

    /** 失败原因码。 */
    private String reasonCode;

    /** 失败原因描述(已脱敏)。 */
    private String reasonMessage;

    /** 命令提交时间(epoch 毫秒)。 */
    private Long submittedAt;

    /** 结果回写时间(epoch 毫秒)。 */
    private Long resultAt;

    /** 创建时间(epoch 毫秒)。 */
    private Long createdAt;

    /** 更新时间(epoch 毫秒)。 */
    private Long updatedAt;
```

- [ ] **Step 3: 写失败的测试**

创建 `armada-api/src/test/java/com/armada/task/mapper/PullTaskPullCallMapperInMemoryTest.java`。

```java
package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 拉人调用 Mapper 的 H2 MySQL 模式测试：幂等键、恢复重投与账号级间隔查询。 */
@SpringJUnitConfig(PullTaskPullCallMapperInMemoryTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskPullCallMapperInMemoryTest {

    private static final long EXECUTION = 501L;
    private static final long PULLER_ACCOUNT = 900L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PullTaskPullCallMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void insertPlannedFillsGeneratedIdAndDefaultsToPlanned() {
        PullTaskPullCall call = planned(1, "idem-1");
        mapper.insertPlanned(call);

        assertThat(call.getId()).isNotNull();
        assertThat(mapper.selectPlannedByExecution(EXECUTION))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getCallStatus()).isEqualTo(PullTaskPullCallStatus.PLANNED.code());
                    assertThat(row.getIdempotencyKey()).isEqualTo("idem-1");
                    assertThat(row.getPlannedMaterialCount()).isEqualTo(5);
                    assertThat(row.getPlannedStationCount()).isEqualTo(2);
                });
    }

    @Test
    void duplicateIdempotencyKeyIsRejected() {
        mapper.insertPlanned(planned(1, "idem-1"));

        assertThatThrownBy(() -> mapper.insertPlanned(planned(2, "idem-1")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void duplicateCallSeqWithinOneExecutionIsRejected() {
        mapper.insertPlanned(planned(1, "idem-1"));

        assertThatThrownBy(() -> mapper.insertPlanned(planned(1, "idem-2")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void plannedCallsSurviveRestartForReplayWithTheOriginalKey() {
        mapper.insertPlanned(planned(1, "idem-1"));
        PullTaskPullCall submitted = planned(2, "idem-2");
        mapper.insertPlanned(submitted);
        mapper.markSubmitted(submitted.getId(), "cmd-2", 800L);

        // 只有仍处于"计划"的调用需要恢复重投；已提交的不得重发。
        assertThat(mapper.selectPlannedByExecution(EXECUTION))
                .extracting(PullTaskPullCall::getIdempotencyKey)
                .containsExactly("idem-1");
    }

    @Test
    void lastSubmittedAtDrivesTheAccountLevelInterval() {
        PullTaskPullCall first = planned(1, "idem-1");
        mapper.insertPlanned(first);
        mapper.markSubmitted(first.getId(), "cmd-1", 1000L);

        PullTaskPullCall second = planned(2, "idem-2");
        mapper.insertPlanned(second);
        mapper.markSubmitted(second.getId(), "cmd-2", 1500L);

        // 拉人间隔只约束同一拉手账号的连续调用。
        assertThat(mapper.selectLastSubmittedAtByPuller(PULLER_ACCOUNT)).isEqualTo(1500L);
        assertThat(mapper.selectLastSubmittedAtByPuller(999L)).isNull();
    }

    @Test
    void callbackIsLocatedByCommandId() {
        PullTaskPullCall call = planned(1, "idem-1");
        mapper.insertPlanned(call);
        mapper.markSubmitted(call.getId(), "cmd-1", 1000L);

        mapper.writeBackResult(call.getId(),
                PullTaskPullCallStatus.WRITTEN_BACK.code(), null, null, 1100L);

        PullTaskPullCall found = mapper.selectByCommandId("cmd-1");
        assertThat(found.getId()).isEqualTo(call.getId());
        assertThat(found.getCallStatus()).isEqualTo(PullTaskPullCallStatus.WRITTEN_BACK.code());
        assertThat(found.getResultAt()).isEqualTo(1100L);
    }

    @Test
    void otherTenantCallsAreInvisible() {
        mapper.insertPlanned(planned(1, "idem-1"));

        TenantContext.set(8L);
        assertThat(mapper.selectPlannedByExecution(EXECUTION)).isEmpty();
        assertThat(mapper.selectLastSubmittedAtByPuller(PULLER_ACCOUNT)).isNull();
        assertThat(mapper.selectByCommandId("cmd-1")).isNull();
    }

    private PullTaskPullCall planned(int callSeq, String idempotencyKey) {
        PullTaskPullCall row = new PullTaskPullCall();
        row.setTaskId(100L);
        row.setGroupExecutionId(EXECUTION);
        row.setCallSeq(callSeq);
        row.setPullerGroupAccountId(701L);
        row.setPullerAccountId(PULLER_ACCOUNT);
        row.setPlannedMaterialCount(5);
        row.setPlannedStationCount(2);
        row.setIdempotencyKey(idempotencyKey);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_pull_call_test");
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(
                    dataSource, interceptor, "mapper/task/PullTaskPullCallMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        PullTaskPullCallMapper pullTaskPullCallMapper(SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(PullTaskPullCallMapper.class);
        }
    }
}
```

- [ ] **Step 4: 运行测试确认失败**

```bash
cd armada-api && mvn -q -Dtest='PullTaskPullCallMapperInMemoryTest' -DfailIfNoTests=false test
```

Expected: 编译失败 —— `PullTaskPullCallMapper` 不存在。

- [ ] **Step 5: 写 Mapper 接口**

创建 `armada-api/src/main/java/com/armada/task/mapper/PullTaskPullCallMapper.java`。

```java
package com.armada.task.mapper;

import com.armada.task.model.entity.PullTaskPullCall;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 单次批量加成员调用数据访问层。 */
@Mapper
public interface PullTaskPullCallMapper {

    /**
     * 在提交协议命令之前写入调用行。
     *
     * <p>必须与"把本次料子和站台的 {@code pull_call_id} 指向该调用"在同一个事务内完成，
     * 之后才投递协议命令。崩溃恢复时看到 {@code call_status=1} 的行，用原
     * {@code idempotency_key} 重投，绝不重新分配料子。</p>
     *
     * @param row 调用行；写入后回填 id
     * @return 新增行数
     */
    int insertPlanned(PullTaskPullCall row);

    /**
     * 取执行行下仍处于"计划"状态的调用，供服务重启后重投。
     *
     * @param groupExecutionId 执行行 ID
     * @return 计划中的调用，按 callSeq 升序
     */
    List<PullTaskPullCall> selectPlannedByExecution(
            @Param("groupExecutionId") long groupExecutionId);

    /**
     * 取某个拉手账号最近一次调用的提交时间，用于校验账号级拉人间隔。
     *
     * <p>拉人间隔只约束同一拉手账号的连续调用；不同拉手在同一群内轮询不设群级间隔。</p>
     *
     * @param pullerAccountId 拉手账号 ID
     * @return 最近提交时间(epoch 毫秒)；该账号尚无已提交调用时为 null
     */
    Long selectLastSubmittedAtByPuller(@Param("pullerAccountId") long pullerAccountId);

    /**
     * 标记调用命令已提交。
     *
     * @param id 调用行 ID
     * @param commandId 协议命令 ID
     * @param now 提交时间(epoch 毫秒)
     * @return 实际更新行数；0 表示该调用已不在计划状态
     */
    int markSubmitted(@Param("id") long id,
                      @Param("commandId") String commandId,
                      @Param("now") long now);

    /**
     * 回写调用整体结果。
     *
     * <p>逐参与者结果分别落在 {@code pull_task_material_member} 和
     * {@code pull_task_group_account} 上，本方法只推进调用行自身的状态。</p>
     *
     * @param id 调用行 ID
     * @param callStatus 调用状态，取值见 PullTaskPullCallStatus
     * @param reasonCode 失败原因码
     * @param reasonMessage 失败原因描述(已脱敏)
     * @param now 回写时间(epoch 毫秒)
     * @return 实际更新行数
     */
    int writeBackResult(@Param("id") long id,
                        @Param("callStatus") int callStatus,
                        @Param("reasonCode") String reasonCode,
                        @Param("reasonMessage") String reasonMessage,
                        @Param("now") long now);

    /**
     * 协议回调按命令 ID 定位调用行。
     *
     * @param commandId 协议命令 ID
     * @return 调用行；不存在或不属于当前租户时为 null
     */
    PullTaskPullCall selectByCommandId(@Param("commandId") String commandId);
}
```

- [ ] **Step 6: 写 Mapper XML**

创建 `armada-api/src/main/resources/mapper/task/PullTaskPullCallMapper.xml`。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.armada.task.mapper.PullTaskPullCallMapper">

  <!-- tenant_id 由租户拦截器注入；写入即为"计划"状态，命令尚未发出。 -->
  <insert id="insertPlanned" useGeneratedKeys="true" keyProperty="id">
    INSERT INTO pull_task_pull_call (
      task_id, group_execution_id, call_seq,
      puller_group_account_id, puller_account_id,
      planned_material_count, planned_station_count,
      call_status, idempotency_key, created_at, updated_at
    ) VALUES (
      #{taskId}, #{groupExecutionId}, #{callSeq},
      #{pullerGroupAccountId}, #{pullerAccountId},
      #{plannedMaterialCount}, #{plannedStationCount},
      1, #{idempotencyKey}, #{createdAt}, #{updatedAt}
    )
  </insert>

  <select id="selectPlannedByExecution" resultType="com.armada.task.model.entity.PullTaskPullCall">
    SELECT *
    FROM pull_task_pull_call
    WHERE group_execution_id = #{groupExecutionId}
      AND call_status = 1
    ORDER BY call_seq ASC
  </select>

  <!-- 走 idx_pull_task_call_puller_time (tenant_id, puller_account_id, submitted_at, id)。 -->
  <select id="selectLastSubmittedAtByPuller" resultType="java.lang.Long">
    SELECT MAX(submitted_at)
    FROM pull_task_pull_call
    WHERE puller_account_id = #{pullerAccountId}
      AND submitted_at IS NOT NULL
  </select>

  <update id="markSubmitted">
    UPDATE pull_task_pull_call
    SET call_status = 2,
        command_id = #{commandId},
        submitted_at = #{now},
        updated_at = #{now}
    WHERE id = #{id}
      AND call_status = 1
  </update>

  <update id="writeBackResult">
    UPDATE pull_task_pull_call
    SET call_status = #{callStatus},
        reason_code = #{reasonCode},
        reason_message = #{reasonMessage},
        result_at = #{now},
        updated_at = #{now}
    WHERE id = #{id}
  </update>

  <select id="selectByCommandId" resultType="com.armada.task.model.entity.PullTaskPullCall">
    SELECT *
    FROM pull_task_pull_call
    WHERE command_id = #{commandId}
  </select>

</mapper>
```

- [ ] **Step 7: 校验 XML 并运行测试**

```bash
cd armada-api && xmllint --noout src/main/resources/mapper/task/PullTaskPullCallMapper.xml \
  && mvn -q -Dtest='PullTaskPullCallMapperInMemoryTest' -DfailIfNoTests=false test
```

Expected: PASS，7 个测试全绿。

- [ ] **Step 8: 提交**

```bash
git add armada-api/src/main/java/com/armada/task/model/enums/PullTaskPullCallStatus.java \
        armada-api/src/main/java/com/armada/task/model/entity/PullTaskPullCall.java \
        armada-api/src/main/java/com/armada/task/mapper/PullTaskPullCallMapper.java \
        armada-api/src/main/resources/mapper/task/PullTaskPullCallMapper.xml \
        armada-api/src/test/java/com/armada/task/mapper/PullTaskPullCallMapperInMemoryTest.java
git commit -m "feat: 新增拉人调用 Mapper

计划状态在提交前落库并带 idempotency_key,崩溃恢复用原键重投不重新
分配料子;按拉手账号取最近提交时间校验账号级拉人间隔。"
```

---

## Task 10: 全量回归、数据模型文档与可选真库补充验证

**Files:**
- Modify: `.harness/wiki/数据模型.md`（由生成器重写，禁手改）
- Create: `.harness/changes/pull-task-normal-link/summary.md`
- Create: `armada-api/src/test/java/com/armada/task/PullTaskNormalLinkCollationDbTest.java`（可选补充测试）

**Interfaces:**
- Consumes: Task 1–9 的全部产出
- Produces: 可交付的数据层。后续 Service 层任务从这里的 Mapper 接口继续。

- [ ] **Step 1: 跑全量测试**

```bash
cd armada-api && mvn -q test
```

Expected: 全绿。任何失败都要修到绿，不得跳过、不得用 `-Dmaven.test.skip`。

重点关注这几类既有测试是否被本次改动波及：
- `FlywayMigration*Test` —— 版本号唯一、`--` 注释空格、历史 checksum
- `PullTaskMapperInMemoryTest` —— Task 3 已补三列，若仍红说明补漏了
- `EpochMillisSchemaDbTest` / `AccountSchemaDbTest` —— 若它们对全库做列类型扫描，新表的时间列必须都是 `BIGINT`

- [ ] **Step 2: 重跑数据模型文档生成器**

```bash
cd /mnt/d/ideaProject/armada/.harness/wiki && python3 gen_datamodel.py
```

Expected: `数据模型.md` 出现 6 张新表和 `pull_task` 的 3 个新列，每列带中文说明。

若生成器需要连库而当前环境不可达，跳过本步并在 Step 4 的 summary 里写明"文档待在可连库环境重跑"，**不要手工编辑 `数据模型.md`**（规范明令禁手改）。

- [ ] **Step 3: 写可选的真库补充测试**

创建 `armada-api/src/test/java/com/armada/task/PullTaskNormalLinkCollationDbTest.java`。

这是**唯一能端到端证明排序规则正确**的测试：H2 默认大小写敏感，`ai_ci` 的重复判定问题只有真 MySQL 会暴露。按 `.harness/rules/编码规范.md`，真库 DbTest 不是本地完成门禁，因此这个类默认不跑。

```java
package com.armada.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.testsupport.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 邀请码大小写敏感的真库补充测试。
 *
 * <p>只有真 MySQL 能证明 {@code normalized_link} 的 {@code ascii_bin} 排序规则生效：
 * 表默认 {@code utf8mb4_0900_ai_ci} 大小写不敏感，漏声明会把仅大小写不同的两条
 * 邀请码判为重复；而 H2 默认大小写敏感，这个缺陷在内存测试里静默通过。</p>
 *
 * <p>本类是可选补充验证，不是本地完成门禁。执行前必须确认目标环境，
 * 用 {@code armada-api/dbtest.sh PullTaskNormalLinkCollationDbTest} 运行。</p>
 */
class PullTaskNormalLinkCollationDbTest extends DbTestBase {

    @Autowired
    private PullTaskGroupExecutionMapper mapper;

    @Test
    void inviteCodesDifferingOnlyByCaseAreDistinctLinks() {
        long taskId = System.nanoTime();

        mapper.insertDraft(draft(taskId, 1, "chat.whatsapp.com/AbCdEfGhIjKlMnOpQrStUv", 1));
        mapper.insertDraft(draft(taskId, 2, "chat.whatsapp.com/abcdefghijklmnopqrstuv", 2));

        List<PullTaskGroupExecution> rows = mapper.selectByTaskId(taskId);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(PullTaskGroupExecution::getNormalizedLink)
                .containsExactly(
                        "chat.whatsapp.com/AbCdEfGhIjKlMnOpQrStUv",
                        "chat.whatsapp.com/abcdefghijklmnopqrstuv");
    }

    private PullTaskGroupExecution draft(long taskId, int seq, String link, int fileIndex) {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setTaskId(taskId);
        row.setSeq(seq);
        row.setNormalizedLink(link);
        row.setInviteCode(link.substring(link.lastIndexOf('/') + 1));
        row.setSourceLinkLineNo(seq);
        row.setSourceFileIndex(fileIndex);
        row.setSourceFileName("material-" + fileIndex + ".txt");
        row.setTotalLineCount(10);
        row.setValidMemberCount(8);
        row.setInvalidLineCount(1);
        row.setDuplicateLineCount(1);
        row.setExecutionStatus(PullTaskExecutionStatus.DRAFT.code());
        long now = System.currentTimeMillis();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }
}
```

**不要在本步执行它。** 执行需要用户先确认目标环境（`AGENTS.md` 红线：真库操作前必须确认目标环境）。把命令写进 summary，交给用户决定何时跑：

```bash
armada-api/dbtest.sh PullTaskNormalLinkCollationDbTest
```

- [ ] **Step 4: 写 change 记录**

创建 `.harness/changes/pull-task-normal-link/summary.md`，按 `.harness/changes/README.md` 的格式，内容至少包含：

- **背景**：链接到 `docs/superpowers/specs/2026-08-02-pull-task-normal-link-data-model-design.md` 与 ADR-0001～0009。
- **改动清单**：`V090` 迁移（6 张新表 + `pull_task` 3 列）、6 套实体/Mapper/XML、12 个枚举、`pull_task` 的 DRAFT 可见性与生命周期乐观锁。
- **规范例外**：`pull_task_group_execution` 33 列超过 ~30 列阈值，理由见本计划 Global Constraints。
- **验证（evidence-before-done）**：粘贴 `mvn -q test` 的**实际命令与退出码**；写明 `gen_datamodel.py` 是否已重跑；写明真库排序规则测试**尚未执行**及其执行命令。
- **未覆盖**：本次只做数据层，Service、Controller、调度器、协议编排、前端均未接入；M1 闭环尚未打通。
- **回滚**：指向同目录 `rollback.sql`，并注明必须同时删 `flyway_schema_history` 中 `version='090'` 的记录。

- [ ] **Step 5: 提交**

```bash
git add .harness/wiki/数据模型.md .harness/changes/pull-task-normal-link/summary.md \
        armada-api/src/test/java/com/armada/task/PullTaskNormalLinkCollationDbTest.java
git commit -m "docs: 补齐普通群链接数据层的模型文档与 change 记录

新增排序规则真库补充测试(默认不跑,需先确认目标环境)。"
```

- [ ] **Step 6: 向用户汇报并请求真库验证授权**

汇报内容必须包含：`mvn -q test` 的真实输出与退出码、新增表和列的清单、`数据模型.md` 是否已重跑、以及一句明确的请求——是否授权在指定环境执行 `armada-api/dbtest.sh PullTaskNormalLinkCollationDbTest` 来验证邀请码大小写敏感。

**不得**在未获授权前连真库，**不得**把"H2 全绿"表述成"排序规则已验证"。

---

## Self-Review

**规格覆盖**：设计规格 §4（`pull_task` 3 列 + DRAFT）→ Task 1/3；§5.1–5.6 六张表 → Task 1 建表，Task 4–9 实体与 Mapper；§3 通用约定（ascii_bin、生成列 else NULL、无租户前缀调度索引、不建外键、TINYINT 枚举带注释）→ Task 1 的 DDL 与 `PullTaskNormalLinkMigrationSqlTest` 的六个断言；§6 一致性规则第 1/5/6/7/10/11 条 → Task 3/5/7 的测试；§9 迁移与回滚 → Task 1 Step 6、Task 10；§10 三层验证门禁 → Task 1（脚本结构）、Task 2–9（H2）、Task 10（可选真库）。

**未覆盖且属于后续任务**：§6 第 2/3/4/8/9 条（单文件单群完整校验、不重试、UNKNOWN 收敛、调度取行的完整条件组合、汇总重算）需要 Service 层参与，落在本计划之外的后续切片；`.harness/changes` 的 `db-migrations.sql` 在 Task 1 建立、`summary.md` 在 Task 10 收口。

**类型一致性检查**：`PullTaskGroupExecution#getId()` 为 `Long`，Task 5 测试用 `row.getId()` 传给 `updateCheckpoint(long, ...)` 自动拆箱，正确；`countAvailableByRole` 返回 `List<PullTaskGroupAccountRoleCount>`，Task 7 Step 2 已定义该投影类；`selectLastSubmittedAtByPuller` 返回装箱 `Long` 以便区分"无记录"与 0；六个 Mapper 的 `command_id` 相关方法命名统一为 `selectByCommandId`（料子表因是提权命令而命名 `selectByAdminCommandId`，与其列名 `admin_command_id` 一致）。

**已知需在实施中当场决策的一处**：Task 5 Step 7 的 `UPDATE ... ORDER BY ... LIMIT` 在 H2 的兼容性（计划里已附拆两步的降级方案）。

**执行前已裁定的两处计划缺陷**：`actor_group_account_id` 改为 `NOT NULL`、踩链接入群写目标账号自身 ID（原设计用 NULL，而 MySQL 唯一索引中 NULL 互不相等，幂等键会失效）；Task 6 的 Interfaces 块删去无人调用的 `countByPullStatus`，补上测试实际使用的 `selectByExecution` 与 `markAdminSubmitted`。
