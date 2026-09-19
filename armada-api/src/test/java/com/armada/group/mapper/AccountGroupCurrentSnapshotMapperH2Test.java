package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Existing;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.ParticipantIdentityMergeWrite;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.ParticipantIdentityRow;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.ParticipantPresenceWrite;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.nio.charset.StandardCharsets;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 账号群当前快照查询的 H2 MySQL 模式映射测试。 */
@SpringJUnitConfig(AccountGroupCurrentSnapshotMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class AccountGroupCurrentSnapshotMapperH2Test {

    private static final long TENANT_ID = 7L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AccountGroupCurrentSnapshotMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(TENANT_ID);
        execute("DROP ALL OBJECTS");
        execute("""
                CREATE TABLE wa_group (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  group_jid VARCHAR(128) NOT NULL, deleted_at BIGINT
                )
                """, """
                CREATE TABLE wa_group_participant (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  group_id BIGINT NOT NULL, pn_jid VARCHAR(128), lid_jid VARCHAR(128),
                  phone VARCHAR(32), updated_at BIGINT DEFAULT 0,
                  presence_status TINYINT, presence_source VARCHAR(64),
                  presence_observed_at BIGINT
                )
                """, """
                CREATE TABLE wa_account_group_binding (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  account_id BIGINT NOT NULL, group_id BIGINT NOT NULL,
                  participant_id BIGINT NOT NULL, was_in_initial_baseline TINYINT,
                  first_post_control_observed_at BIGINT,
                  membership_active_since_at BIGINT, updated_at BIGINT DEFAULT 0
                )
                """, """
                INSERT INTO wa_group (id, tenant_id, group_jid, deleted_at)
                VALUES (101, 7, 'new-group@g.us', NULL),
                       (201, 8, 'other-tenant@g.us', NULL)
                """, """
                INSERT INTO wa_group_participant
                  (id, tenant_id, group_id, pn_jid, presence_status,
                   presence_source, presence_observed_at)
                VALUES (301, 7, 101, '919118818029@s.whatsapp.net', 1,
                        'WGP2_ADD', 200),
                       (401, 8, 201, '919118818029@s.whatsapp.net', 1,
                        'WGP2_ADD', 300)
                """, """
                INSERT INTO wa_account_group_binding
                  (id, tenant_id, account_id, group_id, participant_id,
                   was_in_initial_baseline, first_post_control_observed_at,
                   membership_active_since_at)
                VALUES (501, 7, 1001, 101, 301, 0, 200, 200),
                       (601, 8, 1001, 201, 401, 0, 300, 300)
                """);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void selfMembershipQueryReturnsPreciseMembershipActiveSinceForCurrentTenant() {
        Existing existing = mapper.selectSelfMembershipExistingByTenant(
                TENANT_ID, 1001L, "919118818029@s.whatsapp.net", "new-group@g.us");

        assertThat(existing).isNotNull();
        assertThat(existing.membershipActiveSinceAt()).isEqualTo(200L);
        assertThat(mapper.selectExistingAfterGroupLock(
                TENANT_ID, 1001L, "919118818029@s.whatsapp.net",
                List.of(101L)))
                .singleElement()
                .extracting(Existing::membershipActiveSinceAt)
                .isEqualTo(200L);
        assertThat(mapper.selectSelfMembershipExisting(
                1001L, "919118818029@s.whatsapp.net", "other-tenant@g.us"))
                .isNull();
    }

    @Test
    void selfMembershipReadDoesNotWaitForParticipantOrBindingWriter() throws Exception {
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (Connection writer = dataSource.getConnection()) {
            writer.setAutoCommit(false);
            try (Statement statement = writer.createStatement()) {
                statement.executeUpdate("UPDATE wa_group_participant SET updated_at=900 WHERE id=301");
                statement.executeUpdate("UPDATE wa_account_group_binding SET updated_at=900 WHERE id=501");
            }
            var read = executor.submit(() -> {
                TenantContext.set(TENANT_ID);
                try {
                    var tx = new org.springframework.transaction.support.TransactionTemplate(
                            new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
                    return tx.execute(status -> mapper.selectSelfMembershipExistingByTenant(
                            TENANT_ID, 1001L, "919118818029@s.whatsapp.net", "new-group@g.us"));
                } finally {
                    TenantContext.clear();
                }
            });
            try {
                assertThat(read.get(1, java.util.concurrent.TimeUnit.SECONDS)).isNotNull();
            } finally {
                writer.rollback();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void selfBindingUpsertAllowsActiveSinceRepairOnlyWhileParticipantIsInGroup()
            throws Exception {
        ClassPathResource resource = new ClassPathResource(
                "mapper/group/AccountGroupCurrentSnapshotMapper.xml");
        String xml;
        try (var input = resource.getInputStream()) {
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        String normalizedXml = xml.replaceAll("\\s+", " ");
        assertThat(normalizedXml).contains(
                "CASE WHEN participant.presence_status = 1 "
                        + "AND #{row.membershipActiveSinceAt} IS NOT NULL "
                        + "THEN #{row.membershipActiveSinceAt} ELSE NULL END");
        assertThat(normalizedXml)
                .contains("<sql id=\"earliestMembershipActiveSince\">")
                .contains("ELSE LEAST( wa_account_group_binding.membership_active_since_at, "
                        + "VALUES(membership_active_since_at) )")
                .contains("<update id=\"clearMembershipActiveSinceForAcceptedExit\">")
                .contains("AND participant.presence_status = 2 "
                        + "AND participant.presence_source = #{exit.presenceSource} "
                        + "AND participant.presence_observed_at = #{exit.observedAt}");
    }

    @Test
    void identityMergeSelectsBothRowsThenRepointsDeletesAndCompletesCanonicalIdentity()
            throws SQLException {
        String pnJid = "919000000002@s.whatsapp.net";
        String lidJid = "123456789012345@lid";
        execute("""
                INSERT INTO wa_group_participant
                  (id, tenant_id, group_id, pn_jid, lid_jid, phone, updated_at)
                VALUES (302, 7, 101, '919000000002@s.whatsapp.net', NULL,
                        '919000000002', 100),
                       (303, 7, 101, NULL, '123456789012345@lid',
                        '919000000002', 200)
                """, """
                INSERT INTO wa_account_group_binding
                  (id, tenant_id, account_id, group_id, participant_id, updated_at)
                VALUES (502, 7, 1002, 101, 302, 100)
                """);
        ParticipantPresenceWrite candidate = new ParticipantPresenceWrite(
                101L, "new-group@g.us", pnJid, lidJid, "919000000002",
                1, "FULL_SNAPSHOT", "snapshot-1", 300L, 300L,
                null, null, null, null, 1, "FULL_SNAPSHOT", 300L,
                "snapshot-1", "snapshot-1", null, null, null, null);

        List<ParticipantIdentityRow> identities =
                mapper.selectParticipantIdentityRows(TENANT_ID, List.of(candidate));

        assertThat(identities).extracting(ParticipantIdentityRow::id)
                .containsExactly(302L, 303L);
        ParticipantIdentityMergeWrite merge = new ParticipantIdentityMergeWrite(
                TENANT_ID, 101L, 303L, 302L, pnJid, lidJid, "919000000002", 300L);
        assertThat(mapper.repointSplitParticipantBindings(merge)).isOne();
        assertThat(mapper.deleteSplitParticipantDuplicate(merge)).isOne();
        assertThat(mapper.completeSplitParticipantIdentity(merge)).isOne();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject(
                "SELECT participant_id FROM wa_account_group_binding WHERE id = 502",
                Long.class)).isEqualTo(303L);
        assertThat(jdbc.queryForMap(
                "SELECT pn_jid, lid_jid, phone FROM wa_group_participant WHERE id = 303"))
                .containsEntry("pn_jid", pnJid)
                .containsEntry("lid_jid", lidJid)
                .containsEntry("phone", "919000000002");
    }

    @Test
    void primaryUpdateCompletesLidAndDuplicateOrOlderNotificationPreservesNewerFacts()
            throws SQLException {
        prepareParticipantFactColumns();
        var jdbc = new JdbcTemplate(dataSource);
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        ParticipantPresenceWrite add = fact(101L, "213@lid", 1, "WGP2_ADD", 400L, 1);
        tx.executeWithoutResult(status -> {
            assertThat(mapper.updateParticipantFactsById(TENANT_ID, 301L, add)).isOne();
            assertThat(mapper.updateParticipantFactsById(TENANT_ID, 301L, add)).isOne();
            assertThat(mapper.updateParticipantFactsById(TENANT_ID, 301L,
                    fact(101L, "213@lid", 2, "WGP2_REMOVE", 600L, 0))).isOne();
            assertThat(mapper.updateParticipantFactsById(TENANT_ID, 301L, add)).isOne();
        });
        assertThat(jdbc.queryForMap("SELECT pn_jid,lid_jid,phone,presence_status,"
                + "presence_observed_at,presence_event_id,last_join_event_at,last_exit_event_at "
                + "FROM wa_group_participant WHERE id=301"))
                .containsEntry("pn_jid", "919118818029@s.whatsapp.net")
                .containsEntry("lid_jid", "213@lid")
                .containsEntry("phone", "919118818029")
                .containsEntry("presence_status", 2)
                .containsEntry("presence_observed_at", 600L)
                .containsEntry("presence_event_id", "event-600")
                .containsEntry("last_join_event_at", 400L)
                .containsEntry("last_exit_event_at", 600L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wa_group_participant", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void primaryUpdateRejectsWrongTenantGroupOrConflictingIdentityAndSupportsRollback()
            throws SQLException {
        prepareParticipantFactColumns();
        var jdbc = new JdbcTemplate(dataSource);
        ParticipantPresenceWrite add = fact(101L, "213@lid", 1, "WGP2_ADD", 400L, 1);
        assertThat(mapper.updateParticipantFactsById(8L, 301L, add)).isZero();
        assertThat(mapper.updateParticipantFactsById(TENANT_ID, 301L,
                fact(201L, "213@lid", 1, "WGP2_ADD", 400L, 1))).isZero();
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        tx.executeWithoutResult(status -> {
            assertThat(mapper.updateParticipantFactsById(TENANT_ID, 301L, add)).isOne();
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT lid_jid FROM wa_group_participant WHERE id=301",
                String.class)).isNull();
        assertThat(mapper.updateParticipantFactsById(TENANT_ID, 301L, add)).isOne();
        assertThat(mapper.updateParticipantFactsById(TENANT_ID, 301L,
                fact(101L, "different@lid", 1, "WGP2_ADD", 500L, 1))).isZero();
        assertThat(jdbc.queryForObject("SELECT lid_jid FROM wa_group_participant WHERE id=301",
                String.class)).isEqualTo("213@lid");
        assertThat(jdbc.queryForObject("SELECT presence_observed_at FROM wa_group_participant WHERE id=401",
                Long.class)).isEqualTo(300L);
    }

    @Test
    void primaryUpdateKeepsExactAdminAgainstNewerLightweightMemberSnapshot() throws SQLException {
        prepareParticipantFactColumns();
        assertThat(mapper.updateParticipantFactsById(TENANT_ID, 301L,
                fact(101L, "213@lid", 1, "WGP2_PROMOTE", 400L, 2))).isOne();
        assertThat(mapper.updateParticipantFactsById(TENANT_ID, 301L,
                fact(101L, "213@lid", 1, "GROUP_SNAPSHOT", 600L, 1))).isOne();
        assertThat(new JdbcTemplate(dataSource).queryForMap(
                "SELECT role,role_source,role_observed_at FROM wa_group_participant WHERE id=301"))
                .containsEntry("role", 2)
                .containsEntry("role_source", "WGP2_PROMOTE")
                .containsEntry("role_observed_at", 400L);
    }

    private static ParticipantPresenceWrite fact(
            Long groupId, String lid, int presence, String source, long time, int role) {
        return new ParticipantPresenceWrite(
                groupId, "new-group@g.us", null, lid, "919118818029",
                presence, source, "event-" + time, time, time,
                presence == 1 ? time : null, presence == 2 ? "REMOVE" : null,
                presence == 2 ? time : null, presence == 2 ? source : null,
                role, source, time, "event-" + time, null, null, null, null, null);
    }

    private void prepareParticipantFactColumns() throws SQLException {
        execute("ALTER TABLE wa_group_participant ADD presence_event_id VARCHAR(128)",
                "ALTER TABLE wa_group_participant ADD last_joined_at BIGINT",
                "ALTER TABLE wa_group_participant ADD last_join_event_at BIGINT",
                "ALTER TABLE wa_group_participant ADD last_join_source_event_id VARCHAR(128)",
                "ALTER TABLE wa_group_participant ADD last_exited_at BIGINT",
                "ALTER TABLE wa_group_participant ADD last_exit_type VARCHAR(32)",
                "ALTER TABLE wa_group_participant ADD last_exit_event_at BIGINT",
                "ALTER TABLE wa_group_participant ADD last_exit_source_event_id VARCHAR(128)",
                "ALTER TABLE wa_group_participant ADD last_exit_source_type VARCHAR(64)",
                "ALTER TABLE wa_group_participant ADD role TINYINT DEFAULT 0",
                "ALTER TABLE wa_group_participant ADD role_source VARCHAR(64)",
                "ALTER TABLE wa_group_participant ADD role_observed_at BIGINT",
                "ALTER TABLE wa_group_participant ADD role_event_id VARCHAR(128)",
                "ALTER TABLE wa_group_participant ADD last_snapshot_version VARCHAR(128)");
    }

    private void execute(String... statements) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    /** 本测试加载真实快照 Mapper XML，并启用生产租户拦截器。 */
    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:account_group_current_snapshot_mapper_test;"
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
            factory.setMapperLocations(new ClassPathResource(
                    "mapper/group/AccountGroupCurrentSnapshotMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        AccountGroupCurrentSnapshotMapper accountGroupCurrentSnapshotMapper(
                SqlSessionTemplate template) {
            return template.getMapper(AccountGroupCurrentSnapshotMapper.class);
        }
    }
}
