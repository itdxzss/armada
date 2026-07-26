# Group Pull Lock Convergence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将拉群营销运行期显式锁收敛为单个任务主键行锁，并用条件更新安全处理材料、额度、执行结算和资源释放竞争。

**Architecture:** `marketing_task` 作为同任务唯一显式互斥点；其它实体普通读取，写入时由状态条件、唯一键和影响行数裁决。释放候选取消发生竞争时结束当前轮次，避免 MySQL RR 旧快照继续释放任务级资源。

**Tech Stack:** Java 17、Spring Boot 3.3.5、Spring Transaction、MyBatis/MyBatis-Plus、MySQL 8、H2 MySQL mode、JUnit 5、AssertJ。

---

### Task 1: 用测试锁定目标 SQL 与释放竞争语义

**Files:**
- Modify: `armada-api/src/test/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingAllocatorSqlShapeTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingLifecycleSqlShapeTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingReleaseServiceTest.java`
- Modify: `armada-api/src/test/java/com/armada/testsupport/MysqlModeMapperInMemoryTest.java`

- [ ] **Step 1: 写 SQL 收敛失败测试**

在 allocator SQL 测试中断言 `selectAvailableMaterials` 和 `selectAccountStat` 存在、带原业务条件且不含
`FOR UPDATE`；在 lifecycle SQL 测试中断言整个 `GroupPullMarketingMapper.xml` 只有
`selectTaskForUpdate` 的 SQL 正文包含 `FOR UPDATE`，并断言普通释放候选查询保留 `task_id`、
`group_name IS NULL`、活动状态和 `ORDER BY id`。

- [ ] **Step 2: 写释放竞争失败测试**

在 `GroupPullMarketingReleaseServiceTest` 增加：一条候选执行的 `cancelPreGroupExecution` 返回 `0` 时，
`tryRelease` 返回 `false`，且不调用 `releaseExecutionMaterials`、营销命令取消、残留账号释放、分组释放和
`markResourceReleased`。

- [ ] **Step 3: 更新 H2 目标行为测试**

把 `groupPullReleaseLockQueryExecutesAndKeepsTenantBoundary` 改为普通释放候选查询测试；断言租户插件自动隔离
租户 7 和租户 8，并断言对应方法不再使用 `InterceptorIgnore`。

- [ ] **Step 4: 运行测试确认红灯**

Run:

```bash
cd armada-api
mvn -Dtest='GroupPullMarketingAllocatorSqlShapeTest,GroupPullMarketingLifecycleSqlShapeTest,GroupPullMarketingReleaseServiceTest,MysqlModeMapperInMemoryTest#groupPullReleaseCandidateQueryExecutesAndKeepsTenantBoundary' test
```

Expected: FAIL，原因是普通 Mapper 方法尚不存在、XML 仍有五条额外 `FOR UPDATE`，且释放竞争仍继续释放资源。

### Task 2: 收敛 Mapper API 和 SQL

**Files:**
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingAllocator.java`

- [ ] **Step 1: 修改 Mapper API**

保留 `selectTaskForUpdate`，删除 `selectTaskByIdForUpdate` 和 `selectExecutionByIdForUpdate`；其调用方使用已有
`selectTaskById`、`selectExecutionById`。将方法改名为：

```java
List<GroupPullMarketingMaterial> selectAvailableMaterials(
        @Param("taskId") Long taskId,
        @Param("limit") int limit);

GroupPullMarketingAccountStat selectAccountStat(
        @Param("taskId") Long taskId,
        @Param("accountId") Long accountId);

List<GroupPullMarketingExecution> selectCancelableExecutions(@Param("taskId") Long taskId);
```

删除释放查询的显式租户委托、`TenantContext` 读取和 `@InterceptorIgnore`。

- [ ] **Step 2: 修改 XML**

删除任务扩展和 execution 的锁定查询；材料、额度统计和释放候选查询使用新 ID 并删除 `FOR UPDATE`。材料
`ORDER BY line_no ASC LIMIT #{limit}`、释放候选 `ORDER BY id` 及所有状态条件保持不变。

- [ ] **Step 3: 修改 allocator 调用**

任务扩展改为 `selectTaskById`，额度统计改为 `selectAccountStat`，材料改为
`selectAvailableMaterials`。继续保留任务锁、额度条件更新、材料条件更新和影响行数校验。

- [ ] **Step 4: 运行 SQL 与 H2 测试**

Run:

```bash
cd armada-api
mvn -Dtest='GroupPullMarketingAllocatorSqlShapeTest,GroupPullMarketingLifecycleSqlShapeTest,MysqlModeMapperInMemoryTest#groupPullReleaseCandidateQueryExecutesAndKeepsTenantBoundary' test
```

Expected: PASS。

### Task 3: 消除服务层反向锁序

**Files:**
- Create: `armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingExecutionWorkerTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingFinalizerTest.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingFinalizer.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingExecutionWorker.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingReleaseService.java`

- [ ] **Step 1: 写 Finalizer 条件结算失败测试**

把现有 Finalizer 测试代理改为期待 `selectExecutionById` 和 `selectTaskById`；新增
`markExecutionTerminal` 返回 `0` 时不调用材料、额度、账号释放和营销目标副作用的断言。

- [ ] **Step 2: 写群名锁序失败测试**

创建 Worker 测试，通过无操作事务管理器和 Mapper 记录代理推进一条 `CREATE_GROUP` 执行；让协议创建在群名
保存后抛出测试异常，断言调用顺序包含：

```text
selectTaskForUpdate -> selectExecutionById -> saveGroupNameIfAbsent
```

当前实现会产生 `selectExecutionByIdForUpdate -> selectTaskForUpdate`，因此测试必须先失败。

- [ ] **Step 3: 运行服务测试确认红灯**

Run:

```bash
cd armada-api
mvn -Dtest='GroupPullMarketingFinalizerTest,GroupPullMarketingExecutionWorkerTest,GroupPullMarketingReleaseServiceTest' test
```

Expected: FAIL，原因是调用 API 和锁顺序尚未切换，释放竞争尚未提前结束。

- [ ] **Step 4: 修改 Finalizer 和 Worker**

Finalizer 使用普通 execution/任务扩展查询，仍以 `markExecutionTerminal` 返回 `1` 作为唯一副作用入口。群名
冻结先调用 `selectTaskForUpdate`，再调用 `selectExecutionById`；执行已不存在、已终态或已有群名时按当前幂等
语义返回或失败，不新增兼容路径。

- [ ] **Step 5: 修改 ReleaseService**

任务扩展普通读取。`cancelPreGroupExecutions` 返回包含已取消数量和是否稳定的私有结果；任一
`cancelPreGroupExecution` 返回 `0` 时停止处理后续候选并使 `tryRelease` 返回 `false`，不继续任务级释放。

- [ ] **Step 6: 运行服务测试确认转绿**

Run:

```bash
cd armada-api
mvn -Dtest='GroupPullMarketingFinalizerTest,GroupPullMarketingExecutionWorkerTest,GroupPullMarketingReleaseServiceTest' test
```

Expected: PASS。

### Task 4: 完整验证与提交

**Files:**
- Verify: `armada-api/src/main/java/com/armada/marketing/grouppull/**`
- Verify: `armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml`
- Verify: `armada-api/src/test/java/com/armada/marketing/grouppull/**`
- Verify: `armada-api/src/test/java/com/armada/testsupport/MysqlModeMapperInMemoryTest.java`

- [ ] **Step 1: XML 与锁数量检查**

Run:

```bash
xmllint --noout armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml
rg -n -i 'FOR UPDATE' armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml
```

Expected: XML 合法；实际 SQL 只剩 `selectTaskForUpdate` 一处 `FOR UPDATE`。

- [ ] **Step 2: 运行聚焦回归和 H2 测试**

Run:

```bash
cd armada-api
mvn -Dtest='GroupPullMarketing*Test,MysqlModeMapperInMemoryTest' test
```

Expected: PASS，0 failures，0 errors。

- [ ] **Step 3: 运行全量测试和生产打包脚本测试**

Run:

```bash
cd armada-api
mvn test
cd ..
bash armada-deploy/package-prod.test.sh
```

Expected: 两条命令退出码均为 0。

- [ ] **Step 4: 检查差异并提交**

Run:

```bash
git diff --check
git status --short
git diff -- armada-api docs/superpowers/plans/2026-07-26-group-pull-lock-convergence.md
```

仅暂存本计划和拉群营销锁收敛相关文件，不包含 `.claude/worktrees` 状态；提交信息：

```bash
git commit -m "fix(marketing): 收敛拉群营销行锁"
```
