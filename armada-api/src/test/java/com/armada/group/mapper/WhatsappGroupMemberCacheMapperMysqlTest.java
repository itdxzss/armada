package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.group.model.dto.WhatsappGroupMemberStateWrite;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 在真实 MySQL 8.4 上验证群成员状态批量 upsert 的事件顺序。 */
@Testcontainers(disabledWithoutDocker = true)
class WhatsappGroupMemberCacheMapperMysqlTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8");

    private DriverManagerDataSource dataSource;
    private WhatsappGroupMemberCacheMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             var migration = getClass().getResourceAsStream(
                     "/db/migration/V096__whatsapp_group_member_cache.sql")) {
            statement.execute("DROP TABLE IF EXISTS whatsapp_group_member_state");
            statement.execute("DROP TABLE IF EXISTS whatsapp_group_member_cache");
            for (String sql : new String(migration.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        }

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        factoryBean.setMapperLocations(
                new ClassPathResource("mapper/group/WhatsappGroupMemberCacheMapper.xml"));
        SqlSessionFactory factory = factoryBean.getObject();
        mapper = new SqlSessionTemplate(factory).getMapper(WhatsappGroupMemberCacheMapper.class);
    }

    @Test
    void sameTimeEventWinsSnapshotAndNewerAddRestoresMembership() throws Exception {
        mapper.upsertStates(List.of(state(true, "FULL_SNAPSHOT", 100L, "snapshot-z", "v1")), 1_000L);
        mapper.upsertStates(List.of(state(false, "LEAVE_EVENT", 100L, "leave-a", null)), 2_000L);
        assertThat(row()).isEqualTo(new Row(false, "LEAVE_EVENT", 100L, "leave-a"));

        mapper.upsertStates(List.of(state(true, "FULL_SNAPSHOT", 100L, "snapshot-zz", "v2")), 3_000L);
        assertThat(row()).isEqualTo(new Row(false, "LEAVE_EVENT", 100L, "leave-a"));

        mapper.upsertStates(List.of(state(true, "ADD_EVENT", 101L, "add-a", null)), 4_000L);
        assertThat(row()).isEqualTo(new Row(true, "ADD_EVENT", 101L, "add-a"));
    }

    @Test
    void sameTimeRoleEventWinsAddAndSnapshotButExactExitWinsRole() throws Exception {
        mapper.upsertStates(List.of(state(true, "FULL_SNAPSHOT", 100L, "snapshot-z", "v1")), 1_000L);
        mapper.upsertStates(List.of(state(true, "ADD_EVENT", 100L, "add-z", null)), 2_000L);
        mapper.upsertStates(List.of(state(true, "ROLE_EVENT", 100L, "promote-a", null)), 3_000L);
        assertThat(row()).isEqualTo(new Row(true, "ROLE_EVENT", 100L, "promote-a"));

        mapper.upsertStates(List.of(state(false, "LEAVE_EVENT", 100L, "leave-a", null)), 4_000L);
        assertThat(row()).isEqualTo(new Row(false, "LEAVE_EVENT", 100L, "leave-a"));
    }

    @Test
    void sameTimeSnapshotsUseVersionOrderRegardlessOfArrivalOrder() throws Exception {
        String first = "15550000001@s.whatsapp.net";
        mapper.upsertStates(List.of(stateFor(
                first, true, "FULL_SNAPSHOT", 90L,
                "snapshot:old:" + first, "old")), 1_000L);

        mapper.markSnapshotMissing(
                7L, "120363-test@g.us", "zzzz", 100L,
                "snapshot:zzzz:absent", 10L, 2_000L);
        mapper.upsertStates(List.of(stateFor(
                first, true, "FULL_SNAPSHOT", 100L,
                "snapshot:aaaa:" + first, "aaaa")), 3_000L);
        assertThat(row(first)).isEqualTo(
                new Row(false, "SNAPSHOT_ABSENT", 100L, "snapshot:zzzz:absent"));
    }

    @Test
    void sameTimeHigherMissingSnapshotWinsWhenItArrivesLast() throws Exception {
        String participant = "15550000002@s.whatsapp.net";
        mapper.upsertStates(List.of(stateFor(
                participant, true, "FULL_SNAPSHOT", 100L,
                "snapshot:aaaa:" + participant, "aaaa")), 1_000L);
        mapper.markSnapshotMissing(
                7L, "120363-test@g.us", "zzzz", 100L,
                "snapshot:zzzz:absent", 10L, 2_000L);
        assertThat(row(participant)).isEqualTo(
                new Row(false, "SNAPSHOT_ABSENT", 100L, "snapshot:zzzz:absent"));
    }

    private static WhatsappGroupMemberStateWrite state(
            boolean inGroup,
            String source,
            long updatedAt,
            String sourceEventId,
            String snapshotVersion) {
        return stateFor(
                "15550000001@s.whatsapp.net", inGroup, source, updatedAt,
                sourceEventId, snapshotVersion);
    }

    private static WhatsappGroupMemberStateWrite stateFor(
            String participantJid,
            boolean inGroup,
            String source,
            long updatedAt,
            String sourceEventId,
            String snapshotVersion) {
        return new WhatsappGroupMemberStateWrite(
                7L, "120363-test@g.us", participantJid,
                participantJid.substring(0, participantJid.indexOf('@')),
                false, false, "", inGroup, source, updatedAt,
                sourceEventId, snapshotVersion, 10L);
    }

    private Row row() throws Exception {
        return row("15550000001@s.whatsapp.net");
    }

    private Row row(String participantJid) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT is_in_group, state_source, state_updated_at, source_event_id
                     FROM whatsapp_group_member_state
                     WHERE tenant_id = 7
                       AND group_jid = '120363-test@g.us'
                       AND participant_jid = ?
                     """)) {
            statement.setString(1, participantJid);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return new Row(
                        result.getBoolean("is_in_group"),
                        result.getString("state_source"),
                        result.getLong("state_updated_at"),
                        result.getString("source_event_id"));
            }
        }
    }

    private record Row(boolean inGroup, String source, long updatedAt, String sourceEventId) {
    }
}
