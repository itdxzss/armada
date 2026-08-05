# Flyway V093-V098 Reconciliation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 恢复第一套测试库兼容的 V090-V097 Flyway 历史，将尚未执行的新迁移顺延到 V098，并重新部署 test1 后端。

**Architecture:** 以 test1 的 `flyway_schema_history` 文件名和 checksum 为不可变事实，在代码侧恢复相同迁移历史，不修改数据库记录。通过版本唯一性和历史 checksum 契约测试保护迁移目录，部署时只允许 Flyway 新执行 V098。

**Tech Stack:** Java 17、Spring Boot、Flyway、JUnit 5、AssertJ、Maven、Bash、Docker Compose、MySQL 8。

---

## 文件结构

- Modify: `armada-api/src/test/java/com/armada/boot/FlywayAppliedMigrationCompatibilityTest.java`：锁定 test1 已执行 V090-V097 的文件名和 checksum。
- Verify: `armada-api/src/test/java/com/armada/boot/FlywayMigrationVersionContractTest.java`：验证整个迁移目录没有 Flyway 等价重复版本。
- Move content: `armada-api/src/main/resources/db/migration/V093__whatsapp_group_member_cache.sql` → `V096__whatsapp_group_member_cache.sql`：恢复成员缓存迁移的已发布版本。
- Create: `armada-api/src/main/resources/db/migration/V097__group_departure_unknown_metadata.sql`：恢复 test1 已执行的退群 UNKNOWN 注释迁移。
- Move content: `armada-api/src/main/resources/db/migration/V096__group_list_history_metadata.sql` → `V098__group_list_history_metadata.sql`：为尚未执行的新迁移分配下一个空闲版本。
- Modify: `armada-api/src/test/java/com/armada/group/mapper/WhatsappGroupMemberCacheMapperMysqlTest.java`：读取恢复后的 V096 路径。
- Modify: `armada-api/src/test/java/com/armada/group/GroupListHistoryMetadataMigrationSqlTest.java`：读取顺延后的 V098 路径。
- Deploy: `armada-deploy/deploy-test.sh`：只部署 test1 后端。

### Task 1: 用测试锁定 test1 已执行的迁移历史

**Files:**
- Modify: `armada-api/src/test/java/com/armada/boot/FlywayAppliedMigrationCompatibilityTest.java`
- Test: `armada-api/src/test/java/com/armada/boot/FlywayMigrationVersionContractTest.java`

- [ ] **Step 1: 扩充历史兼容性断言**

在现有 V090、V091 断言之后加入以下精确映射：

```java
assertAppliedMigration(
        "V092__whatsapp_group_member_join_fact.sql",
        -1_133_243_864);
assertAppliedMigration(
        "V093__pull_task_normal_link_execution.sql",
        -1_160_226_712);
assertAppliedMigration(
        "V094__pull_task_group_account_membership_result.sql",
        -1_117_482_777);
assertAppliedMigration(
        "V095__pull_task_standard_full_form_settings.sql",
        -1_758_254_373);
assertAppliedMigration(
        "V096__whatsapp_group_member_cache.sql",
        380_433_951);
assertAppliedMigration(
        "V097__group_departure_unknown_metadata.sql",
        -1_292_654_150);
```

- [ ] **Step 2: 运行测试确认红灯**

Run:

```bash
cd armada-api
mvn -q -Dtest=FlywayAppliedMigrationCompatibilityTest,FlywayMigrationVersionContractTest test
```

Expected: FAIL；兼容性测试报告 V096/V097 文件缺失，版本测试报告
`V93: V093__pull_task_normal_link_execution.sql, V093__whatsapp_group_member_cache.sql`。

### Task 2: 恢复 V096/V097 并顺延新迁移到 V098

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V096__whatsapp_group_member_cache.sql`
- Create: `armada-api/src/main/resources/db/migration/V097__group_departure_unknown_metadata.sql`
- Create: `armada-api/src/main/resources/db/migration/V098__group_list_history_metadata.sql`
- Delete: `armada-api/src/main/resources/db/migration/V093__whatsapp_group_member_cache.sql`
- Delete: `armada-api/src/main/resources/db/migration/V096__group_list_history_metadata.sql`
- Modify: `armada-api/src/test/java/com/armada/group/mapper/WhatsappGroupMemberCacheMapperMysqlTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/GroupListHistoryMetadataMigrationSqlTest.java`

- [ ] **Step 1: 只移动两个尚存 SQL 的版本路径**

使用补丁进行字节不变的路径移动：

```text
V093__whatsapp_group_member_cache.sql
  -> V096__whatsapp_group_member_cache.sql

V096__group_list_history_metadata.sql
  -> V098__group_list_history_metadata.sql
```

成员缓存文件内容不得修改，以保持 checksum `380433951`。群列表历史元数据 SQL 内容也不得修改。

- [ ] **Step 2: 恢复 V097 的精确 SQL**

创建 `V097__group_departure_unknown_metadata.sql`，内容与仓库提交 `9d17127` 完全一致：

```sql
-- 同步群关系与退群事实的 UNKNOWN 枚举说明；仅更新列注释，不改写业务数据。
ALTER TABLE account_group_membership
    MODIFY COLUMN last_exit_type TINYINT NULL
        COMMENT '最近退出方式:3被踢 4主动退出 5退出原因未知';

ALTER TABLE whatsapp_group_departed_member
    MODIFY COLUMN exit_type VARCHAR(16) NOT NULL
        COMMENT '退出方式:LEFT主动退群/REMOVED被移出/UNKNOWN原因未识别';
```

- [ ] **Step 3: 更新测试资源路径**

在 `WhatsappGroupMemberCacheMapperMysqlTest` 中替换：

```java
"/db/migration/V093__whatsapp_group_member_cache.sql"
```

为：

```java
"/db/migration/V096__whatsapp_group_member_cache.sql"
```

在 `GroupListHistoryMetadataMigrationSqlTest` 中替换：

```java
"src/main/resources/db/migration/V096__group_list_history_metadata.sql"
```

为：

```java
"src/main/resources/db/migration/V098__group_list_history_metadata.sql"
```

- [ ] **Step 4: 运行聚焦测试确认转绿**

Run:

```bash
cd armada-api
mvn -q -Dtest=FlywayAppliedMigrationCompatibilityTest,FlywayMigrationVersionContractTest,GroupListHistoryMetadataMigrationSqlTest,WhatsappGroupMemberCacheMapperMysqlTest test
```

Expected: PASS；如果本机 Docker 不可用，Testcontainers 测试按 `disabledWithoutDocker = true` 跳过，其余测试必须通过。

- [ ] **Step 5: 检查改动并提交修复**

Run:

```bash
git diff --check
git status --short
```

只暂存上述迁移和测试文件以及本实施计划，保留 `.claude/worktrees` 的既有状态。提交信息：

```bash
git commit -m "fix(db): reconcile Flyway V093-V098 history"
```

### Task 3: 完整验证、部署 test1 并确认恢复

**Files:**
- Verify: `armada-api/target/*.jar`
- Deploy: `armada-deploy/deploy-test.sh`

- [ ] **Step 1: 运行完整测试和打包**

Run:

```bash
cd armada-api
mvn test
mvn -q -DskipTests clean package
```

Expected: 测试和打包退出码均为 0；任何与本次改动相关的失败都必须先修复。

- [ ] **Step 2: 验证最终制品迁移目录**

Run:

```bash
jar tf target/armada-api-1.0.2-SNAPSHOT.jar \
  | rg 'BOOT-INF/classes/db/migration/V0(9[0-8])__' \
  | sort
```

Expected: V090-V098 每个版本恰好一条；V096 为成员缓存，V097 为退群 UNKNOWN 元数据，V098 为群列表历史元数据，不存在第二个 V093。

- [ ] **Step 3: 重新只读核对 test1 数据库历史**

通过已确认的 test1 SSH 目标，在后端容器内使用既有数据库环境执行只读查询，只输出
V090-V097 的 `version`、`script`、`checksum`、`success`。不得打印密码或完整环境变量。

Expected: 与 Task 1 的八条契约逐项一致且 `success=1`。任一项不一致则停止部署。

- [ ] **Step 4: 执行部署预检并只部署后端**

Run:

```bash
./armada-deploy/deploy-test.sh --env test1 --be --branch 1.0.2-snapshot --dry-run
./armada-deploy/deploy-test.sh --env test1 --be --branch 1.0.2-snapshot -y
```

Expected: 预检只包含 test1 后端；正式命令构建、同步、重建容器和健康检查全部成功。

- [ ] **Step 5: 验证容器、日志、API 和 Flyway 历史**

部署后连续两次读取后端容器状态和 restart count，中间间隔不超过 15 秒；检查最新启动日志，并调用现有未登录后端接口。

Expected:

- 容器为 running，restart count 两次相同；
- 有新鲜的 `Started Application` 日志；
- 无 duplicate version、checksum mismatch、Flyway migration failure；
- API 返回预期未登录业务 JSON，不再返回 Nginx 502；
- `flyway_schema_history` 中 V090-V097 保持原值，V098 恰好一条且 `success=1`。

- [ ] **Step 6: 记录最终提交和部署结果**

Run:

```bash
git status --short
git log -2 --oneline
```

Expected: 仅保留开始前已有的 `.claude/worktrees` 状态；交付信息包含修复提交、测试结果、部署结果和 V098 执行结果，不包含密钥、数据库口令、手机号或远端配置内容。
