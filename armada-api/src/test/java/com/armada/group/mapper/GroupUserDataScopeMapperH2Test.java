package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.model.dto.GroupLinkImportDetailQuery;
import com.armada.group.model.dto.GroupLinkLabelQuery;
import com.armada.group.model.dto.GroupLinkQuery;
import com.armada.group.model.vo.GroupLinkLabelVoRow;
import com.armada.shared.security.DataScope;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;

/** 群入口、标签和导入明细使用生产 Mapper XML 验证用户范围隔离。 */
@SpringJUnitConfig(GroupUserDataScopeMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class GroupUserDataScopeMapperH2Test {

    private static final long CURRENT_TENANT_ID = 7L;
    private static final long OTHER_TENANT_ID = 8L;
    private static final long USER_ONE_ID = 1001L;
    private static final long USER_TWO_ID = 1002L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private GroupListCurrentMapper groupListMapper;

    @Autowired
    private GroupLinkLabelMapper labelMapper;

    @Autowired
    private GroupLinkImportDetailMapper detailMapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(CURRENT_TENANT_ID);
        execute("DROP ALL OBJECTS");
        createSchema();
        insertFixtures();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void selfScopeReturnsOnlyOwnRowsForBothUsers() {
        assertThat(rootCounts(DataScope.self(USER_ONE_ID)))
                .isEqualTo(new RootCounts(1, 1, 1));
        assertThat(rootCounts(DataScope.self(USER_TWO_ID)))
                .isEqualTo(new RootCounts(1, 1, 1));
    }

    @Test
    void allScopeIncludesBothUsersAndHistoricalRowsWithinCurrentTenant() {
        assertThat(rootCounts(DataScope.all(USER_ONE_ID)))
                .isEqualTo(new RootCounts(3, 3, 3));

        try {
            TenantContext.set(OTHER_TENANT_ID);
            assertThat(rootCounts(DataScope.all(USER_ONE_ID)))
                    .isEqualTo(new RootCounts(1, 1, 1));
        } finally {
            TenantContext.set(CURRENT_TENANT_ID);
        }
    }

    @Test
    void missingAndSystemScopeFailClosedAcrossAllRoots() {
        assertThat(rootCounts(null)).isEqualTo(new RootCounts(0, 0, 0));
        assertThat(rootCounts(DataScope.system("group owner reconciliation")))
                .isEqualTo(new RootCounts(0, 0, 0));
    }

    @Test
    void directDetailAndMemberReadsHideOtherUsersHandleEvenForSharedCanonicalGroup() {
        assertThat(groupListMapper.selectGroupDetail(
                CURRENT_TENANT_ID, 21L, DataScope.self(USER_ONE_ID)))
                .satisfies(detail -> assertThat(detail.getGroupJid()).isEqualTo("shared@g.us"));
        assertThat(groupListMapper.selectGroupDetail(
                CURRENT_TENANT_ID, 22L, DataScope.self(USER_ONE_ID))).isNull();
        assertThat(groupListMapper.selectGroupDetailMembers(
                CURRENT_TENANT_ID, 22L, DataScope.self(USER_ONE_ID))).isEmpty();

        assertThat(groupListMapper.selectGroupDetail(
                CURRENT_TENANT_ID, 22L, DataScope.self(USER_TWO_ID)))
                .satisfies(detail -> assertThat(detail.getGroupJid()).isEqualTo("shared@g.us"));
        assertThat(groupListMapper.selectGroupDetail(
                CURRENT_TENANT_ID, 23L, DataScope.all(USER_ONE_ID)))
                .satisfies(detail -> assertThat(detail.getGroupJid()).isEqualTo("historical@g.us"));
    }

    @Test
    void historicalNullOwnerAggregatesOnlyWithHistoricalChildren() {
        GroupLinkLabelQuery query = labelQuery(DataScope.all(USER_ONE_ID));
        query.setId(13L);

        assertThat(labelMapper.selectPage(query))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getOwnerUserId()).isNull();
                    assertThat(row.getLinkCount()).isEqualTo(1L);
                    assertThat(row.getFileCount()).isEqualTo(1L);
                    assertThat(row.getFailedRows()).isEqualTo(1L);
                });
    }

    @Test
    void batchScopedFailedExportCannotReadAnotherUsersBatch() {
        assertThat(detailMapper.selectFailed(
                null, 32L, DataScope.self(USER_ONE_ID))).isEmpty();
        assertThat(detailMapper.selectFailed(
                null, 32L, DataScope.self(USER_TWO_ID))).hasSize(1);
        assertThat(detailMapper.selectFailed(
                null, 33L, DataScope.all(USER_ONE_ID))).hasSize(1);
        assertThat(detailMapper.selectFailed(null, 31L, null)).isEmpty();
    }

    private RootCounts rootCounts(DataScope scope) {
        GroupLinkQuery groupQuery = new GroupLinkQuery();
        groupQuery.applyDataScope(scope);

        GroupLinkLabelQuery labelQuery = labelQuery(scope);

        GroupLinkImportDetailQuery detailQuery = new GroupLinkImportDetailQuery();
        detailQuery.applyDataScope(scope);

        return new RootCounts(
                groupListMapper.count(TenantContext.get(), groupQuery),
                labelMapper.countPage(labelQuery),
                detailMapper.countByQuery(detailQuery));
    }

    private static GroupLinkLabelQuery labelQuery(DataScope scope) {
        GroupLinkLabelQuery query = new GroupLinkLabelQuery();
        query.setPage(1);
        query.setPageSize(20);
        query.applyDataScope(scope);
        return query;
    }

    private void createSchema() throws SQLException {
        execute("""
                CREATE TABLE group_link_label (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, owner_user_id BIGINT,
                  name VARCHAR(128), region VARCHAR(64), remark VARCHAR(255),
                  created_at BIGINT, updated_at BIGINT, created_by BIGINT, deleted_at BIGINT
                )
                """, """
                CREATE TABLE group_link_import_batch (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, owner_user_id BIGINT,
                  label_id BIGINT, batch_name VARCHAR(128), source_file_name VARCHAR(255),
                  total_rows INT, inserted_rows INT, adopted_rows INT, duplicate_rows INT,
                  failed_rows INT, created_at BIGINT, created_by BIGINT, deleted_at BIGINT
                )
                """, """
                CREATE TABLE group_link_import_detail (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, batch_id BIGINT NOT NULL,
                  line_no INT, raw_url VARCHAR(512), group_name VARCHAR(128), result INT,
                  success_type INT, fail_reason VARCHAR(64), existing_origin INT,
                  group_link_id BIGINT, created_at BIGINT
                )
                """, """
                CREATE TABLE group_link (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, owner_user_id BIGINT,
                  group_id BIGINT, group_invite_id BIGINT, link_url VARCHAR(512),
                  group_name VARCHAR(128), label_id BIGINT, folder_id BIGINT,
                  import_batch_id BIGINT, origin INT, membership_state INT,
                  is_historical INT, is_post_control INT, sync_protocol_mask INT,
                  remark VARCHAR(255), created_at BIGINT, updated_at BIGINT,
                  created_by BIGINT, deleted_at BIGINT
                )
                """, """
                CREATE TABLE wa_group (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  group_jid VARCHAR(128), avatar_url VARCHAR(512)
                )
                """, """
                CREATE TABLE wa_group_profile (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, group_id BIGINT NOT NULL,
                  subject VARCHAR(255), description VARCHAR(512), member_count INT,
                  announce_only INT, admin_only_edit_info INT, member_add_mode INT,
                  member_link_mode INT, join_approval_mode INT,
                  ephemeral_duration_seconds INT, wa_created_at BIGINT,
                  metadata_observed_at BIGINT, member_snapshot_at BIGINT,
                  member_snapshot_version VARCHAR(128), health_status INT, banned INT,
                  checked_member_count INT, last_checked_at BIGINT, last_error_code VARCHAR(64),
                  current_invite_id BIGINT, created_at BIGINT, updated_at BIGINT
                )
                """, """
                CREATE TABLE wa_group_participant (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, group_id BIGINT NOT NULL,
                  pn_jid VARCHAR(128), lid_jid VARCHAR(128), phone VARCHAR(32), role INT,
                  presence_status INT, last_snapshot_version VARCHAR(128),
                  created_at BIGINT, updated_at BIGINT
                )
                """);
    }

    private void insertFixtures() throws SQLException {
        execute("""
                INSERT INTO group_link_label
                  (id, tenant_id, owner_user_id, name, created_at, updated_at, deleted_at)
                VALUES
                  (11, 7, 1001, 'U1标签', 100, 100, NULL),
                  (12, 7, 1002, 'U2标签', 100, 100, NULL),
                  (13, 7, NULL, '历史标签', 100, 100, NULL),
                  (14, 7, 1001, '已删标签', 100, 100, 900),
                  (15, 8, 1001, '其他租户标签', 100, 100, NULL)
                """, """
                INSERT INTO group_link_import_batch
                  (id, tenant_id, owner_user_id, label_id, source_file_name,
                   total_rows, inserted_rows, adopted_rows, duplicate_rows, failed_rows,
                   created_at, deleted_at)
                VALUES
                  (31, 7, 1001, 11, 'u1.txt', 1, 0, 0, 0, 1, 100, NULL),
                  (32, 7, 1002, 12, 'u2.txt', 1, 0, 0, 0, 1, 100, NULL),
                  (33, 7, NULL, 13, 'legacy.txt', 1, 0, 0, 0, 1, 100, NULL),
                  (35, 8, 1001, 15, 'other.txt', 1, 0, 0, 0, 1, 100, NULL)
                """, """
                INSERT INTO group_link
                  (id, tenant_id, owner_user_id, group_id, link_url, label_id,
                   import_batch_id, origin, membership_state, is_historical,
                   is_post_control, sync_protocol_mask, created_at, updated_at, deleted_at)
                VALUES
                  (21, 7, 1001, 501, 'wa://group/shared-u1', 11, 31, 5, 2, 0, 1, 1, 100, 100, NULL),
                  (22, 7, 1002, 501, 'wa://group/shared-u2', 12, 32, 5, 2, 0, 1, 1, 100, 100, NULL),
                  (23, 7, NULL, 502, 'wa://group/historical', 13, 33, 5, 2, 1, 0, 1, 100, 100, NULL),
                  (24, 7, 1001, NULL, 'wa://group/deleted', 11, NULL, 5, 2, 0, 0, 1, 100, 100, 900),
                  (25, 8, 1001, 503, 'wa://group/other', 15, 35, 5, 2, 0, 1, 1, 100, 100, NULL)
                """, """
                INSERT INTO group_link_import_detail
                  (id, tenant_id, batch_id, line_no, raw_url, result, fail_reason,
                   group_link_id, created_at)
                VALUES
                  (41, 7, 31, 1, 'u1', 2, 'DUPLICATE', NULL, 100),
                  (42, 7, 32, 1, 'u2', 2, 'DUPLICATE', NULL, 100),
                  (43, 7, 33, 1, 'legacy', 2, 'DUPLICATE', NULL, 100),
                  (45, 8, 35, 1, 'other', 2, 'DUPLICATE', NULL, 100)
                """, """
                INSERT INTO wa_group (id, tenant_id, group_jid, avatar_url)
                VALUES
                  (501, 7, 'shared@g.us', NULL),
                  (502, 7, 'historical@g.us', NULL),
                  (503, 8, 'other@g.us', NULL)
                """, """
                INSERT INTO wa_group_profile
                  (id, tenant_id, group_id, subject, member_count, member_snapshot_at,
                   member_snapshot_version, created_at, updated_at)
                VALUES
                  (601, 7, 501, '共享事实群', 1, 100, 'v1', 100, 100),
                  (602, 7, 502, '历史事实群', 1, 100, 'v1', 100, 100),
                  (603, 8, 503, '其他租户群', 1, 100, 'v1', 100, 100)
                """, """
                INSERT INTO wa_group_participant
                  (id, tenant_id, group_id, pn_jid, phone, role, presence_status,
                   last_snapshot_version, created_at, updated_at)
                VALUES
                  (701, 7, 501, '8613000000001@s.whatsapp.net', '8613000000001', 2, 1, 'v1', 100, 100)
                """);
    }

    private void execute(String... statements) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private record RootCounts(long groupLinks, long labels, long importDetails) {
    }

    /** 加载群域三个生产 Mapper XML 和生产租户插件。 */
    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:group_user_data_scope_mapper_test;"
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
            factory.setMapperLocations(
                    new ClassPathResource("mapper/group/GroupListCurrentMapper.xml"),
                    new ClassPathResource("mapper/group/GroupLinkLabelMapper.xml"),
                    new ClassPathResource("mapper/group/GroupLinkImportDetailMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        GroupListCurrentMapper groupListCurrentMapper(SqlSessionTemplate template) {
            return template.getMapper(GroupListCurrentMapper.class);
        }

        @Bean
        GroupLinkLabelMapper groupLinkLabelMapper(SqlSessionTemplate template) {
            return template.getMapper(GroupLinkLabelMapper.class);
        }

        @Bean
        GroupLinkImportDetailMapper groupLinkImportDetailMapper(SqlSessionTemplate template) {
            return template.getMapper(GroupLinkImportDetailMapper.class);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
