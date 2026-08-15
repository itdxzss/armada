# 群组模型第一阶段兼容基线实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在切换群组表之前，用真实 Mapper XML 锁定三处最容易被换表误改的现有业务口径。

**Architecture:** 本计划只增加测试，不修改生产 Java、Mapper XML、Flyway、接口或业务行为。列表兼容、账号/营销群数量差异和空关系状态营销资格分别独立成三个小任务；测试读取现有 v1 表，并作为后续六表 Adapter/查询切换的黄金结果。

**Tech Stack:** Java 17、JUnit 5、AssertJ、H2 MySQL mode、MyBatis-Plus 租户拦截器、现有 MyBatis XML。

## Global Constraints

- 不修改任何生产 Java、Mapper XML、Flyway、Controller、DTO/VO、前端或协议仓代码。
- 不连接 test1，不部署，不执行远程命令，不读取或输出凭据。
- 群组列表的行集合、稳定排序、legacy 数值 `id`、同 JID alias 行和未解析邀请行保持现有语义。
- 账号列表 `groupsNum` 与营销账号树 `groupCount` 是两套现有口径，不得统一：前者统计未删除且状态为 `IN_GROUP/UNCONFIRMED` 的行；后者统计未删除且 JID 非空白的全部五种状态。
- Mapper 行为必须使用 H2 MySQL mode、真实 Mapper XML 和生产租户拦截器验证，不允许 mock Mapper 或只断言 SQL 文本。
- 测试断言对外可观察结果；不得把旧物理表名写成后续六表实现必须保持的断言。
- 每个任务只改其 `Files` 列出的文件，不顺手清理、抽象或扩展测试框架。

---

### Task 1: 锁定群组列表 alias、未解析邀请和稳定排序

**Files:**
- Modify: `armada-api/src/test/java/com/armada/group/mapper/GroupLinkControlledAdminMapperInMemoryTest.java`

**Interfaces:**
- Consumes: `GroupLinkMapper.countByLabel(GroupLinkQuery)`、`GroupLinkMapper.selectPageByLabel(GroupLinkQuery)`。
- Produces: 一个真实 XML 黄金测试，证明同一 JID 的两个 legacy `group_link.id` 仍返回两行，未解析邀请仍返回一行，顺序为 `created_at DESC, id DESC`。

- [ ] **Step 1: 在现有测试类增加兼容行测试**

在现有两个测试方法之后增加：

```java
@Test
void listPreservesLegacyAliasRowsUnresolvedInvitationAndStableOrder() throws SQLException {
    insertListCompatibilityFixtures();

    GroupLinkQuery query = pageQuery();
    assertThat(mapper.countByLabel(query)).isEqualTo(4L);

    var rows = mapper.selectPageByLabel(query);
    assertThat(rows).extracting(row -> row.getId())
            .containsExactly(204L, 203L, 202L, 201L);
    assertThat(rows).filteredOn(row -> "same-jid@g.us".equals(row.getGroupJid()))
            .extracting(row -> row.getId())
            .containsExactly(203L, 202L);
    assertThat(rows).filteredOn(row -> row.getId().equals(204L))
            .singleElement()
            .satisfies(row -> assertThat(row.getGroupJid()).isNull());
}
```

在 `insertFixtures()` 前增加测试专用 fixture 方法：

```java
private void insertListCompatibilityFixtures() throws SQLException {
    execute(
            """
            INSERT INTO group_link
              (id, tenant_id, link_url, origin, membership_state, created_at, updated_at)
            VALUES
              (202, 7, 'wa://group/same-jid-alias-a@g.us', 5, 2, 200, 200),
              (203, 7, 'wa://group/same-jid-alias-b@g.us', 5, 2, 200, 200),
              (204, 7, 'https://chat.whatsapp.com/unresolved-code', 1, 1, 300, 300)
            """,
            """
            INSERT INTO group_link_preview
              (tenant_id, group_link_id, group_jid, wa_subject, member_size)
            VALUES
              (7, 202, 'same-jid@g.us', '同 JID 别名 A', 10),
              (7, 203, 'same-jid@g.us', '同 JID 别名 B', 10)
            """);
}
```

- [ ] **Step 2: 运行目标测试并验证黄金结果**

Run:

```bash
cd armada-api
mvn -q -Dtest='GroupLinkControlledAdminMapperInMemoryTest' test
```

Expected: exit code `0`；3 个测试通过。失败时只修正测试 fixture/断言与当前生产口径的事实偏差，不修改生产 XML。

- [ ] **Step 3: 做测试敏感性检查并恢复工作树**

临时把新测试的期望顺序改成 `202L, 203L, 204L, 201L`，重跑新方法，确认因顺序断言失败；随后恢复为 `204L, 203L, 202L, 201L` 并再次运行通过。临时错误期望不得提交。

- [ ] **Step 4: 提交本任务**

```bash
git add armada-api/src/test/java/com/armada/group/mapper/GroupLinkControlledAdminMapperInMemoryTest.java
git commit -m "test: lock group list alias compatibility"
```

---

### Task 2: 锁定账号列表和营销账号树的两套群数量口径

**Files:**
- Create: `armada-api/src/test/java/com/armada/group/mapper/GroupMembershipCountSemanticsMapperH2Test.java`

**Interfaces:**
- Consumes: `AccountMapper.selectPage(AccountQuery)`、`MarketingTaskMapper.selectAccountTreeAccounts(Long)`。
- Produces: 一个同时加载 `AccountMapper.xml` 与 `MarketingTaskMapper.xml` 的 H2 黄金测试；同一 fixture 下账号列表 `groupsNum=3`，营销账号树 `groupCount=5`。

- [ ] **Step 1: 建立聚焦的真实 XML 测试类**

新测试类必须使用以下结构和断言；`groupsNum=3` 包含两条可发送状态和一条空白 JID 的 `IN_GROUP` 行，这是当前账号列表 SQL 的精确口径，不得擅自改成 `2`。营销树排除空白 JID，但保留五种非空 JID 状态，因此为 `5`。

```java
package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.dto.AccountQuery;
import com.armada.boot.config.MyBatisConfig;
import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

@SpringJUnitConfig(GroupMembershipCountSemanticsMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class GroupMembershipCountSemanticsMapperH2Test {

    private static final long TENANT_ID = 7L;

    @Autowired
    private DataSource dataSource;
    @Autowired
    private AccountMapper accountMapper;
    @Autowired
    private MarketingTaskMapper marketingTaskMapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(TENANT_ID);
        execute("DROP ALL OBJECTS");
        createSchema();
        insertFixtures();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void accountListAndMarketingTreeKeepDifferentMembershipCountSemantics() {
        AccountQuery accountQuery = new AccountQuery();
        accountQuery.setPhone("923300000501");
        accountQuery.setPage(1);
        accountQuery.setPageSize(10);

        assertThat(accountMapper.selectPage(accountQuery))
                .singleElement()
                .satisfies(row -> assertThat(row.getGroupsNum()).isEqualTo(3));
        assertThat(marketingTaskMapper.selectAccountTreeAccounts(11L))
                .singleElement()
                .satisfies(row -> assertThat(row.getGroupCount()).isEqualTo(5));
    }

    private void insertFixtures() throws SQLException {
        execute(
                "INSERT INTO account_group (id, tenant_id, name, deleted_at) VALUES (11, 7, '当前租户组', NULL)",
                """
                INSERT INTO account
                  (id, tenant_id, ws_phone, account_type, ownership, protocol_account_id,
                   group_baseline_state, account_group_id, created_at, deleted_at)
                VALUES
                  (501, 7, '923300000501', 1, 1, 'acc_501', 3, 11, 100, NULL),
                  (601, 8, '923300000601', 1, 1, 'acc_601', 3, 11, 100, NULL)
                """,
                """
                INSERT INTO account_group_membership
                  (id, tenant_id, account_id, group_jid, membership_status, deleted_at)
                VALUES
                  (1, 7, 501, 'in-group@g.us', 1, NULL),
                  (2, 7, 501, 'unconfirmed@g.us', 2, NULL),
                  (3, 7, 501, 'kicked@g.us', 3, NULL),
                  (4, 7, 501, 'left@g.us', 4, NULL),
                  (5, 7, 501, 'not-in-group@g.us', 5, NULL),
                  (6, 7, 501, 'deleted@g.us', 1, 999),
                  (7, 7, 501, '   ', 1, NULL),
                  (8, 8, 601, 'other-tenant@g.us', 1, NULL)
                """);
    }
```

`createSchema()` 必须一次创建下列最小表和列，不加入无关约束或测试框架：

```sql
CREATE TABLE account (
  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, ws_phone VARCHAR(32) NOT NULL,
  account_type TINYINT NOT NULL, device_os TINYINT, number_source TINYINT,
  channel_name VARCHAR(128), protocol_id VARCHAR(32), protocol_account_id VARCHAR(64),
  group_baseline_state TINYINT NOT NULL, account_group_id BIGINT, ownership TINYINT NOT NULL,
  lease_until BIGINT, dispatched_at BIGINT, created_at BIGINT NOT NULL, deleted_at BIGINT
);
CREATE TABLE account_state (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, account_id BIGINT NOT NULL,
  account_state TINYINT, login_state TINYINT, risk_status TINYINT, risk_end_time BIGINT,
  cooldown_until BIGINT, mute_status TINYINT, block_error_code VARCHAR(32),
  block_reason VARCHAR(255), state_source VARCHAR(64), truth_ip VARCHAR(45),
  proxy_country VARCHAR(64), proxy_source VARCHAR(64), pull_into_group_count INT,
  invalidated_at BIGINT, last_state_sync_time BIGINT
);
CREATE TABLE account_group (
  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, name VARCHAR(100),
  marketing_occupancy_type VARCHAR(32), marketing_occupancy_task_id BIGINT,
  marketing_locked_at BIGINT, deleted_at BIGINT
);
CREATE TABLE account_credential (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL, cred_format TINYINT, deleted_at BIGINT
);
CREATE TABLE ip_proxy (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
  bound_account_id BIGINT, region VARCHAR(64), source VARCHAR(64),
  status TINYINT, deleted_at BIGINT
);
CREATE TABLE country (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, name_zh VARCHAR(64), flag VARCHAR(16), deleted_at BIGINT
);
CREATE TABLE account_group_baseline (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL, baseline_group_jids VARCHAR(1024)
);
CREATE TABLE account_group_membership (
  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, account_id BIGINT NOT NULL,
  group_jid VARCHAR(128) NOT NULL, membership_status TINYINT NOT NULL, deleted_at BIGINT
);
```

`execute(String...)` 使用单个 JDBC connection/statement 顺序执行；内部 `TestConfig` 必须：

- H2 URL 固定为 `jdbc:h2:mem:group_membership_count_semantics;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1`；
- `@Import(MyBatisConfig.class)`，复用生产 `MybatisPlusInterceptor`；
- Mapper location 只加载 `mapper/account/AccountMapper.xml` 与 `mapper/marketing/MarketingTaskMapper.xml`；
- 只暴露 `AccountMapper` 与 `MarketingTaskMapper` 两个 mapper bean。

- [ ] **Step 2: 运行目标测试**

Run:

```bash
cd armada-api
mvn -q -Dtest='GroupMembershipCountSemanticsMapperH2Test' test
```

Expected: exit code `0`；测试真实执行两个 Mapper XML 查询，账号列表为 `3`，营销树为 `5`。

- [ ] **Step 3: 做测试敏感性检查并恢复工作树**

临时把账号列表期望改为 `2`，确认测试因实际值 `3` 失败；恢复为 `3`。再临时把营销树期望改为 `4`，确认测试因实际值 `5` 失败；恢复为 `5`。最终目标测试必须再次通过，临时错误期望不得提交。

- [ ] **Step 4: 提交本任务**

```bash
git add armada-api/src/test/java/com/armada/group/mapper/GroupMembershipCountSemanticsMapperH2Test.java
git commit -m "test: lock group membership count semantics"
```

---

### Task 3: 锁定空关系状态的现有营销资格

**Files:**
- Modify: `armada-api/src/test/java/com/armada/marketing/service/impl/MarketingMembershipSendPolicyTest.java`

**Interfaces:**
- Consumes: `MarketingMembershipSendPolicy.decide(AccountGroupMembershipStatus)`。
- Produces: 空状态继续按现有 `UNCONFIRMED` 口径允许发送的回归断言。

- [ ] **Step 1: 增加空状态行为测试**

增加 `org.junit.jupiter.api.Test` import，并在参数化可发送状态测试之后增加：

```java
@Test
void missingStatusKeepsLegacyUnconfirmedEligibility() {
    var decision = MarketingMembershipSendPolicy.decide(null);

    assertThat(decision.sendable()).isTrue();
    assertThat(decision.reasonCode()).isNull();
    assertThat(decision.reasonMessage()).isNull();
}
```

- [ ] **Step 2: 运行目标测试**

Run:

```bash
cd armada-api
mvn -q -Dtest='MarketingMembershipSendPolicyTest' test
```

Expected: exit code `0`；现有五个枚举分支与新增 null 分支全部通过。

- [ ] **Step 3: 做测试敏感性检查并恢复工作树**

临时把新增测试的 `sendable` 期望改为 `false`，确认失败后恢复为 `true` 并再次运行通过。临时错误期望不得提交。

- [ ] **Step 4: 提交本任务**

```bash
git add armada-api/src/test/java/com/armada/marketing/service/impl/MarketingMembershipSendPolicyTest.java
git commit -m "test: lock missing membership marketing policy"
```

---

## 完成门禁

全部任务完成后运行：

```bash
cd armada-api
mvn -q -Dtest='GroupLinkControlledAdminMapperInMemoryTest,GroupMembershipCountSemanticsMapperH2Test,MarketingMembershipSendPolicyTest,AccountGroupMembershipSnapshotServiceImplTest' test
```

Expected: exit code `0`，且 Maven 不报告失败或跳过目标用例。最后用 `git diff --check` 和 `git status --short` 确认只有本计划测试与计划文件，未混入生产逻辑、DDL、协议、前端或远程环境改动。
