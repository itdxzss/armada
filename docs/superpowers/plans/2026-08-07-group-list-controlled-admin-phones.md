# Controlled Group Admin Phones Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 群组列表的 `admin` / `adminPhones` 只返回当前租户有效上控账号中的管理员和群主号码，同时保持“可用管理员”独立口径。

**Architecture:** 继续由最后一次完整成员快照判断管理员/群主角色，只在现有 `admins` 聚合中增加一次按 `tenant_id + ws_phone` 连接有效 `account` 的内连接。列表投影和管理员关键字搜索复用同一聚合结果，不增加新的查询层、接口字段或数据模型。

**Tech Stack:** Java 17、Spring Boot 3.3.5、MyBatis/MyBatis-Plus、MySQL SQL、H2 MySQL mode、JUnit 5、AssertJ、Maven

## Global Constraints

- 生产逻辑只修改 `GroupLinkMapper.xml` 的现有 `admins` 派生表；不新增表、列、索引、迁移、Java 内存过滤或额外聚合层。
- “已上控”必须同时满足同租户、`account.ws_phone = member.phone`、`account.deleted_at IS NULL`。
- 成员快照 `is_admin = 1` 同时代表管理员和群主；不得额外排除群主。
- 离线、风控或不可执行的上控管理员仍展示；`availableAdmin` / `availableAdminCount` 逻辑保持不变。
- 管理员号码展示与管理员关键字搜索必须共用相同过滤结果。
- 修改 Mapper XML 后必须执行 XML 校验，并使用 H2 MySQL mode 真跑生产 Mapper XML。
- 不触碰工作区中其他会话的群状态持久化和部署脚本在途修改。

---

## File Structure

- Create `armada-api/src/test/java/com/armada/group/mapper/GroupLinkControlledAdminMapperInMemoryTest.java`：隔离的 H2 MySQL mode 行为回归，真跑 `GroupLinkMapper.xml`。
- Modify `armada-api/src/test/java/com/armada/group/mapper/GroupLinkMapperSqlShapeTest.java`：锁定管理员聚合只有一次有效账号内连接，不混入在线状态。
- Modify `armada-api/src/main/resources/mapper/group/GroupLinkMapper.xml`：过滤非上控管理员/群主。
- Modify `armada-api/src/main/java/com/armada/group/model/vo/GroupLinkVO.java`：同步 `adminPhones` 字段业务注释。

### Task 1: Filter group-list admin phones by controlled accounts

**Files:**

- Create: `armada-api/src/test/java/com/armada/group/mapper/GroupLinkControlledAdminMapperInMemoryTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/mapper/GroupLinkMapperSqlShapeTest.java`
- Modify: `armada-api/src/main/resources/mapper/group/GroupLinkMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/group/model/vo/GroupLinkVO.java`

**Interfaces:**

- Consumes: `GroupLinkMapper.countByLabel(GroupLinkQuery)`、`GroupLinkMapper.selectPageByLabel(GroupLinkQuery)`、`account(tenant_id, ws_phone, deleted_at)`、`whatsapp_group_member_snapshot(tenant_id, group_link_id, phone, is_admin)`。
- Produces: 现有 `GroupLinkVoRow.admin` 和 `GroupLinkVO.adminPhones`，字段类型与 JSON 契约不变，仅收窄号码集合。

- [ ] **Step 1: Write the failing H2 behavior tests**

创建独立测试类，使用与 `WhatsappGroupMemberSnapshotMapperDbTest` 相同的 `MyBatisConfig + H2 MODE=MySQL` 装配方式加载真实 `GroupLinkMapper.xml`。测试数据必须同时包含：当前租户有效上控管理员 `1001`、当前租户有效上控群主 `1002`、仅存在于另一租户账号表的外部管理员 `1003`、当前租户普通成员 `1004`、当前租户已软删账号管理员 `1005`。

核心断言写成两个单一行为测试：

```java
@Test
void listReturnsOnlyActiveControlledAdminAndOwnerPhones() {
    GroupLinkQuery query = pageQuery();

    assertThat(mapper.selectPageByLabel(query))
            .singleElement()
            .satisfies(row -> {
                assertThat(row.getAdmin()).isEqualTo("1001, 1002");
                assertThat(row.getAvailableAdminCount()).isZero();
            });
}

@Test
void keywordMatchesControlledAdminButNotExternalAdmin() {
    GroupLinkQuery controlled = pageQuery();
    controlled.setKeyword("1002");
    assertThat(mapper.countByLabel(controlled)).isEqualTo(1L);
    assertThat(mapper.selectPageByLabel(controlled)).hasSize(1);

    GroupLinkQuery external = pageQuery();
    external.setKeyword("1003");
    assertThat(mapper.countByLabel(external)).isZero();
    assertThat(mapper.selectPageByLabel(external)).isEmpty();
}
```

测试夹具使用以下有效/跨租户/软删账号与成员快照：

```sql
INSERT INTO account (id, tenant_id, ws_phone, protocol_account_id, deleted_at) VALUES
  (301, 7, '1001', NULL, NULL),
  (302, 7, '1002', NULL, NULL),
  (303, 7, '1004', NULL, NULL),
  (304, 7, '1005', NULL, 999),
  (401, 8, '1003', NULL, NULL);

INSERT INTO whatsapp_group_member_snapshot
  (tenant_id, group_link_id, group_jid, participant_jid, phone,
   role, is_admin, is_owner, snapshot_at, created_at, updated_at)
VALUES
  (7, 201, 'controlled-admins@g.us', '1001@s.whatsapp.net', '1001', 'ADMIN', TRUE, FALSE, 100, 100, 100),
  (7, 201, 'controlled-admins@g.us', '1002@s.whatsapp.net', '1002', 'OWNER', TRUE, TRUE, 100, 100, 100),
  (7, 201, 'controlled-admins@g.us', '1003@s.whatsapp.net', '1003', 'ADMIN', TRUE, FALSE, 100, 100, 100),
  (7, 201, 'controlled-admins@g.us', '1004@s.whatsapp.net', '1004', 'MEMBER', FALSE, FALSE, 100, 100, 100),
  (7, 201, 'controlled-admins@g.us', '1005@s.whatsapp.net', '1005', 'ADMIN', TRUE, FALSE, 100, 100, 100);
```

H2 schema 只声明 `groupListFrom` 和列表投影实际读取的列。使用下列精简 DDL，不复制营销或任务域的无关表：

```sql
CREATE TABLE group_link (
  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, link_url VARCHAR(255) NOT NULL,
  group_name VARCHAR(128), label_id BIGINT, folder_id BIGINT, import_batch_id BIGINT,
  origin TINYINT NOT NULL, membership_state TINYINT NOT NULL,
  is_historical BOOLEAN DEFAULT FALSE, is_post_control BOOLEAN DEFAULT FALSE,
  sync_protocol_mask TINYINT DEFAULT 0, remark VARCHAR(255),
  deleted_at BIGINT, created_at BIGINT NOT NULL
);
CREATE TABLE group_link_import_batch (
  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, source_file_name VARCHAR(255)
);
CREATE TABLE group_link_preview (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, group_link_id BIGINT NOT NULL,
  group_jid VARCHAR(128), invite_code VARCHAR(64), wa_subject VARCHAR(255), member_size INT,
  owner_phone VARCHAR(32), avatar_url VARCHAR(512), last_preview_at BIGINT,
  creator_country_iso2 VARCHAR(2), creator_continent_code VARCHAR(24), group_created_at BIGINT
);
CREATE TABLE group_link_health (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, group_link_id BIGINT NOT NULL,
  health_status TINYINT, is_banned BOOLEAN, current_count INT,
  last_check_at BIGINT, last_health_error VARCHAR(64)
);
CREATE TABLE group_folder (
  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, name VARCHAR(100), deleted_at BIGINT
);
CREATE TABLE country (
  id BIGINT PRIMARY KEY, iso2 VARCHAR(2), name_zh VARCHAR(64), flag VARCHAR(16), deleted_at BIGINT
);
CREATE TABLE whatsapp_group_member_snapshot (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, group_link_id BIGINT NOT NULL,
  group_jid VARCHAR(128) NOT NULL, participant_jid VARCHAR(128) NOT NULL, phone VARCHAR(32),
  role VARCHAR(32), is_admin BOOLEAN DEFAULT FALSE, is_owner BOOLEAN DEFAULT FALSE,
  snapshot_at BIGINT NOT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL
);
CREATE TABLE account (
  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, ws_phone VARCHAR(32) NOT NULL,
  protocol_account_id VARCHAR(64), deleted_at BIGINT
);
CREATE TABLE account_group_membership (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, group_link_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL, membership_status TINYINT NOT NULL,
  is_admin BOOLEAN, deleted_at BIGINT
);
CREATE TABLE account_state (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, account_id BIGINT NOT NULL,
  login_state TINYINT, account_state TINYINT
);
CREATE TABLE group_metadata_sync_task (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, group_link_id BIGINT NOT NULL,
  status TINYINT, last_success_at BIGINT, last_error_message VARCHAR(512)
);
```

测试装配和基础查询对象使用以下代码：

```java
private static final long TENANT_ID = 7L;
private static final String ACCOUNT_FIXTURE_SQL = """
        INSERT INTO account (id, tenant_id, ws_phone, protocol_account_id, deleted_at) VALUES
          (301, 7, '1001', NULL, NULL),
          (302, 7, '1002', NULL, NULL),
          (303, 7, '1004', NULL, NULL),
          (304, 7, '1005', NULL, 999),
          (401, 8, '1003', NULL, NULL)
        """;
private static final String MEMBER_FIXTURE_SQL = """
        INSERT INTO whatsapp_group_member_snapshot
          (tenant_id, group_link_id, group_jid, participant_jid, phone,
           role, is_admin, is_owner, snapshot_at, created_at, updated_at)
        VALUES
          (7, 201, 'controlled-admins@g.us', '1001@s.whatsapp.net', '1001', 'ADMIN', TRUE, FALSE, 100, 100, 100),
          (7, 201, 'controlled-admins@g.us', '1002@s.whatsapp.net', '1002', 'OWNER', TRUE, TRUE, 100, 100, 100),
          (7, 201, 'controlled-admins@g.us', '1003@s.whatsapp.net', '1003', 'ADMIN', TRUE, FALSE, 100, 100, 100),
          (7, 201, 'controlled-admins@g.us', '1004@s.whatsapp.net', '1004', 'MEMBER', FALSE, FALSE, 100, 100, 100),
          (7, 201, 'controlled-admins@g.us', '1005@s.whatsapp.net', '1005', 'ADMIN', TRUE, FALSE, 100, 100, 100)
        """;

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

private static GroupLinkQuery pageQuery() {
    GroupLinkQuery query = new GroupLinkQuery();
    query.setPage(1);
    query.setPageSize(10);
    return query;
}

private void insertFixtures() throws SQLException {
    execute("""
            INSERT INTO group_link
              (id, tenant_id, link_url, origin, membership_state, created_at)
            VALUES (201, 7, 'wa://group/controlled-admins@g.us', 5, 2, 100)
            """, """
            INSERT INTO group_link_preview
              (tenant_id, group_link_id, group_jid, wa_subject, member_size)
            VALUES (7, 201, 'controlled-admins@g.us', '受控管理员测试群', 5)
            """,
            ACCOUNT_FIXTURE_SQL,
            MEMBER_FIXTURE_SQL);
}

private void execute(String... statements) throws SQLException {
    try (Connection connection = dataSource.getConnection();
         Statement statement = connection.createStatement()) {
        for (String sql : statements) {
            statement.execute(sql);
        }
    }
}

@Configuration(proxyBeanMethods = false)
@Import(MyBatisConfig.class)
static class TestConfig {

    @Bean
    DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:group_link_controlled_admin_mapper_test;"
                + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    @Bean
    SqlSessionFactory sqlSessionFactory(
            DataSource dataSource,
            MybatisPlusInterceptor interceptor) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setPlugins(interceptor);
        factory.setMapperLocations(new ClassPathResource("mapper/group/GroupLinkMapper.xml"));
        return factory.getObject();
    }

    @Bean
    SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }

    @Bean
    GroupLinkMapper groupLinkMapper(SqlSessionTemplate template) {
        return template.getMapper(GroupLinkMapper.class);
    }
}
```

- [ ] **Step 2: Add the failing SQL simplicity guard**

在 `GroupLinkMapperSqlShapeTest` 新增测试，仅截取 `admins` 派生表到 `operable` 派生表之前的 XML 文本：

```java
@Test
void groupListAdminAggregationUsesOneActiveControlledAccountJoin() throws IOException {
    String xml = new String(
            getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
            StandardCharsets.UTF_8);
    int groupListStart = xml.indexOf("<sql id=\"groupListFrom\">");
    int adminsEnd = xml.indexOf("    ) admins", groupListStart);
    String beforeAdminsEnd = xml.substring(groupListStart, adminsEnd);
    int adminsStart = beforeAdminsEnd.lastIndexOf("    LEFT JOIN (");
    String adminsSql = xml.substring(adminsStart, adminsEnd);

    assertThat(adminsSql)
            .contains("INNER JOIN account controlled_account")
            .contains("controlled_account.tenant_id = member.tenant_id")
            .contains("controlled_account.ws_phone = member.phone")
            .contains("controlled_account.deleted_at IS NULL")
            .doesNotContain("account_state")
            .doesNotContain("login_state")
            .doesNotContain("protocol_account_id")
            .doesNotContain("EXISTS");
}
```

- [ ] **Step 3: Run the focused tests and verify RED**

Run:

```bash
cd armada-api
mvn -Dtest='GroupLinkControlledAdminMapperInMemoryTest,GroupLinkMapperSqlShapeTest' test
```

Expected: 新 H2 行为测试显示 `admin` 仍包含 `1003` 和 `1005`，或 SQL 结构测试因缺少 `controlled_account` 内连接失败。失败必须来自缺少本次过滤，而不是 schema 缺列、MyBatis 解析或测试装配错误；若是装配错误，先修测试夹具并重跑到业务断言失败。

- [ ] **Step 4: Implement the minimal SQL change**

把现有管理员聚合替换为以下结构；不要改 `operable`：

```xml
    LEFT JOIN (
      SELECT member.tenant_id, member.group_link_id,
             GROUP_CONCAT(DISTINCT member.phone ORDER BY member.phone SEPARATOR ', ') AS admin
      FROM whatsapp_group_member_snapshot member
      INNER JOIN account controlled_account
        ON controlled_account.tenant_id = member.tenant_id
       AND controlled_account.ws_phone = member.phone
       AND controlled_account.deleted_at IS NULL
      WHERE member.is_admin = 1
        AND member.phone IS NOT NULL
        AND TRIM(member.phone) &lt;&gt; ''
      GROUP BY member.tenant_id, member.group_link_id
    ) admins
```

同步修改 `GroupLinkVO.adminPhones` 注释：

```java
        /** 当前完整成员快照中属于本租户有效上控账号的管理员/群主号码。 */
        List<String> adminPhones,
```

- [ ] **Step 5: Run the focused tests and verify GREEN**

Run:

```bash
cd armada-api
mvn -Dtest='GroupLinkControlledAdminMapperInMemoryTest,GroupLinkMapperSqlShapeTest,GroupConverterTest' test
```

Expected: 全部通过；H2 返回 `1001, 1002`，外部管理员关键字不命中，`availableAdminCount` 仍为 `0`。

- [ ] **Step 6: Validate XML and compile production code**

Run:

```bash
xmllint --noout armada-api/src/main/resources/mapper/group/GroupLinkMapper.xml
cd armada-api
mvn -DskipTests compile
```

Expected: `xmllint` exit 0；Maven `BUILD SUCCESS`。

- [ ] **Step 7: Review scope and regression evidence**

Run:

```bash
git diff --check
git diff -- armada-api/src/main/resources/mapper/group/GroupLinkMapper.xml armada-api/src/main/java/com/armada/group/model/vo/GroupLinkVO.java armada-api/src/test/java/com/armada/group/mapper/GroupLinkControlledAdminMapperInMemoryTest.java armada-api/src/test/java/com/armada/group/mapper/GroupLinkMapperSqlShapeTest.java
git status --short
```

Expected: 本任务只包含上述四个文件；其他会话已有的群状态持久化、测试支持和部署脚本修改保持原样，未被暂存或覆盖。

- [ ] **Step 8: Commit only this behavior change**

```bash
git add armada-api/src/main/resources/mapper/group/GroupLinkMapper.xml \
  armada-api/src/main/java/com/armada/group/model/vo/GroupLinkVO.java \
  armada-api/src/test/java/com/armada/group/mapper/GroupLinkControlledAdminMapperInMemoryTest.java \
  armada-api/src/test/java/com/armada/group/mapper/GroupLinkMapperSqlShapeTest.java
git commit -m "fix(group): show only controlled admin phones"
```
