package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.model.entity.WhatsappGroupMember;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
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

/** 使用 H2 MySQL 模式执行 WhatsApp 群成员事实 Mapper XML。 */
@SpringJUnitConfig(WhatsappGroupMemberMapperH2Test.TestMyBatisPlusConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class WhatsappGroupMemberMapperH2Test {

    private static final String GROUP_JID = "120363000000000001@g.us";
    private static final String MEMBER_JID = "15550000001@s.whatsapp.net";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private WhatsappGroupMemberMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws SQLException {
        executeSql("DROP ALL OBJECTS", """
                CREATE TABLE whatsapp_group_member (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_link_id BIGINT,
                    group_jid VARCHAR(128) NOT NULL,
                    member_jid VARCHAR(128) NOT NULL,
                    participant_jid VARCHAR(128),
                    phone VARCHAR(32),
                    role VARCHAR(32),
                    is_admin TINYINT,
                    is_owner TINYINT,
                    membership_status TINYINT NOT NULL,
                    status_source VARCHAR(32) NOT NULL,
                    status_source_event_id VARCHAR(191) NOT NULL,
                    status_updated_at BIGINT NOT NULL,
                    joined_at BIGINT,
                    last_exit_type TINYINT,
                    last_exited_at BIGINT,
                    first_seen_at BIGINT NOT NULL,
                    last_seen_at BIGINT,
                    observer_account_id BIGINT,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    deleted_at BIGINT,
                    UNIQUE (tenant_id, group_jid, member_jid)
                )
                """, """
                CREATE TABLE whatsapp_group_member_fact (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_link_id BIGINT,
                    group_jid VARCHAR(128) NOT NULL,
                    member_jid VARCHAR(128) NOT NULL,
                    participant_jid VARCHAR(128),
                    phone VARCHAR(32),
                    role VARCHAR(32),
                    is_admin TINYINT,
                    is_owner TINYINT,
                    membership_status TINYINT NOT NULL,
                    status_source VARCHAR(32) NOT NULL,
                    occurred_at BIGINT NOT NULL,
                    source_event_id VARCHAR(191) NOT NULL,
                    observer_account_id BIGINT,
                    created_at BIGINT NOT NULL,
                    UNIQUE (tenant_id, source_event_id, group_jid, member_jid)
                )
                """, """
                CREATE TABLE whatsapp_group_member_snapshot_fact (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_link_id BIGINT,
                    group_jid VARCHAR(128) NOT NULL,
                    member_count INT NOT NULL,
                    announce_only TINYINT,
                    observer_is_admin TINYINT,
                    snapshot_at BIGINT NOT NULL,
                    source_event_id VARCHAR(191) NOT NULL,
                    observer_account_id BIGINT,
                    created_at BIGINT NOT NULL,
                    UNIQUE (tenant_id, source_event_id, group_jid)
                )
                """);
        TenantContext.set(7L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void completeSnapshotMarksOnlyOlderMissingCurrentMembersAndKeepsTenantIsolation() {
        insertCurrentMember(7L, 1_000L);
        insertCurrentMember(8L, 1_000L);
        List<WhatsappGroupMember> missing = mapper.selectMissingCurrentMembers(
                GROUP_JID, List.of("other@s.whatsapp.net"), 2_000L, "snapshot-2");

        assertThat(missing).hasSize(1);
        assertThat(mapper.markMissingMembers(
                missing.stream().map(WhatsappGroupMember::getId).toList(),
                2_000L,
                2_000L,
                99L,
                "snapshot-2")).isEqualTo(1);
        assertThat(currentRow())
                .containsEntry("membership_status", 5)
                .containsEntry("status_source", "MEMBER_SNAPSHOT");

        TenantContext.set(8L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM whatsapp_group_member", Integer.class)).isEqualTo(2);
        assertThat(mapper.selectMissingCurrentMembers(
                GROUP_JID, List.of(MEMBER_JID), 4_000L, "snapshot-4")).isEmpty();
        assertThat(currentRow(8L))
                .containsEntry("membership_status", 1)
                .containsEntry("status_updated_at", 1_000L);
    }

    @Test
    void lateCompleteSnapshotDerivesMissingMembersFromFactsWithoutRollingBackCurrentState() {
        insertCurrentMember(7L, 50L);
        jdbc.update("""
                UPDATE whatsapp_group_member
                SET status_source = 'PARTICIPANT_ADD',
                    status_source_event_id = 'add-200',
                    status_updated_at = 200
                WHERE tenant_id = 7 AND group_jid = ? AND member_jid = ?
                """, GROUP_JID, MEMBER_JID);
        jdbc.update("""
                INSERT INTO whatsapp_group_member_fact (
                    tenant_id, group_link_id, group_jid, member_jid, participant_jid, phone,
                    membership_status, status_source, occurred_at, source_event_id,
                    observer_account_id, created_at)
                VALUES (7, 11, ?, ?, ?, '15550000001', 1,
                        'PARTICIPANT_ADD', 200, 'add-200', 99, 200)
                """, GROUP_JID, MEMBER_JID, MEMBER_JID);

        List<WhatsappGroupMember> missing = mapper.selectMissingCurrentMembers(
                GROUP_JID, List.of("other@s.whatsapp.net"), 100L, "snapshot-100");

        assertThat(missing).extracting(WhatsappGroupMember::getMemberJid)
                .containsExactly(MEMBER_JID);
        assertThat(mapper.markMissingMembers(
                List.of(missing.get(0).getId()), 100L, 100L, 99L, "snapshot-100"))
                .isZero();
        assertThat(currentRow()).containsEntry("status_updated_at", 200L);
    }

    @Test
    void sameMillisecondSnapshotUsesSourceEventIdAsTieBreaker() {
        insertCurrentMember(7L, 100L);
        jdbc.update("""
                UPDATE whatsapp_group_member
                SET status_source_event_id = 'snapshot-z'
                WHERE tenant_id = 7 AND group_jid = ? AND member_jid = ?
                """, GROUP_JID, MEMBER_JID);
        jdbc.update("""
                UPDATE whatsapp_group_member_fact
                SET source_event_id = 'snapshot-z'
                WHERE tenant_id = 7 AND group_jid = ? AND member_jid = ?
                """, GROUP_JID, MEMBER_JID);

        assertThat(mapper.selectMissingCurrentMembers(
                GROUP_JID, List.of("other@s.whatsapp.net"), 100L, "snapshot-a")).isEmpty();
        assertThat(mapper.selectMissingCurrentMembers(
                GROUP_JID, List.of("other@s.whatsapp.net"), 100L, "snapshot-zz"))
                .extracting(WhatsappGroupMember::getMemberJid)
                .containsExactly(MEMBER_JID);
    }

    @Test
    void appendOnlyFactsAndCompleteWatermarksAreIdempotentAndTenantScoped() {
        WhatsappGroupMember fact = new WhatsappGroupMember();
        fact.setGroupLinkId(11L);
        fact.setGroupJid(GROUP_JID);
        fact.setMemberJid(MEMBER_JID);
        fact.setParticipantJid(MEMBER_JID);
        fact.setPhone("15550000001");
        fact.setMembershipStatus(1);
        fact.setStatusSource("MEMBER_SNAPSHOT");
        fact.setStatusSourceEventId("snapshot-1");
        fact.setStatusUpdatedAt(1_000L);
        fact.setObserverAccountId(99L);
        fact.setCreatedAt(1_000L);

        assertThat(mapper.insertMemberFact(fact)).isEqualTo(1);
        assertThat(mapper.insertMemberFact(fact)).isZero();
        assertThat(mapper.insertCompleteSnapshot(
                11L, GROUP_JID, 1, 1_000L, "snapshot-1", 99L,
                false, true, 1_000L)).isEqualTo(1);
        assertThat(mapper.insertCompleteSnapshot(
                11L, GROUP_JID, 1, 1_000L, "snapshot-1", 99L,
                false, true, 1_000L)).isZero();

        TenantContext.set(8L);
        assertThat(mapper.insertMemberFact(fact)).isEqualTo(1);
        assertThat(mapper.insertCompleteSnapshot(
                11L, GROUP_JID, 1, 1_000L, "snapshot-1", 99L,
                false, true, 1_000L)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM whatsapp_group_member_fact", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM whatsapp_group_member_snapshot_fact", Integer.class)).isEqualTo(2);
    }

    @Test
    void everySnapshotEventAppendsItsPositiveMemberFact() {
        insertCurrentMember(7L, 1_000L);
        WhatsappGroupMember snapshot = new WhatsappGroupMember();
        snapshot.setGroupLinkId(11L);
        snapshot.setGroupJid(GROUP_JID);
        snapshot.setMemberJid(MEMBER_JID);
        snapshot.setParticipantJid(MEMBER_JID);
        snapshot.setPhone("15550000001");
        snapshot.setRole("member");
        snapshot.setAdmin(false);
        snapshot.setOwner(false);
        snapshot.setMembershipStatus(1);
        snapshot.setStatusSource("MEMBER_SNAPSHOT");
        snapshot.setStatusSourceEventId("snapshot-2");
        snapshot.setStatusUpdatedAt(2_000L);
        snapshot.setObserverAccountId(99L);
        snapshot.setCreatedAt(2_000L);

        assertThat(mapper.insertMemberFact(snapshot)).isEqualTo(1);

        snapshot.setStatusSourceEventId("snapshot-3");
        snapshot.setStatusUpdatedAt(3_000L);
        assertThat(mapper.insertMemberFact(snapshot)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM whatsapp_group_member_fact "
                        + "WHERE source_event_id IN ('snapshot-2', 'snapshot-3')",
                Integer.class)).isEqualTo(2);
    }

    private void insertCurrentMember(long tenantId, long eventAt) {
        jdbc.update("""
                INSERT INTO whatsapp_group_member (
                    tenant_id, group_link_id, group_jid, member_jid, participant_jid, phone,
                    role, is_admin, is_owner, membership_status, status_source,
                    status_source_event_id, status_updated_at, first_seen_at, last_seen_at,
                    observer_account_id, created_at, updated_at)
                VALUES (?, 11, ?, ?, ?, '15550000001', 'member', 0, 0, 1,
                        'MEMBER_SNAPSHOT', 'snapshot-1', ?, ?, ?, 99, ?, ?)
                """, tenantId, GROUP_JID, MEMBER_JID, MEMBER_JID,
                eventAt, eventAt, eventAt, eventAt, eventAt);
        jdbc.update("""
                INSERT INTO whatsapp_group_member_fact (
                    tenant_id, group_link_id, group_jid, member_jid, participant_jid, phone,
                    role, is_admin, is_owner, membership_status, status_source,
                    occurred_at, source_event_id, observer_account_id, created_at)
                VALUES (?, 11, ?, ?, ?, '15550000001', 'member', 0, 0, 1,
                        'MEMBER_SNAPSHOT', ?, 'snapshot-1', 99, ?)
                """, tenantId, GROUP_JID, MEMBER_JID, MEMBER_JID, eventAt, eventAt);
    }

    private Map<String, Object> currentRow() {
        return currentRow(7L);
    }

    private Map<String, Object> currentRow(long tenantId) {
        return jdbc.queryForMap("""
                SELECT membership_status, status_source, status_updated_at,
                       joined_at, last_exit_type, last_exited_at
                FROM whatsapp_group_member
                WHERE tenant_id = ? AND group_jid = ? AND member_jid = ?
                """, tenantId, GROUP_JID, MEMBER_JID);
    }

    private void executeSql(String... statements) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestMyBatisPlusConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource h2 = new JdbcDataSource();
            h2.setURL("jdbc:h2:mem:whatsapp_group_member_mapper_test"
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            h2.setUser("sa");
            h2.setPassword("");
            return h2;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(
                DataSource dataSource,
                MybatisPlusInterceptor mybatisPlusInterceptor) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setUseGeneratedKeys(true);

            MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
            factoryBean.setDataSource(dataSource);
            factoryBean.setConfiguration(configuration);
            factoryBean.setPlugins(mybatisPlusInterceptor);
            factoryBean.setMapperLocations(
                    new ClassPathResource("mapper/group/WhatsappGroupMemberMapper.xml"));
            return factoryBean.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        WhatsappGroupMemberMapper whatsappGroupMemberMapper(
                SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(WhatsappGroupMemberMapper.class);
        }
    }
}
