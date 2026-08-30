package com.armada.hyperlink.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.hyperlink.task.model.query.HyperlinkMarketingStatCriteria;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** H2+真实 XML 验证租户隔离、跨投影行相加和聚合回填。 */
@SpringJUnitConfig(HyperlinkMarketingStatMapperH2Test.TestConfig.class)
@TestExecutionListeners(listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class HyperlinkMarketingStatMapperH2Test {
    @Autowired private DataSource dataSource;
    @Autowired private HyperlinkMarketingStatMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        execute("DROP ALL OBJECTS");
        execute(dailySchema());
        execute(hourlySchema());
        execute("CREATE TABLE hyperlink_task (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, "
                + "task_type TINYINT NOT NULL, is_short_link_enabled TINYINT NOT NULL)");
        execute("CREATE TABLE hyperlink_task_account_usage (id BIGINT PRIMARY KEY, "
                + "tenant_id BIGINT NOT NULL, hyperlink_task_id BIGINT NOT NULL, "
                + "account_id BIGINT NOT NULL, usage_status TINYINT NOT NULL, "
                + "invalid_at BIGINT, updated_at BIGINT NOT NULL)");
        execute("CREATE TABLE hyperlink_task_recipient (id BIGINT PRIMARY KEY, "
                + "tenant_id BIGINT NOT NULL, hyperlink_task_id BIGINT NOT NULL, account_id BIGINT, "
                + "sender_country_iso2_snapshot CHAR(2), recipient_country_iso2_snapshot CHAR(2), "
                + "sender_account_type_snapshot TINYINT, sender_device_os_snapshot TINYINT, "
                + "send_status TINYINT NOT NULL, submitted_at BIGINT, sent_at BIGINT, "
                + "delivered_at BIGINT, first_visit_at BIGINT, updated_at BIGINT NOT NULL)");
    }

    @Test
    void dailyQuerySumsRowsAndNeverCrossesTenant() throws SQLException {
        execute("INSERT INTO hyperlink_stat_daily VALUES "
                + "(1,7,20260830,'BR','US',1,1,1,1,10,8,4,2,1,3,1,100),"
                + "(2,7,20260830,'BR','US',2,1,2,1,5,4,3,1,1,2,1,200),"
                + "(3,8,20260830,'MX','AR',1,1,1,1,99,99,99,99,99,99,1,300)");

        var rows = mapper.selectDaily(criteria(null));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getSendTotal()).isEqualTo(15);
        assertThat(rows.get(0).getUsedAccountCount()).isEqualTo(3);
        assertThat(rows.get(0).getBannedAccountCount()).isEqualTo(2);
        assertThat(rows.get(0).getUpdatedAt()).isEqualTo(200);
        assertThat(mapper.selectDaily(criteria(1))).singleElement()
                .satisfies(row -> assertThat(row.getSendTotal()).isEqualTo(10));
        assertThat(mapper.selectCountries(criteria(null))).singleElement().satisfies(row -> {
            assertThat(row.getSenderCountryIso2()).isEqualTo("BR");
            assertThat(row.getRecipientCountryIso2()).isEqualTo("US");
        });
    }

    @Test
    void hourlyBackfillCountsDistinctWithinOneDimensionRow() throws SQLException {
        long start = 1_778_000_400_000L;
        execute("INSERT INTO hyperlink_task VALUES (11,7,1,1)");
        execute("INSERT INTO hyperlink_task_account_usage VALUES "
                + "(1,7,11,101,1,NULL,100),(2,7,11,102,3,1778000500000,200),"
                + "(3,7,11,103,4,1778000600000,300)");
        execute(("INSERT INTO hyperlink_task_recipient VALUES "
                + "(1,7,11,101,'BR','US',1,1,3,%d,%d,NULL,%d,100),"
                + "(2,7,11,101,'BR','US',1,1,4,%d,%d,%d,NULL,110),"
                + "(3,7,11,102,'BR','US',1,1,5,%d,%d,%d,%d,120),"
                + "(4,7,11,103,'BR','US',1,1,6,%d,NULL,NULL,NULL,130)")
                .formatted(start + 1, start + 1, start + 10,
                        start + 2, start + 2, start + 2,
                        start + 3, start + 3, start + 3, start + 20,
                        start + 4));

        assertThat(mapper.selectProjectionTenantIds(start, start + 3_600_000L))
                .containsExactly(7L);

        assertThat(mapper.upsertHourlyBucket(7L, start, start + 3_600_000L, 9_000L))
                .isEqualTo(1);
        HyperlinkMarketingStatCriteria criteria = new HyperlinkMarketingStatCriteria(
                7L, "hour", start, start + 3_600_000L, 0, 0,
                null, null, null, null, null, null);

        assertThat(mapper.selectHourly(criteria)).singleElement().satisfies(row -> {
            assertThat(row.getSendTotal()).isEqualTo(4);
            assertThat(row.getSuccessNum()).isEqualTo(3);
            assertThat(row.getDeliveredNum()).isEqualTo(2);
            assertThat(row.getUsedAccountCount()).isEqualTo(3);
            assertThat(row.getBannedAccountCount()).isEqualTo(1);
            assertThat(row.getClickUvNum()).isEqualTo(2);
        });
        assertThat(mapper.selectCountries(criteria)).singleElement().satisfies(row -> {
            assertThat(row.getSenderCountryIso2()).isEqualTo("BR");
            assertThat(row.getRecipientCountryIso2()).isEqualTo("US");
        });
    }

    @Test
    void exactOverviewDeduplicatesAccountsAcrossCountryPairs() throws SQLException {
        long start = 1_778_000_400_000L;
        execute("INSERT INTO hyperlink_task VALUES (11,7,1,1)");
        execute("INSERT INTO hyperlink_task_account_usage VALUES "
                + "(1,7,11,101,3,1778000500000,200)");
        execute(("INSERT INTO hyperlink_task_recipient VALUES "
                + "(1,7,11,101,'BR','US',1,1,3,%d,%d,NULL,NULL,100),"
                + "(2,7,11,101,'BR','ID',1,1,4,%d,%d,%d,%d,110)")
                .formatted(start + 1, start + 1, start + 2,
                        start + 2, start + 2, start + 2));
        HyperlinkMarketingStatCriteria criteria = new HyperlinkMarketingStatCriteria(
                7L, "hour", start, start + 3_600_000L, 0, 0,
                null, null, null, null, null, null);

        var overview = mapper.selectExactOverview(criteria);

        assertThat(overview.getSendTotal()).isEqualTo(2);
        assertThat(overview.getSuccessNum()).isEqualTo(2);
        assertThat(overview.getDeliveredNum()).isEqualTo(1);
        assertThat(overview.getUsedAccountCount()).isEqualTo(1);
        assertThat(overview.getBannedAccountCount()).isEqualTo(1);
        assertThat(overview.getClickUvNum()).isEqualTo(1);
    }

    private HyperlinkMarketingStatCriteria criteria(Integer device) {
        return new HyperlinkMarketingStatCriteria(7L, "day", 0, 0,
                20260830, 20260830, null, null, null, null, device, null);
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String dailySchema() {
        return "CREATE TABLE hyperlink_stat_daily (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "tenant_id BIGINT NOT NULL, stat_date INT NOT NULL, sender_country_iso2 CHAR(2), "
                + "recipient_country_iso2 CHAR(2), account_type TINYINT, task_type TINYINT, "
                + "sender_device_os TINYINT, is_short_link_enabled TINYINT, send_total BIGINT, "
                + "success_num BIGINT, delivered_num BIGINT, used_account_count BIGINT, "
                + "banned_account_count BIGINT, click_uv_num BIGINT, created_at BIGINT, updated_at BIGINT, "
                + "UNIQUE(tenant_id,stat_date,sender_country_iso2,recipient_country_iso2,account_type,"
                + "task_type,sender_device_os,is_short_link_enabled))";
    }

    private static String hourlySchema() {
        return "CREATE TABLE hyperlink_stat_hourly (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "tenant_id BIGINT NOT NULL, stat_hour_start_at BIGINT NOT NULL, "
                + "sender_country_iso2 CHAR(2), recipient_country_iso2 CHAR(2), account_type TINYINT, "
                + "task_type TINYINT, sender_device_os TINYINT, is_short_link_enabled TINYINT, "
                + "send_total BIGINT, success_num BIGINT, delivered_num BIGINT, "
                + "used_account_count BIGINT, banned_account_count BIGINT, click_uv_num BIGINT, "
                + "created_at BIGINT, updated_at BIGINT, UNIQUE(tenant_id,stat_hour_start_at,"
                + "sender_country_iso2,recipient_country_iso2,account_type,task_type,sender_device_os,"
                + "is_short_link_enabled))";
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:hyperlink_marketing_stat_test;MODE=MySQL;"
                    + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            source.setUser("sa");
            source.setPassword("");
            return source;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource source,
                MybatisPlusInterceptor interceptor) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            factory.setMapperLocations(new Resource[]{new ClassPathResource(
                    "mapper/hyperlink/task/HyperlinkMarketingStatMapper.xml")});
            return factory.getObject();
        }

        @Bean SqlSessionTemplate template(SqlSessionFactory value) {
            return new SqlSessionTemplate(value);
        }
        @Bean HyperlinkMarketingStatMapper mapper(SqlSessionTemplate value) {
            return value.getMapper(HyperlinkMarketingStatMapper.class);
        }
    }
}
