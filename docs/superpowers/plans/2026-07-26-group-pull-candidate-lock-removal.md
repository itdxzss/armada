# 拉群营销候选账号查询去锁 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 去掉建群账号和营销账号候选联表查询的 `FOR UPDATE`，保留现有唯一键原子抢占与精确额度锁。

**Architecture:** 同一任务继续由任务行锁串行化；候选账号使用普通租户查询，建群账号由 `marketing_account_occupancy` 唯一键决定抢占结果，营销账号额度由现有统计行锁控制。本次不修改其他锁查询。

**Tech Stack:** Java 17、Spring Boot、MyBatis/MyBatis-Plus、MySQL 8.4、JUnit 5、AssertJ、H2 MySQL 模式。

**Execution constraint:** 用户明确要求本次只修改工作区，不创建 Git 提交。

---

### Task 1: 建立失败回归测试

**Files:**
- Modify: `armada-api/src/test/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingAllocatorSqlShapeTest.java`
- Modify: `armada-api/src/test/java/com/armada/testsupport/MysqlModeMapperInMemoryTest.java`

- [x] **Step 1: 修改 SQL 结构测试，要求两个候选查询无锁**

```java
assertThat(block(xml, "select", "selectBuilderCandidateForUpdate"))
        .contains("marketing_occupancy_task_id IS NULL")
        .contains("ORDER BY a.created_at DESC")
        .contains("LIMIT 1")
        .doesNotContain("FOR UPDATE");
assertThat(block(xml, "select", "selectMarketerCandidateForUpdate"))
        .contains("reserved_group_count")
        .contains("joined_group_count")
        .contains("ORDER BY a.created_at DESC")
        .contains("LIMIT 1")
        .doesNotContain("FOR UPDATE");
```

- [x] **Step 2: 增加租户插件开启时的真实 Mapper 执行测试**

为 H2 测试 schema 补齐 `account`、`account_state`、`marketing_account_occupancy`、`group_pull_marketing_account_stat`，并给 `account_group` 增加营销占用列。插入当前租户和其他租户候选后执行：

```java
GroupPullAccountRefRow builder = groupPullMarketingMapper
        .selectBuilderCandidateForUpdate(157L, 278L);
GroupPullAccountRefRow marketer = groupPullMarketingMapper
        .selectMarketerCandidateForUpdate(157L, 336L, 10);

assertThat(builder.getAccountId()).isEqualTo(41L);
assertThat(marketer.getAccountId()).isEqualTo(42L);
```

- [x] **Step 3: 运行测试并确认 RED**

Run:

```bash
cd armada-api
mvn -q -Dtest=GroupPullMarketingAllocatorSqlShapeTest,MysqlModeMapperInMemoryTest test
```

Expected: `GroupPullMarketingAllocatorSqlShapeTest` 因候选 SQL 仍包含 `FOR UPDATE` 失败，H2 查询因租户插件生成 `FOR UPDATE ORDER BY ... LIMIT 1` 报 SQL 语法错误。

### Task 2: 最小化去锁并同步命名

**Files:**
- Modify: `armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingMapper.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingAllocator.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingAllocatorSqlShapeTest.java`
- Modify: `armada-api/src/test/java/com/armada/testsupport/MysqlModeMapperInMemoryTest.java`

- [x] **Step 1: 删除两条候选 SQL 尾部的 `FOR UPDATE`**

两条 SQL 都保留：

```xml
ORDER BY a.created_at DESC, a.id DESC
LIMIT 1
```

- [x] **Step 2: 将 Mapper 方法改为无锁命名**

```java
GroupPullAccountRefRow selectBuilderCandidate(
        @Param("taskId") Long taskId,
        @Param("builderGroupId") Long builderGroupId);

GroupPullAccountRefRow selectMarketerCandidate(
        @Param("taskId") Long taskId,
        @Param("marketingGroupId") Long marketingGroupId,
        @Param("limit") int limit);
```

同步 XML `id`、分配器调用点和测试查询方法名；注释改为“选择候选账号”，不再描述锁定读取。

- [x] **Step 3: 运行针对性测试并确认 GREEN**

Run:

```bash
cd armada-api
mvn -q -Dtest=GroupPullMarketingAllocatorSqlShapeTest,MysqlModeMapperInMemoryTest test
```

Expected: 两个测试类全部通过。

### Task 3: 完成前验证并保留未提交改动

**Files:**
- Verify all modified files above

- [x] **Step 1: 校验 XML 和差异格式**

```bash
xmllint --noout armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml
git diff --check
```

Expected: 两条命令退出码均为 0。

- [x] **Step 2: 编译打包**

```bash
cd armada-api
mvn -q -DskipTests package
```

Expected: 退出码为 0。

- [x] **Step 3: 展示未提交差异**

```bash
git status --short
git diff --stat
git diff -- armada-api/src/main/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingMapper.java armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingAllocator.java armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml armada-api/src/test/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingAllocatorSqlShapeTest.java armada-api/src/test/java/com/armada/testsupport/MysqlModeMapperInMemoryTest.java
```

Expected: 仅显示本次代码、测试和计划文件，以及用户原有的无关 worktree 状态；不执行 `git commit`。
