# 群组列表数据模型 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地群组列表第一刀的数据模型和导入链接入池规则，确保导入链接、进群任务、拉群任务、自建群进入同一个群组池且不重复、不互相污染。

**Architecture:** 保持 `group_link` 为群入口主表，只补来源与关系态；新建 `group_link_preview` 存协议群元数据，新建 `group_link_health` 存可用性/运行态。导入链接规则在 `GroupLinkImportServiceImpl` 中改为「全新=新增成功、非导入来源命中=收编成功、导入域内重复=失败原因重复」。

**Tech Stack:** Spring Boot 3 + Java 17 + plain MyBatis XML + Flyway + MySQL + JUnit5/AssertJ 真库 DbTest + MapStruct。

---

## Source Of Truth

- Design summary: `.harness/changes/group-list/summary.md`
- Project rules: `.harness/rules/工程结构.md`, `.harness/rules/数据模型规范.md`, `.harness/rules/编码规范.md`
- Existing migrations: `armada-api/src/main/resources/db/migration/V003__group_import_links.sql`, `V006__group_link_batch_name_nullable.sql`, `V008__time_columns_to_epoch_ms.sql`
- Existing import service: `armada-api/src/main/java/com/armada/group/service/impl/GroupLinkImportServiceImpl.java`
- Current latest tracked migration is `V008`, but the working tree already contains `V009__ip_proxy_binding.sql`; this plan uses `V010__group_list_data_model.sql`.

## Out Of Scope

- 群组列表分页 API、前端页面、成员抽屉、协议操作不在本计划内。
- `group_link_label` 不改。
- 不新增 `group_member` 或 `group_link_history`。

## Global Constraints

- 时间列全部使用 `BIGINT epoch 毫秒`，应用层写 `System.currentTimeMillis()`。
- 表名和列名使用 snake_case；Java DTO/VO 使用 camelCase。
- 枚举列用 `TINYINT`，Java 侧用 enum 或常量类集中定义，禁止魔法数字散落在业务代码里。
- 租户表必须有 `tenant_id`；Mapper SQL 不手写 `tenant_id` 条件，依赖租户拦截器。多表 JOIN 时保留现有 `d.tenant_id = b.tenant_id` 这类防串租条件。
- Mapper 仍使用 plain `@Mapper` + XML，不继承 MyBatis-Plus `BaseMapper`。
- 分页、统计、过滤都必须 SQL 下推，禁止内存分页。
- 真库 DbTest 继承 `com.armada.testsupport.DbTestBase`，使用 `armada-api/dbtest.sh '<TestClass 或 模式>'`。
- 每个任务提交时使用精确路径 `git add`，不要 `git add .`，因为工作区已有无关改动。

## File Map

### Create

- `armada-api/src/main/resources/db/migration/V010__group_list_data_model.sql`：schema 迁移。
- `armada-api/src/main/java/com/armada/group/model/entity/GroupLinkPreview.java`：`group_link_preview` 实体。
- `armada-api/src/main/java/com/armada/group/model/entity/GroupLinkHealth.java`：`group_link_health` 实体。
- `armada-api/src/main/java/com/armada/group/model/enums/GroupLinkOrigin.java`：群入口来源枚举。
- `armada-api/src/main/java/com/armada/group/model/enums/GroupMembershipState.java`：我方与群关系枚举。
- `armada-api/src/main/java/com/armada/group/model/enums/GroupLinkHealthStatus.java`：健康状态枚举。
- `armada-api/src/main/java/com/armada/group/model/enums/GroupLinkImportSuccessType.java`：导入成功类型枚举。
- `armada-api/src/main/java/com/armada/group/model/enums/GroupLinkImportFailReason.java`：导入失败原因常量。
- `armada-api/src/main/java/com/armada/group/mapper/GroupLinkPreviewMapper.java`：预览表 Mapper。
- `armada-api/src/main/java/com/armada/group/mapper/GroupLinkHealthMapper.java`：健康表 Mapper。
- `armada-api/src/main/resources/mapper/group/GroupLinkPreviewMapper.xml`：预览表 XML。
- `armada-api/src/main/resources/mapper/group/GroupLinkHealthMapper.xml`：健康表 XML。
- `armada-api/src/test/java/com/armada/group/mapper/GroupListDataModelMigrationDbTest.java`：迁移生效 DbTest。
- `armada-api/src/test/java/com/armada/group/mapper/GroupLinkPreviewMapperDbTest.java`：预览表 Mapper DbTest。
- `armada-api/src/test/java/com/armada/group/mapper/GroupLinkHealthMapperDbTest.java`：健康表 Mapper DbTest。
- `.harness/changes/group-list/db-migrations.sql`：变更随附迁移脚本副本。
- `.harness/changes/group-list/rollback.sql`：变更随附回滚脚本。

### Modify

- `armada-api/src/main/java/com/armada/group/model/entity/GroupLink.java`：新增 `origin`、`membershipState`。
- `armada-api/src/main/java/com/armada/group/model/entity/GroupLinkImportBatch.java`：`skippedRows` 改为 `duplicateRows`，`adoptedRows` 注释恢复为收编成功数。
- `armada-api/src/main/java/com/armada/group/model/entity/GroupLinkImportDetail.java`：新增 `successType`、`existingOrigin`，调整 `result` / `failReason` 注释。
- `armada-api/src/main/java/com/armada/group/model/GroupLinkImportResult.java`：结果码改为 `SUCCESS=1`、`FAILED=2`。
- `armada-api/src/main/java/com/armada/group/model/vo/GroupLinkImportResultVO.java`：前端汇总字段改为 `successRows`、`failedRows`、`duplicateRows`、`formatErrorRows`。
- `armada-api/src/main/java/com/armada/group/model/vo/GroupLinkImportDetailVO.java`：补成功类型、原始来源字段。
- `armada-api/src/main/java/com/armada/group/model/vo/GroupLinkImportDetailVoRow.java`：补成功类型、原始来源字段。
- `armada-api/src/main/java/com/armada/group/converter/GroupConverter.java`：更新结果标签、成功类型标签、来源标签映射。
- `armada-api/src/main/java/com/armada/group/mapper/GroupLinkMapper.java`：补收编活跃链接、复活软删链接、插入新字段。
- `armada-api/src/main/resources/mapper/group/GroupLinkMapper.xml`：同步 Mapper XML。
- `armada-api/src/main/java/com/armada/group/mapper/GroupLinkImportBatchMapper.java`：`skipped` 口径改 `duplicate`。
- `armada-api/src/main/resources/mapper/group/GroupLinkImportBatchMapper.xml`：`skipped_rows` 改 `duplicate_rows`。
- `armada-api/src/main/java/com/armada/group/mapper/GroupLinkImportDetailMapper.java`：查询/导出失败口径改 `result=FAILED`。
- `armada-api/src/main/resources/mapper/group/GroupLinkImportDetailMapper.xml`：插入和查询新增字段，失败查询改口径。
- `armada-api/src/main/java/com/armada/group/service/impl/GroupLinkImportServiceImpl.java`：重写导入入池规则。
- `armada-api/src/test/java/com/armada/group/service/impl/GroupLinkImportServiceDbTest.java`：重写旧 EXISTS 用例，新增收编/重复失败用例。
- `armada-api/src/test/java/com/armada/group/mapper/GroupLinkMapperDbTest.java`：补 origin/membership/adopt 用例。
- `.harness/wiki/数据模型.md`：执行完迁移后由 `gen_datamodel.py` 刷新。

---

# Phase 0 — 迁移红测

### Task 0.1: 写迁移生效 DbTest

**Files:**
- Create: `armada-api/src/test/java/com/armada/group/mapper/GroupListDataModelMigrationDbTest.java`

**Purpose:** 在写 Flyway 前先固定 schema 预期：新增列、新增表、改名列必须存在，旧列必须消失。

- [ ] **Step 1: 创建测试类**

```java
package com.armada.group.mapper;

import com.armada.testsupport.DbTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class GroupListDataModelMigrationDbTest extends DbTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void groupLink_hasOriginAndMembershipColumns() {
        assertThat(columnExists("group_link", "origin")).isTrue();
        assertThat(columnExists("group_link", "membership_state")).isTrue();
        assertThat(columnType("group_link", "origin")).isEqualTo("tinyint");
        assertThat(columnType("group_link", "membership_state")).isEqualTo("tinyint");
    }

    @Test
    void previewAndHealthTablesExist() {
        assertThat(tableExists("group_link_preview")).isTrue();
        assertThat(columnExists("group_link_preview", "group_jid")).isTrue();
        assertThat(columnExists("group_link_preview", "wa_subject")).isTrue();
        assertThat(tableExists("group_link_health")).isTrue();
        assertThat(columnExists("group_link_health", "health_status")).isTrue();
        assertThat(columnExists("group_link_health", "is_banned")).isTrue();
    }

    @Test
    void importBatch_usesDuplicateRowsInsteadOfSkippedRows() {
        assertThat(columnExists("group_link_import_batch", "duplicate_rows")).isTrue();
        assertThat(columnExists("group_link_import_batch", "skipped_rows")).isFalse();
    }

    @Test
    void importDetail_hasNewResultDimensions() {
        assertThat(columnExists("group_link_import_detail", "success_type")).isTrue();
        assertThat(columnExists("group_link_import_detail", "existing_origin")).isTrue();
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class,
                tableName);
        return count != null && count == 1;
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Integer.class,
                tableName,
                columnName);
        return count != null && count == 1;
    }

    private String columnType(String tableName, String columnName) {
        return jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                String.class,
                tableName,
                columnName);
    }
}
```

- [ ] **Step 2: 运行测试确认红**

Run:

```bash
armada-api/dbtest.sh 'com.armada.group.mapper.GroupListDataModelMigrationDbTest'
```

Expected: FAIL，缺少 `origin`、`group_link_preview`、`group_link_health`、`duplicate_rows` 等 schema。

- [ ] **Step 3: Commit 红测**

```bash
git add armada-api/src/test/java/com/armada/group/mapper/GroupListDataModelMigrationDbTest.java
git commit -m "test(group): specify group list data model migration"
```

---

# Phase 1 — Flyway 数据模型

### Task 1.1: 新增 V010 迁移

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V010__group_list_data_model.sql`
- Create: `.harness/changes/group-list/db-migrations.sql`
- Create: `.harness/changes/group-list/rollback.sql`

**Purpose:** 落地 `group_link` 新列、新增 preview/health 两表、调整导入批次和明细字段。

- [ ] **Step 1: 写 V010 迁移**

`armada-api/src/main/resources/db/migration/V010__group_list_data_model.sql`:

```sql
-- 群组列表数据模型第一刀:
-- 1) group_link 补入口来源/关系态
-- 2) group_link_preview 承载协议群元数据
-- 3) group_link_health 承载可用性/运行态
-- 4) 导入链接统计与明细语义收敛

ALTER TABLE group_link
    ADD COLUMN origin TINYINT NOT NULL DEFAULT 1 COMMENT '首次进入群组池来源:1=导入链接 2=进群任务 3=拉群任务 4=自建群' AFTER import_batch_id,
    ADD COLUMN membership_state TINYINT NOT NULL DEFAULT 1 COMMENT '我方与群关系:1=目标未进群 2=已进群 3=自建拥有' AFTER origin,
    ADD KEY idx_group_link_origin (tenant_id, deleted_at, origin),
    ADD KEY idx_group_link_membership (tenant_id, deleted_at, membership_state);

CREATE TABLE group_link_preview (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    group_link_id BIGINT NOT NULL COMMENT '关联group_link.id',
    group_jid VARCHAR(64) DEFAULT NULL COMMENT 'WhatsApp群JID,协议层操作群的真实标识',
    invite_code VARCHAR(64) DEFAULT NULL COMMENT '群邀请链接里的邀请码code',
    wa_subject VARCHAR(255) DEFAULT NULL COMMENT '协议层返回的WhatsApp真实群名称',
    member_size INT DEFAULT NULL COMMENT '预览时刻返回的群成员数量',
    owner_phone VARCHAR(32) DEFAULT NULL COMMENT '群主号码',
    announce_only TINYINT(1) DEFAULT NULL COMMENT '是否仅管理员可发言:NULL=未知 0=否 1=是',
    avatar_url VARCHAR(512) DEFAULT NULL COMMENT '群头像URL',
    last_preview_at BIGINT DEFAULT NULL COMMENT '最近一次预览/解析成功时间(epoch毫秒)',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_group_link_preview_link (tenant_id, group_link_id),
    KEY idx_group_link_preview_jid (tenant_id, group_jid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='群链接协议预览元数据';

CREATE TABLE group_link_health (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    group_link_id BIGINT NOT NULL COMMENT '关联group_link.id',
    health_status TINYINT DEFAULT NULL COMMENT '健康状态:1=可用 2=链接失效 3=不可用;NULL=未检测',
    is_banned TINYINT(1) DEFAULT NULL COMMENT '是否被WhatsApp封禁:NULL=未知 0=未封禁 1=已封禁',
    current_count INT DEFAULT NULL COMMENT '当前群成员数量',
    last_check_at BIGINT DEFAULT NULL COMMENT '最近一次健康检测时间(epoch毫秒)',
    last_health_error VARCHAR(64) DEFAULT NULL COMMENT '最近一次健康检测失败原因',
    health_failure_count INT NOT NULL DEFAULT 0 COMMENT '连续健康检测失败次数;检测成功后归零',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_group_link_health_link (tenant_id, group_link_id),
    KEY idx_group_link_health_status (tenant_id, health_status, is_banned)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='群链接健康状态';

ALTER TABLE group_link_import_batch
    ADD COLUMN duplicate_rows INT NOT NULL DEFAULT 0 COMMENT '重复失败数量(批内重复 + 已在导入链接中重复导入)' AFTER adopted_rows;

UPDATE group_link_import_batch
SET duplicate_rows = skipped_rows + adopted_rows,
    failed_rows = failed_rows + skipped_rows + adopted_rows,
    adopted_rows = 0;

ALTER TABLE group_link_import_batch
    DROP COLUMN skipped_rows;

ALTER TABLE group_link_import_detail
    ADD COLUMN success_type TINYINT DEFAULT NULL COMMENT '成功类型:1=新增 2=收编已有群;失败时为空' AFTER result,
    ADD COLUMN existing_origin TINYINT DEFAULT NULL COMMENT '收编成功时记录已有群入口来源:2=进群任务 3=拉群任务 4=自建群' AFTER fail_reason;

UPDATE group_link_import_detail
SET success_type = CASE WHEN result = 1 THEN 1 ELSE NULL END,
    fail_reason = CASE
        WHEN result IN (2, 3) THEN '重复'
        WHEN result = 4 THEN COALESCE(fail_reason, '格式错误')
        ELSE NULL
    END,
    group_link_id = CASE WHEN result = 1 THEN group_link_id ELSE NULL END,
    result = CASE WHEN result = 1 THEN 1 ELSE 2 END;
```

- [ ] **Step 2: 写随附迁移副本**

Run:

```bash
cp armada-api/src/main/resources/db/migration/V010__group_list_data_model.sql \
   .harness/changes/group-list/db-migrations.sql
```

- [ ] **Step 3: 写 rollback.sql**

`.harness/changes/group-list/rollback.sql`:

```sql
DROP TABLE IF EXISTS group_link_health;
DROP TABLE IF EXISTS group_link_preview;

ALTER TABLE group_link_import_detail
    DROP COLUMN existing_origin,
    DROP COLUMN success_type;

ALTER TABLE group_link_import_batch
    ADD COLUMN skipped_rows INT NOT NULL DEFAULT 0 COMMENT '批内重复跳过行数' AFTER adopted_rows;

UPDATE group_link_import_batch
SET skipped_rows = duplicate_rows;

ALTER TABLE group_link_import_batch
    DROP COLUMN duplicate_rows;

ALTER TABLE group_link
    DROP INDEX idx_group_link_membership,
    DROP INDEX idx_group_link_origin,
    DROP COLUMN membership_state,
    DROP COLUMN origin;
```

- [ ] **Step 4: 运行迁移测试确认绿**

Run:

```bash
armada-api/dbtest.sh 'com.armada.group.mapper.GroupListDataModelMigrationDbTest'
```

Expected: PASS，4 个测试通过。

- [ ] **Step 5: Commit**

```bash
git add armada-api/src/main/resources/db/migration/V010__group_list_data_model.sql \
        .harness/changes/group-list/db-migrations.sql \
        .harness/changes/group-list/rollback.sql
git commit -m "feat(group): add group list data model migration"
```

---

# Phase 2 — 枚举、实体、VO

### Task 2.1: 新增枚举并调整导入结果码

**Files:**
- Create: `armada-api/src/main/java/com/armada/group/model/enums/GroupLinkOrigin.java`
- Create: `armada-api/src/main/java/com/armada/group/model/enums/GroupMembershipState.java`
- Create: `armada-api/src/main/java/com/armada/group/model/enums/GroupLinkHealthStatus.java`
- Create: `armada-api/src/main/java/com/armada/group/model/enums/GroupLinkImportSuccessType.java`
- Create: `armada-api/src/main/java/com/armada/group/model/enums/GroupLinkImportFailReason.java`
- Modify: `armada-api/src/main/java/com/armada/group/model/GroupLinkImportResult.java`
- Test: `armada-api/src/test/java/com/armada/group/model/GroupLinkImportResultTest.java`

- [ ] **Step 1: 新增来源枚举**

`GroupLinkOrigin.java`:

```java
package com.armada.group.model.enums;

public enum GroupLinkOrigin {
    IMPORT(1, "导入链接"),
    JOIN_TASK(2, "进群任务"),
    PULL_TASK(3, "拉群任务"),
    SELF_BUILT(4, "自建群");

    private final int code;
    private final String label;

    GroupLinkOrigin(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static GroupLinkOrigin fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (GroupLinkOrigin value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知群入口来源: " + code);
    }
}
```

- [ ] **Step 2: 新增关系态、健康态、成功类型枚举**

`GroupMembershipState.java`:

```java
package com.armada.group.model.enums;

public enum GroupMembershipState {
    TARGET(1, "目标未进群"),
    JOINED(2, "已进群"),
    OWNER(3, "自建拥有");

    private final int code;
    private final String label;

    GroupMembershipState(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static GroupMembershipState fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (GroupMembershipState value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知群关系态: " + code);
    }
}
```

`GroupLinkHealthStatus.java`:

```java
package com.armada.group.model.enums;

public enum GroupLinkHealthStatus {
    AVAILABLE(1, "可用"),
    LINK_INVALID(2, "链接失效"),
    UNAVAILABLE(3, "不可用");

    private final int code;
    private final String label;

    GroupLinkHealthStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static GroupLinkHealthStatus fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (GroupLinkHealthStatus value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知群健康状态: " + code);
    }
}
```

`GroupLinkImportSuccessType.java`:

```java
package com.armada.group.model.enums;

public enum GroupLinkImportSuccessType {
    INSERTED(1, "新增"),
    ADOPTED(2, "收编已有群");

    private final int code;
    private final String label;

    GroupLinkImportSuccessType(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static GroupLinkImportSuccessType fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (GroupLinkImportSuccessType value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知导入成功类型: " + code);
    }
}
```

- [ ] **Step 3: 新增失败原因常量**

`GroupLinkImportFailReason.java`:

```java
package com.armada.group.model.enums;

public final class GroupLinkImportFailReason {

    public static final String DUPLICATE = "重复";
    public static final String FORMAT_ERROR = "格式错误";

    private GroupLinkImportFailReason() {
    }
}
```

- [ ] **Step 4: 调整 GroupLinkImportResult**

Replace old 4-state result with:

```java
public enum GroupLinkImportResult {
    SUCCESS(1, "成功"),
    FAILED(2, "失败");
}
```

Keep `code()`, `label()`, `fromCode(int code)` behavior.

- [ ] **Step 5: 更新测试**

`GroupLinkImportResultTest` asserts:

```java
assertThat(GroupLinkImportResult.SUCCESS.code()).isEqualTo(1);
assertThat(GroupLinkImportResult.SUCCESS.label()).isEqualTo("成功");
assertThat(GroupLinkImportResult.FAILED.code()).isEqualTo(2);
assertThat(GroupLinkImportResult.FAILED.label()).isEqualTo("失败");
assertThat(GroupLinkImportResult.fromCode(1)).isEqualTo(GroupLinkImportResult.SUCCESS);
assertThatThrownBy(() -> GroupLinkImportResult.fromCode(9))
        .isInstanceOf(IllegalArgumentException.class);
```

- [ ] **Step 6: Run**

```bash
cd armada-api && mvn -q -Dtest='com.armada.group.model.GroupLinkImportResultTest' test
```

Expected: PASS。

- [ ] **Step 7: Commit**

```bash
git add armada-api/src/main/java/com/armada/group/model/enums \
        armada-api/src/main/java/com/armada/group/model/GroupLinkImportResult.java \
        armada-api/src/test/java/com/armada/group/model/GroupLinkImportResultTest.java
git commit -m "feat(group): add group list enums"
```

### Task 2.2: 更新实体和 VO

**Files:**
- Modify: `armada-api/src/main/java/com/armada/group/model/entity/GroupLink.java`
- Modify: `armada-api/src/main/java/com/armada/group/model/entity/GroupLinkImportBatch.java`
- Modify: `armada-api/src/main/java/com/armada/group/model/entity/GroupLinkImportDetail.java`
- Create: `armada-api/src/main/java/com/armada/group/model/entity/GroupLinkPreview.java`
- Create: `armada-api/src/main/java/com/armada/group/model/entity/GroupLinkHealth.java`
- Modify: `armada-api/src/main/java/com/armada/group/model/vo/GroupLinkImportResultVO.java`
- Modify: `armada-api/src/main/java/com/armada/group/model/vo/GroupLinkImportDetailVoRow.java`
- Modify: `armada-api/src/main/java/com/armada/group/model/vo/GroupLinkImportDetailVO.java`

- [ ] **Step 1: GroupLink 增加字段**

Add fields and hand-written getters/setters:

```java
/** 首次进入群组池来源:1=导入链接 2=进群任务 3=拉群任务 4=自建群。 */
private Integer origin;

/** 我方与群关系:1=目标未进群 2=已进群 3=自建拥有。 */
private Integer membershipState;
```

- [ ] **Step 2: GroupLinkImportBatch 改 duplicateRows**

Rename Java field:

```java
/** 重复失败数量(批内重复 + 已在导入链接中重复导入)。 */
private int duplicateRows;
```

Remove `skippedRows`, `getSkippedRows()`, `setSkippedRows(int)`. Add `getDuplicateRows()` / `setDuplicateRows(int)`.

Update `adoptedRows` comment to:

```java
/** 收编已有群入口的成功数量。 */
private int adoptedRows;
```

- [ ] **Step 3: GroupLinkImportDetail 增加字段**

Add fields and getters/setters:

```java
/** 成功类型:1=新增 2=收编已有群;失败时为空。 */
private Integer successType;

/** 收编成功时记录已有群入口来源。 */
private Integer existingOrigin;
```

Update comments:

```java
/** 导入结果:1=成功 2=失败。 */
private int result;

/** 失败原因:重复/格式错误;成功时为空。 */
private String failReason;
```

- [ ] **Step 4: 新增 GroupLinkPreview 实体**

Fields: `Long id, tenantId, groupLinkId, lastPreviewAt, createdAt, updatedAt; String groupJid, inviteCode, waSubject, ownerPhone, avatarUrl; Integer memberSize; Boolean announceOnly;` with hand-written getters/setters.

- [ ] **Step 5: 新增 GroupLinkHealth 实体**

Fields: `Long id, tenantId, groupLinkId, lastCheckAt, createdAt, updatedAt; Integer healthStatus, currentCount, healthFailureCount; Boolean banned; String lastHealthError;`

Use Java property name `banned` for DB column `is_banned`; Mapper XML will alias `is_banned AS banned`.

- [ ] **Step 6: 更新导入结果 VO**

`GroupLinkImportResultVO.java`:

```java
public record GroupLinkImportResultVO(
        Long batchId,
        int totalRows,
        int successRows,
        int failedRows,
        int duplicateRows,
        int formatErrorRows,
        List<String> errors) {
}
```

- [ ] **Step 7: 更新导入明细 VO**

Add to both row and VO:

```java
Integer successType;
String successTypeLabel;
Integer existingOrigin;
String existingOriginLabel;
```

Keep `result`, `resultLabel`, `failReason`.

- [ ] **Step 8: Compile**

```bash
cd armada-api && mvn -q -DskipTests compile
```

Expected: this compile step may fail on renamed Mapper/XML/converter references that are wired in Phase 3. Syntax errors inside the new classes must be fixed before moving to Phase 3.

---

# Phase 3 — Mapper 层

### Task 3.1: 更新 GroupLinkMapper

**Files:**
- Modify: `armada-api/src/main/java/com/armada/group/mapper/GroupLinkMapper.java`
- Modify: `armada-api/src/main/resources/mapper/group/GroupLinkMapper.xml`
- Modify: `armada-api/src/test/java/com/armada/group/mapper/GroupLinkMapperDbTest.java`

- [ ] **Step 1: Mapper 接口新增方法**

Add:

```java
int adoptActiveIntoImport(@Param("id") Long id,
                          @Param("labelId") Long labelId,
                          @Param("batchId") Long batchId,
                          @Param("updatedAt") long updatedAt);
```

Change `adoptToLabel` javadoc to say it is for soft-deleted row revival.

- [ ] **Step 2: XML insert 增加新字段**

Update `insert`:

```xml
INSERT INTO group_link (link_url, group_name, label_id, import_batch_id, origin, membership_state,
  created_at, updated_at, created_by)
VALUES (#{linkUrl}, #{groupName}, #{labelId}, #{importBatchId}, #{origin}, #{membershipState},
  #{createdAt}, #{updatedAt}, #{createdBy})
```

- [ ] **Step 3: XML 增加收编更新**

```xml
<update id="adoptActiveIntoImport">
  UPDATE group_link
  SET label_id = #{labelId},
      import_batch_id = #{batchId},
      updated_at = #{updatedAt}
  WHERE id = #{id}
    AND deleted_at IS NULL
    AND label_id IS NULL
</update>
```

- [ ] **Step 4: XML 查询映射确认**

`selectAnyByUrl` currently uses `SELECT *`; with underscore-to-camelCase it maps `membership_state` to `membershipState`. Keep this query.

- [ ] **Step 5: DbTest 增加收编用例**

Add test:

```java
@Test
void adoptActiveIntoImport_setsImportOwnershipWithoutChangingOrigin() {
    GroupLinkLabel label = insertLabel("收编目标分组");
    GroupLinkImportBatch batch = insertBatch(label.getId(), "adopt.txt");

    GroupLink link = buildLink("chat.whatsapp.com/AdoptFromPullTask", null, null);
    link.setOrigin(GroupLinkOrigin.PULL_TASK.code());
    link.setMembershipState(GroupMembershipState.TARGET.code());
    mapper.insert(link);

    int updated = mapper.adoptActiveIntoImport(link.getId(), label.getId(), batch.getId(), System.currentTimeMillis());
    assertThat(updated).isEqualTo(1);

    GroupLink after = mapper.selectAnyByUrl("chat.whatsapp.com/AdoptFromPullTask");
    assertThat(after.getLabelId()).isEqualTo(label.getId());
    assertThat(after.getImportBatchId()).isEqualTo(batch.getId());
    assertThat(after.getOrigin()).isEqualTo(GroupLinkOrigin.PULL_TASK.code());
}
```

Add imports:

```java
import com.armada.group.model.enums.GroupLinkOrigin;
import com.armada.group.model.enums.GroupMembershipState;
```

- [ ] **Step 6: Run**

```bash
armada-api/dbtest.sh 'com.armada.group.mapper.GroupLinkMapperDbTest'
```

Expected: PASS。

### Task 3.2: 更新导入 Batch/Detail Mapper

**Files:**
- Modify: `armada-api/src/main/java/com/armada/group/mapper/GroupLinkImportBatchMapper.java`
- Modify: `armada-api/src/main/resources/mapper/group/GroupLinkImportBatchMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/group/mapper/GroupLinkImportDetailMapper.java`
- Modify: `armada-api/src/main/resources/mapper/group/GroupLinkImportDetailMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/group/converter/GroupConverter.java`

- [ ] **Step 1: Batch XML 使用 duplicate_rows**

Replace `skipped_rows` with `duplicate_rows` in insert and update:

```xml
INSERT INTO group_link_import_batch (label_id, batch_name, source_file_name,
  total_rows, inserted_rows, adopted_rows, duplicate_rows, failed_rows, created_at, created_by)
VALUES (#{labelId}, #{batchName}, #{sourceFileName},
  #{totalRows}, #{insertedRows}, #{adoptedRows}, #{duplicateRows}, #{failedRows}, #{createdAt}, #{createdBy})
```

```xml
duplicate_rows = #{duplicateRows},
```

- [ ] **Step 2: Detail XML 插入新增字段**

```xml
INSERT INTO group_link_import_detail (
  batch_id, line_no, raw_url, group_name, result, success_type, fail_reason,
  existing_origin, group_link_id, created_at
)
VALUES
<foreach collection="rows" item="r" separator=",">
  (#{r.batchId}, #{r.lineNo}, #{r.rawUrl}, #{r.groupName}, #{r.result}, #{r.successType}, #{r.failReason},
   #{r.existingOrigin}, #{r.groupLinkId}, #{r.createdAt})
</foreach>
```

- [ ] **Step 3: Detail 查询投影新增字段**

Add selected columns to both `selectPage` and `selectFailed`:

```xml
d.success_type AS successType,
d.existing_origin AS existingOrigin,
```

Change failed query condition:

```xml
AND d.result = 2
```

- [ ] **Step 4: Converter 标签映射**

Update `GroupConverter.toImportDetailVO` mappings:

```java
@Mapping(target = "resultLabel",
        expression = "java(com.armada.group.model.GroupLinkImportResult.fromCode(row.getResult()).label())")
@Mapping(target = "successTypeLabel",
        expression = "java(row.getSuccessType() == null ? null : com.armada.group.model.enums.GroupLinkImportSuccessType.fromCode(row.getSuccessType()).label())")
@Mapping(target = "existingOriginLabel",
        expression = "java(row.getExistingOrigin() == null ? null : com.armada.group.model.enums.GroupLinkOrigin.fromCode(row.getExistingOrigin()).label())")
GroupLinkImportDetailVO toImportDetailVO(GroupLinkImportDetailVoRow row);
```

- [ ] **Step 5: Run XML parser**

```bash
xmllint --noout armada-api/src/main/resources/mapper/group/GroupLinkMapper.xml \
  armada-api/src/main/resources/mapper/group/GroupLinkImportBatchMapper.xml \
  armada-api/src/main/resources/mapper/group/GroupLinkImportDetailMapper.xml
```

Expected: no output, exit 0。

### Task 3.3: 新增 Preview/Health Mapper

**Files:**
- Create: `armada-api/src/main/java/com/armada/group/mapper/GroupLinkPreviewMapper.java`
- Create: `armada-api/src/main/resources/mapper/group/GroupLinkPreviewMapper.xml`
- Create: `armada-api/src/main/java/com/armada/group/mapper/GroupLinkHealthMapper.java`
- Create: `armada-api/src/main/resources/mapper/group/GroupLinkHealthMapper.xml`
- Create: `armada-api/src/test/java/com/armada/group/mapper/GroupLinkPreviewMapperDbTest.java`
- Create: `armada-api/src/test/java/com/armada/group/mapper/GroupLinkHealthMapperDbTest.java`

- [ ] **Step 1: Preview Mapper 接口**

```java
@Mapper
public interface GroupLinkPreviewMapper {
    int upsert(GroupLinkPreview row);
    GroupLinkPreview selectByGroupLinkId(@Param("groupLinkId") Long groupLinkId);
}
```

- [ ] **Step 2: Preview XML**

```xml
<insert id="upsert" useGeneratedKeys="true" keyProperty="id">
  INSERT INTO group_link_preview (
    group_link_id, group_jid, invite_code, wa_subject, member_size, owner_phone,
    announce_only, avatar_url, last_preview_at, created_at, updated_at
  ) VALUES (
    #{groupLinkId}, #{groupJid}, #{inviteCode}, #{waSubject}, #{memberSize}, #{ownerPhone},
    #{announceOnly}, #{avatarUrl}, #{lastPreviewAt}, #{createdAt}, #{updatedAt}
  )
  ON DUPLICATE KEY UPDATE
    group_jid = VALUES(group_jid),
    invite_code = VALUES(invite_code),
    wa_subject = VALUES(wa_subject),
    member_size = VALUES(member_size),
    owner_phone = VALUES(owner_phone),
    announce_only = VALUES(announce_only),
    avatar_url = VALUES(avatar_url),
    last_preview_at = VALUES(last_preview_at),
    updated_at = VALUES(updated_at)
</insert>

<select id="selectByGroupLinkId" resultType="com.armada.group.model.entity.GroupLinkPreview">
  SELECT *
  FROM group_link_preview
  WHERE group_link_id = #{groupLinkId}
  LIMIT 1
</select>
```

- [ ] **Step 3: Health Mapper 接口**

```java
@Mapper
public interface GroupLinkHealthMapper {
    int upsert(GroupLinkHealth row);
    GroupLinkHealth selectByGroupLinkId(@Param("groupLinkId") Long groupLinkId);
}
```

- [ ] **Step 4: Health XML**

Use `is_banned AS banned` for Java property mapping:

```xml
<insert id="upsert" useGeneratedKeys="true" keyProperty="id">
  INSERT INTO group_link_health (
    group_link_id, health_status, is_banned, current_count, last_check_at,
    last_health_error, health_failure_count, created_at, updated_at
  ) VALUES (
    #{groupLinkId}, #{healthStatus}, #{banned}, #{currentCount}, #{lastCheckAt},
    #{lastHealthError}, #{healthFailureCount}, #{createdAt}, #{updatedAt}
  )
  ON DUPLICATE KEY UPDATE
    health_status = VALUES(health_status),
    is_banned = VALUES(is_banned),
    current_count = VALUES(current_count),
    last_check_at = VALUES(last_check_at),
    last_health_error = VALUES(last_health_error),
    health_failure_count = VALUES(health_failure_count),
    updated_at = VALUES(updated_at)
</insert>

<select id="selectByGroupLinkId" resultType="com.armada.group.model.entity.GroupLinkHealth">
  SELECT id, tenant_id AS tenantId, group_link_id AS groupLinkId, health_status AS healthStatus,
         is_banned AS banned, current_count AS currentCount, last_check_at AS lastCheckAt,
         last_health_error AS lastHealthError, health_failure_count AS healthFailureCount,
         created_at AS createdAt, updated_at AS updatedAt
  FROM group_link_health
  WHERE group_link_id = #{groupLinkId}
  LIMIT 1
</select>
```

- [ ] **Step 5: DbTests**

Write each test with these assertions:

1. Insert a `group_link`.
2. Upsert preview/health row.
3. Select by `groupLinkId`.
4. Assert fields round trip.
5. Upsert again with changed values.
6. Assert unique row updated, not duplicated.

Run:

```bash
armada-api/dbtest.sh 'com.armada.group.mapper.GroupLinkPreviewMapperDbTest,com.armada.group.mapper.GroupLinkHealthMapperDbTest'
```

Expected: PASS。

- [ ] **Step 6: Commit mapper/entity work**

```bash
git add armada-api/src/main/java/com/armada/group/model/entity \
        armada-api/src/main/java/com/armada/group/model/vo \
        armada-api/src/main/java/com/armada/group/converter/GroupConverter.java \
        armada-api/src/main/java/com/armada/group/mapper \
        armada-api/src/main/resources/mapper/group \
        armada-api/src/test/java/com/armada/group/mapper
git commit -m "feat(group): add group preview and health mappers"
```

---

# Phase 4 — 导入链接入池规则

### Task 4.1: 写业务规则 DbTest

**Files:**
- Modify: `armada-api/src/test/java/com/armada/group/service/impl/GroupLinkImportServiceDbTest.java`

- [ ] **Step 1: 替换旧 EXISTS 用例**

Remove or rewrite `importLinks_existingActiveUrl_reportsExists_labelUnchanged`.

Add three tests:

```java
@Test
void importLinks_existingImportLink_failsAsDuplicateAndDoesNotMoveLabel() {
    // labelA 先导入链接, labelB 再导入同链接
    // 断言: after.labelId 仍是 labelA; result.failedRows=1; duplicateRows=1;
    // detail.result=FAILED; failReason=重复; groupLinkId=null
}

@Test
void importLinks_existingPullTaskLinkWithoutLabel_adoptsIntoImportGroup() {
    // 手动插入 group_link: origin=PULL_TASK, labelId=null, importBatchId=null
    // 导入同链接到 label
    // 断言: labelId/importBatchId 被写入; origin 仍 PULL_TASK;
    // result.successRows=1; detail.successType=ADOPTED; existingOrigin=PULL_TASK
}

@Test
void importLinks_batchDuplicate_failsSecondRowAsDuplicate() {
    // 同一批两行同链接
    // 断言: 第一行 SUCCESS/INSERTED; 第二行 FAILED/重复; duplicateRows=1; failedRows=1
}
```

Use constants:

```java
GroupLinkImportResult.SUCCESS.code()
GroupLinkImportResult.FAILED.code()
GroupLinkImportSuccessType.INSERTED.code()
GroupLinkImportSuccessType.ADOPTED.code()
GroupLinkOrigin.PULL_TASK.code()
GroupLinkImportFailReason.DUPLICATE
```

- [ ] **Step 2: 更新混合结果用例**

Existing `importLinks_mixedOutcomes_batchCountsCorrect` asserts:

```java
assertThat(result.totalRows()).isEqualTo(4);
assertThat(result.successRows()).isEqualTo(1);
assertThat(result.duplicateRows()).isEqualTo(2); // 已在导入链接重复 + 批内重复
assertThat(result.failedRows()).isEqualTo(3);    // 两个重复 + 一个格式错误
assertThat(result.formatErrorRows()).isEqualTo(1);
```

- [ ] **Step 3: Run to verify red**

```bash
armada-api/dbtest.sh 'com.armada.group.service.impl.GroupLinkImportServiceDbTest'
```

Expected: FAIL because service still uses old `EXISTS` behavior and old VO fields。

### Task 4.2: 改 GroupLinkImportServiceImpl

**Files:**
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/GroupLinkImportServiceImpl.java`

- [ ] **Step 1: 改 Persisted record**

Replace current `Persisted(GroupLinkImportResult result, Long linkId)` with:

```java
private record Persisted(GroupLinkImportResult result,
                         Integer successType,
                         String failReason,
                         Long linkId,
                         Integer existingOrigin) {
}
```

- [ ] **Step 2: 改 persist 规则**

Use this logic:

```java
private Persisted persist(Long labelId, Long batchId, String url) {
    GroupLink existing = groupLinkMapper.selectAnyByUrl(url);
    long now = System.currentTimeMillis();
    if (existing == null) {
        GroupLink row = new GroupLink();
        row.setLinkUrl(url);
        row.setLabelId(labelId);
        row.setImportBatchId(batchId);
        row.setOrigin(GroupLinkOrigin.IMPORT.code());
        row.setMembershipState(GroupMembershipState.TARGET.code());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        groupLinkMapper.insert(row);
        return new Persisted(GroupLinkImportResult.SUCCESS,
                GroupLinkImportSuccessType.INSERTED.code(), null, row.getId(), null);
    }
    if (existing.getDeletedAt() != null) {
        groupLinkMapper.adoptToLabel(existing.getId(), labelId, batchId, null, now);
        return new Persisted(GroupLinkImportResult.SUCCESS,
                GroupLinkImportSuccessType.INSERTED.code(), null, existing.getId(), null);
    }
    if (existing.getLabelId() == null) {
        groupLinkMapper.adoptActiveIntoImport(existing.getId(), labelId, batchId, now);
        return new Persisted(GroupLinkImportResult.SUCCESS,
                GroupLinkImportSuccessType.ADOPTED.code(), null, existing.getId(), existing.getOrigin());
    }
    return new Persisted(GroupLinkImportResult.FAILED, null,
            GroupLinkImportFailReason.DUPLICATE, null, null);
}
```

Imports:

```java
import com.armada.group.model.enums.GroupLinkImportFailReason;
import com.armada.group.model.enums.GroupLinkImportSuccessType;
import com.armada.group.model.enums.GroupLinkOrigin;
import com.armada.group.model.enums.GroupMembershipState;
```

- [ ] **Step 3: 改循环计数**

Counters:

```java
int inserted = 0;
int adopted = 0;
int duplicate = 0;
int formatError = 0;
```

Mapping:

- `Kind.FAILED`: `result=FAILED`, `failReason=FORMAT_ERROR`, `formatError++`, add error.
- `Kind.DUPLICATE`: `result=FAILED`, `failReason=DUPLICATE`, `duplicate++`.
- persisted success/inserted: `result=SUCCESS`, `successType=INSERTED`, `inserted++`.
- persisted success/adopted: `result=SUCCESS`, `successType=ADOPTED`, `existingOrigin` set, `adopted++`.
- persisted failed duplicate: `result=FAILED`, `failReason=DUPLICATE`, `duplicate++`.

Set:

```java
d.setSuccessType(p.successType());
d.setFailReason(p.failReason());
d.setExistingOrigin(p.existingOrigin());
d.setGroupLinkId(p.linkId());
```

For failed duplicate, keep `groupLinkId=null`.

- [ ] **Step 4: 改批次统计**

```java
batch.setTotalRows(outcomes.size());
batch.setInsertedRows(inserted);
batch.setAdoptedRows(adopted);
batch.setDuplicateRows(duplicate);
batch.setFailedRows(duplicate + formatError);
importBatchMapper.updateCounts(batch);
```

- [ ] **Step 5: 改返回 VO**

```java
return new GroupLinkImportResultVO(
        batch.getId(),
        outcomes.size(),
        inserted + adopted,
        duplicate + formatError,
        duplicate,
        formatError,
        errors);
```

- [ ] **Step 6: 更新日志字段**

Log `inserted`, `adopted`, `duplicate`, `formatError`, `failedRows`，不要再写 `exists`。

- [ ] **Step 7: Run service DbTest**

```bash
armada-api/dbtest.sh 'com.armada.group.service.impl.GroupLinkImportServiceDbTest'
```

Expected: PASS。

### Task 4.3: 更新导入明细查询和导出测试

**Files:**
- Modify: `armada-api/src/test/java/com/armada/group/mapper/GroupLinkImportDetailMapperDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/converter/GroupConverterTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/controller/GroupLinkImportControllerTest.java`

- [ ] **Step 1: Detail Mapper 测试改失败口径**

Change failed export assertions to select `result=FAILED` rows:

```java
assertThat(rows).allSatisfy(row ->
        assertThat(row.getResult()).isEqualTo(GroupLinkImportResult.FAILED.code()));
```

- [ ] **Step 2: Converter 测试补标签**

Assert:

```java
assertThat(vo.resultLabel()).isEqualTo("成功");
assertThat(vo.successTypeLabel()).isEqualTo("收编已有群");
assertThat(vo.existingOriginLabel()).isEqualTo("拉群任务");
```

- [ ] **Step 3: Controller 测试更新响应字段**

Existing import result JSON asserts `successRows`, `failedRows`, `duplicateRows`, `formatErrorRows` instead of `inserted`, `exists`, `duplicated`, `failed`.

- [ ] **Step 4: Run group tests**

```bash
armada-api/dbtest.sh 'GroupLinkImportDetailMapperDbTest'
cd armada-api && mvn -q -Dtest='GroupConverterTest,GroupLinkImportControllerTest' test
```

Expected: PASS。

- [ ] **Step 5: Commit import rule work**

```bash
git add armada-api/src/main/java/com/armada/group \
        armada-api/src/main/resources/mapper/group \
        armada-api/src/test/java/com/armada/group
git commit -m "feat(group): enforce import adoption and duplicate rules"
```

---

# Phase 5 — 文档与数据模型刷新

### Task 5.1: 刷新自动数据模型文档

**Files:**
- Modify: `.harness/wiki/数据模型.md`

- [ ] **Step 1: 导出真库 information_schema**

Use the workspace database wrapper that points to the test RDS:

```bash
./mysql-mcp-testdb.sh --batch --raw --skip-column-names -e \
"SELECT table_name,column_name,column_type,is_nullable,column_default,column_comment,ordinal_position
 FROM information_schema.columns
 WHERE table_schema = DATABASE()
 ORDER BY table_name, ordinal_position" > /tmp/wheel_columns.tsv
```

```bash
./mysql-mcp-testdb.sh --batch --raw --skip-column-names -e \
"SELECT table_name,index_name,column_name,non_unique,seq_in_index
 FROM information_schema.statistics
 WHERE table_schema = DATABASE()
 ORDER BY table_name,index_name,seq_in_index" > /tmp/wheel_indexes.tsv
```

```bash
./mysql-mcp-testdb.sh --batch --raw --skip-column-names -e \
"SELECT table_name,table_comment
 FROM information_schema.tables
 WHERE table_schema = DATABASE()
 ORDER BY table_name" > /tmp/wheel_tables.tsv
```

- [ ] **Step 2: 运行生成脚本**

```bash
python3 .harness/wiki/gen_datamodel.py
```

Then copy `/tmp/datamodel_tables.md` into `.harness/wiki/数据模型.md`.

- [ ] **Step 3: Verify key sections**

Run:

```bash
rg -n "group_link_preview|group_link_health|duplicate_rows|origin|membership_state" .harness/wiki/数据模型.md
```

Expected: all five terms appear.

### Task 5.2: 全量验证

**Files:** no new source files.

- [ ] **Step 1: XML validation**

```bash
xmllint --noout armada-api/src/main/resources/mapper/group/*.xml
```

Expected: no output, exit 0。

- [ ] **Step 2: DbTests**

```bash
armada-api/dbtest.sh 'Group*DbTest'
```

Expected: PASS。

- [ ] **Step 3: Unit/controller tests**

```bash
cd armada-api && mvn -q -Dtest='Group*Test' test
```

Expected: PASS。

- [ ] **Step 4: Compile**

```bash
cd armada-api && mvn -q -DskipTests compile
```

Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit docs and final fixes**

```bash
git add .harness/wiki/数据模型.md \
        .harness/changes/group-list/summary.md \
        armada-api/src/main/java/com/armada/group \
        armada-api/src/main/resources/mapper/group \
        armada-api/src/test/java/com/armada/group
git commit -m "docs(group): refresh group list data model"
```

---

## Acceptance Criteria

- `group_link` has `origin` and `membership_state`.
- `group_link_preview` and `group_link_health` exist and have passing Mapper DbTests.
- `group_link_import_batch` uses `duplicate_rows`; `skipped_rows` is gone.
- `group_link_import_detail.result` is only `1=成功` or `2=失败`.
- Importing a link already in an import group fails with `fail_reason=重复` and does not move groups.
- Importing a link from拉群/进群/自建 with `label_id IS NULL` succeeds as `success_type=收编已有群`.
- Frontend-facing import result exposes success total, not inserted/adopted split.
- Group DbTests, XML validation, and compile all pass.

## Execution Notes

- The test DB credentials come from `armada-api/.env`; do not print or commit that file.
- Keep `V010__group_list_data_model.sql` unless another `V010` appears before implementation; if so, choose the next free version and update this plan line.
- This plan intentionally stops before group list API and frontend. Start a separate plan after this data-model slice is green.
