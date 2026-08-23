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
        Existing existing = mapper.selectSelfMembershipExistingForUpdate(
                TENANT_ID, 1001L, "919118818029@s.whatsapp.net", "new-group@g.us");

        assertThat(existing).isNotNull();
        assertThat(existing.membershipActiveSinceAt()).isEqualTo(200L);
        assertThat(mapper.selectExistingForUpdate(
                TENANT_ID, 1001L, "919118818029@s.whatsapp.net",
                List.of("new-group@g.us")))
                .singleElement()
                .extracting(Existing::membershipActiveSinceAt)
                .isEqualTo(200L);
        assertThat(mapper.selectSelfMembershipExisting(
                1001L, "919118818029@s.whatsapp.net", "other-tenant@g.us"))
                .isNull();
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
                mapper.selectParticipantIdentityRowsForUpdate(TENANT_ID, List.of(candidate));

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
