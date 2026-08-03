package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.group.model.dto.WhatsappGroupDepartureFact;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 在真实 MySQL 8.4 上执行退群事实 row-alias upsert。 */
@Testcontainers(disabledWithoutDocker = true)
class WhatsappGroupDepartedMemberMapperMysqlTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8");

    private DriverManagerDataSource dataSource;
    private WhatsappGroupDepartedMemberMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             var migration = getClass().getResourceAsStream(
                     "/db/migration/V091__whatsapp_group_departed_member.sql")) {
            statement.execute("DROP TABLE IF EXISTS whatsapp_group_departed_member");
            statement.execute(new String(migration.readAllBytes(), StandardCharsets.UTF_8));
        }

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        factoryBean.setMapperLocations(
                new ClassPathResource("mapper/group/WhatsappGroupDepartedMemberMapper.xml"));
        SqlSessionFactory factory = factoryBean.getObject();
        mapper = new SqlSessionTemplate(factory).getMapper(WhatsappGroupDepartedMemberMapper.class);
    }

    @Test
    void upsertIsDeterministicPreservesPhoneAndDoesNotTouchReplayTimestamp() throws Exception {
        WhatsappGroupDepartureFact history = fact(
                "15550000001@s.whatsapp.net", "15550000001",
                new DepartureState("LEFT", 100L, "history-z", "HISTORY_SYNC"));
        mapper.upsertLatest(history, 1_000L);

        WhatsappGroupDepartureFact notification = fact(
                "15550000001@s.whatsapp.net", null,
                new DepartureState("REMOVED", 100L, "notification-a", "WGP2_NOTIFICATION"));
        mapper.upsertLatest(notification, 2_000L);
        assertThat(row("15550000001@s.whatsapp.net")).isEqualTo(
                new Row("15550000001", "REMOVED", "notification-a", "WGP2_NOTIFICATION", 2_000L));

        mapper.upsertLatest(notification, 3_000L);
        assertThat(row("15550000001@s.whatsapp.net").updatedAt()).isEqualTo(2_000L);

        WhatsappGroupDepartureFact sameSourceHigherId = fact(
                "15550000001@s.whatsapp.net", "15550000001",
                new DepartureState("LEFT", 100L, "notification-b", "WGP2_NOTIFICATION"));
        mapper.upsertLatest(sameSourceHigherId, 4_000L);
        assertThat(row("15550000001@s.whatsapp.net")).isEqualTo(
                new Row("15550000001", "LEFT", "notification-b", "WGP2_NOTIFICATION", 4_000L));

        mapper.upsertLatest(fact(
                "15550000001@s.whatsapp.net", "19999999999",
                new DepartureState("REMOVED", 99L, "older", "WGP2_NOTIFICATION")), 5_000L);
        assertThat(row("15550000001@s.whatsapp.net")).isEqualTo(
                new Row("15550000001", "LEFT", "notification-b", "WGP2_NOTIFICATION", 4_000L));

        mapper.upsertLatest(fact(
                "15550000002@s.whatsapp.net", "15550000002",
                new DepartureState("LEFT", 100L, "same-Z", "WGP2_NOTIFICATION")), 6_000L);
        mapper.upsertLatest(fact(
                "15550000002@s.whatsapp.net", "15550000002",
                new DepartureState("REMOVED", 100L, "same-z", "WGP2_NOTIFICATION")), 7_000L);
        assertThat(row("15550000002@s.whatsapp.net")).isEqualTo(
                new Row("15550000002", "REMOVED", "same-z", "WGP2_NOTIFICATION", 7_000L));
    }

    private static WhatsappGroupDepartureFact fact(
            String participantJid,
            String phone,
            DepartureState state) {
        return new WhatsappGroupDepartureFact(
                7L, "120363-test@g.us", participantJid, phone,
                state.eventAt(), state.exitType(), state.eventAt(),
                state.sourceEventId(), state.sourceType());
    }

    private Row row(String participantJid) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT phone, exit_type, source_event_id, source_type, updated_at
                     FROM whatsapp_group_departed_member
                     WHERE tenant_id = 7
                       AND group_jid = '120363-test@g.us'
                       AND participant_jid = ?
                     """)) {
            statement.setString(1, participantJid);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return new Row(
                        result.getString("phone"),
                        result.getString("exit_type"),
                        result.getString("source_event_id"),
                        result.getString("source_type"),
                        result.getLong("updated_at"));
            }
        }
    }

    private record DepartureState(
            String exitType,
            long eventAt,
            String sourceEventId,
            String sourceType) {
    }

    private record Row(
            String phone,
            String exitType,
            String sourceEventId,
            String sourceType,
            long updatedAt) {
    }
}
