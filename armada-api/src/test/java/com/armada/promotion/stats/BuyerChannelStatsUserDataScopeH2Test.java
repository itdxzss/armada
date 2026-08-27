package com.armada.promotion.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.AuthPrincipal;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 使用 H2 执行统计服务的真实 JDBC SQL，验证渠道根继承的数据范围。 */
class BuyerChannelStatsUserDataScopeH2Test {

    private JdbcDataSource dataSource;
    private JdbcTemplate jdbc;
    private BuyerChannelStatsService service;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:buyer_channel_stats_user_scope_test;"
                + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        service = new BuyerChannelStatsService(jdbc);
        execute("DROP ALL OBJECTS");
        createSchema();
        insertFixtures();
    }

    @AfterEach
    void tearDown() {
        DataScopeContext.clear();
    }

    @Test
    void ordinaryUserOptionsContainOnlyOwnChannelsAndOwnCreatorIdentity() {
        DataScopeContext.open(DataScope.self(1001L));

        BuyerChannelStatsModels.Options options = service.options(7L);

        assertThat(options.channels()).extracting(BuyerChannelStatsModels.Option::id)
                .containsExactly(101L);
        assertThat(options.creators()).extracting(BuyerChannelStatsModels.Option::id)
                .containsExactly(1001L);
        assertThat(service.list(query(), 7L))
                .extracting(BuyerChannelStatsModels.StatsRow::channelId)
                .containsExactly(101L);
    }

    @Test
    void adminCanSeeAllTenantChannelsWhileMissingAndSystemScopesFailClosed() {
        DataScopeContext.open(DataScope.all(9001L));
        assertThat(service.options(7L).channels())
                .extracting(BuyerChannelStatsModels.Option::id)
                .containsExactlyInAnyOrder(101L, 102L);
        assertThat(service.list(query(), 7L))
                .extracting(BuyerChannelStatsModels.StatsRow::channelId)
                .containsExactlyInAnyOrder(101L, 102L);

        DataScopeContext.clear();
        assertThatThrownBy(() -> service.list(query(), 7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ErrorCode.ACCESS_DENIED.code()));

        DataScopeContext.open(DataScope.system("stats scheduler"));
        assertThatThrownBy(() -> service.options(7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ErrorCode.ACCESS_DENIED.code()));
    }

    @Test
    void dailyMetricUpdateRequiresVisibleChannelAndAuditsActualActor() {
        BuyerChannelStatsModels.DailyInput input = new BuyerChannelStatsModels.DailyInput(
                "BR", "2026-08-26", "2026-08-26",
                new BigDecimal("10.00"), 100L, 5L,
                new BigDecimal("0.10"), BigDecimal.ZERO, 0);
        AuthPrincipal userOne = principal(1001L, List.of("USER"));
        DataScopeContext.open(DataScope.self(1001L));

        assertThatThrownBy(() -> service.update(102L, LocalDate.parse("2026-08-26"), input, userOne))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ErrorCode.NOT_FOUND.code()));

        AuthPrincipal admin = principal(9001L, List.of("TENANT_ADMIN"));
        try (DataScopeContext.Scope ignored = DataScopeContext.open(DataScope.all(9001L))) {
            assertThat(service.update(102L, LocalDate.parse("2026-08-26"), input, admin)
                    .daily().version()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject(
                "SELECT updated_by FROM promotion_channel_daily_ad_metric WHERE channel_id=102",
                Long.class)).isEqualTo(9001L);
    }

    private static BuyerChannelStatsModels.Query query() {
        return new BuyerChannelStatsModels.Query(
                "2026-08-26", "2026-08-26", null,
                null, null, null, null, null, null, null);
    }

    private static AuthPrincipal principal(long userId, List<String> roles) {
        return new AuthPrincipal(userId, 7L, "u" + userId, "用户" + userId,
                "tenant-7", "租户7", roles, List.of());
    }

    private void createSchema() throws SQLException {
        execute("""
                CREATE TABLE promotion_landing_template (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  template_name VARCHAR(128) NOT NULL, deleted_at BIGINT
                )
                """, """
                CREATE TABLE promotion_domain (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  landing_template_id BIGINT NOT NULL, deleted_at BIGINT
                )
                """, """
                CREATE TABLE promotion_channel (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  channel_name VARCHAR(128) NOT NULL, channel_code VARCHAR(32) NOT NULL,
                  owner_user_id BIGINT NOT NULL, created_by BIGINT,
                  target_country_value VARCHAR(16), promotion_domain_id BIGINT NOT NULL,
                  deleted_at BIGINT
                )
                """, """
                CREATE TABLE country (
                  id BIGINT PRIMARY KEY, iso2 VARCHAR(2), name_zh VARCHAR(64),
                  is_enabled INT, sort_order INT, deleted_at BIGINT
                )
                """, """
                CREATE TABLE sys_user (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  nickname VARCHAR(64), username VARCHAR(64), status INT
                )
                """, """
                CREATE TABLE promotion_channel_daily_ad_metric (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  channel_id BIGINT NOT NULL, country_code VARCHAR(16) NOT NULL,
                  stat_date DATE NOT NULL, spend DECIMAL(20,6) NOT NULL DEFAULT 0,
                  impressions BIGINT NOT NULL DEFAULT 0, clicks BIGINT NOT NULL DEFAULT 0,
                  service_rate DECIMAL(9,6) NOT NULL DEFAULT 0,
                  other_fee DECIMAL(20,6) NOT NULL DEFAULT 0,
                  version INT NOT NULL DEFAULT 1, updated_by BIGINT,
                  created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
                  UNIQUE (tenant_id, channel_id, country_code, stat_date)
                )
                """, """
                CREATE TABLE promotion_pairing_session (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  promotion_channel_id BIGINT NOT NULL, phone VARCHAR(32),
                  status INT NOT NULL, created_at BIGINT NOT NULL
                )
                """, """
                CREATE TABLE account (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  owner_user_id BIGINT, promotion_channel_id BIGINT, deleted_at BIGINT
                )
                """, """
                CREATE TABLE account_state (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  account_id BIGINT NOT NULL, account_state INT, updated_at BIGINT
                )
                """);
    }

    private void insertFixtures() throws SQLException {
        execute("""
                INSERT INTO promotion_landing_template (id,tenant_id,template_name,deleted_at)
                VALUES (301,7,'template-7',NULL),(303,8,'template-8',NULL)
                """, """
                INSERT INTO promotion_domain (id,tenant_id,landing_template_id,deleted_at)
                VALUES (201,7,301,NULL),(202,7,301,NULL),(203,8,303,NULL)
                """, """
                INSERT INTO promotion_channel
                  (id,tenant_id,channel_name,channel_code,owner_user_id,created_by,
                   target_country_value,promotion_domain_id,deleted_at)
                VALUES
                  (101,7,'u1-channel','u1code',1001,1001,'IN',201,NULL),
                  (102,7,'u2-channel','u2code',1002,1002,'BR',202,NULL),
                  (103,8,'other-channel','other',1001,1001,'US',203,NULL)
                """, """
                INSERT INTO country (id,iso2,name_zh,is_enabled,sort_order,deleted_at)
                VALUES (1,'IN','印度',1,1,NULL),(2,'BR','巴西',1,2,NULL),(3,'US','美国',1,3,NULL)
                """, """
                INSERT INTO sys_user (id,tenant_id,nickname,username,status)
                VALUES (1001,7,'U1','u1',1),(1002,7,'U2','u2',1),(9001,7,'Admin','admin',1)
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
}
