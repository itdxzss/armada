# Marketing Account Group Send Interval Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for progress tracking.

**Goal:** 在普通营销任务中新增“单账号下群组发送间隔”，默认 0.5 秒、范围 0.5～3.0 秒、步长 0.1 秒，并由 Armada 在 Kafka producer 前按租户、协议后端、协议账号执行持久化节流。

**Architecture:** 前端提交秒数，营销任务聚合以整数毫秒保存；`MarketingRoundWorker` 为整轮内每个账号的群命令生成稳定初始排期，outbox 快照保存 `next_retry_at` 与 `dispatch_interval_ms`。dispatcher 通过独立账号水位表和短事务一次只放行同账号最早命令，其他账号可并行；精确唤醒负责正常路径，现有 10 秒扫描只负责重启和漏触发恢复。Kafka payload、Web 协议层和 Android Zhuan 均不增加字段。

**Tech Stack:** Vue 3、TypeScript、Element Plus、Node test runner、Java 17、Spring Boot、MyBatis、MySQL 8、Flyway、Kafka、JUnit 5、Mockito、AssertJ。

---

## 开工边界与文件地图

实现前必须重新阅读两个仓库各自的 `AGENTS.md`。当前 `armada` 主工作区有用户的未提交改动，且与本功能会修改的 `MarketingTaskServiceImpl.java`、`MarketingTaskMapper.xml` 和部分测试重叠；执行阶段先使用 `using-git-worktrees` 创建隔离 worktree，基线至少包含提交 `b5cc35a` 和设计提交 `15e82e4`，不得覆盖或顺带提交主工作区改动。前端仓库当前干净，也使用独立功能分支以便分仓提交。

### 前端：`wheel-saas-pure-web`

- Modify: `src/api/marketing-task.ts`
- Modify: `src/views/task/group-marketing/components/GroupMarketingCreateDrawer.vue`
- Modify: `src/views/task/group-marketing/composables/useGroupMarketingTaskPage.ts`
- Modify: `src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts`

### 后端任务聚合：`armada/armada-api`

- Create: `src/main/java/com/armada/marketing/model/MarketingAccountGroupSendInterval.java`
- Modify: `src/main/java/com/armada/marketing/model/dto/CreateMarketingTaskDTO.java`
- Modify: `src/main/java/com/armada/marketing/model/entity/MarketingTask.java`
- Modify: `src/main/java/com/armada/marketing/model/vo/MarketingTaskVO.java`
- Modify: `src/main/java/com/armada/marketing/model/vo/MarketingTaskDetailVO.java`
- Modify: `src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java`
- Modify: `src/main/resources/mapper/marketing/MarketingTaskMapper.xml`
- Create: `src/test/java/com/armada/marketing/model/MarketingAccountGroupSendIntervalTest.java`
- Modify: `src/test/java/com/armada/marketing/service/MarketingTaskServiceImplLifecycleTest.java`
- Modify: `src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java`
- Modify: `src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java`

### 后端命令排期与 outbox

- Create: `src/main/java/com/armada/platform/protocol/model/command/MessageDispatchPolicy.java`
- Modify: `src/main/java/com/armada/platform/protocol/model/command/MessageSendCommand.java`
- Modify: `src/main/java/com/armada/marketing/scheduler/MarketingRoundWorker.java`
- Modify: `src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingWorker.java`
- Modify: `src/main/java/com/armada/group/service/impl/HistoricalGroupMarketingServiceImpl.java`
- Modify: `src/main/java/com/armada/platform/protocol/model/entity/ProtocolCommandOutbox.java`
- Modify: `src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java`
- Modify: `src/test/java/com/armada/marketing/scheduler/MarketingRoundWorkerTest.java`
- Modify: `src/test/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImplTest.java`
- Modify: `src/test/java/com/armada/platform/protocol/routing/RoutingMessageSendPortTest.java`
- Modify: `src/test/java/com/armada/platform/protocol/backend/web/WebMessageSendBackendTest.java`
- Modify: `src/test/java/com/armada/platform/protocol/backend/android/AndroidMessageSendBackendTest.java`

### 后端持久化节流与唤醒

- Create: `src/main/java/com/armada/platform/protocol/model/entity/ProtocolCommandDispatchPace.java`
- Create: `src/main/java/com/armada/platform/protocol/mapper/ProtocolCommandDispatchPaceMapper.java`
- Create: `src/main/resources/mapper/platform/protocol/ProtocolCommandDispatchPaceMapper.xml`
- Create: `src/main/java/com/armada/platform/kafka/dispatch/ProtocolCommandDispatchPaceService.java`
- Create: `src/main/java/com/armada/platform/kafka/dispatch/ProtocolCommandDispatchWakeup.java`
- Modify: `src/main/java/com/armada/platform/protocol/mapper/ProtocolCommandOutboxMapper.java`
- Modify: `src/main/resources/mapper/platform/protocol/ProtocolCommandOutboxMapper.xml`
- Modify: `src/main/java/com/armada/platform/kafka/dispatch/ProtocolCommandDispatcher.java`
- Modify: `src/main/java/com/armada/platform/kafka/config/ProtocolKafkaConfiguration.java`
- Modify: `src/test/java/com/armada/platform/kafka/dispatch/ProtocolCommandDispatcherTest.java`
- Create: `src/test/java/com/armada/platform/kafka/dispatch/ProtocolCommandDispatchPaceServiceTest.java`
- Create: `src/test/java/com/armada/platform/kafka/dispatch/ProtocolCommandDispatchWakeupTest.java`
- Create: `src/test/java/com/armada/platform/protocol/mapper/ProtocolCommandDispatchPaceMapperDbTest.java`
- Modify: `src/test/java/com/armada/platform/protocol/mapper/ProtocolCommandOutboxMapperDbTest.java`

### Schema、变更记录与自动文档

- Create: `armada-api/src/main/resources/db/migration/V058__marketing_account_group_dispatch_interval.sql`
- Create: `.harness/changes/marketing-account-group-send-interval/db-migrations.sql`
- Create: `.harness/changes/marketing-account-group-send-interval/rollback.sql`
- Create: `.harness/changes/marketing-account-group-send-interval/summary.md`
- Modify by generator only: `.harness/wiki/数据模型.md`
- Modify: `.harness/changes/2026-07-18-marketing-account-group-send-interval.md`

---

## Task 0: 建立不会覆盖用户改动的隔离 worktree

- [ ] **Step 1: 确认两个仓库基线**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git status --short --branch
git merge-base --is-ancestor 15e82e4 HEAD
git merge-base --is-ancestor b5cc35a HEAD
```

Expected: 两个 `merge-base` 命令退出码均为 0。主工作区允许有用户改动，但不得清理、stash 或提交它们。

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
git status --short --branch
```

Expected: 前端工作区干净；若出现新改动，先停下确认归属。

- [ ] **Step 2: 按 `using-git-worktrees` 建立固定路径 worktree**

```bash
mkdir -p /Users/daishuaishuai/IdeaProjects/.worktrees
cd /Users/daishuaishuai/IdeaProjects/armada
git worktree add /Users/daishuaishuai/IdeaProjects/.worktrees/armada-marketing-account-pace \
  -b codex/marketing-account-pace
```

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
git worktree add /Users/daishuaishuai/IdeaProjects/.worktrees/wheel-marketing-account-pace \
  -b codex/marketing-account-pace
```

如果任一固定分支或 worktree 已存在，先用 `git worktree list` 验证它确实属于本功能；不得删除未知 worktree 或强制改写分支。

- [ ] **Step 3: 从此只在隔离路径实施**

```bash
git -C /Users/daishuaishuai/IdeaProjects/.worktrees/armada-marketing-account-pace status --short --branch
git -C /Users/daishuaishuai/IdeaProjects/.worktrees/wheel-marketing-account-pace status --short --branch
```

Expected: 两个新 worktree 都干净。下文所有后端命令以 `armada-marketing-account-pace` 为根，所有前端命令以 `wheel-marketing-account-pace` 为根。

---

## Task 1: 前端表单、校验和请求字段

- [ ] **Step 1: 先写失败测试**

在 `useGroupMarketingTaskPage.test.ts` 增加以下覆盖：

1. `createForm.accountGroupSendIntervalSeconds` 初始值为 `0.5`。
2. 创建请求包含 `accountGroupSendIntervalSeconds: 0.5`。
3. 导出的纯函数对 `0.5`、`0.6`、`3` 返回 `true`，对 `0.4`、`3.1`、`0.55`、`NaN`、`Infinity` 返回 `false`。
4. 非法值创建时不调用 `/api/marketing-tasks`，并提示稳定文案：`单账号下群组发送间隔必须为0.5到3秒，最多一位小数`。
5. 把表单值改为 `2.4` 后再次 `openCreateDrawer()`，值恢复为 `0.5`。

测试直接导入：

```ts
import {
  endOfDayTimestamp,
  isValidAccountGroupSendIntervalSeconds,
  useGroupMarketingTaskPage
} from "./useGroupMarketingTaskPage";
```

- [ ] **Step 2: 运行测试并确认 RED**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/wheel-marketing-account-pace
node --test src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts
```

Expected: TypeScript/运行时因字段和校验函数不存在而失败；不能通过删断言转绿。

- [ ] **Step 3: 增加最小 TypeScript 实现**

在 `marketing-task.ts` 的 `MarketingTaskRow` 与 `CreateMarketingTaskRequest` 增加：

```ts
accountGroupSendIntervalSeconds: number;
```

在 `GroupMarketingCreateForm` 和 `emptyCreateForm()` 增加：

```ts
accountGroupSendIntervalSeconds: number;
```

```ts
accountGroupSendIntervalSeconds: 0.5,
```

增加并在 `createTask()` 生命周期校验之后、API 调用之前使用：

```ts
export function isValidAccountGroupSendIntervalSeconds(
  value: number
): boolean {
  return (
    Number.isFinite(value) &&
    value >= 0.5 &&
    value <= 3 &&
    Number.isInteger(value * 10)
  );
}
```

非法时执行以下代码后立即返回：

```ts
ElMessage.warning(
  "单账号下群组发送间隔必须为0.5到3秒，最多一位小数"
);
return;
```

创建请求映射增加：

```ts
accountGroupSendIntervalSeconds: form.accountGroupSendIntervalSeconds,
```

- [ ] **Step 4: 增加页面控件**

在“单轮发送数量”与现有“发送间隔”之间插入：

```vue
<el-form-item label="单账号下群组发送间隔">
  <el-input-number
    v-model="form.accountGroupSendIntervalSeconds"
    :min="0.5"
    :max="3"
    :step="0.1"
    :precision="1"
  />
  <span class="unit">秒</span>
</el-form-item>
```

- [ ] **Step 5: 运行前端聚焦验证**

```bash
node --test src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts
pnpm typecheck
```

Expected: 测试全部通过，`typecheck` 退出码为 0。

- [ ] **Step 6: 提交前端切片**

```bash
git add src/api/marketing-task.ts \
  src/views/task/group-marketing/components/GroupMarketingCreateDrawer.vue \
  src/views/task/group-marketing/composables/useGroupMarketingTaskPage.ts \
  src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts
git diff --cached --check
git commit -m "feat(marketing): add account group send interval field"
```

---

## Task 2: 营销任务配置、API 映射和 Flyway

- [ ] **Step 1: 为数值对象写失败测试**

新建 `MarketingAccountGroupSendIntervalTest`，精确覆盖：

```java
assertThat(MarketingAccountGroupSendInterval.toMillis(null)).isEqualTo(500);
assertThat(MarketingAccountGroupSendInterval.toMillis(new BigDecimal("0.5"))).isEqualTo(500);
assertThat(MarketingAccountGroupSendInterval.toMillis(new BigDecimal("0.6"))).isEqualTo(600);
assertThat(MarketingAccountGroupSendInterval.toMillis(new BigDecimal("3.0"))).isEqualTo(3000);
assertThat(MarketingAccountGroupSendInterval.toSeconds(500)).isEqualByComparingTo("0.5");
assertThat(MarketingAccountGroupSendInterval.toSeconds(3000)).isEqualByComparingTo("3.0");
```

对 `0.4`、`3.1`、`0.55` 断言 `BusinessException` 且消息包含完整稳定文案。

在 `MarketingTaskServiceImplLifecycleTest` 增加：

- null 字段创建时捕获 `insertTask` 参数，断言 `accountGroupSendIntervalMs == 500`；
- `1.7` 创建时断言保存 `1700`；
- `0.55` 在查询模板和账号之前被拒绝。

在 `MarketingTaskMapperSqlShapeTest` 断言 result map、`TaskColumns` 与 `insertTask` 都包含 `account_group_send_interval_ms`。

- [ ] **Step 2: 运行测试并确认 RED**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-marketing-account-pace/armada-api
mvn -Dtest='MarketingAccountGroupSendIntervalTest,MarketingTaskServiceImplLifecycleTest,MarketingTaskMapperSqlShapeTest' test
```

Expected: 新类型和字段不存在导致编译或断言失败。

- [ ] **Step 3: 实现唯一换算入口**

创建 `MarketingAccountGroupSendInterval`，只暴露以下稳定 API：

```java
public final class MarketingAccountGroupSendInterval {
    public static final int DEFAULT_MILLIS = 500;
    private static final BigDecimal MIN_SECONDS = new BigDecimal("0.5");
    private static final BigDecimal MAX_SECONDS = new BigDecimal("3.0");
    private static final String ERROR_MESSAGE =
            "单账号下群组发送间隔必须为0.5到3秒，最多一位小数";

    private MarketingAccountGroupSendInterval() {
    }

    public static int toMillis(BigDecimal seconds) {
        if (seconds == null) {
            return DEFAULT_MILLIS;
        }
        BigDecimal normalized = seconds.stripTrailingZeros();
        if (seconds.compareTo(MIN_SECONDS) < 0
                || seconds.compareTo(MAX_SECONDS) > 0
                || normalized.scale() > 1) {
            throw invalid();
        }
        try {
            return seconds.movePointRight(3).intValueExact();
        } catch (ArithmeticException ex) {
            throw invalid();
        }
    }

    public static BigDecimal toSeconds(Integer millis) {
        int normalized = millis == null ? DEFAULT_MILLIS : millis;
        return BigDecimal.valueOf(normalized, 3).setScale(1, RoundingMode.UNNECESSARY);
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.VALIDATION, ERROR_MESSAGE);
    }
}
```

- [ ] **Step 4: 串通 DTO、实体、Service、VO 与 Mapper**

使用同一个字段名和类型：

- `CreateMarketingTaskDTO.accountGroupSendIntervalSeconds`: `BigDecimal`
- `MarketingTask.accountGroupSendIntervalMs`: `Integer`
- `MarketingTaskVO.accountGroupSendIntervalSeconds`: `BigDecimal`
- `MarketingTaskDetailVO.accountGroupSendIntervalSeconds`: `BigDecimal`

DTO 的旧短构造器继续向新字段传 `null`，保证滚动发布期间旧调用按 500ms 归一；不要新增第二套默认算法。

`validateRequest()` 调用：

```java
MarketingAccountGroupSendInterval.toMillis(request.accountGroupSendIntervalSeconds());
```

`buildTask()` 写入：

```java
task.setAccountGroupSendIntervalMs(
        MarketingAccountGroupSendInterval.toMillis(request.accountGroupSendIntervalSeconds()));
```

`toVO()` 与 `toDetailVO()` 返回：

```java
MarketingAccountGroupSendInterval.toSeconds(task.getAccountGroupSendIntervalMs())
```

Mapper 的 `MarketingTaskResultMap`、`TaskColumns`、`insertTask` 列和值同步增加 `account_group_send_interval_ms`，放在 `send_interval_seconds` 后面。

- [ ] **Step 5: 写 V058 与配套回滚脚本**

先重新运行以下命令，确认没有其它分支加入 V058；若输出已有 V058，停止并选用当前最大版本加一：

```bash
rg --files armada-api/src/main/resources/db/migration | sort -V | tail -n 5
```

`V058__marketing_account_group_dispatch_interval.sql` 必须包含三部分：

```sql
-- 1. marketing_task 新列：information_schema 守卫后执行
ALTER TABLE marketing_task
  ADD COLUMN account_group_send_interval_ms INT NOT NULL DEFAULT 500
  COMMENT '单账号下相邻群消息Kafka推送最小间隔(毫秒)'
  AFTER send_interval_seconds;

-- 2. protocol_command_outbox 新列：information_schema 守卫后执行
ALTER TABLE protocol_command_outbox
  ADD COLUMN dispatch_interval_ms INT NOT NULL DEFAULT 0
  COMMENT '同一投递节流键的Kafka最小推送间隔(毫秒);0=不节流'
  AFTER next_retry_at;

-- 3. 账号投递水位
CREATE TABLE IF NOT EXISTS protocol_command_dispatch_pace (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id BIGINT NOT NULL COMMENT '租户ID',
  protocol_backend VARCHAR(16) NOT NULL COMMENT '协议后端:WEB/ANDROID',
  protocol_account_id VARCHAR(128) NOT NULL COMMENT '协议层账号ID',
  next_allowed_at BIGINT NOT NULL DEFAULT 0 COMMENT '该账号下一次允许Kafka推送时间(epoch毫秒)',
  created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
  updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
  PRIMARY KEY (id),
  UNIQUE KEY uq_protocol_command_dispatch_pace
    (tenant_id, protocol_backend, protocol_account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='协议账号Kafka命令投递节流水位';
```

两个新增列都按 V048 的 `SET`、`PREPARE`、`EXECUTE`、`DEALLOCATE PREPARE` 形式增加 `information_schema.columns` 守卫。`NOT NULL DEFAULT 500` 会原位回填旧任务；不要另写把旧任务改为 0 的更新。

`.harness/changes/marketing-account-group-send-interval/db-migrations.sql` 保存相同前滚 SQL。`rollback.sql` 只包含：

```sql
DROP TABLE IF EXISTS protocol_command_dispatch_pace;
ALTER TABLE protocol_command_outbox DROP COLUMN dispatch_interval_ms;
ALTER TABLE marketing_task DROP COLUMN account_group_send_interval_ms;
```

`summary.md` 写明三项聚合归属：任务配置属于 `marketing_task`；投递快照属于 outbox；账号水位属于 dispatcher 基础设施状态，不复制营销业务事实。

- [ ] **Step 6: 增加真库创建/读取断言**

在 `MarketingTaskCreateReadDbTest` 增加两条测试：

- 请求 null 后，创建返回、列表返回、详情返回都为 `0.5`，SQL 查询列值为 `500`；
- 请求 `2.3` 后，上述 API 都为 `2.3`，SQL 查询列值为 `2300`。

构造带生命周期时间的 `CreateMarketingTaskDTO` 调用点显式补入新参数；短构造器调用保持不变，以覆盖 null 兼容路径。

- [ ] **Step 7: 先确认真库目标，再运行 DbTest**

`dbtest.sh` 会加载 `armada-api/.env` 并可能执行 Flyway。只展示 `DB_URL` 中的主机和库名，不展示用户名或密码；按工作区红线向用户确认目标环境后才运行：

```bash
set -a
source .env
set +a
pace_db_target="${DB_URL#jdbc:mysql://}"
pace_db_authority="${pace_db_target%%/*}"
pace_db_schema_query="${pace_db_target#*/}"
pace_db_schema="${pace_db_schema_query%%\?*}"
pace_db_host="${pace_db_authority%%:*}"
printf 'DbTest target host=%s schema=%s\n' "$pace_db_host" "$pace_db_schema"
```

把这两个非敏感值发给用户并取得确认，然后运行：

```bash
./dbtest.sh 'MarketingTaskCreateReadDbTest'
```

Expected: Flyway 应用 V058，新增用例通过。若存在既有 checksum 漂移，记录真实错误并停止；不得执行 `flyway repair` 或手工改共享库。

- [ ] **Step 8: 运行单元回归并提交后端任务配置切片**

```bash
mvn -Dtest='MarketingAccountGroupSendIntervalTest,MarketingTaskServiceImplLifecycleTest,MarketingTaskMapperSqlShapeTest' test
xmllint --noout src/main/resources/mapper/marketing/MarketingTaskMapper.xml
git diff --check
```

在隔离 worktree 中提交列出的本任务文件。若没有隔离 worktree，只能使用 `git add -p` 选择本功能 hunk，禁止把用户现有 takeover 改动带入提交。

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-marketing-account-pace
git add armada-api/src/main/resources/db/migration/V058__marketing_account_group_dispatch_interval.sql \
  armada-api/src/main/java/com/armada/marketing/model/MarketingAccountGroupSendInterval.java \
  armada-api/src/main/java/com/armada/marketing/model/dto/CreateMarketingTaskDTO.java \
  armada-api/src/main/java/com/armada/marketing/model/entity/MarketingTask.java \
  armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskVO.java \
  armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskDetailVO.java \
  armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java \
  armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml \
  armada-api/src/test/java/com/armada/marketing/model/MarketingAccountGroupSendIntervalTest.java \
  armada-api/src/test/java/com/armada/marketing/service/MarketingTaskServiceImplLifecycleTest.java \
  armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java \
  armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java \
  .harness/changes/marketing-account-group-send-interval
git diff --cached --check
git commit -m "feat(marketing): persist account group send interval"
```

---

## Task 3: 整轮账号内初始排期与 outbox 快照

- [ ] **Step 1: 先写 worker 和 outbox 失败测试**

在 `MarketingRoundWorkerTest` 增加：

1. 一个账号三个动态群、固定 `Clock` 为 `1_000_000`、任务间隔 500ms，捕获命令并断言 `notBeforeAt` 为 `1_000_000/1_000_500/1_001_000`。
2. 两个账号各两个群，断言两个账号各自第一条都从 `1_000_000` 开始。
3. `outboxBatchSize=1` 时同账号三个群会调用三次 `MessageSendPort.enqueue`，但三个批次的序号仍是 `0/1/2`，不能每批重置为 0。
4. Web 与 Android 目标都携带相同内部策略。

在 `ProtocolCommandOutboxServiceImplTest` 增加：

- paced 普通营销命令保存 `nextRetryAt=1_000_500`、`dispatchIntervalMs=500`；
- immediate 建群营销/历史群命令保存 `nextRetryAt=0`、`dispatchIntervalMs=0`；
- `payloadJson` 不包含 `notBeforeAt`、`dispatchIntervalMs` 或 `dispatchPolicy`。

Web/Android backend 测试各增加一条 paced command，断言产生的 wire payload 与 immediate command 相同且无 pacing 字段。

- [ ] **Step 2: 运行测试并确认 RED**

```bash
mvn -Dtest='MarketingRoundWorkerTest,ProtocolCommandOutboxServiceImplTest,WebMessageSendBackendTest,AndroidMessageSendBackendTest' test
```

Expected: `MessageDispatchPolicy`、命令字段或 outbox 字段不存在导致失败。

- [ ] **Step 3: 增加内部投递策略**

创建：

```java
public record MessageDispatchPolicy(long notBeforeAt, int dispatchIntervalMs) {
    private static final MessageDispatchPolicy IMMEDIATE = new MessageDispatchPolicy(0L, 0);

    public MessageDispatchPolicy {
        if (notBeforeAt < 0L || dispatchIntervalMs < 0) {
            throw new IllegalArgumentException("消息投递排期不能为负数");
        }
    }

    public static MessageDispatchPolicy immediate() {
        return IMMEDIATE;
    }
}
```

`MessageSendCommand` 在 `correlation` 与 `commandId` 之间增加：

```java
MessageDispatchPolicy dispatchPolicy,
```

所有非普通营销生产调用点显式传 `MessageDispatchPolicy.immediate()`；不要增加兼容重载。使用以下命令保证主代码和测试构造器全部更新：

```bash
rg -n 'new MessageSendCommand\(' src/main src/test
```

- [ ] **Step 4: 在整轮范围计算账号序号**

`MarketingRoundWorker.executeClaimedRound()` 把本轮的 `now` 传给 `enqueueCommands()`。在批处理循环外创建：

```java
Map<AccountDispatchKey, Integer> accountPositions = new HashMap<>();
int dispatchIntervalMs = task.getAccountGroupSendIntervalMs() == null
        ? MarketingAccountGroupSendInterval.DEFAULT_MILLIS
        : task.getAccountGroupSendIntervalMs();
```

每个实际目标先生成 `ProtocolAccountRef account = accountRef(target)`，再以：

```java
new AccountDispatchKey(account.backend(), account.protocolAccountId())
```

累计位置。位置从 0 开始，策略为：

```java
new MessageDispatchPolicy(
        roundStartedAt + (long) position * dispatchIntervalMs,
        dispatchIntervalMs)
```

`AccountDispatchKey` 是 `MarketingRoundWorker` 私有 record。位置 Map 必须定义在整个 `enqueueCommands()` 方法，而不是 `enqueueBatch()` 或批次分支中。

- [ ] **Step 5: 写入 outbox 内部元数据**

`ProtocolCommandOutbox` 增加 `Integer dispatchIntervalMs` 及 getter/setter。`ProtocolCommandOutboxMapper.xml` 的 `Columns`、`batchInsertPending` 列和值同步增加 `dispatch_interval_ms`。

`toMessageOutboxRow()` 使用：

```java
MessageDispatchPolicy policy = command.dispatchPolicy();
row.setDispatchIntervalMs(policy.dispatchIntervalMs());
row.setNextRetryAt(policy.notBeforeAt());
```

其他 outbox 类型不设置该字段，由实体 null 配合数据库默认 0；如当前批量 INSERT 显式写列，则统一在所有转换方法写 `0`，保持行为可见且避免 null 覆盖 `NOT NULL`。

- [ ] **Step 6: 运行聚焦测试与真库 outbox 测试**

```bash
mvn -Dtest='MarketingRoundWorkerTest,ProtocolCommandOutboxServiceImplTest,RoutingMessageSendPortTest,WebMessageSendBackendTest,AndroidMessageSendBackendTest' test
./dbtest.sh 'ProtocolCommandOutboxMapperDbTest#batchInsertPendingAndSelectDispatchable_returnsOnlyDuePendingRows'
xmllint --noout src/main/resources/mapper/platform/protocol/ProtocolCommandOutboxMapper.xml
```

真库目标沿用 Task 2 已确认环境；若本次会换 `.env`，必须重新确认。

- [ ] **Step 7: 提交排期切片**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-marketing-account-pace
git add armada-api/src/main/java/com/armada/platform/protocol/model/command/MessageDispatchPolicy.java \
  armada-api/src/main/java/com/armada/platform/protocol/model/command/MessageSendCommand.java \
  armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundWorker.java \
  armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingWorker.java \
  armada-api/src/main/java/com/armada/group/service/impl/HistoricalGroupMarketingServiceImpl.java \
  armada-api/src/main/java/com/armada/platform/protocol/model/entity/ProtocolCommandOutbox.java \
  armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java \
  armada-api/src/main/resources/mapper/platform/protocol/ProtocolCommandOutboxMapper.xml \
  armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundWorkerTest.java \
  armada-api/src/test/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImplTest.java \
  armada-api/src/test/java/com/armada/platform/protocol/routing/RoutingMessageSendPortTest.java \
  armada-api/src/test/java/com/armada/platform/protocol/backend/web/WebMessageSendBackendTest.java \
  armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidMessageSendBackendTest.java
git diff --check
git diff --cached --check
git commit -m "feat(marketing): schedule per-account group commands"
```

---

## Task 4: 持久化账号水位与无失败计数延期

- [ ] **Step 1: 先写 Pace Service 失败测试**

`ProtocolCommandDispatchPaceServiceTest` 使用 Mockito 覆盖：

1. 水位不存在时 `insertIfAbsent`，锁定到 `nextAllowedAt=0`，在 `now=10_000`、间隔 500ms 时返回 acquired，并更新到 `10_500`。
2. 水位为 `10_700` 时返回 denied(`10_700`)，不更新水位。
3. `dispatchIntervalMs<=0`、tenant/backend/account 任一缺失时抛出明确异常，不能返回 acquired。
4. 更新行数不是 1 时抛异常。

在 `ProtocolCommandOutboxMapperDbTest` 增加 `markDeferred_releasesLockWithoutIncrementingRetryOrOverwritingError`：先插入并锁定一行，把 `retry_count` 和 `last_error` 设为已有值，调用 `markDeferred` 后断言状态回到 PENDING、锁清空、`next_retry_at` 更新，而已有 retry/error 不变。

新建 `ProtocolCommandDispatchPaceMapperDbTest`：

- 同一 `(tenant, backend, account)` 第二次 `insertIfAbsent` 返回 0；
- 两线程同时调用 `tryAcquire`，固定同一个 `now` 和 500ms，结果恰好一个 acquired、一个 denied；
- 测试 `finally` 按精确租户/backend/account 删除测试水位，避免 `REQUIRES_NEW` 提交事实污染测试库。

- [ ] **Step 2: 运行单元测试并确认 RED**

```bash
mvn -Dtest='ProtocolCommandDispatchPaceServiceTest' test
```

Expected: 新 service/mapper/entity 不存在。

- [ ] **Step 3: 实现水位实体和 Mapper**

`ProtocolCommandDispatchPace` 字段与表一一对应：`id`、`tenantId`、`protocolBackend`、`protocolAccountId`、`nextAllowedAt`、`createdAt`、`updatedAt`。

`ProtocolCommandDispatchPaceMapper` 的三个跨租户方法全部标注 `@InterceptorIgnore(tenantLine = "true")`：

```java
int insertIfAbsent(Long tenantId, String protocolBackend,
                   String protocolAccountId, long now);

ProtocolCommandDispatchPace selectForUpdate(Long tenantId,
                                             String protocolBackend,
                                             String protocolAccountId);

int updateNextAllowedAt(Long id, long nextAllowedAt, long updatedAt);
```

XML 使用：

```sql
INSERT IGNORE INTO protocol_command_dispatch_pace
  (tenant_id, protocol_backend, protocol_account_id,
   next_allowed_at, created_at, updated_at)
VALUES
  (#{tenantId}, #{protocolBackend}, #{protocolAccountId}, 0, #{now}, #{now})
```

`selectForUpdate` 必须精确按唯一键查询并以 `FOR UPDATE` 结尾；`updateNextAllowedAt` 按主键更新水位和 `updated_at`。

- [ ] **Step 4: 实现独立短事务门禁**

`ProtocolCommandDispatchPaceService.tryAcquire(ProtocolCommandOutbox row, long now)` 标注：

```java
@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
```

返回嵌套 record：

```java
public record AcquireResult(boolean acquired, long nextAllowedAt) {
}
```

算法固定为：

```java
mapper.insertIfAbsent(tenantId, backend, accountId, now);
ProtocolCommandDispatchPace pace = mapper.selectForUpdate(tenantId, backend, accountId);
if (pace == null) {
    throw new IllegalStateException("协议账号投递节流水位不存在");
}
if (pace.getNextAllowedAt() > now) {
    return new AcquireResult(false, pace.getNextAllowedAt());
}
long nextAllowedAt = Math.addExact(now, dispatchIntervalMs);
if (mapper.updateNextAllowedAt(pace.getId(), nextAllowedAt, now) != 1) {
    throw new IllegalStateException("协议账号投递节流水位更新失败");
}
return new AcquireResult(true, nextAllowedAt);
```

水位推进发生在 Kafka publish 之前的独立提交事务中，因此 producer 失败也不能回退时隙。

- [ ] **Step 5: 增加 `markDeferred`**

`ProtocolCommandOutboxMapper` 按现有 `markRetry` 的 id/commandId 双路径增加：

```java
int markDeferred(ProtocolCommandOutbox lockedRow, long nextRetryAt, long updatedAt);
```

SQL 只能执行以下状态变化：

```sql
SET status = #{pendingStatus},
    next_retry_at = #{row.nextRetryAt},
    locked_by = NULL,
    locked_at = NULL,
    updated_at = #{row.updatedAt}
```

不得修改 `retry_count` 或 `last_error`。WHERE 必须继续校验 `LOCKED + locked_by + locked_at`，afterCommit 内存行使用 `command_id`，兜底扫描行使用 `id`。

- [ ] **Step 6: 运行单元、XML 与真库测试**

```bash
mvn -Dtest='ProtocolCommandDispatchPaceServiceTest' test
xmllint --noout src/main/resources/mapper/platform/protocol/ProtocolCommandDispatchPaceMapper.xml
xmllint --noout src/main/resources/mapper/platform/protocol/ProtocolCommandOutboxMapper.xml
./dbtest.sh 'ProtocolCommandOutboxMapperDbTest#markDeferred_releasesLockWithoutIncrementingRetryOrOverwritingError'
./dbtest.sh 'ProtocolCommandDispatchPaceMapperDbTest'
```

Expected: 并发 DbTest 恰好一个 acquired；所有测试行按精确 key 清理。

- [ ] **Step 7: 提交持久化节流基础设施**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-marketing-account-pace
git add armada-api/src/main/java/com/armada/platform/protocol/model/entity/ProtocolCommandDispatchPace.java \
  armada-api/src/main/java/com/armada/platform/protocol/mapper/ProtocolCommandDispatchPaceMapper.java \
  armada-api/src/main/resources/mapper/platform/protocol/ProtocolCommandDispatchPaceMapper.xml \
  armada-api/src/main/java/com/armada/platform/kafka/dispatch/ProtocolCommandDispatchPaceService.java \
  armada-api/src/main/java/com/armada/platform/protocol/mapper/ProtocolCommandOutboxMapper.java \
  armada-api/src/main/resources/mapper/platform/protocol/ProtocolCommandOutboxMapper.xml \
  armada-api/src/test/java/com/armada/platform/kafka/dispatch/ProtocolCommandDispatchPaceServiceTest.java \
  armada-api/src/test/java/com/armada/platform/protocol/mapper/ProtocolCommandDispatchPaceMapperDbTest.java \
  armada-api/src/test/java/com/armada/platform/protocol/mapper/ProtocolCommandOutboxMapperDbTest.java
git diff --check
git diff --cached --check
git commit -m "feat(protocol): add persistent account dispatch pace"
```

---

## Task 5: Dispatcher 放行算法与精确唤醒

- [ ] **Step 1: 先写 dispatcher 失败测试**

扩展 `ProtocolCommandDispatcherTest`：

1. 同一节流键三条同时到期，pace acquired 后只把第一条交给 `publishBatch`；其余两条分别延期到 `now+500`、`now+1000`，不调用 `markRetry`。
2. 两个不同账号各两条同时到期，`publishBatch` 同时收到两个账号的第一条。
3. pace denied 到 `now+300` 时，该账号所有行按 `now+300` 起顺延，没有任何一条发布。
4. pace service 抛数据库异常时，本账号没有命令发布；能释放时按 retry delay 延期，释放失败则保留 LOCKED 等待现有超时恢复。
5. Kafka publish 失败沿用现有 RETRY/DEAD，但不调用任何水位回退方法；下一账号仍按自己的结果处理。
6. `dispatchIntervalMs=0` 的行仍一次性进入现有 `publishBatch`。
7. afterCommit 输入全部是未来行时不调用 `markLockedByCommandIds`，只注册最早一次唤醒。
8. fallback 扫描拿到同账号多条过期行时仍只发布一条，证明重启不会突发追赶。

新建 `ProtocolCommandDispatchWakeupTest`，用 mock `TaskScheduler` 和 `ScheduledFuture` 覆盖：

- 已有更早唤醒时忽略更晚请求；
- 新请求更早时取消旧 future 并替换；
- 被取消但竞态启动的旧 runnable 因时间戳不匹配不执行 dispatch；
- 当前 runnable 完成后允许注册下一次唤醒。

- [ ] **Step 2: 运行测试并确认 RED**

```bash
mvn -Dtest='ProtocolCommandDispatcherTest,ProtocolCommandDispatchWakeupTest' test
```

Expected: dispatcher 仍整批发布，且 wakeup 类型不存在。

- [ ] **Step 3: 注册单线程 TaskScheduler 并实现合并唤醒**

`ProtocolKafkaConfiguration` 新增：

```java
@Bean(name = "protocolCommandDispatchTaskScheduler")
public TaskScheduler protocolCommandDispatchTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("protocol-command-wakeup-");
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    scheduler.initialize();
    return scheduler;
}
```

`ProtocolCommandDispatchWakeup` 使用一个 monitor、`ScheduledFuture<?> scheduledFuture` 和 `long scheduledAt=Long.MAX_VALUE`。`schedule(long dueAt, Runnable dispatch)` 规则：

- 有未完成任务且 `scheduledAt <= dueAt` 时忽略新请求；
- 新请求更早时 `cancel(false)` 旧任务并替换；
- 包装 runnable 执行前再次核对 `scheduledAt`，旧竞态 runnable 不执行；
- 真正运行前清空当前 future/时间，再调用 `dispatch.run()`；
- 运行异常只记录安全日志，低频 scheduler 继续兜底。

- [ ] **Step 4: 在 afterCommit 主路径识别未来行**

`dispatchInsertedRows()` 在锁定前：

1. 以当前 `now` 把输入拆成 `nextRetryAt <= now` 的 due rows 与 future rows；
2. future rows 只把最早时间传给 `wakeup.schedule(earliestFutureAt, this::dispatchPendingNow)`；
3. 只对 due rows 调 `markLockedByCommandIds`；
4. 没有 due rows 时返回 empty，不打印“未抢到锁”警告。

这样每个 outbox 批次都可注册候选时间，但进程内最终只保留全局最早唤醒。

- [ ] **Step 5: 实现同账号只放行最早一条**

在 `sendLockedRows()` 调 publisher 前执行：

1. `dispatchIntervalMs<=0` 直接加入 publishable。
2. paced rows 按 `(tenantId, protocolBackend, protocolAccountId)` 分组。
3. 每组按 `nextRetryAt`、`id`、`commandId` 稳定排序。
4. 只对组内第一条调用 `paceService.tryAcquire(first, now)`。
5. acquired 时第一条加入 publishable，延期剩余行；denied 时延期全组。
6. 不同组的第一条与 immediate 行合并后一次调用 `publishBatch`。

延期时间计算必须使用前一条命令的间隔：

```text
cursor = acquireResult.nextAllowedAt
对每条待延期行:
  row.nextRetryAt = cursor
  cursor = cursor + row.dispatchIntervalMs
```

acquired 时从组内第二条开始；denied 时从第一条开始。每次 `markDeferred` 成功后注册该行最早 `nextRetryAt`。延期失败的行绝不能加入 publishable。

若 pace service 抛异常，先尝试把该组以 `now + retryDelayMs` 起重新延期并唤醒；mapper 同时失败时保留 LOCKED，交给 `recoverExpiredLocks()`，绝不绕过水位直接发布。

- [ ] **Step 6: 保留 Kafka 失败语义与安全日志**

publisher 只处理 publishable rows，成功/RETRY/DEAD 回写沿用现有代码。水位服务没有 rollback API。新增日志只允许包含 `commandId`、`task/aggregateId`、内部 `protocolAccountId`、backend、`dispatchIntervalMs`、`deferredUntil`；不得输出手机号、正文、图片或 payload JSON。

- [ ] **Step 7: 运行 dispatcher 聚焦回归**

```bash
mvn -Dtest='ProtocolCommandDispatcherTest,ProtocolCommandDispatchWakeupTest,ProtocolCommandDispatchTriggerTest,ProtocolCommandDispatcherPropertiesTest' test
```

Expected: 同账号只发一条、不同账号并行、未来行精确唤醒、非 paced 行保持原行为。

- [ ] **Step 8: 运行完整相关后端测试并提交**

```bash
mvn -Dtest='MarketingAccountGroupSendIntervalTest,MarketingTaskServiceImplLifecycleTest,MarketingTaskMapperSqlShapeTest,MarketingRoundWorkerTest,ProtocolCommandOutboxServiceImplTest,RoutingMessageSendPortTest,WebMessageSendBackendTest,AndroidMessageSendBackendTest,ProtocolCommandDispatcherTest,ProtocolCommandDispatchWakeupTest,ProtocolCommandDispatchTriggerTest' test
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-marketing-account-pace
git add armada-api/src/main/java/com/armada/platform/kafka/config/ProtocolKafkaConfiguration.java \
  armada-api/src/main/java/com/armada/platform/kafka/dispatch/ProtocolCommandDispatcher.java \
  armada-api/src/main/java/com/armada/platform/kafka/dispatch/ProtocolCommandDispatchWakeup.java \
  armada-api/src/test/java/com/armada/platform/kafka/dispatch/ProtocolCommandDispatcherTest.java \
  armada-api/src/test/java/com/armada/platform/kafka/dispatch/ProtocolCommandDispatchWakeupTest.java
git diff --check
git diff --cached --check
git commit -m "feat(protocol): pace marketing commands per account"
```

---

## Task 6: 自动数据模型、全量验证与交付记录

- [ ] **Step 1: 在已确认并已迁移的测试库导出 information_schema**

从 `armada-api/.env` 加载变量但不回显凭据。将 `DB_URL` 的标准 JDBC MySQL 地址拆成任务专用变量后，使用 `MYSQL_PWD` 调用 mysql，分别生成：

```text
/tmp/wheel_columns.tsv
/tmp/wheel_indexes.tsv
/tmp/wheel_tables.tsv
```

在仓库根目录执行以下 zsh 命令：

```bash
set -a
source armada-api/.env
set +a
pace_model_target="${DB_URL#jdbc:mysql://}"
pace_model_authority="${pace_model_target%%/*}"
pace_model_schema_query="${pace_model_target#*/}"
pace_model_schema="${pace_model_schema_query%%\?*}"
pace_model_host="${pace_model_authority%%:*}"
if [[ "$pace_model_authority" == *:* ]]; then
  pace_model_port="${pace_model_authority##*:}"
else
  pace_model_port="3306"
fi
MYSQL_PWD="$DB_PASSWORD" mysql \
  --host="$pace_model_host" --port="$pace_model_port" \
  --user="$DB_USER" --database="$pace_model_schema" \
  --batch --raw --skip-column-names \
  --execute="SELECT table_name,column_name,column_type,is_nullable,column_default,column_comment,ordinal_position FROM information_schema.columns WHERE table_schema = DATABASE() ORDER BY table_name,ordinal_position" \
  > /tmp/wheel_columns.tsv
MYSQL_PWD="$DB_PASSWORD" mysql \
  --host="$pace_model_host" --port="$pace_model_port" \
  --user="$DB_USER" --database="$pace_model_schema" \
  --batch --raw --skip-column-names \
  --execute="SELECT table_name,index_name,column_name,non_unique,seq_in_index FROM information_schema.statistics WHERE table_schema = DATABASE() ORDER BY table_name,index_name,seq_in_index" \
  > /tmp/wheel_indexes.tsv
MYSQL_PWD="$DB_PASSWORD" mysql \
  --host="$pace_model_host" --port="$pace_model_port" \
  --user="$DB_USER" --database="$pace_model_schema" \
  --batch --raw --skip-column-names \
  --execute="SELECT table_name,table_comment FROM information_schema.tables WHERE table_schema = DATABASE() ORDER BY table_name" \
  > /tmp/wheel_tables.tsv
```

三个查询的列顺序必须保持不变，因为 `.harness/wiki/gen_datamodel.py` 按该位置解析 TSV。

- [ ] **Step 2: 只通过生成器刷新 wiki**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-marketing-account-pace
python3 .harness/wiki/gen_datamodel.py
cp /tmp/datamodel_tables.md .harness/wiki/数据模型.md
rg -n 'account_group_send_interval_ms|dispatch_interval_ms|protocol_command_dispatch_pace' .harness/wiki/数据模型.md
```

Expected: 三个名称都出现，`protocol_command_dispatch_pace` 唯一键包含 tenant/backend/account。禁止手改生成结果。

- [ ] **Step 3: 运行所有相关真库 DbTest**

```bash
cd armada-api
./dbtest.sh 'MarketingTaskCreateReadDbTest'
./dbtest.sh 'ProtocolCommandOutboxMapperDbTest'
./dbtest.sh 'ProtocolCommandDispatchPaceMapperDbTest'
```

Expected: 创建/读取精确换算、outbox 延期、唯一水位与并发门禁全部通过。

- [ ] **Step 4: 运行前端最终验证**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/wheel-marketing-account-pace
node --test src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts
pnpm typecheck
pnpm build
git diff --check
```

- [ ] **Step 5: 运行后端最终验证**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-marketing-account-pace/armada-api
mvn test
```

然后回到仓库根目录：

```bash
xmllint --noout armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml
xmllint --noout armada-api/src/main/resources/mapper/platform/protocol/ProtocolCommandOutboxMapper.xml
xmllint --noout armada-api/src/main/resources/mapper/platform/protocol/ProtocolCommandDispatchPaceMapper.xml
git diff --check
```

如果全量测试因既有 Flyway checksum 或外部服务失败，记录命令、退出码和首个真实错误；聚焦测试不能替代全量完成声明。

- [ ] **Step 6: 更新变更记录**

将 `.harness/changes/2026-07-18-marketing-account-group-send-interval.md` 与目录内 `summary.md` 更新为真实结果：

- 前后端 commit；
- RED/GREEN 测试证据；
- DbTest 确认过的环境名称，不记录密码；
- `mvn test`、`pnpm typecheck`、`pnpm build` 的真实结果；
- 未修改 `armada-protocol` 和 Android Zhuan；
- 未部署。任何测试/生产部署仍需单独确认目标环境。

- [ ] **Step 7: 需求级回归检查**

```bash
rg -n 'accountGroupSendIntervalSeconds|account_group_send_interval_ms|dispatch_interval_ms|protocol_command_dispatch_pace' \
  /Users/daishuaishuai/IdeaProjects/.worktrees/wheel-marketing-account-pace/src \
  /Users/daishuaishuai/IdeaProjects/.worktrees/armada-marketing-account-pace/armada-api/src/main \
  /Users/daishuaishuai/IdeaProjects/.worktrees/armada-marketing-account-pace/armada-api/src/test
rg -n 'dispatchPolicy|dispatchIntervalMs|notBeforeAt' \
  /Users/daishuaishuai/IdeaProjects/.worktrees/armada-marketing-account-pace/armada-api/src/main/java/com/armada/platform/protocol/backend
```

第一条应覆盖 UI、API、任务、worker、outbox、dispatcher 和测试；第二条只能命中 Armada 内部 command/outbox 处理，不得出现在 Web/Android wire payload DTO 字段中。

- [ ] **Step 8: 提交文档和生成模型**

```bash
cd /Users/daishuaishuai/IdeaProjects/.worktrees/armada-marketing-account-pace
git add .harness/wiki/数据模型.md \
  .harness/changes/2026-07-18-marketing-account-group-send-interval.md \
  .harness/changes/marketing-account-group-send-interval
git diff --cached --check
git commit -m "docs: record marketing account dispatch pacing"
```

---

## 实施完成判定

只有以下条件同时满足才可以声称完成：

- 页面名称精确为“单账号下群组发送间隔”，默认 0.5、最大 3、步长 0.1。
- 前后端都拒绝越界和超过一位小数，后端 null 使用 500ms。
- 旧任务迁移后为 500ms，非普通营销 outbox 为 0ms。
- 同账号跨批次、跨轮次、跨任务、多实例和重启恢复都不会突发；不同账号可同批发布。
- deferred 不增加 `retry_count`、不覆盖 `last_error`；Kafka 失败不回退水位。
- Kafka payload 没有 pacing 字段，协议层与 Android Zhuan 没有代码改动。
- 聚焦单测、确认环境后的真库 DbTest、前端 test/typecheck/build 和后端 `mvn test` 都有真实证据，或如实报告外部阻塞。
