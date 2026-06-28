# 进群任务 · 第一刀(CRUD 切片)实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 wheel「进群任务」重构进 armada 的第一刀 —— 纯 DB、零协议、零引擎的 CRUD 切片:建/查/改/删 DRAFT 进群任务,生成账号×链接计划行,明细可看。

**Architecture:** 两张新表 `join_task`(配置)+ `join_task_result`(账号×链接计划行),V007 一次建齐(含引擎/Kafka 列,CRUD 不写)。计划行生成抽成**纯函数** `PlanRowGenerator`(无 Spring/DB,可单测);Service 编排建任务/列表/详情/编辑/批删;Controller 收口 7 端点。设计见 `docs/superpowers/specs/2026-06-26-join-task-crud-slice-design.md` 与 `docs/business/join-task-data-model.md`。

**Tech Stack:** Spring Boot + plain MyBatis(`@Mapper` + 手写 XML)+ MyBatis-Plus 租户行隔离 + Flyway + MySQL;JUnit5 + AssertJ 真库 DbTest。

## Global Constraints

- 时间列全 **BIGINT epoch 毫秒**,应用层写(`System.currentTimeMillis()`),无 DB `CURRENT_TIMESTAMP`。
- 出入参 JSON **camelCase**;统一信封 `ApiResponse.ok(data)`;可恢复错误抛 `BusinessException(ErrorCode.VALIDATION/NOT_FOUND, msg)`,**禁落 HTTP 200 吞错**。
- 实体纯 POJO + 手写 getter/setter(无 Lombok / 无 MP 注解);Mapper plain `@Mapper`(不继承 BaseMapper)+ 手写 XML(比较符 `&lt;`/`&gt;`,禁裸 `<`/`>`)。
- **`tenant_id` 永不手写** —— MyBatis 租户拦截器注入(INSERT 列清单不含 tenant_id;查询不写 tenant_id 条件)。两表均有 tenant_id,**不进** `MyBatisConfig.IGNORED_TABLES`。
- 状态存**英文枚举码**(`DRAFT`/`PENDING`/`SUCCESS`/`FAILED` 等),中文展示前端转。
- 列名靠 `map-underscore-to-camel-case` 或 `AS` 别名映射。
- 分页 SQL 下推(`LIMIT/OFFSET`),`count` + `selectPage` 共用 `<sql id="filter">`;禁内存分页/load-all。
- 真库 DbTest 扩展 `com.armada.testsupport.DbTestBase`(已 `@SpringBootTest webEnvironment=NONE` + `@Transactional` 回滚 + `TenantContext.set(1)`);跑 `armada-api/dbtest.sh '<TestClass 或 模式>'`。
- 迁移文件名 `V007__join_task.sql`(armada 现到 V006);表/列必带 COMMENT;`ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci`。
- 包归属 `com.armada.task.*`(现 `.gitkeep` 空壳);XML 放 `resources/mapper/task/`。**不加** `@RequirePerm`(对齐现有 account/group controller,权限后置)。
- 提交粒度:每 Task 末 `git add <精确路径>`(**禁 `git add .`** —— 共享脏 checkout 有他人 WIP)+ commit。

---

# Phase 0 — 数据地基

### Task 0.1: V007 迁移 + 迁移生效 DbTest

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V007__join_task.sql`
- Test: `armada-api/src/test/java/com/armada/task/mapper/JoinTaskMigrationDbTest.java`

**Interfaces — Produces:** 表 `join_task`(29 列)、`join_task_result`(13 列),列与 `docs/business/join-task-data-model.md` 逐字一致。

- [ ] **Step 1: 写迁移 V007**(两表完整 DDL,逐字照 `join-task-data-model.md`)

`V007__join_task.sql`:
```sql
-- 进群任务第一刀:join_task(配置)+ join_task_result(账号×链接计划行)。
-- 时间列全 BIGINT epoch 毫秒,应用层写;两表均含 tenant_id 走拦截器行隔离;无业务唯一键故不建 is_active。
-- 引擎/Kafka 列(group_jid/is_admin/promoted_at + idx_jtr_admin_lookup)一次建齐,CRUD 不写,引擎切片直接用。
CREATE TABLE join_task (
    id                     BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '主键',
    tenant_id              BIGINT       NOT NULL                 COMMENT '租户ID(拦截器注入)',
    name                   VARCHAR(128) NOT NULL DEFAULT ''      COMMENT '任务名称',
    account_group_ids      VARCHAR(512) NOT NULL DEFAULT ''      COMMENT '选中账号分组id(JSON数组快照)',
    account_group_names    VARCHAR(512) NOT NULL DEFAULT ''      COMMENT '账号分组名快照(展示用,/连接,免JOIN)',
    selected_account_ids   TEXT                  DEFAULT NULL    COMMENT '选中账号id(JSON数组快照,编辑回填)',
    links_text             TEXT                  DEFAULT NULL    COMMENT '进群链接输入框原始文本(编辑回填,不去重/拆行)',
    distribution_mode      VARCHAR(32)  NOT NULL DEFAULT 'FIXED_ACCOUNTS_PER_LINK'
                                                                 COMMENT '分配方式:FIXED_ACCOUNTS_PER_LINK每链接固定账号数 / FIXED_ACCOUNT_MULTI_LINK固定账号多链接',
    accounts_per_link      INT          NOT NULL DEFAULT 0       COMMENT '方式一:每条群链接分配账号数',
    executor_account_count INT          NOT NULL DEFAULT 0       COMMENT '方式二:参与执行账号数',
    links_per_account      INT          NOT NULL DEFAULT 0       COMMENT '方式二:每账号进群链接数',
    fixed_interval_min_sec INT          NOT NULL DEFAULT 0       COMMENT '方式一进群间隔下限(秒)',
    fixed_interval_max_sec INT          NOT NULL DEFAULT 0       COMMENT '方式一进群间隔上限(秒)',
    multi_interval_min_sec INT          NOT NULL DEFAULT 0       COMMENT '方式二进群间隔下限(秒)',
    multi_interval_max_sec INT          NOT NULL DEFAULT 0       COMMENT '方式二进群间隔上限(秒)',
    interval_label         VARCHAR(64)  NOT NULL DEFAULT ''      COMMENT '进群间隔展示(如10-20s),筛选下拉去重源',
    retry_enabled          TINYINT(1)   NOT NULL DEFAULT 0       COMMENT '失败是否自动重试',
    retry_limit            INT          NOT NULL DEFAULT 0       COMMENT '重试次数上限',
    failure_policy         VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '失败处理策略快照(JSON/标签,编辑回填)',
    total                  INT          NOT NULL DEFAULT 0       COMMENT '计划进群次数(=实际生成的PENDING行数)',
    executed               INT          NOT NULL DEFAULT 0       COMMENT '已执行次数(引擎回写,建时0)',
    success                INT          NOT NULL DEFAULT 0       COMMENT '成功进群数(引擎回写,建时0)',
    failed                 INT          NOT NULL DEFAULT 0       COMMENT '失败数(引擎回写,建时0)',
    pending                INT          NOT NULL DEFAULT 0       COMMENT '待执行数(建时=total)',
    status                 VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT '状态码:DRAFT/RUNNING/PAUSED/STOPPED/DONE/FAILED(中文前端转)',
    created_by             BIGINT                DEFAULT NULL    COMMENT '创建人user_id(暂无鉴权上下文,NULL)',
    created_at             BIGINT       NOT NULL                 COMMENT '创建时间(epoch毫秒,应用层写)',
    updated_at             BIGINT       NOT NULL                 COMMENT '更新时间(epoch毫秒,应用层写)',
    deleted_at             BIGINT                DEFAULT NULL    COMMENT '软删时间(epoch毫秒),NULL=有效',
    PRIMARY KEY (id),
    KEY idx_join_task_tenant (tenant_id, deleted_at, id),
    KEY idx_join_task_status (tenant_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '进群任务';

CREATE TABLE join_task_result (
    id           BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '主键',
    tenant_id    BIGINT       NOT NULL                 COMMENT '租户ID',
    join_task_id BIGINT       NOT NULL                 COMMENT '→join_task.id',
    account      VARCHAR(64)  NOT NULL DEFAULT ''      COMMENT '执行账号号码/别名(快照,展示用)',
    account_id   BIGINT                DEFAULT NULL    COMMENT '→account.id;建任务时回填;无效链接行为NULL',
    link         VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '群链接',
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '进群结果码:PENDING/SUCCESS/FAILED(中文前端转)',
    reason       VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '失败原因(无效链接行建时即写)',
    group_jid    VARCHAR(64)  NOT NULL DEFAULT ''      COMMENT '〔引擎〕进群成功后回填群JID',
    is_admin     TINYINT(1)   NOT NULL DEFAULT 0       COMMENT '〔Kafka〕是否已成管理员',
    promoted_at  BIGINT                DEFAULT NULL    COMMENT '〔Kafka〕成为管理员时间(epoch毫秒)',
    created_at   BIGINT       NOT NULL                 COMMENT '创建时间(epoch毫秒)',
    updated_at   BIGINT       NOT NULL                 COMMENT '更新时间(epoch毫秒,引擎逐行回写)',
    PRIMARY KEY (id),
    KEY idx_jtr_task (tenant_id, join_task_id, id),
    KEY idx_jtr_admin_lookup (tenant_id, group_jid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '进群任务明细(每账号每链接结果)';
```

- [ ] **Step 2: 写迁移生效 DbTest(先红)**

`JoinTaskMigrationDbTest.java`(扩展 `DbTestBase`,直接查 `information_schema` 断言两表存在 + 关键列):
```java
package com.armada.task.mapper;

import com.armada.testsupport.DbTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class JoinTaskMigrationDbTest extends DbTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void v007_createsJoinTaskTables() {
        Integer t1 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='join_task'",
                Integer.class);
        Integer t2 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='join_task_result'",
                Integer.class);
        assertThat(t1).isEqualTo(1);
        assertThat(t2).isEqualTo(1);
    }

    @Test
    void joinTask_timeColumnsAreBigint() {
        String type = jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema=DATABASE() AND table_name='join_task' AND column_name='created_at'",
                String.class);
        assertThat(type).isEqualTo("bigint");
    }
}
```

- [ ] **Step 3: 跑测试验证红→绿**

Run: `armada-api/dbtest.sh 'com.armada.task.mapper.JoinTaskMigrationDbTest'`
Expected: 先因表不存在失败 → 加迁移后 PASS(Flyway 启动自动应用 V007)。

- [ ] **Step 4: Commit**
```bash
git add armada-api/src/main/resources/db/migration/V007__join_task.sql \
        armada-api/src/test/java/com/armada/task/mapper/JoinTaskMigrationDbTest.java
git commit -m "feat(join-task): V007 join_task + join_task_result tables"
```

### Task 0.2: 实体 + 枚举常量

**Files:**
- Create: `armada-api/src/main/java/com/armada/task/model/entity/JoinTask.java`
- Create: `armada-api/src/main/java/com/armada/task/model/entity/JoinTaskResult.java`
- Create: `armada-api/src/main/java/com/armada/task/model/entity/JoinTaskStatus.java`
- Create: `armada-api/src/main/java/com/armada/task/model/entity/JoinResultStatus.java`
- Create: `armada-api/src/main/java/com/armada/task/model/entity/DistributionMode.java`

**Interfaces — Produces:**
- `JoinTask`(POJO,字段一一对应 `join_task` 列:`Long id; Long tenantId; String name; String accountGroupIds; String accountGroupNames; String selectedAccountIds; String linksText; String distributionMode; int accountsPerLink; int executorAccountCount; int linksPerAccount; int fixedIntervalMinSec; int fixedIntervalMaxSec; int multiIntervalMinSec; int multiIntervalMaxSec; String intervalLabel; boolean retryEnabled; int retryLimit; String failurePolicy; int total; int executed; int success; int failed; int pending; String status; Long createdBy; Long createdAt; Long updatedAt; Long deletedAt;` + 手写 getter/setter)。
- `JoinTaskResult`(POJO:`Long id; Long tenantId; Long joinTaskId; String account; Long accountId; String link; String status; String reason; String groupJid; boolean isAdmin; Long promotedAt; Long createdAt; Long updatedAt;` + getter/setter)。
- `JoinTaskStatus`:`public static final String DRAFT="DRAFT", RUNNING=..., PAUSED, STOPPED, DONE, FAILED;`(私有构造)。
- `DistributionMode`:`FIXED_ACCOUNTS_PER_LINK`、`FIXED_ACCOUNT_MULTI_LINK`(String 常量)。
- `JoinResultStatus`:`PENDING`、`SUCCESS`、`FAILED`(String 常量)。

- [ ] **Step 1: 写 5 个类**。实体为纯 POJO + 手写 getter/setter,**风格逐字照** `com.armada.account.model.entity.Account.java`(时间字段 `Long`,`isAdmin` 用 `isAdmin()/setAdmin()`)。3 个常量类为 `final class` + 私有构造 + `public static final String`。

- [ ] **Step 2: 编译验证**。Run: `(cd armada-api && mvn -q -o compile)`  Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**
```bash
git add armada-api/src/main/java/com/armada/task/model/entity/
git commit -m "feat(join-task): entities + status/mode constants"
```

---

# Phase 1 — Mapper 层

### Task 1.1: JoinTaskMapper + XML

**Files:**
- Create: `armada-api/src/main/java/com/armada/task/mapper/JoinTaskMapper.java`
- Create: `armada-api/src/main/resources/mapper/task/JoinTaskMapper.xml`
- Create: `armada-api/src/main/java/com/armada/task/model/dto/JoinTaskFilter.java`
- Test: `armada-api/src/test/java/com/armada/task/mapper/JoinTaskMapperDbTest.java`

**Interfaces:**
- Consumes: `JoinTask`(Task 0.2)。
- Produces:
  - `int insert(JoinTask task)`(`useGeneratedKeys`,回填 id)。
  - `JoinTask selectByTenantAndId(@Param("id") Long id)`(`deleted_at IS NULL` + `LIMIT 1`)。
  - `long countPage(@Param("f") JoinTaskFilter f)`。
  - `List<JoinTask> selectPage(@Param("f") JoinTaskFilter f, @Param("offset") int offset, @Param("limit") int limit)`(`ORDER BY id DESC`)。
  - `List<String> selectDistinctIntervals()`(非空 `interval_label` 去重)。
  - `int update(JoinTask task)`(覆盖配置列 + 计数器,`WHERE id=#{id} AND deleted_at IS NULL`)。
  - `int batchSoftDelete(@Param("ids") List<Long> ids, @Param("deletedAt") long deletedAt)`(`deleted_at IS NULL` 才置,幂等)。
  - `JoinTaskFilter`(record:`String keyword; String status; Long groupId; String distributionMode; String interval; Long dateFrom; Long dateTo;`)。

- [ ] **Step 1: 写 Mapper 接口 + JoinTaskFilter**(plain `@Mapper`,方法签名同上)。

- [ ] **Step 2: 写 XML**(关键片段;`tenant_id` 不出现在 INSERT/WHERE,拦截器注入):
```xml
<mapper namespace="com.armada.task.mapper.JoinTaskMapper">
  <insert id="insert" useGeneratedKeys="true" keyProperty="id">
    INSERT INTO join_task (
      name, account_group_ids, account_group_names, selected_account_ids, links_text,
      distribution_mode, accounts_per_link, executor_account_count, links_per_account,
      fixed_interval_min_sec, fixed_interval_max_sec, multi_interval_min_sec, multi_interval_max_sec,
      interval_label, retry_enabled, retry_limit, failure_policy,
      total, executed, success, failed, pending, status,
      created_by, created_at, updated_at
    ) VALUES (
      #{name}, #{accountGroupIds}, #{accountGroupNames}, #{selectedAccountIds}, #{linksText},
      #{distributionMode}, #{accountsPerLink}, #{executorAccountCount}, #{linksPerAccount},
      #{fixedIntervalMinSec}, #{fixedIntervalMaxSec}, #{multiIntervalMinSec}, #{multiIntervalMaxSec},
      #{intervalLabel}, #{retryEnabled}, #{retryLimit}, #{failurePolicy},
      #{total}, #{executed}, #{success}, #{failed}, #{pending}, #{status},
      #{createdBy}, #{createdAt}, #{updatedAt}
    )
  </insert>

  <select id="selectByTenantAndId" resultType="com.armada.task.model.entity.JoinTask">
    SELECT * FROM join_task WHERE id = #{id} AND deleted_at IS NULL LIMIT 1
  </select>

  <sql id="filter">
    deleted_at IS NULL
    <if test="f.keyword != null and f.keyword != ''">
      AND (name LIKE CONCAT('%', #{f.keyword}, '%') OR links_text LIKE CONCAT('%', #{f.keyword}, '%'))
    </if>
    <if test="f.status != null and f.status != ''">AND status = #{f.status}</if>
    <if test="f.distributionMode != null and f.distributionMode != ''">AND distribution_mode = #{f.distributionMode}</if>
    <if test="f.interval != null and f.interval != ''">AND interval_label = #{f.interval}</if>
    <if test="f.groupId != null">AND account_group_ids LIKE CONCAT('%', #{f.groupId}, '%')</if>
    <if test="f.dateFrom != null">AND created_at &gt;= #{f.dateFrom}</if>
    <if test="f.dateTo != null">AND created_at &lt; #{f.dateTo}</if>
  </sql>

  <select id="countPage" resultType="long">
    SELECT COUNT(*) FROM join_task WHERE <include refid="filter"/>
  </select>

  <select id="selectPage" resultType="com.armada.task.model.entity.JoinTask">
    SELECT * FROM join_task WHERE <include refid="filter"/>
    ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}
  </select>

  <select id="selectDistinctIntervals" resultType="string">
    SELECT DISTINCT interval_label FROM join_task
    WHERE deleted_at IS NULL AND interval_label &lt;&gt; '' ORDER BY interval_label
  </select>

  <update id="update">
    UPDATE join_task SET
      name=#{name}, account_group_ids=#{accountGroupIds}, account_group_names=#{accountGroupNames},
      selected_account_ids=#{selectedAccountIds}, links_text=#{linksText}, distribution_mode=#{distributionMode},
      accounts_per_link=#{accountsPerLink}, executor_account_count=#{executorAccountCount}, links_per_account=#{linksPerAccount},
      fixed_interval_min_sec=#{fixedIntervalMinSec}, fixed_interval_max_sec=#{fixedIntervalMaxSec},
      multi_interval_min_sec=#{multiIntervalMinSec}, multi_interval_max_sec=#{multiIntervalMaxSec},
      interval_label=#{intervalLabel}, retry_enabled=#{retryEnabled}, retry_limit=#{retryLimit},
      failure_policy=#{failurePolicy}, total=#{total}, pending=#{pending}, updated_at=#{updatedAt}
    WHERE id=#{id} AND deleted_at IS NULL
  </update>

  <update id="batchSoftDelete">
    UPDATE join_task SET deleted_at=#{deletedAt}, updated_at=#{deletedAt}
    WHERE deleted_at IS NULL AND id IN
    <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
  </update>
</mapper>
```
> `groupId` 命中 `account_group_ids` JSON 串用 `LIKE`(够第一刀;精确匹配留后续硬化 TODO)。`interval_label &lt;&gt; ''` = `<>`。

- [ ] **Step 3: 写 DbTest(先红)** `JoinTaskMapperDbTest`:`insert` 后 `selectByTenantAndId` 取回字段一致(时间为传入 epoch);`selectPage`/`countPage` 按 keyword/status/mode/interval/日期区间命中;`selectDistinctIntervals` 去重;`update` 改配置列;`batchSoftDelete` 置 `deleted_at`、幂等(再删返回 0)、删后 `selectByTenantAndId` 为 null。每个 DbTest 用 `System.currentTimeMillis()` 显式写时间。

- [ ] **Step 4: 跑** `armada-api/dbtest.sh 'com.armada.task.mapper.JoinTaskMapperDbTest'` → PASS。
- [ ] **Step 5: Commit** `git add` 上述 4 文件 + `git commit -m "feat(join-task): JoinTaskMapper + XML (CRUD/list/filter)"`。

### Task 1.2: JoinTaskResultMapper + XML

**Files:**
- Create: `armada-api/src/main/java/com/armada/task/mapper/JoinTaskResultMapper.java`
- Create: `armada-api/src/main/resources/mapper/task/JoinTaskResultMapper.xml`
- Test: `armada-api/src/test/java/com/armada/task/mapper/JoinTaskResultMapperDbTest.java`

**Interfaces — Produces:**
- `int insertResults(@Param("rows") List<JoinTaskResult> rows)`(批量;每行须先 set `createdAt`/`updatedAt`)。
- `List<JoinTaskResult> selectResultsByTask(@Param("joinTaskId") Long joinTaskId)`(`ORDER BY id`)。
- `int deleteResultsByTask(@Param("joinTaskId") Long joinTaskId)`(物理删该任务计划行,供 update 重建)。

- [ ] **Step 1: 写 Mapper + XML**:
```xml
<mapper namespace="com.armada.task.mapper.JoinTaskResultMapper">
  <insert id="insertResults">
    INSERT INTO join_task_result
      (join_task_id, account, account_id, link, status, reason, created_at, updated_at)
    VALUES
    <foreach collection="rows" item="r" separator=",">
      (#{r.joinTaskId}, #{r.account}, #{r.accountId}, #{r.link}, #{r.status}, #{r.reason}, #{r.createdAt}, #{r.updatedAt})
    </foreach>
  </insert>

  <select id="selectResultsByTask" resultType="com.armada.task.model.entity.JoinTaskResult">
    SELECT * FROM join_task_result WHERE join_task_id = #{joinTaskId} ORDER BY id
  </select>

  <delete id="deleteResultsByTask">
    DELETE FROM join_task_result WHERE join_task_id = #{joinTaskId}
  </delete>
</mapper>
```
> 批量 INSERT 不写 `tenant_id`(拦截器注入)。`group_jid`/`is_admin`/`promoted_at` 不在 INSERT 列(用 DB 默认值)。

- [ ] **Step 2: DbTest(先红)** `JoinTaskResultMapperDbTest`:`insertResults` 3 行 → `selectResultsByTask` 取回 3 行保序、`account`/`account_id`/`status`/`reason` 一致、`group_jid=''`/`is_admin=false` 为默认;`deleteResultsByTask` 后查空。
- [ ] **Step 3: 跑** `armada-api/dbtest.sh 'com.armada.task.mapper.JoinTaskResultMapperDbTest'` → PASS。
- [ ] **Step 4: Commit** `feat(join-task): JoinTaskResultMapper + XML`。

---

# Phase 2 — 计划行算法(纯函数)+ 建任务

### Task 2.1: 链接分类 + 计划行生成纯函数 + 单测

**Files:**
- Create: `armada-api/src/main/java/com/armada/task/service/LinkClassifier.java`
- Create: `armada-api/src/main/java/com/armada/task/service/PlanRowGenerator.java`
- Create: `armada-api/src/main/java/com/armada/task/model/dto/SelectedAccount.java`
- Create: `armada-api/src/main/java/com/armada/task/model/dto/PlanRow.java`
- Test: `armada-api/src/test/java/com/armada/task/service/PlanRowGeneratorTest.java`(纯单测,无 Spring)

**Interfaces — Produces:**
- `SelectedAccount`(record:`Long accountId; String phone;`)。
- `PlanRow`(record:`Long accountId; String account; String link; String status; String reason;`)。
- `LinkClassifier`:`record Classified(List<String> valid, List<String> invalid){}`;`static Classified classify(String linksText)` —— 按行拆 → trim → 去空 → `LinkedHashSet` 去重保序 → 含 `chat.whatsapp.com`(忽略大小写)入 `valid`,否则 `invalid`。
- `PlanRowGenerator`:`static List<PlanRow> generate(String mode, List<SelectedAccount> accounts, List<String> validLinks, List<String> invalidLinks, int accountsPerLink, int executorAccountCount, int linksPerAccount)` —— 先无效链接 → `FAILED` 行(account="",accountId=null,reason="非群链接"),再按 mode 生成 `PENDING` 行(账号取 `accounts.get(idx % n)` 的 phone+accountId 双填)。负参数归一为 0。

- [ ] **Step 1: 写单测(先红)** `PlanRowGeneratorTest`:
```java
package com.armada.task.service;

import com.armada.task.model.dto.PlanRow;
import com.armada.task.model.dto.SelectedAccount;
import com.armada.task.model.entity.DistributionMode;
import com.armada.task.model.entity.JoinResultStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class PlanRowGeneratorTest {

    private static final SelectedAccount A = new SelectedAccount(1L, "911");
    private static final SelectedAccount B = new SelectedAccount(2L, "922");
    private static final SelectedAccount C = new SelectedAccount(3L, "933");

    @Test
    void mode1_eachLinkGetsN_roundRobinAcrossLinks() {
        // 方式一:2链接 · N=2 · [A,B,C] → L1:[A,B] L2:[C,A](rr 连续)
        List<PlanRow> rows = PlanRowGenerator.generate(
                DistributionMode.FIXED_ACCOUNTS_PER_LINK,
                List.of(A, B, C), List.of("L1", "L2"), List.of(), 2, 0, 0);
        assertThat(rows).extracting("account", "link", "status")
                .containsExactly(
                        tuple("911", "L1", JoinResultStatus.PENDING),
                        tuple("922", "L1", JoinResultStatus.PENDING),
                        tuple("933", "L2", JoinResultStatus.PENDING),
                        tuple("911", "L2", JoinResultStatus.PENDING));
        assertThat(rows).extracting("accountId").containsExactly(1L, 2L, 3L, 1L);
    }

    @Test
    void mode2_eachAccountJoinsFirstKLinks_cappedByAvailable() {
        // 方式二:M=2 · K=3 · 仅2条有效链接 → linkCap=2;[A,B] → A:[L1,L2] B:[L1,L2]
        List<PlanRow> rows = PlanRowGenerator.generate(
                DistributionMode.FIXED_ACCOUNT_MULTI_LINK,
                List.of(A, B), List.of("L1", "L2"), List.of(), 0, 2, 3);
        assertThat(rows).extracting("account", "link")
                .containsExactly(
                        tuple("911", "L1"), tuple("911", "L2"),
                        tuple("922", "L1"), tuple("922", "L2"));
    }

    @Test
    void invalidLinks_becomeFailedRows() {
        List<PlanRow> rows = PlanRowGenerator.generate(
                DistributionMode.FIXED_ACCOUNTS_PER_LINK,
                List.of(A), List.of(), List.of("not-a-link"), 1, 0, 0);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).status()).isEqualTo(JoinResultStatus.FAILED);
        assertThat(rows.get(0).account()).isEmpty();
        assertThat(rows.get(0).accountId()).isNull();
        assertThat(rows.get(0).reason()).isEqualTo("非群链接");
    }

    @Test
    void classify_splitsDedupsAndKeepsOrder() {
        LinkClassifier.Classified c = LinkClassifier.classify(
                "https://chat.whatsapp.com/AAA\n\nbad\nhttps://chat.whatsapp.com/AAA\nhttps://chat.whatsapp.com/BBB");
        assertThat(c.valid()).containsExactly("https://chat.whatsapp.com/AAA", "https://chat.whatsapp.com/BBB");
        assertThat(c.invalid()).containsExactly("bad");
    }
}
```

- [ ] **Step 2: 跑验证红** `armada-api/dbtest.sh 'com.armada.task.service.PlanRowGeneratorTest'` → 编译失败/红。

- [ ] **Step 3: 写实现**。`LinkClassifier.classify` 如上;`PlanRowGenerator.generate`:
```java
public static List<PlanRow> generate(String mode, List<SelectedAccount> accounts,
        List<String> validLinks, List<String> invalidLinks,
        int accountsPerLink, int executorAccountCount, int linksPerAccount) {
    List<PlanRow> rows = new ArrayList<>();
    for (String link : invalidLinks) {
        rows.add(new PlanRow(null, "", link, JoinResultStatus.FAILED, "非群链接"));
    }
    int n = accounts.size();
    if (DistributionMode.FIXED_ACCOUNT_MULTI_LINK.equals(mode)) {
        int accountCount = Math.max(executorAccountCount, 0);
        int linkCap = Math.min(Math.max(linksPerAccount, 0), validLinks.size());
        for (int a = 0; a < accountCount; a++) {
            SelectedAccount acc = n == 0 ? null : accounts.get(a % n);
            for (int l = 0; l < linkCap; l++) {
                rows.add(pending(acc, validLinks.get(l)));
            }
        }
    } else {
        int perLink = Math.max(accountsPerLink, 0);
        int rr = 0;
        for (String link : validLinks) {
            for (int i = 0; i < perLink; i++) {
                SelectedAccount acc = n == 0 ? null : accounts.get(rr % n);
                rr++;
                rows.add(pending(acc, link));
            }
        }
    }
    return rows;
}
private static PlanRow pending(SelectedAccount acc, String link) {
    return new PlanRow(acc == null ? null : acc.accountId(),
            acc == null ? "" : acc.phone(), link, JoinResultStatus.PENDING, "");
}
```

- [ ] **Step 4: 跑** → PASS。
- [ ] **Step 5: Commit** `feat(join-task): pure plan-row generator + link classifier`。

### Task 2.2: 建任务 service(create)+ DbTest

**Files:**
- Create: `armada-api/src/main/java/com/armada/task/model/dto/CreateJoinTaskRequest.java`
- Create: `armada-api/src/main/java/com/armada/task/service/JsonIds.java`
- Create: `armada-api/src/main/java/com/armada/task/service/JoinTaskService.java`(接口)
- Create: `armada-api/src/main/java/com/armada/task/service/impl/JoinTaskServiceImpl.java`
- Create: `armada-api/src/main/java/com/armada/task/model/vo/JoinTaskVO.java`
- Test: `armada-api/src/test/java/com/armada/task/service/JoinTaskCreateDbTest.java`

**Interfaces:**
- Consumes: `JoinTaskMapper`、`JoinTaskResultMapper`、`PlanRowGenerator`、`LinkClassifier`。
- Produces:
  - `CreateJoinTaskRequest`(record):`String name; List<Long> accountGroupIds; List<String> accountGroupNames; List<SelectedAccount> selectedAccounts; String linksText; String distributionMode; Integer accountsPerLink; Integer executorAccountCount; Integer linksPerAccount; Integer fixedIntervalMinSec; Integer fixedIntervalMaxSec; Integer multiIntervalMinSec; Integer multiIntervalMaxSec; Boolean retryEnabled; Integer retryLimit; String failurePolicy;`。
  - `JsonIds`:`static String toJson(List<?> ids)`(null/空 → `"[]"`)、`static List<Long> parseLongs(String json)`、`static List<Long> idsOf(List<SelectedAccount> accs)`。Jackson `ObjectMapper`。
  - `JoinTaskVO`(record,camelCase,列表行):`Long id; String name; String accountGroupNames; int total; int executed; int success; int failed; int pending; String intervalLabel; String distributionMode; String failurePolicy; boolean retryEnabled; int retryLimit; String status; Long createdBy; Long createdAt;`。
  - `JoinTaskService.createTask(CreateJoinTaskRequest req) -> JoinTaskVO`。

- [ ] **Step 1: 写 DbTest(先红)** `JoinTaskCreateDbTest`(扩展 `DbTestBase`,`@Autowired JoinTaskService` + `JoinTaskResultMapper`):
  - 方式一:2 条 `chat.whatsapp.com` 链接 + N=2 + `selectedAccounts=[{1,"911"},{2,"922"},{3,"933"}]` → 建后 `selectResultsByTask` 得 4 行 PENDING,账号轮询 `911,922,933,911`、`account_id` 双填 `1,2,3,1`;回读 `join_task`:`status=DRAFT`、`total=4`、`pending=4`、`executed=success=failed=0`、`interval_label` 来自 fixed 区间。
  - 方式二:M=2,K=3,2 链接 → 4 行;`total=4`(数实际行)、`pending=4`。
  - 含 1 条无效链接 → 多一条 `FAILED` 行(reason=非群链接),但 `total` 只数 PENDING。
  - `name` 空 → 抛 `BusinessException`(code=VALIDATION)。
  - `selected_account_ids` 落库 = `[1,2,3]` JSON(供回填)。

- [ ] **Step 2: 跑验证红** `armada-api/dbtest.sh 'com.armada.task.service.JoinTaskCreateDbTest'`。

- [ ] **Step 3: 写 `JsonIds` + `JoinTaskService` 接口 + `JoinTaskServiceImpl.createTask`**:
```java
@Transactional
public JoinTaskVO createTask(CreateJoinTaskRequest req) {
    if (req == null || req.name() == null || req.name().isBlank()) {
        throw new BusinessException(ErrorCode.VALIDATION, "任务名称不能为空");
    }
    String mode = DistributionMode.FIXED_ACCOUNT_MULTI_LINK.equals(req.distributionMode())
            ? DistributionMode.FIXED_ACCOUNT_MULTI_LINK : DistributionMode.FIXED_ACCOUNTS_PER_LINK;
    LinkClassifier.Classified links = LinkClassifier.classify(req.linksText());
    List<SelectedAccount> accounts = req.selectedAccounts() == null ? List.of() : req.selectedAccounts();
    List<PlanRow> rows = PlanRowGenerator.generate(mode, accounts, links.valid(), links.invalid(),
            n(req.accountsPerLink()), n(req.executorAccountCount()), n(req.linksPerAccount()));
    int total = (int) rows.stream().filter(r -> JoinResultStatus.PENDING.equals(r.status())).count();
    long now = System.currentTimeMillis();

    JoinTask task = new JoinTask();
    task.setName(req.name().trim());
    task.setAccountGroupIds(JsonIds.toJson(req.accountGroupIds()));
    task.setAccountGroupNames(joinNames(req.accountGroupNames()));
    task.setSelectedAccountIds(JsonIds.toJson(JsonIds.idsOf(accounts)));
    task.setLinksText(req.linksText() == null ? "" : req.linksText());
    task.setDistributionMode(mode);
    task.setFailurePolicy(req.failurePolicy() == null ? "" : req.failurePolicy());
    task.setAccountsPerLink(n(req.accountsPerLink()));
    task.setExecutorAccountCount(n(req.executorAccountCount()));
    task.setLinksPerAccount(n(req.linksPerAccount()));
    task.setFixedIntervalMinSec(n(req.fixedIntervalMinSec()));
    task.setFixedIntervalMaxSec(n(req.fixedIntervalMaxSec()));
    task.setMultiIntervalMinSec(n(req.multiIntervalMinSec()));
    task.setMultiIntervalMaxSec(n(req.multiIntervalMaxSec()));
    task.setIntervalLabel(intervalLabel(mode, req));
    task.setRetryEnabled(Boolean.TRUE.equals(req.retryEnabled()));
    task.setRetryLimit(n(req.retryLimit()));
    task.setTotal(total);
    task.setExecuted(0); task.setSuccess(0); task.setFailed(0); task.setPending(total);
    task.setStatus(JoinTaskStatus.DRAFT);
    task.setCreatedAt(now); task.setUpdatedAt(now);
    joinTaskMapper.insert(task);

    persistRows(task.getId(), rows, now);
    return toVO(joinTaskMapper.selectByTenantAndId(task.getId()));
}
// n() = 负/ null 归 0;joinNames = 以 "/" 连接;intervalLabel = 按 mode 取 min-max + "s";
// persistRows:每行 set joinTaskId/createdAt/updatedAt 后 insertResults(空则跳过)。
```

- [ ] **Step 4: 跑** → PASS。
- [ ] **Step 5: Commit** `feat(join-task): create task + plan-row persistence`。

---

# Phase 3 — 读路径 / 编辑 / 批删 / Controller

### Task 3.1: 列表 + 间隔 + 详情 + 明细(读路径)+ DbTest

**Files:**
- Modify: `JoinTaskService`/`JoinTaskServiceImpl`(+`listTasks`/`intervalOptions`/`getDetail`/`results`)
- Create: `armada-api/src/main/java/com/armada/task/model/dto/JoinTaskQuery.java`(`extends PageQuery`)
- Create: `armada-api/src/main/java/com/armada/task/model/vo/JoinTaskDetailVO.java`
- Create: `armada-api/src/main/java/com/armada/task/model/vo/JoinResultRowVO.java`
- Create: `armada-api/src/main/java/com/armada/task/service/GroupLinkMask.java`
- Test: `armada-api/src/test/java/com/armada/task/service/JoinTaskReadDbTest.java`

**Interfaces — Produces:**
- `JoinTaskQuery extends PageQuery`:`String keyword; String status; Long groupId; String distributionMode; String interval; Long dateFrom; Long dateTo;` + getter/setter(`@ModelAttribute` 绑定),`toFilter()` 产 `JoinTaskFilter`。
- `JoinTaskDetailVO`(record):全配置 + `List<Long> accountGroupIds; List<Long> selectedAccountIds;` 由 JSON 列解析。
- `JoinResultRowVO`(record):`String account; String link; String status; String reason; boolean isAdmin;`。
- `GroupLinkMask.mask(String link)`:群链接 → `chat.whatsapp.com/****` + 邀请码末 3 位;非群链接原样。
- `listTasks(JoinTaskQuery) -> PageResult<JoinTaskVO>`、`intervalOptions() -> List<String>`、`getDetail(Long) -> JoinTaskDetailVO`(不存在抛 `NOT_FOUND`)、`results(Long) -> List<JoinResultRowVO>`。

- [ ] **Step 1: DbTest(先红)** `JoinTaskReadDbTest`:建 2 任务 → `listTasks` 分页/各筛选命中(keyword/status/mode/interval/日期);`countPage` 对;`intervalOptions` 去重;`getDetail` JSON 列解析回 List、不存在抛 NOT_FOUND;`results` 链接脱敏 + 行序。
- [ ] **Step 2: 跑红**。
- [ ] **Step 3: 实现**(list 照 `account` 块 `total==0` 短路;converter `toVO`/`toDetailVO`/`toResultRowVO`,时间 Long 直映)。
- [ ] **Step 4: 跑** → PASS。
- [ ] **Step 5: Commit** `feat(join-task): list/intervals/detail/results read paths`。

### Task 3.2: 编辑(update)+ 批量软删 + DbTest

**Files:**
- Modify: `JoinTaskService`/`JoinTaskServiceImpl`(+`updateTask`/`batchDelete`)
- Create: `armada-api/src/main/java/com/armada/task/model/dto/JoinTaskIdsDTO.java`(`List<Long> ids`)
- Test: `armada-api/src/test/java/com/armada/task/service/JoinTaskMutationDbTest.java`

**Interfaces — Produces:**
- `updateTask(Long id, CreateJoinTaskRequest req) -> JoinTaskDetailVO`:不存在抛 NOT_FOUND;`status != DRAFT || executed > 0` 抛 VALIDATION「任务已执行,不能编辑」;否则覆盖配置 + `deleteResultsByTask` + 重新 `generate`/`persistRows`,`total/pending` 跟随。
- `batchDelete(List<Long> ids) -> int`:`batchSoftDelete`,返回删除数;空/ null → 0。

- [ ] **Step 1: DbTest(先红)** `JoinTaskMutationDbTest`:建任务 → `updateTask` 改名 + 换分配方式 → 计划行重建、行数变、`total` 跟随;构造 `executed>0`(直接 mapper 改)→ updateTask 抛 VALIDATION;`batchDelete` 软删返回数、幂等(再删返 0)、删后 `getDetail` 抛 NOT_FOUND。
- [ ] **Step 2: 跑红**。 **Step 3: 实现**。 **Step 4: 跑 PASS**。
- [ ] **Step 5: Commit** `feat(join-task): edit (DRAFT-only) + batch soft delete`。

### Task 3.3: Controller 收口 + 全量回归 + wiki

**Files:**
- Create: `armada-api/src/main/java/com/armada/task/controller/JoinTaskController.java`
- Test: `armada-api/src/test/java/com/armada/task/JoinTaskApiDbTest.java`(可选 MockMvc 或手动冒烟)

**Interfaces — Produces:** 7 端点(见 spec 表),全 `ApiResponse.ok(...)`;`@RestController @RequestMapping("/api/join-tasks")`;list 用 `@ModelAttribute JoinTaskQuery`,create/update 用 `@RequestBody CreateJoinTaskRequest`,batch-delete 用 `@RequestBody JoinTaskIdsDTO`。

- [ ] **Step 1: 写 Controller**(7 方法,照 `AccountController` 风格,无 `@RequirePerm`):
  `GET /` → list;`GET /intervals` → intervals;`POST /` → create;`GET /{id}` → detail;`PUT /{id}` → update;`GET /{id}/results` → results;`POST /batch-delete` → batchDelete。
- [ ] **Step 2: 全量回归**:`armada-api/dbtest.sh 'com.armada.task.**'` 全绿;`(cd armada-api && mvn -q -o compile)` 通过。
- [ ] **Step 3: 重跑 wiki**:`python3 .harness/wiki/gen_datamodel.py` + `python3 .harness/wiki/parse_endpoints.py`(接口协议.md 含 7 新端点;数据模型.md 含两新表)。
- [ ] **Step 4: Commit** `feat(join-task): controller + endpoints (slice 1 done)`。

---

## Self-Review(写完核对)

- **Spec 覆盖**:端点 7→Task 1.1/3.1/3.2/3.3;数据模型→0.1;实体→0.2;Mapper→1.1/1.2;算法两方式+total实际行+双填→2.1/2.2;无效链接FAILED→2.1;编辑DRAFT+校验→3.2;脱敏→3.1;跨租户→各 DbTest 由 `DbTestBase` 租户上下文保证(可补 tenant=2 查不到用例)。延后 TODO(start/group_link登记/严格校验/复制/活性过滤)**不在本计划**,spec 已列。
- **类型一致**:`PlanRow`/`SelectedAccount` record 字段全程一致;时间 `Long` epoch;`JoinResultStatus.PENDING/SUCCESS/FAILED`、`DistributionMode.*`、`JoinTaskStatus.DRAFT` 常量引用统一;`total` = PENDING 行数(数实际)贯穿 create/update。
- **占位扫描**:无 TBD;`n()`/`joinNames()`/`intervalLabel()`/`persistRows()` 私有辅助在 Task 2.2 已点明语义,实现时一并写。
- **跨租户补充**:建议在 `JoinTaskCreateDbTest` 末加一条 tenant=2 查不到 tenant=1 任务的隔离断言(照 `account` 块 cross-tenant DbTest)。
