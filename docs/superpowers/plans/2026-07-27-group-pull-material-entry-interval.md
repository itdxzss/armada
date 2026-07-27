# Group Pull Material Entry Interval Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将拉群营销的批量加料改为前端可配置的逐料随机间隔执行，默认每次等待 5 分钟并在上下 20% 范围内随机。

**Architecture:** 后端把基准间隔保存在拉群任务扩展表中，复用单群执行的 `next_execute_at` 和 `stage_retry_count` 做持久化调度；每次 worker 只向协议层提交一个料子，成功、失败和重试都原子保存下一次到期时间。前端只在现有创建抽屉增加分钟输入并转换成秒提交，详情页只读回显。

**Tech Stack:** Java 17、Spring Boot 3.3.5、MyBatis/MySQL 8、Flyway、JUnit 5、AssertJ、Mockito；Vue 3、TypeScript、Element Plus、Node test runner、pnpm。

---

## 文件结构

后端仓库为 `/Users/daishuaishuai/IdeaProjects/armada`：

- 新建 `armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMaterialEntryDelayPolicy.java`，只负责配置归一化、随机边界和下一次时间计算。
- 新建 `armada-api/src/main/resources/db/migration/V080__group_pull_material_entry_interval.sql`，只增加任务级间隔字段。
- 扩展拉群 DTO、实体、VO 和 Mapper，保持 API、数据库和详情回显同名字段 `materialEntryIntervalSeconds`。
- 扩展 `GroupPullMarketingExecutionWorker`，保留其它群操作的原重试策略，只替换 `ADD_MATERIALS` 为持久化逐条执行。
- 新建聚焦测试 `GroupPullMarketingMaterialEntryWorkerTest` 和 `GroupPullMarketingResumeSchedulingTest`，避免继续把单一锁序测试文件膨胀成综合测试。

前端仓库为 `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web`：

- 新建 `src/views/task/group-pull-marketing/material-entry-interval.ts`，负责纯函数式提示文本计算。
- 修改现有 API、页面 composable、创建抽屉和详情摘要，不增加路由或页面。
- 新建 `.harness/changes/group-pull-material-entry-interval/summary.md`，记录跨仓功能的前端边界和最终验证证据。

### Task 1: 建立后端间隔策略

**Files:**
- Create: `armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMaterialEntryDelayPolicy.java`
- Create: `armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMaterialEntryDelayPolicyTest.java`

- [ ] **Step 1: 写配置默认值、边界和随机端点失败测试**

```java
package com.armada.marketing.grouppull.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GroupPullMaterialEntryDelayPolicyTest {

    @Test
    void normalizesDefaultAndRejectsValuesOutsideWholeMinuteRange() {
        assertThat(GroupPullMaterialEntryDelayPolicy.normalizeBaseSeconds(null)).isEqualTo(300);
        assertThat(GroupPullMaterialEntryDelayPolicy.normalizeBaseSeconds(60)).isEqualTo(60);
        assertThat(GroupPullMaterialEntryDelayPolicy.normalizeBaseSeconds(3600)).isEqualTo(3600);
        assertThatThrownBy(() -> GroupPullMaterialEntryDelayPolicy.normalizeBaseSeconds(59))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GroupPullMaterialEntryDelayPolicy.normalizeBaseSeconds(3601))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GroupPullMaterialEntryDelayPolicy.normalizeBaseSeconds(301))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void calculatesInclusiveTwentyPercentWindowAndUsesRandomSecond() {
        GroupPullMaterialEntryDelayPolicy lower =
                new GroupPullMaterialEntryDelayPolicy((origin, bound) -> origin);
        GroupPullMaterialEntryDelayPolicy upper =
                new GroupPullMaterialEntryDelayPolicy((origin, bound) -> bound - 1);

        assertThat(lower.delayWindow(300))
                .isEqualTo(new GroupPullMaterialEntryDelayPolicy.DelayWindow(240_000L, 360_000L));
        assertThat(lower.nextExecuteAt(1_000L, 300)).isEqualTo(241_000L);
        assertThat(upper.nextExecuteAt(1_000L, 300)).isEqualTo(361_000L);
    }
}
```

- [ ] **Step 2: 运行测试确认红灯**

Run:

```bash
cd armada-api
mvn -Dtest=GroupPullMaterialEntryDelayPolicyTest test
```

Expected: FAIL，类 `GroupPullMaterialEntryDelayPolicy` 尚不存在。

- [ ] **Step 3: 实现无状态配置规则和可测随机源**

```java
package com.armada.marketing.grouppull.service;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/** 拉群营销逐料执行的基准间隔校验和随机调度策略。 */
@Component
public class GroupPullMaterialEntryDelayPolicy {

    public static final int DEFAULT_BASE_SECONDS = 300;
    public static final int MIN_BASE_SECONDS = 60;
    public static final int MAX_BASE_SECONDS = 3_600;
    private static final int JITTER_MIN_PERCENT = 80;
    private static final int JITTER_MAX_PERCENT = 120;

    private final LongRangeRandom random;

    public GroupPullMaterialEntryDelayPolicy() {
        this((origin, bound) -> ThreadLocalRandom.current().nextLong(origin, bound));
    }

    GroupPullMaterialEntryDelayPolicy(LongRangeRandom random) {
        this.random = random;
    }

    public static int normalizeBaseSeconds(Integer configuredSeconds) {
        int value = configuredSeconds == null ? DEFAULT_BASE_SECONDS : configuredSeconds;
        if (value < MIN_BASE_SECONDS || value > MAX_BASE_SECONDS || value % 60 != 0) {
            throw new IllegalArgumentException("拉料间隔必须是1到60的整数分钟");
        }
        return value;
    }

    public DelayWindow delayWindow(int configuredSeconds) {
        int base = normalizeBaseSeconds(configuredSeconds);
        long minSeconds = base * JITTER_MIN_PERCENT / 100L;
        long maxSeconds = base * JITTER_MAX_PERCENT / 100L;
        return new DelayWindow(minSeconds * 1_000L, maxSeconds * 1_000L);
    }

    public long nextExecuteAt(long now, int configuredSeconds) {
        DelayWindow window = delayWindow(configuredSeconds);
        long minSeconds = window.minDelayMillis() / 1_000L;
        long maxSeconds = window.maxDelayMillis() / 1_000L;
        return now + random.nextLong(minSeconds, maxSeconds + 1L) * 1_000L;
    }

    public record DelayWindow(long minDelayMillis, long maxDelayMillis) {
    }

    @FunctionalInterface
    interface LongRangeRandom {
        long nextLong(long originInclusive, long boundExclusive);
    }
}
```

- [ ] **Step 4: 运行测试确认转绿**

Run:

```bash
cd armada-api
mvn -Dtest=GroupPullMaterialEntryDelayPolicyTest test
```

Expected: PASS，2 tests，0 failures。

- [ ] **Step 5: 提交策略类**

```bash
git add armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMaterialEntryDelayPolicy.java \
  armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMaterialEntryDelayPolicyTest.java
git commit -m "feat(marketing): add group pull material delay policy"
```

### Task 2: 持久化任务配置并扩展 API

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V080__group_pull_material_entry_interval.sql`
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/model/dto/CreateGroupPullMarketingTaskDTO.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/model/entity/GroupPullMarketingTask.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/model/vo/GroupPullMarketingTaskDetailVO.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/service/impl/GroupPullMarketingTaskServiceImpl.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml`
- Modify: `armada-api/src/test/java/com/armada/marketing/grouppull/GroupPullMarketingMigrationSqlTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/grouppull/GroupPullMarketingSchemaDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingMapperDbTest.java`
- Create: `armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingTaskConfigurationTest.java`

- [ ] **Step 1: 写迁移、Mapper 往返和服务校验失败测试**

在 `GroupPullMarketingMigrationSqlTest` 增加对新迁移的独立读取，避免修改历史 V070：

```java
private static final Path MATERIAL_INTERVAL_MIGRATION = Path.of(
        "src/main/resources/db/migration/V080__group_pull_material_entry_interval.sql");

@Test
void materialEntryIntervalMigrationAddsBackwardCompatibleDefault() throws IOException {
    String sql = Files.readString(MATERIAL_INTERVAL_MIGRATION, StandardCharsets.UTF_8);
    assertThat(sql)
            .contains("ADD COLUMN material_entry_interval_seconds INT NOT NULL DEFAULT 300")
            .contains("AFTER material_per_group");
}
```

在 `GroupPullMarketingMapperDbTest` 的 fixture 中设置 `task.setMaterialEntryIntervalSeconds(600)`，并增加：

```java
assertThat(mapper.selectTaskById(taskId).getMaterialEntryIntervalSeconds()).isEqualTo(600);
```

新建 `GroupPullMarketingTaskConfigurationTest`，以无依赖 service 验证非法值在访问数据库前被拒绝：

```java
@Test
void rejectsMaterialEntryIntervalOutsideWholeMinuteRange() {
    GroupPullMarketingTaskServiceImpl service =
            new GroupPullMarketingTaskServiceImpl(null, null, null, null, null, null, null, null);

    assertThatThrownBy(() -> service.create(request(59), null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("拉料间隔必须是1到60的整数分钟");
}

private CreateGroupPullMarketingTaskDTO request(Integer intervalSeconds) {
    return new CreateGroupPullMarketingTaskDTO(
            "间隔测试", 1L, null, null, 2L, 10, 3L, 30,
            null, 3, 3, intervalSeconds, 1, true, null,
            System.currentTimeMillis() + 60_000L);
}
```

- [ ] **Step 2: 运行聚焦测试确认红灯**

Run:

```bash
cd armada-api
mvn -Dtest='GroupPullMarketingMigrationSqlTest,GroupPullMarketingTaskConfigurationTest' test
```

Expected: FAIL，新迁移和 DTO 字段尚不存在。

- [ ] **Step 3: 新增 V080 迁移**

```sql
ALTER TABLE group_pull_marketing_task
    ADD COLUMN material_entry_interval_seconds INT NOT NULL DEFAULT 300
        COMMENT '逐个拉料的基准间隔秒数;实际按上下20%随机'
        AFTER material_per_group;
```

- [ ] **Step 4: 扩展 DTO、实体和详情 VO**

在 `CreateGroupPullMarketingTaskDTO` 中把字段放在 `materialPerGroup` 后：

```java
Integer materialPerGroup,
Integer materialEntryIntervalSeconds,
Integer speakPermission,
```

在 `GroupPullMarketingTask` 增加字段和标准 getter/setter：

```java
private Integer materialEntryIntervalSeconds;

public Integer getMaterialEntryIntervalSeconds() {
    return materialEntryIntervalSeconds;
}

public void setMaterialEntryIntervalSeconds(Integer materialEntryIntervalSeconds) {
    this.materialEntryIntervalSeconds = materialEntryIntervalSeconds;
}
```

在 `GroupPullMarketingTaskDetailVO` 的 `materialPerGroup` 后增加：

```java
Integer materialEntryIntervalSeconds,
```

- [ ] **Step 5: 接入后端校验和默认值**

在 `validateRequest` 中调用统一策略并把异常转换成业务校验错误：

```java
try {
    GroupPullMaterialEntryDelayPolicy.normalizeBaseSeconds(
            request.materialEntryIntervalSeconds());
} catch (IllegalArgumentException exception) {
    throw new BusinessException(ErrorCode.VALIDATION, exception.getMessage());
}
```

在 `buildExtension` 中写入归一化值：

```java
row.setMaterialEntryIntervalSeconds(
        GroupPullMaterialEntryDelayPolicy.normalizeBaseSeconds(
                request.materialEntryIntervalSeconds()));
```

- [ ] **Step 6: 扩展 MyBatis 插入、读取和详情映射**

在 `TaskResultMap`、`TaskColumns`、`insertTask` 和 `TaskDetailResultMap` 中把 `material_entry_interval_seconds` 与 `materialEntryIntervalSeconds` 放在 `material_per_group` 后；详情查询增加：

```sql
gp.material_entry_interval_seconds AS materialEntryIntervalSeconds,
```

插入列和值分别增加：

```xml
material_per_group, material_entry_interval_seconds, speak_permission
#{materialPerGroup}, #{materialEntryIntervalSeconds}, #{speakPermission}
```

- [ ] **Step 7: 扩展真库结构断言**

在 `GroupPullMarketingSchemaDbTest.migrationCreatesFiveTablesAndPublicColumns` 增加：

```java
assertThat(columnExists(
        "group_pull_marketing_task", "material_entry_interval_seconds")).isTrue();
```

- [ ] **Step 8: 运行单测和可用时的真库测试**

Run:

```bash
cd armada-api
mvn -Dtest='GroupPullMarketingMigrationSqlTest,GroupPullMarketingTaskConfigurationTest,GroupPullMaterialEntryDelayPolicyTest' test
```

Expected: PASS。

在已明确连接本地测试 MySQL 时再运行：

```bash
./dbtest.sh GroupPullMarketingSchemaDbTest,GroupPullMarketingMapperDbTest
```

Expected: PASS，V080 字段存在且 Mapper 往返为 600；未配置测试 MySQL 时记录跳过原因，不连接远程环境。

- [ ] **Step 9: 提交后端契约**

```bash
git add armada-api/src/main/resources/db/migration/V080__group_pull_material_entry_interval.sql \
  armada-api/src/main/java/com/armada/marketing/grouppull/model/dto/CreateGroupPullMarketingTaskDTO.java \
  armada-api/src/main/java/com/armada/marketing/grouppull/model/entity/GroupPullMarketingTask.java \
  armada-api/src/main/java/com/armada/marketing/grouppull/model/vo/GroupPullMarketingTaskDetailVO.java \
  armada-api/src/main/java/com/armada/marketing/grouppull/service/impl/GroupPullMarketingTaskServiceImpl.java \
  armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml \
  armada-api/src/test/java/com/armada/marketing/grouppull/GroupPullMarketingMigrationSqlTest.java \
  armada-api/src/test/java/com/armada/marketing/grouppull/GroupPullMarketingSchemaDbTest.java \
  armada-api/src/test/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingMapperDbTest.java \
  armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingTaskConfigurationTest.java
git commit -m "feat(marketing): persist group pull material interval"
```

### Task 3: 增加逐料状态持久化 SQL

**Files:**
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml`
- Modify: `armada-api/src/test/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingLifecycleSqlShapeTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/grouppull/GroupPullMarketingRecoveryDbTest.java`

- [ ] **Step 1: 写 SQL 形状和幂等状态失败测试**

在 `GroupPullMarketingLifecycleSqlShapeTest` 增加：

```java
@Test
void materialEntryQueriesAreSingleRowConditionalAndPersistRetrySchedule() throws IOException {
    String xml = readResource(MAPPER_XML);
    assertThat(block(xml, "select", "selectNextPendingExecutionMaterial"))
            .contains("entry_status = 1")
            .contains("ORDER BY em.allocation_no ASC")
            .contains("LIMIT 1");
    assertThat(block(xml, "update", "updateMaterialStageProgress"))
            .contains("stage_retry_count = #{nextRetryCount}")
            .contains("stage_retry_count = #{expectedRetryCount}")
            .contains("next_execute_at = #{nextExecuteAt}")
            .contains("current_stage = #{expectedStage}");
    assertThat(block(xml, "update", "rescheduleMaterialExecutionsOnResume"))
            .contains("current_stage = 5")
            .contains("entry_status = 1")
            .contains("RAND()")
            .contains("next_execute_at");
    assertThat(block(xml, "update", "updateMaterialEntryResult"))
            .contains("entry_status = 1");
}
```

在 `GroupPullMarketingRecoveryDbTest` 将 `markGroupCreated` 调用扩展为显式下一次时间，并增加一次只有预期 retry count 才能改排期的断言：

```java
long nextExecuteAt = now + 300_000L;
assertThat(mapper.markGroupCreated(
        executionId, 3, "recovery-group@g.us", 5, nextExecuteAt, now + 2)).isEqualTo(1);
assertThat(jdbc.queryForObject(
        "SELECT next_execute_at FROM group_pull_marketing_execution WHERE id = ?",
        Long.class, executionId)).isEqualTo(nextExecuteAt);

assertThat(mapper.updateMaterialStageProgress(
        executionId, 2, 5, 0, 1, now + 600_000L, null, now + 3)).isEqualTo(1);
assertThat(mapper.updateMaterialStageProgress(
        executionId, 2, 5, 0, 1, now + 700_000L, null, now + 4)).isZero();
```

同一恢复测试后续 `markExecutionTerminal` 的 `expectedStage` 从 4 改成 5，与本段 `markGroupCreated(... nextStage=5 ...)` 保持一致。

- [ ] **Step 2: 运行测试确认红灯**

Run:

```bash
cd armada-api
mvn -Dtest='GroupPullMarketingLifecycleSqlShapeTest' test
```

Expected: FAIL，新 Mapper 方法尚不存在。

- [ ] **Step 3: 增加 Mapper 方法契约**

```java
GroupPullMarketingExecutionMaterial selectNextPendingExecutionMaterial(
        @Param("executionId") Long executionId);

long countPendingExecutionMaterials(@Param("executionId") Long executionId);

int updateMaterialStageProgress(
        @Param("id") Long id,
        @Param("executionStatus") int executionStatus,
        @Param("expectedStage") int expectedStage,
        @Param("expectedRetryCount") int expectedRetryCount,
        @Param("nextRetryCount") int nextRetryCount,
        @Param("nextExecuteAt") long nextExecuteAt,
        @Param("reason") String reason,
        @Param("now") long now);

int rescheduleMaterialExecutionsOnResume(
        @Param("taskId") Long taskId,
        @Param("now") long now,
        @Param("minDelayMillis") long minDelayMillis,
        @Param("maxDelayMillis") long maxDelayMillis);
```

把 `markGroupCreated` 扩展为：

```java
int markGroupCreated(
        @Param("id") Long id,
        @Param("expectedStage") int expectedStage,
        @Param("groupJid") String groupJid,
        @Param("nextStage") int nextStage,
        @Param("nextExecuteAt") long nextExecuteAt,
        @Param("createdAt") long createdAt);
```

- [ ] **Step 4: 实现单条查询、条件进度和恢复随机 SQL**

```xml
<select id="selectNextPendingExecutionMaterial" resultMap="ExecutionMaterialResultMap">
    SELECT em.id, em.tenant_id, em.execution_id, em.material_id,
           material.phone AS material_phone,
           em.allocation_no, em.friend_status, em.friend_failure_reason,
           em.entry_status, em.entry_failure_reason, em.created_at, em.updated_at
    FROM group_pull_marketing_execution_material em
    JOIN group_pull_marketing_material material ON material.id = em.material_id
    WHERE em.execution_id = #{executionId}
      AND em.entry_status = 1
    ORDER BY em.allocation_no ASC
    LIMIT 1
</select>

<select id="countPendingExecutionMaterials" resultType="long">
    SELECT COUNT(*)
    FROM group_pull_marketing_execution_material
    WHERE execution_id = #{executionId}
      AND entry_status = 1
</select>

<update id="updateMaterialStageProgress">
    UPDATE group_pull_marketing_execution
    SET stage_retry_count = #{nextRetryCount},
        next_execute_at = #{nextExecuteAt},
        failure_reason = CASE
            WHEN #{reason} IS NULL OR #{reason} = '' THEN failure_reason
            ELSE LEFT(CONCAT_WS(';', NULLIF(failure_reason, ''), #{reason}), 255)
        END,
        updated_at = #{now}
    WHERE id = #{id}
      AND execution_status = #{executionStatus}
      AND current_stage = #{expectedStage}
      AND stage_retry_count = #{expectedRetryCount}
</update>

<update id="rescheduleMaterialExecutionsOnResume">
    UPDATE group_pull_marketing_execution
    SET next_execute_at = #{now} + #{minDelayMillis}
            + FLOOR(RAND() * (
                (#{maxDelayMillis} - #{minDelayMillis}) / 1000 + 1
              )) * 1000,
        updated_at = #{now}
    WHERE task_id = #{taskId}
      AND execution_status IN (1, 2)
      AND current_stage = 5
      AND EXISTS (
          SELECT 1
          FROM group_pull_marketing_execution_material relation
          WHERE relation.execution_id = group_pull_marketing_execution.id
            AND relation.entry_status = 1
      )
</update>
```

把 `markGroupCreated` 的 `next_execute_at` 改为 `#{nextExecuteAt}`；把 `updateMaterialEntryResult` 的条件改为 `entry_status = 1`。

- [ ] **Step 5: 运行 SQL 测试和可用时的恢复真库测试**

Run:

```bash
cd armada-api
mvn -Dtest='GroupPullMarketingLifecycleSqlShapeTest' test
```

Expected: PASS。

在本地测试 MySQL 可用时运行 `./dbtest.sh GroupPullMarketingRecoveryDbTest`，Expected: PASS；未配置时不访问远程环境。

- [ ] **Step 6: 提交持久化状态机 SQL**

```bash
git add armada-api/src/main/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingMapper.java \
  armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml \
  armada-api/src/test/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingLifecycleSqlShapeTest.java \
  armada-api/src/test/java/com/armada/marketing/grouppull/GroupPullMarketingRecoveryDbTest.java
git commit -m "feat(marketing): persist paced material entry progress"
```

### Task 4: 首个料子等待并逐条推进

**Files:**
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingExecutionWorker.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingExecutionWorkerTest.java`
- Create: `armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingMaterialEntryWorkerTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/grouppull/scheduler/GroupPullMarketingSchedulerTest.java`

- [ ] **Step 1: 写首个等待、单成员成功、间隔失败重试和群封禁测试**

新测试用 Mockito 创建处于 `ADD_MATERIALS` 的执行，固定 delay policy 返回 `now + 250_000`。核心断言：

```java
@Test
void addsOnlyFirstPendingMaterialAndSchedulesNextOne() {
    fixture.pending(material(11L, 1, "861380000001"), material(12L, 2, "861380000002"));
    fixture.participantSuccess("861380000001@s.whatsapp.net");

    fixture.worker().process(501L);

    verify(fixture.participantPort).updateParticipants(
            eq(fixture.builder.protocolRef()), eq("group@g.us"),
            eq(List.of("861380000001@s.whatsapp.net")), eq(GroupParticipantAction.ADD));
    verify(fixture.participantPort, times(1)).updateParticipants(any(), any(), anyList(), any());
    verify(fixture.mapper).updateMaterialEntryResult(11L, 2, null, anyLong());
    verify(fixture.mapper).updateMaterialStageProgress(
            501L, 2, 5, 0, 0, fixture.nextExecuteAt, null, anyLong());
    verify(fixture.mapper, never()).advanceExecutionStage(
            anyLong(), anyInt(), anyInt(), eq(6), anyInt(), anyLong(), anyLong());
}

@Test
void advancesOnlyAfterLastPendingMaterialSucceeds() {
    fixture.pending(material(11L, 1, "861380000001"));
    fixture.participantSuccess("861380000001@s.whatsapp.net");
    when(fixture.mapper.countPendingExecutionMaterials(501L)).thenReturn(0L);

    fixture.worker().process(501L);

    verify(fixture.mapper).advanceExecutionStage(
            501L, 2, 5, 6, 2, anyLong(), anyLong());
}
```

测试文件内定义完整、可复用的最小 fixture，不调用网络或真实数据库：

```java
private static final PlatformTransactionManager NO_OP_TRANSACTION_MANAGER =
        new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };

private static GroupPullMarketingExecutionMaterial material(
        long id, int allocationNo, String phone) {
    GroupPullMarketingExecutionMaterial row = new GroupPullMarketingExecutionMaterial();
    row.setId(id);
    row.setExecutionId(501L);
    row.setMaterialId(id + 100L);
    row.setAllocationNo(allocationNo);
    row.setMaterialPhone(phone);
    row.setEntryStatus(1);
    return row;
}

private static final class Fixture {
    private final GroupPullMarketingMapper mapper = mock(GroupPullMarketingMapper.class);
    private final GroupPullMarketingFinalizer finalizer = mock(GroupPullMarketingFinalizer.class);
    private final GroupParticipantPort participantPort = mock(GroupParticipantPort.class);
    private final GroupPullMaterialEntryDelayPolicy delayPolicy =
            mock(GroupPullMaterialEntryDelayPolicy.class);
    private final GroupPullMarketingExecution execution = new GroupPullMarketingExecution();
    private final GroupPullAccountRefRow builder = new GroupPullAccountRefRow();
    private final GroupPullMarketingTask task = new GroupPullMarketingTask();
    private final MarketingTask runtimeTask = new MarketingTask();
    private final long nextExecuteAt = 9_999_000L;

    private Fixture() {
        execution.setId(501L);
        execution.setTaskId(101L);
        execution.setBuilderAccountId(201L);
        execution.setMarketingAccountId(301L);
        execution.setGroupJid("group@g.us");
        execution.setExecutionStatus(GroupPullExecutionStatus.EXECUTING.code());
        execution.setCurrentStage(GroupPullExecutionStage.ADD_MATERIALS.code());
        execution.setStageRetryCount(0);
        execution.setNextExecuteAt(0L);
        builder.setAccountId(201L);
        builder.setWsPhone("8613900000201");
        builder.setProtocolId("WEB");
        builder.setProtocolAccountId("acc_201");
        builder.setAccountState(AccountStateCode.NORMAL);
        builder.setLoginState(AccountLoginStateCode.ONLINE);
        task.setMarketingTaskId(101L);
        task.setMaterialEntryIntervalSeconds(300);
        task.setResourceStatus(GroupPullResourceStatus.LOCKED.code());
        runtimeTask.setId(101L);
        runtimeTask.setStatus(MarketingTaskStatus.SENDING.code());
        runtimeTask.setTaskEndAt(System.currentTimeMillis() + 3_600_000L);
        when(mapper.selectExecutionById(501L)).thenReturn(execution);
        when(mapper.tryLeaseExecution(eq(501L), eq(2), eq(5), anyLong(), anyLong()))
                .thenReturn(1);
        when(mapper.selectAccountRef(201L)).thenReturn(builder);
        when(mapper.selectTaskById(101L)).thenReturn(task);
        when(mapper.selectTaskRuntime(101L)).thenReturn(runtimeTask);
        when(mapper.updateMaterialEntryResult(anyLong(), anyInt(), any(), anyLong()))
                .thenReturn(1);
        when(mapper.updateMaterialStageProgress(
                anyLong(), anyInt(), anyInt(), anyInt(), anyInt(),
                anyLong(), any(), anyLong())).thenReturn(1);
        when(mapper.advanceExecutionStage(
                anyLong(), anyInt(), anyInt(), anyInt(), anyInt(), anyLong(), anyLong()))
                .thenReturn(1);
        when(mapper.appendExecutionFailureReason(anyLong(), any(), anyLong())).thenReturn(1);
        when(delayPolicy.nextExecuteAt(anyLong(), eq(300))).thenReturn(nextExecuteAt);
    }

    private GroupPullMarketingExecutionWorker worker() {
        return new GroupPullMarketingExecutionWorker(
                mapper, finalizer, null, null, null, participantPort,
                null, null, null, null, delayPolicy, NO_OP_TRANSACTION_MANAGER);
    }

    private void pending(GroupPullMarketingExecutionMaterial... rows) {
        when(mapper.selectNextPendingExecutionMaterial(501L))
                .thenReturn(rows.length == 0 ? null : rows[0]);
        when(mapper.countPendingExecutionMaterials(501L))
                .thenReturn(Math.max(rows.length - 1L, 0L));
    }

    private void participantSuccess(String jid) {
        when(participantPort.updateParticipants(any(), any(), anyList(), any()))
                .thenReturn(new GroupParticipantBatchResult(
                        false, List.of(new GroupParticipantBatchResult.Item(jid, "OK", "200"))));
    }

    private void participantFailure(String rawStatus) {
        when(participantPort.updateParticipants(any(), any(), anyList(), any()))
                .thenReturn(new GroupParticipantBatchResult(
                        false, List.of(new GroupParticipantBatchResult.Item(
                                "861380000001@s.whatsapp.net", "FAILED", rawStatus))));
    }

    private void groupBanned() {
        when(participantPort.updateParticipants(any(), any(), anyList(), any()))
                .thenThrow(new ProtocolException(
                        ProtocolErrorCode.GROUP_UNAVAILABLE, "GROUP_BANNED"));
    }
}
```

另写两个建群路径测试：建群响应已确认营销账号时，`markGroupCreated` 的 `nextStage=5` 且 `nextExecuteAt` 为随机未来时间；需要补加营销账号时先进入阶段 4，补加成功后推进阶段 5 时才写随机未来时间。再加入首次失败只排一次未来重试、第三次失败才标记 relation 失败、明确群封禁立即收口，以及 `ALREADY_IN` 按成功处理的测试；这些测试中的时间参数用 `anyLong()` 捕获当前时间，只对固定 policy 返回的 `nextExecuteAt` 做精确断言。

- [ ] **Step 2: 运行 worker 测试确认红灯**

Run:

```bash
cd armada-api
mvn -Dtest='GroupPullMarketingExecutionWorkerTest,GroupPullMarketingMaterialEntryWorkerTest,GroupPullMarketingSchedulerTest' test
```

Expected: FAIL，worker 仍批量提交且构造器没有 delay policy。

- [ ] **Step 3: 注入 delay policy 并更新测试构造器**

在 worker 增加字段和构造参数：

```java
private final GroupPullMaterialEntryDelayPolicy materialEntryDelayPolicy;

public GroupPullMarketingExecutionWorker(
        GroupPullMarketingMapper mapper,
        GroupPullMarketingFinalizer finalizer,
        GroupLinkRegistryService groupRegistry,
        ContactPort contactPort,
        GroupCreatePort groupCreatePort,
        GroupParticipantPort participantPort,
        GroupSettingsPort settingsPort,
        GroupMemberListPort memberListPort,
        GroupInvitePort invitePort,
        GroupLeavePort leavePort,
        GroupPullMaterialEntryDelayPolicy materialEntryDelayPolicy,
        PlatformTransactionManager transactionManager) {
    this.mapper = mapper;
    this.finalizer = finalizer;
    this.groupRegistry = groupRegistry;
    this.contactPort = contactPort;
    this.groupCreatePort = groupCreatePort;
    this.participantPort = participantPort;
    this.settingsPort = settingsPort;
    this.memberListPort = memberListPort;
    this.invitePort = invitePort;
    this.leavePort = leavePort;
    this.materialEntryDelayPolicy = materialEntryDelayPolicy;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
}
```

给 `GroupPullMarketingExecutionWorkerTest` 和 `GroupPullMarketingSchedulerTest` 的所有 `super(...)`/构造调用在事务管理器前补一个 policy 参数；不执行真实 worker 的测试可以传 `null`。

- [ ] **Step 4: 让两个进入料子阶段的路径都先排期**

`saveCreatedGroup` 读取当前任务间隔并把 `markGroupCreated` 的到期时间区分为立即补营销号或延后加料：

```java
// createGroup 已读取 task；成功时调用 saveCreatedGroup(execution, task, marketer, result)。
private void saveCreatedGroup(
        GroupPullMarketingExecution execution,
        GroupPullMarketingTask task,
        GroupPullAccountRefRow marketer,
        GroupCreateResult result) {
    if (result == null || !StringUtils.hasText(result.groupJid())) {
        throw new ProtocolException(
                ProtocolErrorCode.GROUP_CREATE_RESULT_UNCONFIRMED,
                "建群成功响应缺少群 JID");
    }
    boolean marketerJoined = createResultConfirmsParticipant(result, marketer.getWsPhone());
    int nextStage = marketerJoined
            ? GroupPullExecutionStage.ADD_MATERIALS.code()
            : GroupPullExecutionStage.ADD_MARKETER.code();
    long now = System.currentTimeMillis();
    long nextExecuteAt = marketerJoined
            ? materialEntryDelayPolicy.nextExecuteAt(
                    now, task.getMaterialEntryIntervalSeconds())
            : now;
    transactionTemplate.executeWithoutResult(status -> {
        if (mapper.markGroupCreated(
                execution.getId(), GroupPullExecutionStage.CREATE_GROUP.code(),
                result.groupJid(), nextStage, nextExecuteAt, now) != 1) {
            throw new IllegalStateException("保存建群结果时执行状态已变化");
        }
        if (marketerJoined && mapper.confirmMarketingQuota(
                execution.getTaskId(), marketer.getAccountId(), now) != 1) {
            throw new IllegalStateException("营销账号群额度确认失败");
        }
    });
}
```

`addMarketer` 成功后先读取 task 并生成第一次加料时间：

```java
GroupPullMarketingTask task = requireTask(execution.getTaskId());
transactionTemplate.executeWithoutResult(status -> {
    long now = System.currentTimeMillis();
    if (mapper.confirmMarketingQuota(
            execution.getTaskId(), marketer.getAccountId(), now) != 1) {
        throw new IllegalStateException("营销账号群额度确认失败");
    }
    advanceAt(
            execution, GroupPullExecutionStage.ADD_MATERIALS,
            GroupPullExecutionStatus.EXECUTING,
            materialEntryDelayPolicy.nextExecuteAt(
                    now, task.getMaterialEntryIntervalSeconds()),
            now);
});
```

新增通用 helper：

```java
private void advanceAt(
        GroupPullMarketingExecution execution,
        GroupPullExecutionStage nextStage,
        GroupPullExecutionStatus nextStatus,
        long nextExecuteAt,
        long now) {
    if (mapper.advanceExecutionStage(
            execution.getId(), execution.getExecutionStatus(), execution.getCurrentStage(),
            nextStage.code(), nextStatus.code(), nextExecuteAt, now) != 1) {
        throw new IllegalStateException("推进拉群执行阶段失败");
    }
    execution.setCurrentStage(nextStage.code());
    execution.setExecutionStatus(nextStatus.code());
    execution.setStageRetryCount(0);
    execution.setNextExecuteAt(nextExecuteAt);
}
```

普通 `advance` 调用 `advanceAt(..., now, now)`；进入 `ADD_MATERIALS` 时传 `materialEntryDelayPolicy.nextExecuteAt(...)`。

- [ ] **Step 5: 把 `addMaterials` 改为单条协议调用**

成功路径只选择第一条 pending，并把数据库结果与下一次排期放进同一事务：

```java
private void addMaterials(
        GroupPullMarketingExecution execution,
        GroupPullAccountRefRow builder) {
    GroupPullMarketingExecutionMaterial material =
            mapper.selectNextPendingExecutionMaterial(execution.getId());
    if (material == null) {
        advance(execution, GroupPullExecutionStage.SET_MARKETER_ADMIN,
                GroupPullExecutionStatus.EXECUTING);
        return;
    }
    GroupPullMarketingTask task = requireTask(execution.getTaskId());
    MaterialAttempt attempt = attemptMaterial(execution, builder, material);
    if (attempt.groupBanned()) {
        finalizer.fail(execution.getId(), "群已封禁：" + attempt.failureReason());
        return;
    }
    if (attempt.success()) {
        completeMaterialAttempt(execution, task, material, true, null);
        return;
    }
    scheduleMaterialFailure(execution, task, material, attempt.failureReason());
}

private MaterialAttempt attemptMaterial(
        GroupPullMarketingExecution execution,
        GroupPullAccountRefRow builder,
        GroupPullMarketingExecutionMaterial material) {
    String jid = WhatsappJids.userJid(material.getMaterialPhone());
    try {
        GroupParticipantBatchResult result = participantPort.updateParticipants(
                builder.protocolRef(), execution.getGroupJid(), List.of(jid),
                GroupParticipantAction.ADD);
        GroupParticipantBatchResult.Item item = result == null || result.results() == null
                ? null
                : result.results().stream()
                        .filter(candidate -> candidate != null
                                && phoneOf(candidate.jid()).equals(phoneOf(jid)))
                        .findFirst()
                        .orElse(null);
        return GroupPullRetryPolicy.isParticipantSuccess(item)
                ? new MaterialAttempt(true, false, null)
                : new MaterialAttempt(false, false,
                        item == null ? "协议未确认料子进群" : firstText(item.rawStatus(), item.status()));
    } catch (ProtocolException exception) {
        if (groupBanned(exception)) {
            mapper.markGroupBanned(execution.getId(), System.currentTimeMillis());
            return new MaterialAttempt(false, true, compactReason(exception));
        }
        return new MaterialAttempt(false, false, compactReason(exception));
    } catch (RuntimeException exception) {
        return new MaterialAttempt(false, false, compactReason(exception));
    }
}

private record MaterialAttempt(boolean success, boolean groupBanned, String failureReason) {
}

private void scheduleMaterialFailure(
        GroupPullMarketingExecution execution,
        GroupPullMarketingTask task,
        GroupPullMarketingExecutionMaterial material,
        String reason) {
    int currentRetryCount = execution.getStageRetryCount() == null
            ? 0
            : Math.max(execution.getStageRetryCount(), 0);
    int failedAttempts = currentRetryCount + 1;
    if (failedAttempts < GroupPullRetryPolicy.groupOperationAttempts()) {
        long now = System.currentTimeMillis();
        long nextExecuteAt = materialEntryDelayPolicy.nextExecuteAt(
                now, task.getMaterialEntryIntervalSeconds());
        if (mapper.updateMaterialStageProgress(
                execution.getId(), execution.getExecutionStatus(),
                GroupPullExecutionStage.ADD_MATERIALS.code(),
                currentRetryCount, failedAttempts, nextExecuteAt, null, now) != 1) {
            throw new IllegalStateException("保存料子重试排期失败");
        }
        return;
    }
    completeMaterialAttempt(
            execution, task, material, false,
            "料子顺序" + material.getAllocationNo() + "进群失败：" + reason);
}

private void completeMaterialAttempt(
        GroupPullMarketingExecution execution,
        GroupPullMarketingTask task,
        GroupPullMarketingExecutionMaterial material,
        boolean success,
        String finalReason) {
    transactionTemplate.executeWithoutResult(status -> {
        long now = System.currentTimeMillis();
        int expectedRetryCount = execution.getStageRetryCount() == null
                ? 0
                : Math.max(execution.getStageRetryCount(), 0);
        if (mapper.updateMaterialEntryResult(
                material.getId(), success ? ENTRY_SUCCESS : ENTRY_FAILED,
                success ? null : finalReason, now) != 1) {
            throw new IllegalStateException("保存料子进群结果失败");
        }
        if (mapper.countPendingExecutionMaterials(execution.getId()) == 0) {
            if (!success && mapper.appendExecutionFailureReason(
                    execution.getId(), finalReason, now) != 1) {
                throw new IllegalStateException("保存料子失败原因失败");
            }
            advanceAt(
                    execution, GroupPullExecutionStage.SET_MARKETER_ADMIN,
                    GroupPullExecutionStatus.EXECUTING, now, now);
            return;
        }
        long nextExecuteAt = materialEntryDelayPolicy.nextExecuteAt(
                now, task.getMaterialEntryIntervalSeconds());
        if (mapper.updateMaterialStageProgress(
                execution.getId(), execution.getExecutionStatus(),
                GroupPullExecutionStage.ADD_MATERIALS.code(),
                expectedRetryCount, 0, nextExecuteAt,
                success ? null : finalReason, now) != 1) {
            throw new IllegalStateException("保存下一条料子排期失败");
        }
    });
}
```

协议请求和数据库不构成分布式事务；恢复后若协议已成功但数据库尚未提交，`ALREADY_IN` 会按成功处理，不消耗重试次数。所有关键 Mapper 影响行数必须等于 1，否则抛异常让事务回滚。

- [ ] **Step 6: 运行 worker 测试确认转绿**

Run:

```bash
cd armada-api
mvn -Dtest='GroupPullMarketingExecutionWorkerTest,GroupPullMarketingMaterialEntryWorkerTest,GroupPullMarketingSchedulerTest' test
```

Expected: PASS；一次只有一个 JID，首条、下一条和失败重试均有未来排期，第三次失败才结束当前料子。

- [ ] **Step 7: 提交逐条成功路径**

```bash
git add armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingExecutionWorker.java \
  armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingExecutionWorkerTest.java \
  armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingMaterialEntryWorkerTest.java \
  armada-api/src/test/java/com/armada/marketing/grouppull/scheduler/GroupPullMarketingSchedulerTest.java
git commit -m "feat(marketing): pace group pull material entries"
```

### Task 5: 离线处理、恢复重排和安全日志

**Files:**
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingMapper.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingExecutionWorker.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/service/impl/GroupPullMarketingTaskServiceImpl.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml`
- Modify: `armada-api/src/test/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingLifecycleSqlShapeTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingMaterialEntryWorkerTest.java`
- Create: `armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingResumeSchedulingTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingTaskGroupServiceTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingTaskConfigurationTest.java`

- [ ] **Step 1: 补充离线、恢复和安全日志测试并保留失败回归**

在 worker 测试覆盖以下精确断言：

```java
@Test
void firstFailureSchedulesOneRetryWithoutCallingProtocolAgain() {
    fixture.execution.setStageRetryCount(0);
    fixture.pending(material(11L, 1, "861380000001"));
    when(fixture.participantPort.updateParticipants(any(), any(), anyList(), any()))
            .thenThrow(new ProtocolException(ProtocolErrorCode.TIMEOUT, "timeout"));

    fixture.worker().process(501L);

    verify(fixture.participantPort, times(1)).updateParticipants(any(), any(), anyList(), any());
    verify(fixture.mapper).updateMaterialStageProgress(
            501L, 2, 5, 0, 1, fixture.nextExecuteAt, null, anyLong());
    verify(fixture.mapper, never()).updateMaterialEntryResult(anyLong(), anyInt(), any(), anyLong());
}

@Test
void thirdFailureMarksCurrentMaterialFailedThenSchedulesNext() {
    fixture.execution.setStageRetryCount(2);
    fixture.pending(material(11L, 1, "861380000001"), material(12L, 2, "861380000002"));
    fixture.participantFailure("403");

    fixture.worker().process(501L);

    verify(fixture.mapper).updateMaterialEntryResult(
            11L, 3, "料子顺序1进群失败：403", anyLong());
    verify(fixture.mapper).updateMaterialStageProgress(
            501L, 2, 5, 2, 0, fixture.nextExecuteAt,
            "料子顺序1进群失败：403", anyLong());
    verify(fixture.participantPort, times(1)).updateParticipants(any(), any(), anyList(), any());
}

@Test
void explicitGroupBanStopsExecutionWithoutSchedulingRetry() {
    fixture.pending(material(11L, 1, "861380000001"));
    fixture.groupBanned();

    fixture.worker().process(501L);

    verify(fixture.mapper).markGroupBanned(eq(501L), anyLong());
    verify(fixture.finalizer).fail(501L, "群已封禁：GROUP_BANNED");
    verify(fixture.mapper, never()).updateMaterialStageProgress(
            anyLong(), anyInt(), anyInt(), anyInt(), anyInt(), anyLong(), any(), anyLong());
}
```

这些首次失败、第三次失败和群封禁断言保留为回归。另增加离线测试：阶段 5 的建群账号离线时不调用协议，但按一次失败把 retry 由 0 改为 1 并排 4～6 分钟；其它阶段仍沿用 15 秒在线复查。增加一次 `ALREADY_IN` 回执测试，断言直接标记成功且不增加 retry。

新建 resume 测试，验证基准 300 秒时调用：

```java
verify(mapper).rescheduleMaterialExecutionsOnResume(
        eq(taskId), anyLong(), eq(240_000L), eq(360_000L));
```

`GroupPullMarketingResumeSchedulingTest` 使用以下业务 fixture；`detail()` 按新增字段后的 record 顺序构造：

```java
@Test
void resumeReschedulesWaitingMaterialExecutionsWithinTaskWindow() {
    long taskId = 101L;
    GroupPullMarketingMapper mapper = mock(GroupPullMarketingMapper.class);
    AccountGroupMapper accountGroupMapper = mock(AccountGroupMapper.class);
    AccountProtocolLookupService lookup = mock(AccountProtocolLookupService.class);
    GroupPullMaterialEntryDelayPolicy policy = new GroupPullMaterialEntryDelayPolicy();
    MarketingTask task = new MarketingTask();
    task.setId(taskId);
    task.setAccountGroupId(21L);
    GroupPullMarketingTask extension = new GroupPullMarketingTask();
    extension.setMarketingTaskId(taskId);
    extension.setBuilderGroupId(11L);
    extension.setResourceStatus(GroupPullResourceStatus.LOCKED.code());
    extension.setMaterialPerGroup(3);
    extension.setMaterialEntryIntervalSeconds(300);
    AccountGroup marketingGroup = new AccountGroup();
    marketingGroup.setId(21L);
    marketingGroup.setMarketingOccupancyType(MarketingBusinessType.GROUP_PULL.code());
    marketingGroup.setMarketingOccupancyTaskId(taskId);
    when(mapper.selectTaskForUpdate(taskId)).thenReturn(task);
    when(mapper.selectTaskById(taskId)).thenReturn(extension);
    when(accountGroupMapper.selectById(21L)).thenReturn(marketingGroup);
    when(mapper.resumeTask(eq(taskId), anyLong())).thenReturn(1);
    when(lookup.findRandomOnlineNormalByGroupId(11L)).thenReturn(Optional.empty());
    when(mapper.selectTaskDetail(taskId)).thenReturn(detail(taskId));

    GroupPullMarketingTaskServiceImpl service = new GroupPullMarketingTaskServiceImpl(
            mapper, null, null, accountGroupMapper, lookup, null, null, null, policy);

    service.resume(taskId);

    verify(mapper).rescheduleMaterialExecutionsOnResume(
            eq(taskId), anyLong(), eq(240_000L), eq(360_000L));
}

private GroupPullMarketingTaskDetailVO detail(long taskId) {
    return new GroupPullMarketingTaskDetailVO(
            taskId, "恢复测试", 2, 1, 2,
            11L, null, null, 21L, 10, 31L, 30,
            null, 3, 3, 300, 1, true, null,
            System.currentTimeMillis() + 60_000L,
            3, 0, 0, 0, 1, 0,
            System.currentTimeMillis(), System.currentTimeMillis());
}
```

用 Logback test appender 捕获逐料日志，断言包含 `taskId`、`executionId`、`allocationNo`、`attempt`、`result` 和 `nextExecuteAt`，且不包含测试手机号。

```java
@Test
void materialProgressLogContainsSafeRoutingFactsWithoutPhone() {
    ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
            LoggerFactory.getLogger(GroupPullMarketingExecutionWorker.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
        fixture.pending(material(11L, 1, "861380000001"));
        fixture.participantSuccess("861380000001@s.whatsapp.net");
        fixture.worker().process(501L);

        String messages = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
        assertThat(messages)
                .contains("taskId=101")
                .contains("executionId=501")
                .contains("allocationNo=1")
                .contains("attempt=1")
                .contains("result=SUCCESS")
                .doesNotContain("861380000001");
    } finally {
        logger.detachAppender(appender);
        appender.stop();
    }
}
```

在 `GroupPullMarketingLifecycleSqlShapeTest` 增加阶段 5 生命周期闸门：

```java
@Test
void dueExecutionQueryNeverAdvancesMaterialStageWhilePaused() throws IOException {
    String sql = block(readResource(MAPPER_XML), "select", "selectDueExecutionDispatches");
    assertThat(sql)
            .contains("execution.current_stage &lt;&gt; 5")
            .contains("task.status &lt;&gt; 5");
}
```

worker 测试再增加两条：暂停状态只延后原阶段且不调用协议；任务已结束或资源释放中时批量把尚未执行料子标记失败并推进管理员阶段，同样不调用协议。

```java
@Test
void releasingTaskSkipsAllPendingMaterialsWithoutProtocolCall() {
    fixture.pending(material(11L, 1, "861380000001"), material(12L, 2, "861380000002"));
    fixture.runtimeTask.setStatus(MarketingTaskStatus.CLOSED.code());
    fixture.task.setResourceStatus(GroupPullResourceStatus.RELEASING.code());
    when(fixture.mapper.failPendingExecutionMaterials(
            eq(501L), eq("任务已停止，未继续拉料"), anyLong())).thenReturn(2);

    fixture.worker().process(501L);

    verify(fixture.participantPort, never()).updateParticipants(any(), any(), anyList(), any());
    verify(fixture.mapper).failPendingExecutionMaterials(
            eq(501L), eq("任务已停止，未继续拉料"), anyLong());
    verify(fixture.mapper).advanceExecutionStage(
            eq(501L), eq(2), eq(5), eq(6), eq(2), anyLong(), anyLong());
}
```

- [ ] **Step 2: 运行测试确认红灯**

Run:

```bash
cd armada-api
mvn -Dtest='GroupPullMarketingMaterialEntryWorkerTest,GroupPullMarketingResumeSchedulingTest' test
```

Expected: FAIL，阶段 5 离线仍走 15 秒通用复查，恢复任务也尚未重排。

- [ ] **Step 3: 增加不记录手机号的结构化进度日志**

```java
private void logMaterialProgress(
        GroupPullMarketingExecution execution,
        GroupPullMarketingExecutionMaterial material,
        int attempt,
        String result,
        Long nextExecuteAt) {
    log.info(
            "拉群逐料执行 taskId={} executionId={} allocationNo={} attempt={} result={} nextExecuteAt={}",
            execution.getTaskId(), execution.getId(), material.getAllocationNo(),
            attempt, result, nextExecuteAt);
}
```

在首次/二次失败排期后记录 `RETRY` 和下一次时间；成功落库后记录 `SUCCESS`；第三次失败落库后记录 `FAILED`。日志参数只允许任务、执行、顺序、次数、结果和时间，不传 `materialPhone` 或 JID。

- [ ] **Step 4: 增加任务生命周期闸门并特殊处理阶段 5 离线预检**

Mapper 增加轻量任务事实和未执行料子收口方法：

```java
MarketingTask selectTaskRuntime(@Param("taskId") Long taskId);

int failPendingExecutionMaterials(
        @Param("executionId") Long executionId,
        @Param("reason") String reason,
        @Param("now") long now);
```

```xml
<select id="selectTaskRuntime" resultType="com.armada.marketing.model.entity.MarketingTask">
    SELECT id, status, task_end_at
    FROM marketing_task
    WHERE id = #{taskId}
      AND business_type = 2
      AND deleted_at IS NULL
</select>

<update id="failPendingExecutionMaterials">
    UPDATE group_pull_marketing_execution_material
    SET entry_status = 3,
        entry_failure_reason = #{reason},
        updated_at = #{now}
    WHERE execution_id = #{executionId}
      AND entry_status = 1
</update>
```

在 `selectDueExecutionDispatches` 的公共 WHERE 中增加阶段 5 暂停条件；暂停时不调度加料，到期和释放状态仍允许 worker 被调度并在协议调用前把未执行料子安全收口：

```sql
AND (
    execution.current_stage &lt;&gt; 5
    OR task.status &lt;&gt; 5
)
```

worker 在任何阶段 5 协议调用前再次检查任务事实，覆盖“调度后立刻暂停”的窄窗口：

```java
private GroupPullMarketingTask activeMaterialTaskOrStop(
        GroupPullMarketingExecution execution) {
    long now = System.currentTimeMillis();
    MarketingTask owner = mapper.selectTaskRuntime(execution.getTaskId());
    GroupPullMarketingTask task = requireTask(execution.getTaskId());
    if (owner != null && Integer.valueOf(MarketingTaskStatus.PAUSED.code())
            .equals(owner.getStatus())) {
        mapper.delayExecution(
                execution.getId(), execution.getExecutionStatus(),
                GroupPullExecutionStage.ADD_MATERIALS.code(),
                now + OFFLINE_RECHECK_DELAY.toMillis(), null, now);
        return null;
    }
    boolean stopped = owner == null
            || !Integer.valueOf(MarketingTaskStatus.SENDING.code()).equals(owner.getStatus())
            || owner.getTaskEndAt() == null
            || owner.getTaskEndAt() <= now
            || !Integer.valueOf(GroupPullResourceStatus.LOCKED.code())
                    .equals(task.getResourceStatus());
    if (!stopped) {
        return task;
    }
    transactionTemplate.executeWithoutResult(status -> {
        String reason = "任务已停止，未继续拉料";
        mapper.failPendingExecutionMaterials(execution.getId(), reason, now);
        if (mapper.appendExecutionFailureReason(execution.getId(), reason, now) != 1) {
            throw new IllegalStateException("保存停止拉料原因失败");
        }
        advanceAt(
                execution, GroupPullExecutionStage.SET_MARKETER_ADMIN,
                GroupPullExecutionStatus.EXECUTING, now, now);
    });
    return null;
}
```

`addMaterials` 首行调用该方法，返回空时直接结束本轮。

在 `process` 的在线判断处保留其它阶段原行为，仅阶段 5 走一次失败记录：

```java
if (!online(builder)) {
    if (stage == GroupPullExecutionStage.ADD_MATERIALS) {
        GroupPullMarketingTask task = activeMaterialTaskOrStop(execution);
        if (task != null) {
            GroupPullMarketingExecutionMaterial material =
                    mapper.selectNextPendingExecutionMaterial(execution.getId());
            if (material != null) {
                scheduleMaterialFailure(execution, task, material, "建群账号离线");
            }
        }
    } else {
        mapper.delayExecution(
                executionId, execution.getExecutionStatus(), stage.code(),
                now + OFFLINE_RECHECK_DELAY.toMillis(), null, now);
    }
    return;
}
```

账号已封禁或不可用继续沿用 `finalizer.fail`，不为确定不可恢复的账号浪费三个料子周期。

- [ ] **Step 5: 恢复任务时为所有阶段 5 执行独立随机重排**

在 `resume` 成功更新任务状态后增加：

```java
GroupPullMaterialEntryDelayPolicy.DelayWindow window =
        materialEntryDelayPolicy.delayWindow(
                extension.getMaterialEntryIntervalSeconds());
mapper.rescheduleMaterialExecutionsOnResume(
        id, now, window.minDelayMillis(), window.maxDelayMillis());
```

给 `GroupPullMarketingTaskServiceImpl` 增加依赖字段，并把 policy 作为构造器最后一个参数：

```java
private final GroupPullMaterialEntryDelayPolicy materialEntryDelayPolicy;

public GroupPullMarketingTaskServiceImpl(
        GroupPullMarketingMapper mapper,
        MarketingTaskMapper marketingTaskMapper,
        MarketingTemplateMapper templateMapper,
        AccountGroupMapper accountGroupMapper,
        AccountProtocolLookupService accountProtocolLookupService,
        GroupPullMarketingMaterialParser materialParser,
        MarketingGroupOccupancyService groupOccupancyService,
        MarketingAccountOccupancyService accountOccupancyService,
        GroupPullMaterialEntryDelayPolicy materialEntryDelayPolicy) {
    this.mapper = mapper;
    this.marketingTaskMapper = marketingTaskMapper;
    this.templateMapper = templateMapper;
    this.accountGroupMapper = accountGroupMapper;
    this.accountProtocolLookupService = accountProtocolLookupService;
    this.materialParser = materialParser;
    this.groupOccupancyService = groupOccupancyService;
    this.accountOccupancyService = accountOccupancyService;
    this.materialEntryDelayPolicy = materialEntryDelayPolicy;
}
```

同步更新 `GroupPullMarketingTaskGroupServiceTest` 和 `GroupPullMarketingTaskConfigurationTest` 的 service 构造调用。SQL 中每一行单独求值 `RAND()`，不能在 Java 中生成一个时间后批量写成相同值。

- [ ] **Step 6: 运行重试和恢复测试确认转绿**

Run:

```bash
cd armada-api
mvn -Dtest='GroupPullMarketingMaterialEntryWorkerTest,GroupPullMarketingResumeSchedulingTest,GroupPullMarketingExecutionWorkerTest,GroupPullMarketingSchedulerTest' test
```

Expected: PASS；任何单次 worker 调用最多一次 ADD 协议请求，失败重试均排到未来，恢复任务不立即补拉。

- [ ] **Step 7: 提交失败与生命周期语义**

```bash
git add armada-api/src/main/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingMapper.java \
  armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingExecutionWorker.java \
  armada-api/src/main/java/com/armada/marketing/grouppull/service/impl/GroupPullMarketingTaskServiceImpl.java \
  armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml \
  armada-api/src/test/java/com/armada/marketing/grouppull/mapper/GroupPullMarketingLifecycleSqlShapeTest.java \
  armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingMaterialEntryWorkerTest.java \
  armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingResumeSchedulingTest.java \
  armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingTaskGroupServiceTest.java \
  armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingTaskConfigurationTest.java
git commit -m "feat(marketing): delay material retries and resume"
```

### Task 6: 扩展前端 API、表单状态和纯提示函数

**Files:**
- Create: `../wheel-saas-pure-web/.harness/changes/group-pull-material-entry-interval/summary.md`
- Create: `../wheel-saas-pure-web/src/views/task/group-pull-marketing/material-entry-interval.ts`
- Create: `../wheel-saas-pure-web/src/views/task/group-pull-marketing/material-entry-interval.test.ts`
- Modify: `../wheel-saas-pure-web/src/api/group-pull-marketing.ts`
- Modify: `../wheel-saas-pure-web/src/api/group-pull-marketing.test.ts`
- Modify: `../wheel-saas-pure-web/src/views/task/group-pull-marketing/composables/useGroupPullMarketingPage.ts`
- Modify: `../wheel-saas-pure-web/src/views/task/group-pull-marketing/composables/useGroupPullMarketingPage.test.ts`

- [ ] **Step 1: 先创建前端变更记录**

```markdown
# 变更记录：拉群营销逐料随机间隔

- 日期：2026-07-27
- 状态：进行中
- 后端设计：`../../../../armada/docs/superpowers/specs/2026-07-27-group-pull-material-entry-interval-design.md`

## 目标

在现有拉群营销创建抽屉配置 1～60 分钟的拉料基准间隔，默认 5 分钟；页面展示固定 ±20% 的实际随机范围。

## 边界

- 不新增页面或路由。
- 表单使用分钟，API 使用秒。
- 详情只读展示创建时冻结的配置。

```

- [ ] **Step 2: 写提示函数、默认值、校验和请求秒数失败测试**

```ts
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { materialEntryIntervalHint } from "./material-entry-interval";

describe("material entry interval", () => {
  it("formats the fixed twenty percent range", () => {
    assert.equal(materialEntryIntervalHint(5), "实际每次随机等待 4～6 分钟（±20%）");
    assert.equal(materialEntryIntervalHint(10), "实际每次随机等待 8～12 分钟（±20%）");
    assert.equal(materialEntryIntervalHint(1), "实际每次随机等待 0.8～1.2 分钟（±20%）");
  });
});
```

在 `useGroupPullMarketingPage.test.ts` 增加默认值 `5`，分别把值设为 `0`、`1.5`、`61` 并断言提示 `拉料间隔必须是1到60的整数分钟`。保存测试按以下代码解析首个请求的 multipart `config` Blob：

```ts
const createCall = armadaCalls()[0];
const formData = (createCall.opts as { data: FormData }).data;
const configPart = formData.get("config");
assert.ok(configPart instanceof Blob);
const savedConfig = JSON.parse(await configPart.text()) as {
  materialEntryIntervalSeconds: number;
};
assert.equal(savedConfig.materialEntryIntervalSeconds, 300);
```

在 API 测试的 `config` fixture 增加 `materialEntryIntervalSeconds: 300`。

- [ ] **Step 3: 运行前端聚焦测试确认红灯**

Run:

```bash
cd ../wheel-saas-pure-web
node --import ./src/api/__tests__/node-test-alias.mjs --test --experimental-strip-types \
  src/api/group-pull-marketing.test.ts \
  src/views/task/group-pull-marketing/material-entry-interval.test.ts \
  src/views/task/group-pull-marketing/composables/useGroupPullMarketingPage.test.ts
```

Expected: FAIL，新字段和提示函数尚不存在。

- [ ] **Step 4: 实现纯提示函数**

```ts
function formatMinutes(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(1);
}

export function materialEntryIntervalHint(baseMinutes: number): string {
  const lower = baseMinutes * 0.8;
  const upper = baseMinutes * 1.2;
  return `实际每次随机等待 ${formatMinutes(lower)}～${formatMinutes(upper)} 分钟（±20%）`;
}
```

- [ ] **Step 5: 扩展 API 和创建表单**

在 `CreateGroupPullMarketingConfig` 和 `GroupPullMarketingTaskDetail` 增加：

```ts
materialEntryIntervalSeconds: number;
```

在 `GroupPullMarketingCreateForm` 增加：

```ts
materialEntryIntervalMinutes: number;
```

`emptyCreateForm` 默认写 `materialEntryIntervalMinutes: 5`。在 `materialPerGroup` 校验后增加：

```ts
if (
  !Number.isInteger(createForm.materialEntryIntervalMinutes) ||
  createForm.materialEntryIntervalMinutes < 1 ||
  createForm.materialEntryIntervalMinutes > 60
) {
  return "拉料间隔必须是1到60的整数分钟";
}
```

`toCreateConfig` 增加：

```ts
materialEntryIntervalSeconds:
  createForm.materialEntryIntervalMinutes * 60,
```

- [ ] **Step 6: 运行前端状态测试确认转绿**

Run 使用 Step 3 的同一命令。

Expected: PASS。

- [ ] **Step 7: 提交前端契约和状态**

```bash
git add .harness/changes/group-pull-material-entry-interval/summary.md \
  src/api/group-pull-marketing.ts src/api/group-pull-marketing.test.ts \
  src/views/task/group-pull-marketing/material-entry-interval.ts \
  src/views/task/group-pull-marketing/material-entry-interval.test.ts \
  src/views/task/group-pull-marketing/composables/useGroupPullMarketingPage.ts \
  src/views/task/group-pull-marketing/composables/useGroupPullMarketingPage.test.ts
git commit -m "feat(task): add group pull material interval config"
```

### Task 7: 在现有抽屉和详情摘要展示配置

**Files:**
- Modify: `../wheel-saas-pure-web/src/views/task/group-pull-marketing/components/GroupPullMarketingCreateDrawer.vue`
- Modify: `../wheel-saas-pure-web/src/views/task/group-pull-marketing/components/GroupPullMarketingCreateDrawer.test.ts`
- Modify: `../wheel-saas-pure-web/src/views/task/group-pull-marketing/components/GroupPullMarketingSummary.vue`
- Create: `../wheel-saas-pure-web/src/views/task/group-pull-marketing/components/GroupPullMarketingSummary.test.ts`

- [ ] **Step 1: 写现有页面控件和详情回显失败测试**

在创建抽屉源码测试中增加：

```ts
assert.match(source, /label="拉料间隔（分钟）"/);
assert.match(source, /v-model="form\.materialEntryIntervalMinutes"/);
assert.match(source, /:min="1"/);
assert.match(source, /:max="60"/);
assert.match(source, /materialEntryIntervalHint/);
```

新建摘要源码测试：

```ts
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";

const source = readFileSync(
  new URL("./GroupPullMarketingSummary.vue", import.meta.url),
  "utf8"
);

describe("group pull marketing summary", () => {
  it("shows frozen material entry interval and jitter", () => {
    assert.match(source, /label="拉料间隔"/);
    assert.match(source, /detail\.materialEntryIntervalSeconds \/ 60/);
    assert.match(source, /随机 ±20%/);
  });
});
```

- [ ] **Step 2: 运行组件测试确认红灯**

Run:

```bash
cd ../wheel-saas-pure-web
node --test --experimental-strip-types \
  src/views/task/group-pull-marketing/components/GroupPullMarketingCreateDrawer.test.ts \
  src/views/task/group-pull-marketing/components/GroupPullMarketingSummary.test.ts
```

Expected: FAIL，控件和摘要尚未渲染。

- [ ] **Step 3: 在现有抽屉增加 Element Plus 数字输入**

脚本引入提示函数并增加 computed：

```ts
import { materialEntryIntervalHint } from "../material-entry-interval";

const materialIntervalTip = computed(() =>
  materialEntryIntervalHint(form.value.materialEntryIntervalMinutes)
);
```

在“单群抽取数量”后增加：

```vue
<el-form-item label="拉料间隔（分钟）" required>
  <el-input-number
    v-model="form.materialEntryIntervalMinutes"
    :min="1"
    :max="60"
    :step="1"
    :precision="0"
  />
  <span class="field-tip">{{ materialIntervalTip }}</span>
</el-form-item>
```

- [ ] **Step 4: 在任务详情摘要增加只读回显**

```vue
<el-descriptions-item label="拉料间隔">
  {{ detail.materialEntryIntervalSeconds / 60 }} 分钟（随机 ±20%）
</el-descriptions-item>
```

- [ ] **Step 5: 运行组件测试和类型检查**

Run:

```bash
node --test --experimental-strip-types \
  src/views/task/group-pull-marketing/components/GroupPullMarketingCreateDrawer.test.ts \
  src/views/task/group-pull-marketing/components/GroupPullMarketingSummary.test.ts
pnpm typecheck
```

Expected: 组件测试 PASS；TypeScript 与 Vue 类型检查 PASS。

- [ ] **Step 6: 提交前端控件和详情**

```bash
git add src/views/task/group-pull-marketing/components/GroupPullMarketingCreateDrawer.vue \
  src/views/task/group-pull-marketing/components/GroupPullMarketingCreateDrawer.test.ts \
  src/views/task/group-pull-marketing/components/GroupPullMarketingSummary.vue \
  src/views/task/group-pull-marketing/components/GroupPullMarketingSummary.test.ts
git commit -m "feat(task): show group pull material interval"
```

### Task 8: 完整回归、变更记录和交付检查

**Files:**
- Modify: `../wheel-saas-pure-web/.harness/changes/group-pull-material-entry-interval/summary.md`
- Verify: `armada-api/src/main/java/com/armada/marketing/grouppull/**`
- Verify: `armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml`
- Verify: `armada-api/src/main/resources/db/migration/V080__group_pull_material_entry_interval.sql`
- Verify: `../wheel-saas-pure-web/src/api/group-pull-marketing*`
- Verify: `../wheel-saas-pure-web/src/views/task/group-pull-marketing/**`

- [ ] **Step 1: 运行后端聚焦回归**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest='GroupPullMaterialEntryDelayPolicyTest,GroupPullMarketingMigrationSqlTest,GroupPullMarketingTaskConfigurationTest,GroupPullMarketingTaskGroupServiceTest,GroupPullMarketingLifecycleSqlShapeTest,GroupPullMarketingExecutionWorkerTest,GroupPullMarketingMaterialEntryWorkerTest,GroupPullMarketingResumeSchedulingTest,GroupPullMarketingSchedulerTest,GroupPullMarketingFinalizerTest,GroupPullRetryPolicyTest' test
```

Expected: PASS，0 failures，0 errors。

- [ ] **Step 2: 校验 Mapper XML 和生产打包路径**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
xmllint --noout armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml
bash armada-deploy/package-prod.test.sh
```

Expected: XML 合法；生产打包脚本退出码 0。

- [ ] **Step 3: 运行前端全部拉群营销测试**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
node --import ./src/api/__tests__/node-test-alias.mjs --test --test-reporter=dot --experimental-strip-types \
  src/api/group-pull-marketing.test.ts \
  src/views/task/group-pull-marketing/constants.test.ts \
  src/views/task/group-pull-marketing/material-entry-interval.test.ts \
  src/views/task/group-pull-marketing/components/GroupPullMarketingCreateDrawer.test.ts \
  src/views/task/group-pull-marketing/components/GroupPullMarketingGroupTable.test.ts \
  src/views/task/group-pull-marketing/components/GroupPullMarketingSummary.test.ts \
  src/views/task/group-pull-marketing/components/GroupPullMarketingTaskTable.test.ts \
  src/views/task/group-pull-marketing/composables/useGroupPullMarketingPage.test.ts \
  src/views/task/group-pull-marketing/composables/useGroupPullMarketingDetail.test.ts
```

Expected: PASS，0 failures。

- [ ] **Step 4: 运行前端工程验证**

Run:

```bash
pnpm typecheck
pnpm exec eslint --max-warnings 0 \
  src/api/group-pull-marketing.ts \
  src/api/group-pull-marketing.test.ts \
  src/views/task/group-pull-marketing/material-entry-interval.ts \
  src/views/task/group-pull-marketing/material-entry-interval.test.ts \
  src/views/task/group-pull-marketing/composables/useGroupPullMarketingPage.ts \
  src/views/task/group-pull-marketing/composables/useGroupPullMarketingPage.test.ts \
  src/views/task/group-pull-marketing/components/GroupPullMarketingCreateDrawer.vue \
  src/views/task/group-pull-marketing/components/GroupPullMarketingCreateDrawer.test.ts \
  src/views/task/group-pull-marketing/components/GroupPullMarketingSummary.vue \
  src/views/task/group-pull-marketing/components/GroupPullMarketingSummary.test.ts
pnpm exec stylelint --max-warnings 0 \
  src/views/task/group-pull-marketing/components/GroupPullMarketingCreateDrawer.vue \
  src/views/task/group-pull-marketing/components/GroupPullMarketingSummary.vue
pnpm build
```

Expected: typecheck、ESLint、Stylelint 和生产构建全部退出码 0。

- [ ] **Step 5: 回填前端变更记录**

把 summary 状态改为“已完成”，并追加以下已执行命令对应的固定格式结果；每一项只写“通过”或实际失败原因，不虚构测试数量：

```markdown
## 验证

- 后端聚焦测试：通过。
- Mapper XML 与生产打包脚本：通过。
- 前端拉群营销测试：通过。
- 前端 typecheck、ESLint、Stylelint、生产构建：通过。
- 本地 MySQL 测试：通过。
```

如果本地 MySQL 未配置，将最后一行明确写成：`本地 MySQL 测试：未运行；本机未配置测试库，且未连接远程环境。`

- [ ] **Step 6: 检查两个仓库差异边界**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git diff --check
git status --short

cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
git diff --check
git status --short
```

Expected: 无空白错误；只暂存本计划列出的功能文件，不包含 `.claude/worktrees`、浏览器临时目录、凭据或用户已有改动。

- [ ] **Step 7: 提交最终变更记录**

```bash
git add .harness/changes/group-pull-material-entry-interval/summary.md
git commit -m "docs(task): record group pull material interval verification"
```

部署、SSH 和第一套环境验收不属于本地实现步骤。代码合并后必须再次向用户确认目标为第一套测试环境，才可执行 Flyway、部署和小规模真实任务验证。
