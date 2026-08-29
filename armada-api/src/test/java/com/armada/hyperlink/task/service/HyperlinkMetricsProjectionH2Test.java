package com.armada.hyperlink.task.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.hyperlink.task.mapper.HyperlinkTaskAccountStatMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRoundMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRuntimeMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 真实 Mapper XML 验证 recipient 变化的有界、并发安全指标投影。 */
@SpringJUnitConfig(HyperlinkMetricsProjectionH2Test.TestConfig.class)
@TestExecutionListeners(listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class HyperlinkMetricsProjectionH2Test {
    private static final int CONCURRENT_RECIPIENTS = 600;

    @Autowired
    private DataSource dataSource;
    @Autowired
    private HyperlinkMetricsProjectionService service;

    @BeforeEach
    void setUp() throws SQLException {
        execute("DROP ALL OBJECTS", runtimeSchema(), roundSchema(), recipientSchema(),
                accountStatSchema(), accountUsageSchema());
    }

    @Test
    void twoWorkersWithOverlappingCandidatesNeverDoubleProject() throws Exception {
        insertRuntimeAndRound(7L, 11L, 21L);
        insertSuccessfulRecipients(CONCURRENT_RECIPIENTS);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = workers.submit(() -> projectAfter(start));
            Future<Integer> second = workers.submit(() -> projectAfter(start));
            start.countDown();

            int firstCount = first.get(10, TimeUnit.SECONDS);
            int secondCount = second.get(10, TimeUnit.SECONDS);
            assertThat(firstCount).isBetween(0, HyperlinkMetricsProjectionService.BATCH_SIZE);
            assertThat(secondCount).isBetween(0, HyperlinkMetricsProjectionService.BATCH_SIZE);
            int concurrentlyProjected = firstCount + secondCount;
            assertThat(concurrentlyProjected)
                    .isBetween(HyperlinkMetricsProjectionService.BATCH_SIZE, CONCURRENT_RECIPIENTS);
            assertThat(concurrentlyProjected + service.projectNextBatch())
                    .isEqualTo(CONCURRENT_RECIPIENTS);
        } finally {
            workers.shutdownNow();
        }

        assertThat(queryLong("SELECT send_total FROM hyperlink_task_runtime WHERE hyperlink_task_id=11"))
                .isEqualTo(CONCURRENT_RECIPIENTS);
        assertThat(queryLong("SELECT success_num FROM hyperlink_task_round WHERE id=21"))
                .isEqualTo(CONCURRENT_RECIPIENTS);
        assertThat(queryLong("SELECT send_total FROM hyperlink_task_account_stat "
                + "WHERE hyperlink_task_id=11 AND account_id=41"))
                .isEqualTo(CONCURRENT_RECIPIENTS);
        assertThat(queryLong("SELECT COUNT(*) FROM hyperlink_task_recipient "
                + "WHERE send_status<>metrics_projected_status")).isZero();

        assertThat(service.projectNextBatch()).isZero();
        assertThat(queryLong("SELECT success_num FROM hyperlink_task_runtime WHERE hyperlink_task_id=11"))
                .isEqualTo(CONCURRENT_RECIPIENTS);
    }

    @Test
    void statusTransitionsAreMonotonicAndUnsubmittedFailuresDoNotIncreaseSendTotal()
            throws SQLException {
        insertRuntimeAndRound(7L, 11L, 21L);
        execute("INSERT INTO hyperlink_task_account_usage "
                + "(tenant_id,hyperlink_task_id,account_id,invalid_at) VALUES (7,11,41,900)");
        execute("INSERT INTO hyperlink_task_recipient "
                        + "(id,tenant_id,hyperlink_task_id,hyperlink_task_round_id,account_id,"
                        + "send_status,metrics_projected_status,submitted_at,metrics_projected_at,"
                        + "created_at,updated_at) VALUES "
                        + "(1,7,11,NULL,NULL,6,1,NULL,NULL,100,200),"
                        + "(2,7,11,21,41,3,1,1000,NULL,100,201),"
                        + "(3,7,11,21,41,4,3,1100,150,100,202),"
                        + "(4,7,11,21,41,5,4,1200,160,100,203)");

        assertThat(service.projectNextBatch()).isEqualTo(4);

        assertThat(queryRow("SELECT send_total,success_num,delivered_num,read_num,fail_num,fail_404_num,"
                + "used_account_count,invalid_account_count,last_send_at "
                + "FROM hyperlink_task_runtime WHERE hyperlink_task_id=11"))
                .containsExactly(1L, 1L, 1L, 1L, 1L, 0L, 1L, 1L, 1_000L);
        assertThat(queryRow("SELECT assigned_recipient_count,send_total,success_num,delivered_num,"
                + "read_num,fail_num,last_send_at FROM hyperlink_task_round WHERE id=21"))
                .containsExactly(1L, 1L, 1L, 1L, 1L, 0L, 1_000L);
        assertThat(queryRow("SELECT send_total,success_num,delivered_num,read_num,failed_num,"
                + "first_send_at,last_send_at "
                + "FROM hyperlink_task_account_stat WHERE hyperlink_task_id=11 AND account_id=41"))
                .containsExactly(1L, 1L, 1L, 1L, 0L, 1_000L, 1_000L);
        assertThat(queryRow("SELECT send_total,failed_num FROM hyperlink_task_account_stat "
                + "WHERE hyperlink_task_id=11 AND account_id IS NULL"))
                .containsExactly(0L, 1L);
    }

    private int projectAfter(CountDownLatch start) throws Exception {
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("等待并发投影开始超时");
        }
        return service.projectNextBatch();
    }

    private void insertRuntimeAndRound(long tenantId, long taskId, long roundId) throws SQLException {
        execute("INSERT INTO hyperlink_task_runtime "
                        + "(tenant_id,hyperlink_task_id,send_total,success_num,delivered_num,read_num,"
                        + "fail_num,fail_404_num,created_at,updated_at) VALUES ("
                        + tenantId + "," + taskId + ",0,0,0,0,0,0,100,100)",
                "INSERT INTO hyperlink_task_round "
                        + "(id,tenant_id,hyperlink_task_id,assigned_recipient_count,send_total,"
                        + "success_num,delivered_num,read_num,fail_num,fail_404_num,created_at,updated_at) "
                        + "VALUES (" + roundId + "," + tenantId + "," + taskId
                        + ",0,0,0,0,0,0,0,100,100)");
    }

    private void insertSuccessfulRecipients(int count) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO hyperlink_task_recipient "
                             + "(id,tenant_id,hyperlink_task_id,hyperlink_task_round_id,account_id,"
                             + "send_status,metrics_projected_status,submitted_at,created_at,updated_at) "
                             + "VALUES (?,7,11,21,41,3,1,1000,100,?)")) {
            for (int id = 1; id <= count; id++) {
                statement.setInt(1, id);
                statement.setInt(2, 100 + id);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private long queryLong(String sql) throws SQLException {
        return queryRow(sql).get(0);
    }

    private java.util.List<Long> queryRow(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            java.util.List<Long> values = new java.util.ArrayList<>();
            for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
                values.add(result.getLong(column));
            }
            return values;
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

    private String runtimeSchema() {
        return "CREATE TABLE hyperlink_task_runtime (tenant_id BIGINT NOT NULL, "
                + "hyperlink_task_id BIGINT PRIMARY KEY, send_total BIGINT DEFAULT 0, "
                + "success_num BIGINT DEFAULT 0, delivered_num BIGINT DEFAULT 0, "
                + "read_num BIGINT DEFAULT 0, fail_num BIGINT DEFAULT 0, fail_404_num BIGINT DEFAULT 0, "
                + "used_account_count INT DEFAULT 0, invalid_account_count INT DEFAULT 0, "
                + "last_send_at BIGINT, metrics_updated_at BIGINT, created_at BIGINT, updated_at BIGINT)";
    }

    private String roundSchema() {
        return "CREATE TABLE hyperlink_task_round (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, "
                + "hyperlink_task_id BIGINT NOT NULL, assigned_recipient_count INT DEFAULT 0, "
                + "send_total BIGINT DEFAULT 0, success_num BIGINT DEFAULT 0, delivered_num BIGINT DEFAULT 0, "
                + "read_num BIGINT DEFAULT 0, fail_num BIGINT DEFAULT 0, fail_404_num BIGINT DEFAULT 0, "
                + "last_send_at BIGINT, created_at BIGINT, updated_at BIGINT)";
    }

    private String recipientSchema() {
        return "CREATE TABLE hyperlink_task_recipient (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, "
                + "hyperlink_task_id BIGINT NOT NULL, hyperlink_task_round_id BIGINT, account_id BIGINT, "
                + "send_status TINYINT NOT NULL, metrics_projected_status TINYINT NOT NULL, "
                + "needs_metrics_projection TINYINT GENERATED ALWAYS AS "
                + "(CASE WHEN send_status<>metrics_projected_status THEN 1 ELSE NULL END), "
                + "submitted_at BIGINT, metrics_projected_at BIGINT, created_at BIGINT, updated_at BIGINT, "
                + "INDEX idx_projection(needs_metrics_projection,tenant_id,hyperlink_task_id,updated_at,id))";
    }

    private String accountStatSchema() {
        return "CREATE TABLE hyperlink_task_account_stat (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "tenant_id BIGINT NOT NULL, hyperlink_task_id BIGINT NOT NULL, account_id BIGINT, "
                + "account_bucket_key BIGINT GENERATED ALWAYS AS (COALESCE(account_id,0)), "
                + "send_total BIGINT DEFAULT 0, success_num BIGINT DEFAULT 0, delivered_num BIGINT DEFAULT 0, "
                + "read_num BIGINT DEFAULT 0, failed_num BIGINT DEFAULT 0, fail_404_num BIGINT DEFAULT 0, "
                + "first_send_at BIGINT, last_send_at BIGINT, created_at BIGINT, updated_at BIGINT, "
                + "reconciled_at BIGINT, UNIQUE(tenant_id,hyperlink_task_id,account_bucket_key))";
    }

    private String accountUsageSchema() {
        return "CREATE TABLE hyperlink_task_account_usage (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "tenant_id BIGINT NOT NULL, hyperlink_task_id BIGINT NOT NULL, account_id BIGINT NOT NULL, "
                + "invalid_at BIGINT)";
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:hyperlink_metrics_projection_test;MODE=MySQL;"
                    + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
            source.setUser("sa");
            source.setPassword("");
            return source;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
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
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskRuntimeMapper.xml"),
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskRoundMapper.xml"),
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskAccountStatMapper.xml")
            });
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean HyperlinkTaskRecipientMapper recipientMapper(SqlSessionTemplate value) {
            return value.getMapper(HyperlinkTaskRecipientMapper.class);
        }
        @Bean HyperlinkTaskRuntimeMapper runtimeMapper(SqlSessionTemplate value) {
            return value.getMapper(HyperlinkTaskRuntimeMapper.class);
        }
        @Bean HyperlinkTaskRoundMapper roundMapper(SqlSessionTemplate value) {
            return value.getMapper(HyperlinkTaskRoundMapper.class);
        }
        @Bean HyperlinkTaskAccountStatMapper accountStatMapper(SqlSessionTemplate value) {
            return value.getMapper(HyperlinkTaskAccountStatMapper.class);
        }
        @Bean
        HyperlinkMetricsProjectionService projectionService(HyperlinkTaskRecipientMapper recipients,
                HyperlinkTaskRuntimeMapper runtimes, HyperlinkTaskRoundMapper rounds,
                HyperlinkTaskAccountStatMapper accountStats) {
            return new HyperlinkMetricsProjectionService(recipients, runtimes, rounds, accountStats);
        }
    }
}
