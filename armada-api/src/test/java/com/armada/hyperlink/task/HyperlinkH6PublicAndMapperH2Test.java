package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.hyperlink.task.controller.HyperlinkPublicRedirectController;
import com.armada.hyperlink.task.mapper.HyperlinkTaskAccountUsageMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskContentMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRuntimeMapper;
import com.armada.hyperlink.task.service.HyperlinkPublicClickService;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** H6 公网并发、租户隔离、动态分桶和封号去重的真实 Mapper 验证。 */
@SpringJUnitConfig(HyperlinkH6PublicAndMapperH2Test.TestConfig.class)
@TestExecutionListeners(listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class HyperlinkH6PublicAndMapperH2Test {
    @Autowired private DataSource dataSource;
    @Autowired private HyperlinkPublicClickService clickService;
    @Autowired private HyperlinkTaskRecipientMapper recipientMapper;
    @Autowired private HyperlinkTaskAccountUsageMapper usageMapper;

    @BeforeEach
    void setUp() throws SQLException {
        execute("DROP ALL OBJECTS", recipientSchema(), runtimeSchema(), contentSchema(), usageSchema());
        TenantContext.clear();
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void concurrentFirstAndRepeatVisitsProduceOneUvTwoPvAnd302() throws Exception {
        insertPublicFacts("AbC9", "https://example.test/landing?a=1");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<HyperlinkPublicClickService.RedirectOutcome> first = workers.submit(
                    () -> visitAfter(start));
            Future<HyperlinkPublicClickService.RedirectOutcome> second = workers.submit(
                    () -> visitAfter(start));
            start.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).status().name()).isEqualTo("FOUND");
            assertThat(second.get(5, TimeUnit.SECONDS).status().name()).isEqualTo("FOUND");
        } finally {
            workers.shutdownNow();
        }

        assertThat(row("SELECT click_count,first_visit_at IS NOT NULL,last_visit_at IS NOT NULL "
                + "FROM hyperlink_task_recipient WHERE id=1")).containsExactly(2L, 1L, 1L);
        assertThat(row("SELECT click_uv_num,click_total FROM hyperlink_task_runtime "
                + "WHERE hyperlink_task_id=11")).containsExactly(1L, 2L);
        assertThat(row("SELECT COUNT(*) FROM hyperlink_task_recipient WHERE id=1 "
                + "AND first_visit_ip_address IS NOT NULL "
                + "AND first_visit_user_agent LIKE '%Android 14%' "
                + "AND first_visit_device='mobile' AND first_visit_os='Android' "
                + "AND first_visit_browser='Chrome' AND first_visit_language='pt-BR'"))
                .containsExactly(1L);

        MockHttpServletRequest request = request();
        var response = new HyperlinkPublicRedirectController(clickService).redirect("AbC9", request);
        assertThat(response.getStatusCode().value()).isEqualTo(302);
        assertThat(response.getHeaders().getLocation()).hasToString("https://example.test/landing?a=1");
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    void invalidAndCaseChangedCodeAre404AndUnsafeTargetIs410WithoutCounting() throws SQLException {
        insertPublicFacts("Case9", "javascript:alert(1)");
        var controller = new HyperlinkPublicRedirectController(clickService);

        assertThat(controller.redirect("case9", request()).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.redirect("NoSuch", request()).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.redirect("Case9", request()).getStatusCode().value()).isEqualTo(410);
        assertThat(row("SELECT click_count FROM hyperlink_task_recipient WHERE id=1"))
                .containsExactly(0L);
        assertThat(row("SELECT click_uv_num,click_total FROM hyperlink_task_runtime "
                + "WHERE hyperlink_task_id=11")).containsExactly(0L, 0L);
    }

    @Test
    void thirtyMinuteBucketsIncludeSeventyTwoHourLastMillisecondButExcludeBoundary()
            throws SQLException {
        long anchor = 1_000_000L;
        execute("INSERT INTO hyperlink_task_recipient "
                + "(id,tenant_id,hyperlink_task_id,recipient_phone_snapshot,short_code,click_count,"
                + "first_visit_at,last_visit_at,created_at,updated_at) VALUES "
                + "(1,7,11,'1','a001',1," + anchor + "," + anchor + ",1,1),"
                + "(2,7,11,'2','a002',1," + (anchor + 1_800_000L - 1) + ",1,1,1),"
                + "(3,7,11,'3','a003',1," + (anchor + 1_800_000L) + ",1,1,1),"
                + "(4,7,11,'4','a004',1," + (anchor + 72 * 3_600_000L - 1) + ",1,1,1),"
                + "(5,7,11,'5','a005',1," + (anchor + 72 * 3_600_000L) + ",1,1,1)");
        TenantContext.set(7L);

        var rows = recipientMapper.selectVisitUvBuckets(11, anchor,
                anchor + 72 * 3_600_000L, 1_800_000L);
        assertThat(rows).extracting(row -> List.of(row.getBucketNo(), row.getNewUv()))
                .containsExactly(List.of(0, 2L), List.of(1, 1L), List.of(143, 1L));
    }

    @Test
    void tenantIsolationAndUnknownBanReasonAreStableAndAccountRowsStayDeduplicated()
            throws SQLException {
        execute("INSERT INTO hyperlink_task_recipient "
                        + "(id,tenant_id,hyperlink_task_id,recipient_phone_snapshot,short_code,"
                        + "click_count,first_visit_at,last_visit_at,created_at,updated_at) VALUES "
                        + "(1,7,11,'100','x001',1,100,100,1,1),"
                        + "(2,8,11,'200','x002',1,100,100,1,1)",
                "INSERT INTO hyperlink_task_account_usage "
                        + "(id,tenant_id,hyperlink_task_id,account_id,invalid_code,invalid_reason,"
                        + "invalid_at) VALUES (1,7,11,31,NULL,NULL,100),(2,7,11,32,'','  ',101),"
                        + "(3,7,11,33,'account_offline',NULL,102),(4,8,11,34,'foreign',NULL,103)");

        TenantContext.set(7L);
        assertThat(recipientMapper.countClicked(11, null, null)).isEqualTo(1);
        assertThat(usageMapper.selectBanReasonStats(11))
                .extracting(row -> List.of(row.getReason(), row.getAccountCount()))
                .containsExactly(List.of("未知原因", 2L), List.of("account_offline", 1L));

        TenantContext.set(8L);
        assertThat(recipientMapper.countClicked(11, null, null)).isEqualTo(1);
        assertThat(usageMapper.selectBanReasonStats(11)).hasSize(1);
    }

    private HyperlinkPublicClickService.RedirectOutcome visitAfter(CountDownLatch start)
            throws Exception {
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return clickService.visit("AbC9", request());
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/public/hl/AbC9");
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) Chrome/127.0 Mobile");
        request.addHeader("Accept-Language", "pt-BR,pt;q=0.9");
        return request;
    }

    private void insertPublicFacts(String code, String target) throws SQLException {
        execute("INSERT INTO hyperlink_task_recipient "
                        + "(id,tenant_id,hyperlink_task_id,recipient_phone_snapshot,short_code,"
                        + "click_count,created_at,updated_at) VALUES (1,7,11,'5511999','" + code
                        + "',0,1,1)",
                "INSERT INTO hyperlink_task_runtime "
                        + "(hyperlink_task_id,tenant_id,click_uv_num,click_total,created_at,updated_at) "
                        + "VALUES (11,7,0,0,1,1)",
                "INSERT INTO hyperlink_task_content "
                        + "(hyperlink_task_id,tenant_id,message_schema_version,message_type,promotion_link,"
                        + "created_at,updated_at) VALUES (11,7,1,1,'" + target + "',1,1)");
    }

    private List<Long> row(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            List<Long> values = new java.util.ArrayList<>();
            for (int index = 1; index <= result.getMetaData().getColumnCount(); index++) {
                values.add(result.getLong(index));
            }
            return values;
        }
    }

    private void execute(String... statements) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) statement.execute(sql);
        }
    }

    private String recipientSchema() {
        return """
                CREATE TABLE hyperlink_task_recipient (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, hyperlink_task_id BIGINT NOT NULL,
                  recipient_phone_snapshot VARCHAR(32) NOT NULL, sender_phone_snapshot VARCHAR(32),
                  short_code VARCHAR(24) UNIQUE, click_count INT NOT NULL DEFAULT 0,
                  first_visit_at BIGINT, last_visit_at BIGINT, first_visit_ip_address VARBINARY(16),
                  first_visit_user_agent VARCHAR(512), first_visit_browser VARCHAR(64),
                  first_visit_os VARCHAR(64), first_visit_device VARCHAR(64),
                  first_visit_language VARCHAR(32), first_visit_country_iso2 CHAR(2),
                  attribution_purged_at BIGINT, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL)
                """;
    }

    private String runtimeSchema() {
        return """
                CREATE TABLE hyperlink_task_runtime (
                  hyperlink_task_id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  first_visit_at BIGINT, last_visit_at BIGINT, click_uv_num INT NOT NULL DEFAULT 0,
                  click_total BIGINT NOT NULL DEFAULT 0, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL)
                """;
    }

    private String contentSchema() {
        return """
                CREATE TABLE hyperlink_task_content (
                  hyperlink_task_id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  message_schema_version INT, message_type INT, title VARCHAR(255), content VARCHAR(255),
                  link_description VARCHAR(255), promotion_link VARCHAR(500), buttons CLOB,
                  card_text VARCHAR(255), link_preview_asset_id BIGINT, body_main_asset_id BIGINT,
                  created_at BIGINT, updated_at BIGINT)
                """;
    }

    private String usageSchema() {
        return """
                CREATE TABLE hyperlink_task_account_usage (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, hyperlink_task_id BIGINT NOT NULL,
                  account_id BIGINT NOT NULL, invalid_code VARCHAR(64), invalid_reason VARCHAR(255),
                  invalid_at BIGINT, UNIQUE(tenant_id,hyperlink_task_id,account_id))
                """;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class TestConfig {
        @Bean DataSource dataSource() {
            JdbcDataSource h2 = new JdbcDataSource();
            h2.setURL("jdbc:h2:mem:hyperlink_h6;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            h2.setUser("sa");
            h2.setPassword("");
            return h2;
        }
        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                MybatisPlusInterceptor interceptor) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            factory.setMapperLocations(
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskRecipientMapper.xml"),
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskRuntimeMapper.xml"),
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskContentMapper.xml"),
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskAccountUsageMapper.xml"));
            return factory.getObject();
        }
        @Bean SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }
        @Bean HyperlinkTaskRecipientMapper recipientMapper(SqlSessionTemplate template) {
            return template.getMapper(HyperlinkTaskRecipientMapper.class);
        }
        @Bean HyperlinkTaskRuntimeMapper runtimeMapper(SqlSessionTemplate template) {
            return template.getMapper(HyperlinkTaskRuntimeMapper.class);
        }
        @Bean HyperlinkTaskContentMapper contentMapper(SqlSessionTemplate template) {
            return template.getMapper(HyperlinkTaskContentMapper.class);
        }
        @Bean HyperlinkTaskAccountUsageMapper usageMapper(SqlSessionTemplate template) {
            return template.getMapper(HyperlinkTaskAccountUsageMapper.class);
        }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean HyperlinkPublicClickService clickService(HyperlinkTaskRecipientMapper recipient,
                HyperlinkTaskRuntimeMapper runtime, HyperlinkTaskContentMapper content,
                ObjectMapper objectMapper) {
            return new HyperlinkPublicClickService(recipient, runtime, content, objectMapper);
        }
    }
}
