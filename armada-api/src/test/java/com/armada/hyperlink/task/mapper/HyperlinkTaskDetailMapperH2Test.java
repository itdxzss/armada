package com.armada.hyperlink.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.hyperlink.task.model.dto.HyperlinkRecipientQuery;
import com.armada.hyperlink.task.model.vo.HyperlinkRecipientRow;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskSummaryRow;
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
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** H4 收信人真实 H2/MyBatis XML、状态、筛选、分页和租户隔离测试。 */
@SpringJUnitConfig(HyperlinkTaskDetailMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class HyperlinkTaskDetailMapperH2Test {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private HyperlinkTaskDetailMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        resetSchema();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void mapsAllStatusesAndSelectsTheFrozenStatusTimestamp() throws SQLException {
        for (int status = 1; status <= 7; status++) {
            insertRecipient(7, 9, "+6281000" + status, "ID", "US", status,
                    status >= 6 ? "完整失败原因" : null, status);
        }

        HyperlinkRecipientQuery query = query(9);
        query.setPageSize(20);
        List<HyperlinkRecipientRow> rows = mapper.selectRecipients(query);

        assertThat(rows).extracting(HyperlinkRecipientRow::getStatusCode)
                .containsExactly(1, 2, 3, 4, 5, 6, 7);
        assertThat(rows.subList(0, 2)).extracting(HyperlinkRecipientRow::getStatusAt)
                .containsOnlyNulls();
        assertThat(rows.subList(2, 7)).extracting(HyperlinkRecipientRow::getStatusAt)
                .containsExactly(1_003L, 2_004L, 3_005L, 4_006L, 4_007L);
    }

    @Test
    void pushesPhoneCountriesAndExactFailureReasonIntoSql() throws SQLException {
        insertRecipient(7, 9, "+62811112222", "ID", "US", 6, "完整失败原因", 1);
        insertRecipient(7, 9, "+62811113333", "ID", "US", 6, "完整失败原因-更多", 2);
        insertRecipient(7, 9, "+62811114444", "MY", "US", 6, "完整失败原因", 3);
        insertRecipient(7, 9, "+62811115555", "ID", "GB", 6, "完整失败原因", 4);

        HyperlinkRecipientQuery query = query(9);
        query.setPhoneLike("%111122%");
        query.setRecipientCountryIso2("ID");
        query.setSenderCountryIso2("US");
        query.setFailReason("完整失败原因");

        assertThat(mapper.countRecipients(query)).isEqualTo(1);
        assertThat(mapper.selectRecipients(query))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getRecipientPhone()).isEqualTo("+62811112222");
                    assertThat(row.getRecipientCountryIso2()).isEqualTo("ID");
                    assertThat(row.getSenderCountryIso2()).isEqualTo("US");
                    assertThat(row.getFailReason()).isEqualTo("完整失败原因");
                });
    }

    @Test
    void paginatesInDatabaseAndKeepsOtherTenantsInvisible() throws SQLException {
        for (int index = 1; index <= 5; index++) {
            insertRecipient(7, 9, "+861380000000" + index, "CN", "SG", 3, null, index);
        }
        insertRecipient(8, 9, "+8613999999999", "CN", "SG", 7, "越权行", 9);

        HyperlinkRecipientQuery query = query(9);
        query.setPage(2);
        query.setPageSize(2);
        query.setSortOrder("desc");

        assertThat(mapper.countRecipients(query)).isEqualTo(5);
        assertThat(mapper.selectRecipients(query)).extracting(HyperlinkRecipientRow::getId)
                .containsExactly(3L, 2L);

        TenantContext.set(8L);
        HyperlinkRecipientQuery otherTenant = query(9);
        assertThat(mapper.countRecipients(otherTenant)).isEqualTo(1);
        assertThat(mapper.selectRecipients(otherTenant))
                .singleElement()
                .extracting(HyperlinkRecipientRow::getFailReason)
                .isEqualTo("越权行");
    }

    @Test
    void summaryReadsAllFrozenRuntimeMetricsWithinTenant() throws SQLException {
        execute("""
                INSERT INTO hyperlink_task (id, tenant_id, task_name) VALUES (9, 7, 'H4任务')
                """);
        execute("""
                INSERT INTO hyperlink_task_runtime
                    (hyperlink_task_id, tenant_id, recipient_total, send_total, success_num,
                     delivered_num, read_num, fail_num, fail_404_num, used_account_count,
                     invalid_account_count, click_uv_num, click_total, actual_concurrency,
                     execution_duration_sec, run_status, active_since_at, metrics_updated_at,
                     first_visit_at, last_visit_at)
                VALUES (9, 7, 10, 9, 7, 5, 3, 2, 1, 4, 1, 2, 3, 6, 30, 1,
                        1000, 2000, 3000, 4000)
                """);

        HyperlinkTaskSummaryRow row = mapper.selectSummary(9);
        assertThat(row.getTaskName()).isEqualTo("H4任务");
        assertThat(row.getRecipientTotal()).isEqualTo(10);
        assertThat(row.getSuccessNum()).isEqualTo(7);
        assertThat(row.getDeliveredNum()).isEqualTo(5);
        assertThat(row.getFailedNum()).isEqualTo(2);
        assertThat(row.getUnregisteredNum()).isEqualTo(1);
        assertThat(row.getClickUvNum()).isEqualTo(2);
        assertThat(row.getClickTotal()).isEqualTo(3);

        TenantContext.set(8L);
        assertThat(mapper.selectSummary(9)).isNull();
    }

    private static HyperlinkRecipientQuery query(long taskId) {
        HyperlinkRecipientQuery query = new HyperlinkRecipientQuery();
        query.setTaskId(taskId);
        return query;
    }

    private void insertRecipient(
            long tenantId,
            long taskId,
            String phone,
            String recipientCountry,
            String senderCountry,
            int status,
            String failReason,
            int ordinal) throws SQLException {
        String reason = failReason == null ? "NULL" : "'" + failReason + "'";
        execute("INSERT INTO hyperlink_task_recipient "
                + "(id, tenant_id, hyperlink_task_id, recipient_phone_snapshot, "
                + "recipient_country_iso2_snapshot, account_id, sender_phone_snapshot, "
                + "sender_country_iso2_snapshot, send_status, fail_code, fail_reason, "
                + "sent_at, delivered_at, read_at, failed_at, created_at) VALUES ("
                + ordinal + ", " + tenantId + ", " + taskId + ", '" + phone + "', '"
                + recipientCountry + "', 100, '+12025550123', '" + senderCountry + "', "
                + status + ", " + (failReason == null ? "NULL" : "'E'" ) + ", " + reason
                + ", " + (1_000 + ordinal) + ", " + (2_000 + ordinal) + ", "
                + (3_000 + ordinal) + ", " + (4_000 + ordinal) + ", 100)");
    }

    private void resetSchema() throws SQLException {
        execute("DROP ALL OBJECTS");
        execute("""
                CREATE TABLE hyperlink_task (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    task_name VARCHAR(1024) NOT NULL
                )
                """);
        execute("""
                CREATE TABLE hyperlink_task_runtime (
                    hyperlink_task_id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    recipient_total INT, send_total BIGINT, success_num BIGINT,
                    delivered_num BIGINT, read_num BIGINT, fail_num BIGINT,
                    fail_404_num BIGINT, used_account_count INT, invalid_account_count INT,
                    click_uv_num BIGINT, click_total BIGINT, actual_concurrency INT,
                    execution_duration_sec BIGINT, run_status INT, active_since_at BIGINT,
                    metrics_updated_at BIGINT, first_visit_at BIGINT, last_visit_at BIGINT
                )
                """);
        execute("""
                CREATE TABLE hyperlink_task_recipient (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    hyperlink_task_id BIGINT NOT NULL,
                    recipient_phone_snapshot VARCHAR(32) NOT NULL,
                    recipient_country_iso2_snapshot CHAR(2),
                    account_id BIGINT,
                    sender_phone_snapshot VARCHAR(32),
                    sender_country_iso2_snapshot CHAR(2),
                    send_status INT NOT NULL,
                    fail_code VARCHAR(64),
                    fail_reason VARCHAR(255),
                    sent_at BIGINT,
                    delivered_at BIGINT,
                    read_at BIGINT,
                    failed_at BIGINT,
                    created_at BIGINT NOT NULL
                )
                """);
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:hyperlink_task_detail_mapper_test;MODE=MySQL;"
                    + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
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
                    "mapper/hyperlink/task/HyperlinkTaskDetailMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        HyperlinkTaskDetailMapper mapper(SqlSessionTemplate template) {
            return template.getMapper(HyperlinkTaskDetailMapper.class);
        }
    }
}
