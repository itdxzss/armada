package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientClaimMapper;
import com.armada.hyperlink.task.model.vo.HyperlinkProvisionCandidate;
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
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 使用真实 claim Mapper XML 验证计费恢复候选不会丢失或提前重试。 */
@SpringJUnitConfig(HyperlinkTaskRecipientClaimMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class HyperlinkTaskRecipientClaimMapperH2Test {

    @Autowired
    private DataSource dataSource;
    @Autowired
    private HyperlinkTaskRecipientClaimMapper claimMapper;

    @BeforeEach
    void setUp() throws SQLException {
        executeSql("DROP ALL OBJECTS", claimSchema(), runtimeSchema(), billingSchema());
    }

    @Test
    void releasedClaimReentersStoppedCleanupForEveryBillingFinalizationCrashWindow()
            throws SQLException {
        insertFacts(11, 5, 4, 2, 6, 3, 0L); // settle 超时
        insertFacts(12, 5, 4, 2, 6, 3, 0L); // settle 成功、本地写失败
        insertFacts(13, 5, 4, 2, 3, 0, null); // settled 后、登记 release 前崩溃
        insertFacts(14, 5, 4, 2, 6, 4, 0L); // release 外部结果未知
        insertFacts(15, 5, 1, 2, 6, 3, 0L); // 正常运行任务不能被清理扫描

        assertThat(taskIds(claimMapper.selectCleanupCandidates(100)))
                .containsExactly(11L, 12L, 13L, 14L);
    }

    @Test
    void pendingBillingOperationIsSelectedOnlyAfterRetryTimeForProvisionAndCleanup()
            throws SQLException {
        long future = System.currentTimeMillis() + 600_000L;
        insertFacts(21, 5, 4, 2, 6, 3, future);
        insertFacts(22, 3, 0, 1, 6, 1, future);
        insertFacts(23, 4, 4, 2, 6, 4, future);

        assertThat(claimMapper.selectCleanupCandidates(100)).isEmpty();
        assertThat(claimMapper.selectProvisionCandidates(100)).isEmpty();

        executeSql("UPDATE hyperlink_billing_reservation SET next_retry_at=0");

        assertThat(taskIds(claimMapper.selectCleanupCandidates(100)))
                .containsExactly(21L, 23L);
        assertThat(taskIds(claimMapper.selectProvisionCandidates(100)))
                .containsExactly(22L);
    }

    @Test
    void releasingClaimRequiresRebuildOrTerminalRuntimeState() throws SQLException {
        insertFacts(31, 4, 1, 2, 2, 0, null);
        insertFacts(32, 4, 0, 1, 2, 0, null);
        insertFacts(33, 4, 2, 2, 6, 3, 0L);
        insertFacts(34, 4, 4, 2, 6, 3, 0L);
        insertFacts(35, 4, 0, 2, 2, 0, null);

        assertThat(taskIds(claimMapper.selectCleanupCandidates(100)))
                .containsExactly(32L, 33L, 34L);
    }

    private java.util.List<Long> taskIds(java.util.List<HyperlinkProvisionCandidate> candidates) {
        return candidates.stream().map(HyperlinkProvisionCandidate::taskId).toList();
    }

    private void insertFacts(long taskId, int claimStatus, int runStatus, int provisionStatus,
            int billingStatus, int pendingOperation, Long nextRetryAt) throws SQLException {
        String retry = nextRetryAt == null ? "NULL" : nextRetryAt.toString();
        executeSql("INSERT INTO hyperlink_task_recipient_claim "
                        + "(id, tenant_id, hyperlink_task_id, claim_status, updated_at) VALUES ("
                        + taskId + ", 7, " + taskId + ", " + claimStatus + ", " + taskId + ")",
                "INSERT INTO hyperlink_task_runtime "
                        + "(id, tenant_id, hyperlink_task_id, run_status, provision_status) VALUES ("
                        + taskId + ", 7, " + taskId + ", " + runStatus + ", "
                        + provisionStatus + ")",
                "INSERT INTO hyperlink_billing_reservation "
                        + "(id, tenant_id, hyperlink_task_id, reservation_status, pending_operation, "
                        + "next_retry_at) VALUES (" + taskId + ", 7, " + taskId + ", "
                        + billingStatus + ", " + pendingOperation + ", " + retry + ")");
    }

    private void executeSql(String... statements) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private String claimSchema() {
        return """
                CREATE TABLE hyperlink_task_recipient_claim (
                    id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                    hyperlink_task_id BIGINT NOT NULL, claim_status INT NOT NULL,
                    lease_expires_at BIGINT, updated_at BIGINT NOT NULL)
                """;
    }

    private String runtimeSchema() {
        return """
                CREATE TABLE hyperlink_task_runtime (
                    id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                    hyperlink_task_id BIGINT NOT NULL, run_status INT NOT NULL,
                    provision_status INT NOT NULL)
                """;
    }

    private String billingSchema() {
        return """
                CREATE TABLE hyperlink_billing_reservation (
                    id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                    hyperlink_task_id BIGINT NOT NULL, reservation_status INT NOT NULL,
                    pending_operation INT NOT NULL, next_retry_at BIGINT)
                """;
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            JdbcDataSource h2 = new JdbcDataSource();
            h2.setURL("jdbc:h2:mem:hyperlink_claim_candidates;MODE=MySQL;"
                    + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            h2.setUser("sa");
            h2.setPassword("");
            return h2;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                MybatisPlusInterceptor mybatisPlusInterceptor) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(mybatisPlusInterceptor);
            factory.setMapperLocations(new ClassPathResource(
                    "mapper/hyperlink/task/HyperlinkTaskRecipientClaimMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        HyperlinkTaskRecipientClaimMapper claimMapper(SqlSessionTemplate template) {
            return template.getMapper(HyperlinkTaskRecipientClaimMapper.class);
        }
    }
}
