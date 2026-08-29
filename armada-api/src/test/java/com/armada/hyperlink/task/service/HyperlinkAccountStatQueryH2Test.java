package com.armada.hyperlink.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.armada.boot.config.MyBatisConfig;
import com.armada.hyperlink.task.mapper.HyperlinkTaskAccountStatMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.model.dto.HyperlinkAccountStatQuery;
import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import com.armada.hyperlink.task.model.vo.HyperlinkAccountStatItemVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.response.PageResult;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.core.io.Resource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 真实 H2 + Mapper XML 覆盖 H5 两条查询路径与租户边界。 */
@SpringJUnitConfig(HyperlinkAccountStatQueryH2Test.TestConfig.class)
@TestExecutionListeners(listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class HyperlinkAccountStatQueryH2Test {

    private static final long SNAPSHOT_AT = 864_000_000L;

    @Autowired
    private DataSource dataSource;
    @Autowired
    private HyperlinkAccountStatQueryService service;

    @BeforeEach
    void setUp() throws SQLException {
        execute("DROP ALL OBJECTS", accountStatSchema(), accountUsageSchema(), recipientSchema());
        insertFixtures();
        TenantContext.set(7L);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void defaultQueryUsesProjectionAndKeepsTheUnassignedBucket() throws SQLException {
        execute("INSERT INTO hyperlink_task_recipient "
                + "(id,tenant_id,hyperlink_task_id,account_id,sender_country_iso2_snapshot,"
                + "send_status,submitted_at,created_at,updated_at) "
                + "VALUES (999,7,11,999,'BR',5,1500,100,100)");

        PageResult<HyperlinkAccountStatItemVO> result = service.list(11L, new HyperlinkAccountStatQuery());

        assertThat(result.total()).isEqualTo(12);
        assertThat(result.list()).extracting(HyperlinkAccountStatItemVO::accountId)
                .doesNotContain(999L);
        assertThat(result.list().get(0).accountId()).isEqualTo(101L);
        HyperlinkAccountStatItemVO unassigned = result.list().stream()
                .filter(row -> row.accountId() == null)
                .findFirst().orElseThrow();
        assertThat(unassigned.bucketKey()).isZero();
        assertThat(unassigned.senderPhone()).isNull();
        assertThat(unassigned.senderCountryIso2()).isNull();
        assertThat(unassigned.accountType()).isNull();
        assertThat(unassigned.retentionDays()).isEqualByComparingTo("0.0");
        assertThat(unassigned.failedNum()).isEqualTo(1);
    }

    @Test
    void timeQueryAggregatesSubmittedFactsAndAppliesCountryAndSuccessRate() {
        HyperlinkAccountStatQuery query = new HyperlinkAccountStatQuery();
        query.setStartAt(1_000L);
        query.setEndAt(2_000L);
        query.setSenderCountryIso2("br");
        query.setSuccessRateMin(new BigDecimal("70"));
        query.setSuccessRateMax(new BigDecimal("80"));

        PageResult<HyperlinkAccountStatItemVO> result = service.list(11L, query);

        assertThat(result.total()).isOne();
        assertThat(result.list()).singleElement().satisfies(row -> {
            assertThat(row.accountId()).isEqualTo(101L);
            assertThat(row.successNum()).isEqualTo(3);
            assertThat(row.deliveredNum()).isEqualTo(2);
            assertThat(row.failedNum()).isEqualTo(1);
            assertThat(row.lastSendAt()).isEqualTo(1_300L);
            assertThat(row.senderCountryIso2()).isEqualTo("BR");
        });
    }

    @Test
    void timeQueryShowsOneUnassignedRowAndExcludesUnsubmittedStopFacts() {
        HyperlinkAccountStatQuery query = new HyperlinkAccountStatQuery();
        query.setStartAt(1_000L);
        query.setEndAt(2_000L);
        query.setSuccessRateMax(BigDecimal.ZERO);

        PageResult<HyperlinkAccountStatItemVO> result = service.list(11L, query);

        assertThat(result.list()).singleElement().satisfies(row -> {
            assertThat(row.accountId()).isNull();
            assertThat(row.bucketKey()).isZero();
            assertThat(row.failedNum()).isOne();
            assertThat(row.lastSendAt()).isEqualTo(1_500L);
        });
    }

    @Test
    void unknownCountryKeepsTheUnassignedAggregateOnBothPaths() {
        HyperlinkAccountStatQuery cumulative = new HyperlinkAccountStatQuery();
        cumulative.setSenderCountryIso2("UNKNOWN");
        assertThat(service.list(11L, cumulative).list())
                .extracting(HyperlinkAccountStatItemVO::accountId)
                .containsExactly((Long) null);

        HyperlinkAccountStatQuery ranged = new HyperlinkAccountStatQuery();
        ranged.setStartAt(1_000L);
        ranged.setEndAt(2_000L);
        ranged.setSenderCountryIso2("UNKNOWN");
        assertThat(service.list(11L, ranged).list())
                .extracting(HyperlinkAccountStatItemVO::accountId)
                .containsExactly((Long) null);
    }

    @Test
    void countryRateSortingAndAllowedPaginationAreDatabaseDriven() {
        HyperlinkAccountStatQuery filtered = new HyperlinkAccountStatQuery();
        filtered.setSenderCountryIso2("BR");
        filtered.setSuccessRateMin(new BigDecimal("80"));
        filtered.setSortField("deliveredNum");
        filtered.setSortOrder("asc");
        PageResult<HyperlinkAccountStatItemVO> filteredResult = service.list(11L, filtered);
        assertThat(filteredResult.list()).extracting(HyperlinkAccountStatItemVO::accountId)
                .containsExactly(103L, 101L);

        HyperlinkAccountStatQuery secondPage = new HyperlinkAccountStatQuery();
        secondPage.setPage(2);
        secondPage.setPageSize(10);
        secondPage.setSortField("failedNum");
        secondPage.setSortOrder("asc");
        PageResult<HyperlinkAccountStatItemVO> page = service.list(11L, secondPage);
        assertThat(page.total()).isEqualTo(12);
        assertThat(page.list()).hasSize(2);
        assertThat(page.list()).extracting(HyperlinkAccountStatItemVO::bucketKey)
                .doesNotHaveDuplicates();
    }

    @Test
    void tenantInterceptorAndTaskOwnershipPreventCrossTenantReads() {
        PageResult<HyperlinkAccountStatItemVO> tenantSeven = service.list(
                11L, new HyperlinkAccountStatQuery());
        assertThat(tenantSeven.list()).extracting(HyperlinkAccountStatItemVO::accountId)
                .doesNotContain(201L);

        TenantContext.set(8L);
        PageResult<HyperlinkAccountStatItemVO> tenantEight = service.list(
                11L, new HyperlinkAccountStatQuery());
        assertThat(tenantEight.total()).isOne();
        assertThat(tenantEight.list()).extracting(HyperlinkAccountStatItemVO::accountId)
                .containsExactly(201L);

        TenantContext.set(9L);
        assertThatThrownBy(() -> service.list(11L, new HyperlinkAccountStatQuery()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("超链任务不存在");
    }

    @Test
    void rejectsInvalidRateTimeSortAndPageInputs() {
        HyperlinkAccountStatQuery invalidRate = new HyperlinkAccountStatQuery();
        invalidRate.setSuccessRateMin(new BigDecimal("80"));
        invalidRate.setSuccessRateMax(new BigDecimal("70"));
        assertValidation(invalidRate, "成功率最小值不能大于最大值");

        HyperlinkAccountStatQuery invalidTime = new HyperlinkAccountStatQuery();
        invalidTime.setStartAt(100L);
        assertValidation(invalidTime, "开始时间和结束时间必须同时提供");

        HyperlinkAccountStatQuery invalidSort = new HyperlinkAccountStatQuery();
        invalidSort.setSortField("lastSendAt");
        assertValidation(invalidSort, "不支持的排序字段");

        HyperlinkAccountStatQuery invalidPageSize = new HyperlinkAccountStatQuery();
        invalidPageSize.setPageSize(15);
        assertValidation(invalidPageSize, "pageSize 仅支持");
    }

    private void assertValidation(HyperlinkAccountStatQuery query, String message) {
        assertThatThrownBy(() -> service.list(11L, query))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(message);
    }

    private void insertFixtures() throws SQLException {
        execute("INSERT INTO hyperlink_task_account_stat "
                        + "(id,tenant_id,hyperlink_task_id,account_id,send_total,success_num,"
                        + "delivered_num,failed_num,last_send_at,updated_at) VALUES "
                        + "(1,7,11,NULL,0,0,0,1,NULL,500),"
                        + "(2,7,11,101,10,8,6,2,1800,500),"
                        + "(3,7,11,102,2,1,1,1,1900,500),"
                        + "(4,7,11,103,5,5,5,0,1950,500),"
                        + "(20,8,11,201,1,1,1,0,1700,500)",
                "INSERT INTO hyperlink_task_account_usage "
                        + "(id,tenant_id,hyperlink_task_id,account_id,account_phone_snapshot,"
                        + "sender_country_iso2_snapshot,account_type_snapshot,account_created_at_snapshot) VALUES "
                        + "(1,7,11,101,'551100000101','BR',1,0),"
                        + "(2,7,11,102,'12025550102','US',2,432000000),"
                        + "(3,7,11,103,'551100000103','BR',2,0),"
                        + "(20,8,11,201,'447700900201','GB',1,0)",
                "INSERT INTO hyperlink_task_recipient "
                        + "(id,tenant_id,hyperlink_task_id,account_id,sender_country_iso2_snapshot,"
                        + "send_status,submitted_at,created_at,updated_at) VALUES "
                        + "(1,7,11,101,'BR',3,1000,100,100),"
                        + "(2,7,11,101,'BR',4,1100,100,100),"
                        + "(3,7,11,101,'BR',5,1200,100,100),"
                        + "(4,7,11,101,'BR',6,1300,100,100),"
                        + "(5,7,11,101,'BR',5,500,100,100),"
                        + "(6,7,11,102,'US',3,1400,100,100),"
                        + "(7,7,11,102,'US',7,1450,100,100),"
                        + "(8,7,11,NULL,NULL,6,1500,100,100),"
                        + "(9,7,11,NULL,NULL,6,NULL,100,100),"
                        + "(20,8,11,201,'GB',5,1600,100,100)");
        for (long accountId = 104; accountId <= 111; accountId++) {
            execute("INSERT INTO hyperlink_task_account_stat "
                            + "(tenant_id,hyperlink_task_id,account_id,send_total,success_num,"
                            + "delivered_num,failed_num,last_send_at,updated_at) VALUES "
                            + "(7,11," + accountId + ",1,1,0,0,1600,500)",
                    "INSERT INTO hyperlink_task_account_usage "
                            + "(tenant_id,hyperlink_task_id,account_id,account_phone_snapshot,"
                            + "sender_country_iso2_snapshot,account_type_snapshot,account_created_at_snapshot) "
                            + "VALUES (7,11," + accountId + ",'55" + accountId + "','MX',1,0)");
        }
    }

    private void execute(String... statements) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private String accountStatSchema() {
        return "CREATE TABLE hyperlink_task_account_stat (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "tenant_id BIGINT NOT NULL, hyperlink_task_id BIGINT NOT NULL, account_id BIGINT, "
                + "account_bucket_key BIGINT GENERATED ALWAYS AS (COALESCE(account_id,0)), "
                + "send_total BIGINT DEFAULT 0, success_num BIGINT DEFAULT 0, delivered_num BIGINT DEFAULT 0, "
                + "failed_num BIGINT DEFAULT 0, last_send_at BIGINT, updated_at BIGINT, "
                + "UNIQUE(tenant_id,hyperlink_task_id,account_bucket_key))";
    }

    private String accountUsageSchema() {
        return "CREATE TABLE hyperlink_task_account_usage (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "tenant_id BIGINT NOT NULL, hyperlink_task_id BIGINT NOT NULL, account_id BIGINT NOT NULL, "
                + "account_phone_snapshot VARCHAR(32), sender_country_iso2_snapshot CHAR(2), "
                + "account_type_snapshot TINYINT, account_created_at_snapshot BIGINT)";
    }

    private String recipientSchema() {
        return "CREATE TABLE hyperlink_task_recipient (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, "
                + "hyperlink_task_id BIGINT NOT NULL, account_id BIGINT, "
                + "sender_country_iso2_snapshot CHAR(2), send_status TINYINT NOT NULL, "
                + "submitted_at BIGINT, created_at BIGINT, updated_at BIGINT)";
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:hyperlink_account_stats_test;MODE=MySQL;"
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
            factory.setMapperLocations(new Resource[] {
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskRecipientMapper.xml"),
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskAccountStatMapper.xml")
            });
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        HyperlinkTaskRecipientMapper recipientMapper(SqlSessionTemplate template) {
            return template.getMapper(HyperlinkTaskRecipientMapper.class);
        }

        @Bean
        HyperlinkTaskAccountStatMapper accountStatMapper(SqlSessionTemplate template) {
            return template.getMapper(HyperlinkTaskAccountStatMapper.class);
        }

        @Bean
        HyperlinkTaskMapper taskMapper() {
            HyperlinkTaskMapper mapper = mock(HyperlinkTaskMapper.class);
            when(mapper.selectById(11L)).thenAnswer(invocation -> {
                Long tenantId = TenantContext.get();
                return tenantId != null && (tenantId == 7L || tenantId == 8L)
                        ? new HyperlinkTask() : null;
            });
            return mapper;
        }

        @Bean
        HyperlinkAccountStatCriteriaFactory criteriaFactory() {
            return new HyperlinkAccountStatCriteriaFactory();
        }

        @Bean
        HyperlinkAccountStatQueryService service(HyperlinkTaskMapper tasks,
                HyperlinkTaskAccountStatMapper stats, HyperlinkTaskRecipientMapper recipients,
                HyperlinkAccountStatCriteriaFactory factory) {
            return new HyperlinkAccountStatQueryService(tasks, stats, recipients, factory,
                    Clock.fixed(Instant.ofEpochMilli(SNAPSHOT_AT), ZoneOffset.UTC));
        }
    }
}
