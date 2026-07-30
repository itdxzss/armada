# Flyway V085 Conflict Resolution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 恢复测试库已执行的 V085 迁移，并将当前尚未执行的历史群迁移顺延到 V086/V087，使 Flyway validate 与后续 migrate 都能通过。

**Architecture:** 数据库迁移历史是不可改写的事实源，因此保留已执行 V085 的文件名、内容和 checksum。两个当前分支新增迁移仅调整版本文件名，业务 SQL 保持不变；Java 合同测试同步锁定已执行 checksum 和新路径。

**Tech Stack:** Java 17、JUnit 5、AssertJ、Flyway 10.10.0、Maven、MySQL Flyway SQL

---

### Task 1: 用测试锁定正确迁移历史

**Files:**
- Modify: `armada-api/src/test/java/com/armada/boot/FlywayAppliedMigrationCompatibilityTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/HistoricalGroupPreviewSchemaSqlTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/HistoricalGroupPullSourceAccountGroupSqlTest.java`

- [ ] **Step 1: 写失败测试**

在兼容性测试中增加：

```java
assertAppliedMigration(
        "V085__account_group_membership_last_exit.sql",
        810_248_183);
```

并把两条历史群合同测试的迁移路径分别改为：

```java
"src/main/resources/db/migration/V086__historical_group_created_at.sql"
"src/main/resources/db/migration/V087__historical_group_pull_source_account_group.sql"
```

- [ ] **Step 2: 运行测试确认 RED**

Run:

```bash
mvn -Dtest='FlywayAppliedMigrationCompatibilityTest,HistoricalGroupPreviewSchemaSqlTest,HistoricalGroupPullSourceAccountGroupSqlTest' test
```

Expected: FAIL；旧 V085 文件缺失或 checksum 不匹配，且新 V087 路径不存在。

### Task 2: 恢复迁移历史并顺延待执行迁移

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V085__account_group_membership_last_exit.sql`
- Rename: `armada-api/src/main/resources/db/migration/V085__historical_group_created_at.sql` → `armada-api/src/main/resources/db/migration/V086__historical_group_created_at.sql`
- Rename: `armada-api/src/main/resources/db/migration/V086__historical_group_pull_source_account_group.sql` → `armada-api/src/main/resources/db/migration/V087__historical_group_pull_source_account_group.sql`

- [ ] **Step 1: 恢复旧 V085**

从提交 `c0c88b0` 恢复 `V085__account_group_membership_last_exit.sql` 的完整内容，不做任何格式化或语义修改，确保 checksum 为 `810248183`。

- [ ] **Step 2: 顺延新迁移**

只移动两个迁移文件，不修改 SQL 内容，使历史群创建时间先于来源账号组迁移执行。

- [ ] **Step 3: 运行聚焦测试确认 GREEN**

Run:

```bash
mvn -Dtest='FlywayAppliedMigrationCompatibilityTest,FlywayMigrationVersionContractTest,HistoricalGroupPreviewSchemaSqlTest,HistoricalGroupPullSourceAccountGroupSqlTest' test
```

Expected: BUILD SUCCESS，相关测试零失败。

### Task 3: 完整验证与变更审计

**Files:**
- Verify: `armada-api/src/main/resources/db/migration/`
- Verify: `armada-api/src/test/java/com/armada/boot/`
- Verify: `armada-api/src/test/java/com/armada/group/`

- [ ] **Step 1: 运行完整测试**

Run:

```bash
mvn test
```

Expected: BUILD SUCCESS，零失败。

- [ ] **Step 2: 核验迁移版本与 checksum**

Run:

```bash
git diff --check
git diff --name-status
```

Expected: 无 whitespace 错误；diff 仅包含本次设计、计划、三条迁移文件和三处测试更新。

- [ ] **Step 3: 提交本次修复**

```bash
git add docs/superpowers/specs/2026-07-30-flyway-v085-conflict-resolution-design.md \
  docs/superpowers/plans/2026-07-30-flyway-v085-conflict-resolution.md \
  armada-api/src/main/resources/db/migration/V085__account_group_membership_last_exit.sql \
  armada-api/src/main/resources/db/migration/V086__historical_group_created_at.sql \
  armada-api/src/main/resources/db/migration/V087__historical_group_pull_source_account_group.sql \
  armada-api/src/test/java/com/armada/boot/FlywayAppliedMigrationCompatibilityTest.java \
  armada-api/src/test/java/com/armada/group/HistoricalGroupPreviewSchemaSqlTest.java \
  armada-api/src/test/java/com/armada/group/HistoricalGroupPullSourceAccountGroupSqlTest.java
git commit -m "fix: resolve Flyway V085 migration conflict"
```
