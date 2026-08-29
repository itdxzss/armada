package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRoundMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRuntimeMapper;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRuntime;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskRoundStatus;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskRunStatus;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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

/** 真实 Runtime/Round Mapper XML 的状态、时钟和 UNKNOWN 候选闭环。 */
@SpringJUnitConfig(HyperlinkRuntimeRoundMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class HyperlinkRuntimeRoundMapperH2Test {

    @Autowired
    private DataSource dataSource;
    @Autowired
    private HyperlinkTaskRuntimeMapper runtimeMapper;
    @Autowired
    private HyperlinkTaskRoundMapper roundMapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        execute("DROP ALL OBJECTS", runtimeSchema(), roundSchema(), recipientSchema(), usageSchema());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void pauseResumeAndNaturalCompletionAccumulateOnlyActiveSeconds() throws SQLException {
        execute("INSERT INTO hyperlink_task_runtime "
                + "(id,tenant_id,hyperlink_task_id,is_enabled,run_status,provision_status,"
                + "execution_duration_sec,active_since_at,actual_concurrency,updated_at) "
                + "VALUES (1,7,11,TRUE,1,2,0,1000,2,1000)");

        assertThat(runtimeMapper.transition(11L, true, HyperlinkTaskRunStatus.RUNNING.code(),
                true, HyperlinkTaskRunStatus.PAUSED.code(), 2, 11_000L)).isEqualTo(1);
        HyperlinkTaskRuntime paused = runtimeMapper.selectByTaskId(11L);
        assertThat(paused.getExecutionDurationSec()).isEqualTo(10L);
        assertThat(paused.getActiveSinceAt()).isNull();

        assertThat(runtimeMapper.transition(11L, true, HyperlinkTaskRunStatus.PAUSED.code(),
                true, HyperlinkTaskRunStatus.RUNNING.code(), 2, 20_000L)).isEqualTo(1);
        assertThat(runtimeMapper.markCompletedIfIdle(11L, 25_000L)).isEqualTo(1);

        HyperlinkTaskRuntime completed = runtimeMapper.selectByTaskId(11L);
        assertThat(completed.getRunStatus()).isEqualTo(HyperlinkTaskRunStatus.COMPLETED.code());
        assertThat(completed.getExecutionDurationSec()).isEqualTo(15L);
        assertThat(completed.getFinishedAt()).isEqualTo(25_000L);
        assertThat(completed.getActualConcurrency()).isZero();
    }

    @Test
    void plannedEndStopsAtTheGivenClockAndPreservesElapsedRuntime() throws SQLException {
        execute("INSERT INTO hyperlink_task_runtime "
                + "(id,tenant_id,hyperlink_task_id,is_enabled,run_status,provision_status,"
                + "execution_duration_sec,active_since_at,actual_concurrency,updated_at) "
                + "VALUES (2,7,12,TRUE,1,2,4,100000,3,100000)");

        assertThat(runtimeMapper.stopAtDeadline(12L, 109_500L)).isEqualTo(1);

        HyperlinkTaskRuntime stopped = runtimeMapper.selectByTaskId(12L);
        assertThat(stopped.getRunStatus()).isEqualTo(HyperlinkTaskRunStatus.STOPPED.code());
        assertThat(stopped.getExecutionDurationSec()).isEqualTo(13L);
        assertThat(stopped.getFinishedAt()).isEqualTo(109_500L);
        assertThat(stopped.getActiveSinceAt()).isNull();
    }

    @Test
    void roundCannotCompleteUntilItsSingleLogicalSendLeavesSending() throws SQLException {
        execute("INSERT INTO hyperlink_task_round "
                        + "(id,tenant_id,hyperlink_task_id,round_no,round_status,scheduled_at,"
                        + "next_dispatch_at,actual_concurrency,version,created_at,updated_at) "
                        + "VALUES (21,7,11,1,5,1000,1000,1,1,1000,1000)",
                "INSERT INTO hyperlink_task_recipient "
                        + "(id,tenant_id,hyperlink_task_id,hyperlink_task_round_id,send_status,"
                        + "command_id,protocol_backend,submitted_at,next_dispatch_at) "
                        + "VALUES (31,7,11,21,2,'hl:7:11:31',1,1000,2000)");

        assertThat(roundMapper.markCompleted(21L, 3_000L)).isZero();
        execute("UPDATE hyperlink_task_recipient SET send_status=3 WHERE id=31");
        assertThat(roundMapper.markCompleted(21L, 4_000L)).isEqualTo(1);

        assertThat(queryLong("SELECT round_status FROM hyperlink_task_round WHERE id=21"))
                .isEqualTo(HyperlinkTaskRoundStatus.COMPLETED.code());
        assertThat(queryLong("SELECT finished_at FROM hyperlink_task_round WHERE id=21"))
                .isEqualTo(4_000L);
        assertThat(queryLong("SELECT actual_concurrency FROM hyperlink_task_round WHERE id=21"))
                .isZero();
    }

    @Test
    void futurePlannedRoundIsNeitherLifecycleNorDispatchCandidate() throws SQLException {
        execute("INSERT INTO hyperlink_task_runtime "
                        + "(id,tenant_id,hyperlink_task_id,is_enabled,run_status,provision_status,"
                        + "execution_duration_sec,actual_concurrency,updated_at) "
                        + "VALUES (1,7,11,TRUE,1,2,0,0,1000)",
                "INSERT INTO hyperlink_task_round "
                        + "(id,tenant_id,hyperlink_task_id,round_no,round_status,scheduled_at,"
                        + "next_dispatch_at,actual_concurrency,version,created_at,updated_at) "
                        + "VALUES (21,7,11,2,1,5000,5000,0,1,1000,1000)");

        assertThat(roundMapper.selectLifecycleCandidates(4_999L, 10)).isEmpty();
        assertThat(roundMapper.selectDispatchCandidates(4_999L, 10)).isEmpty();
        assertThat(roundMapper.selectLifecycleCandidates(5_000L, 10))
                .extracting(candidate -> candidate.taskId()).containsExactly(11L);

        execute("UPDATE hyperlink_task_round SET round_status=3 WHERE id=21");
        assertThat(roundMapper.selectDispatchCandidates(4_999L, 10)).isEmpty();
        assertThat(roundMapper.selectDispatchCandidates(5_000L, 10))
                .extracting(candidate -> candidate.taskId()).containsExactly(11L);
    }

    @Test
    void noAccountRoundRespectsItsThirtySecondRecheckGate() throws SQLException {
        execute("INSERT INTO hyperlink_task_runtime "
                        + "(id,tenant_id,hyperlink_task_id,is_enabled,run_status,provision_status,"
                        + "execution_duration_sec,actual_concurrency,updated_at) "
                        + "VALUES (1,7,11,TRUE,1,2,0,0,1000)",
                "INSERT INTO hyperlink_task_round "
                        + "(id,tenant_id,hyperlink_task_id,round_no,round_status,scheduled_at,"
                        + "next_dispatch_at,actual_concurrency,version,created_at,updated_at) "
                        + "VALUES (21,7,11,1,10,1000,31000,0,1,1000,1000)");

        assertThat(roundMapper.selectLifecycleCandidates(30_999L, 10)).isEmpty();
        assertThat(roundMapper.selectLifecycleCandidates(31_000L, 10))
                .extracting(candidate -> candidate.taskId()).containsExactly(11L);
    }

    @Test
    void readyFirstRoundCanMoveBetweenNowAndScheduledAndUsesTheEditedDelay() throws SQLException {
        execute("INSERT INTO hyperlink_task_runtime "
                        + "(id,tenant_id,hyperlink_task_id,is_enabled,run_status,provision_status,"
                        + "execution_duration_sec,actual_concurrency,updated_at) "
                        + "VALUES (1,7,11,TRUE,0,2,0,0,1000)",
                "INSERT INTO hyperlink_task_round "
                        + "(id,tenant_id,hyperlink_task_id,round_no,round_status,scheduled_at,"
                        + "next_dispatch_at,actual_concurrency,send_total,version,created_at,updated_at) "
                        + "VALUES (21,7,11,1,3,3601000,3601000,1,0,1,1000,1000)");

        assertThat(roundMapper.rescheduleUnconsumedFirstRound(11L, 2_000L, 2_000L)).isEqualTo(1);
        assertThat(queryLong("SELECT scheduled_at FROM hyperlink_task_round WHERE id=21"))
                .isEqualTo(2_000L);
        assertThat(queryLong("SELECT next_dispatch_at FROM hyperlink_task_round WHERE id=21"))
                .isEqualTo(2_000L);

        long scheduled60 = 2_000L + 60L * 60_000L;
        assertThat(roundMapper.rescheduleUnconsumedFirstRound(11L, scheduled60, 2_000L))
                .isEqualTo(1);
        long scheduled120 = 2_000L + 120L * 60_000L;
        assertThat(roundMapper.rescheduleUnconsumedFirstRound(11L, scheduled120, 2_000L))
                .isEqualTo(1);

        assertThat(roundMapper.selectStartCandidates(scheduled60, 10)).isEmpty();
        assertThat(roundMapper.selectStartCandidates(scheduled120, 10))
                .extracting(candidate -> candidate.taskId()).containsExactly(11L);
    }

    @Test
    void noAccountFirstRoundCanRescheduleAndStillExcludesStartedOrLaterRounds() throws SQLException {
        execute("INSERT INTO hyperlink_task_runtime "
                        + "(id,tenant_id,hyperlink_task_id,is_enabled,run_status,provision_status,"
                        + "execution_duration_sec,actual_concurrency,updated_at) "
                        + "VALUES (1,7,11,TRUE,0,2,0,0,1000)",
                "INSERT INTO hyperlink_task_round "
                        + "(id,tenant_id,hyperlink_task_id,round_no,round_status,scheduled_at,"
                        + "next_dispatch_at,actual_concurrency,send_total,version,created_at,updated_at) "
                        + "VALUES (21,7,11,1,10,3601000,3601000,0,0,1,1000,1000)");

        assertThat(roundMapper.rescheduleUnconsumedFirstRound(11L, 2_000L, 2_000L)).isEqualTo(1);
        assertThat(queryLong("SELECT scheduled_at FROM hyperlink_task_round WHERE id=21"))
                .isEqualTo(2_000L);
        long scheduled60 = 2_000L + 60L * 60_000L;
        assertThat(roundMapper.rescheduleUnconsumedFirstRound(11L, scheduled60, 2_000L))
                .isEqualTo(1);
        long scheduled120 = 2_000L + 120L * 60_000L;
        assertThat(roundMapper.rescheduleUnconsumedFirstRound(11L, scheduled120, 2_000L))
                .isEqualTo(1);
        assertThat(roundMapper.selectStartCandidates(scheduled60, 10)).isEmpty();
        assertThat(roundMapper.selectStartCandidates(scheduled120, 10))
                .extracting(candidate -> candidate.taskId()).containsExactly(11L);

        assertThat(roundMapper.markStarted(21L, scheduled120)).isEqualTo(1);
        assertThat(roundMapper.rescheduleUnconsumedFirstRound(11L, scheduled120 + 1, scheduled120))
                .isZero();
        execute("UPDATE hyperlink_task_round SET started_at=NULL, scheduled_at=2000, "
                + "next_dispatch_at=2000 WHERE id=21");
        assertThat(roundMapper.rescheduleUnconsumedFirstRound(11L, 5_000L, 2_001L)).isEqualTo(1);
        assertThat(roundMapper.markStarted(21L, 2_001L)).isZero();
        assertThat(roundMapper.markStarted(21L, 5_000L)).isEqualTo(1);
        execute("UPDATE hyperlink_task_round SET round_no=2, started_at=NULL WHERE id=21");
        assertThat(roundMapper.rescheduleUnconsumedFirstRound(11L, scheduled120 + 1, scheduled120))
                .isZero();
    }

    @Test
    void firstRoundRescheduleAndDueStartUseMutuallyExclusiveStateCas() throws SQLException {
        execute("INSERT INTO hyperlink_task_runtime "
                        + "(id,tenant_id,hyperlink_task_id,is_enabled,run_status,provision_status,"
                        + "execution_duration_sec,actual_concurrency,updated_at) "
                        + "VALUES (1,7,11,TRUE,0,2,0,0,1000)",
                "INSERT INTO hyperlink_task_round "
                        + "(id,tenant_id,hyperlink_task_id,round_no,round_status,scheduled_at,"
                        + "next_dispatch_at,actual_concurrency,send_total,version,created_at,updated_at) "
                        + "VALUES (21,7,11,1,3,1000,1000,1,0,1,1000,1000)");

        assertThat(roundMapper.markStarted(21L, 1_000L)).isEqualTo(1);
        assertThat(roundMapper.markStarted(21L, 1_000L)).isZero();
        assertThat(roundMapper.rescheduleUnconsumedFirstRound(11L, 5_000L, 1_001L)).isZero();

        execute("UPDATE hyperlink_task_round SET started_at=NULL, scheduled_at=1000, "
                + "next_dispatch_at=1000, version=1 WHERE id=21");
        assertThat(roundMapper.rescheduleUnconsumedFirstRound(11L, 5_000L, 1_001L)).isEqualTo(1);
        assertThat(roundMapper.markStarted(21L, 1_001L)).isZero();
        assertThat(roundMapper.markStarted(21L, 5_000L)).isEqualTo(1);

        execute("UPDATE hyperlink_task_round SET round_no=2, started_at=NULL, "
                + "scheduled_at=1000, next_dispatch_at=1000, version=1 WHERE id=21");
        assertThat(roundMapper.rescheduleUnconsumedFirstRound(11L, 5_000L, 1_001L)).isZero();
        execute("UPDATE hyperlink_task_round SET round_no=1, round_status=4 WHERE id=21");
        assertThat(roundMapper.rescheduleUnconsumedFirstRound(11L, 5_000L, 1_001L)).isZero();
        execute("UPDATE hyperlink_task_round SET round_status=3, send_total=1 WHERE id=21");
        assertThat(roundMapper.rescheduleUnconsumedFirstRound(11L, 5_000L, 1_001L)).isZero();
    }

    @Test
    void completionIgnoresAnotherTenantsSameNumericTaskFacts() throws SQLException {
        execute("INSERT INTO hyperlink_task_runtime "
                        + "(id,tenant_id,hyperlink_task_id,is_enabled,run_status,provision_status,"
                        + "execution_duration_sec,actual_concurrency,updated_at) "
                        + "VALUES (1,7,11,TRUE,1,2,0,0,1000)",
                "INSERT INTO hyperlink_task_round "
                        + "(id,tenant_id,hyperlink_task_id,round_no,round_status,scheduled_at,"
                        + "next_dispatch_at,actual_concurrency,version,created_at,updated_at) "
                        + "VALUES (21,8,11,1,3,1000,1000,1,1,1000,1000)",
                "INSERT INTO hyperlink_task_recipient "
                        + "(id,tenant_id,hyperlink_task_id,hyperlink_task_round_id,send_status,"
                        + "command_id,protocol_backend,submitted_at,next_dispatch_at) "
                        + "VALUES (31,8,11,21,1,NULL,NULL,NULL,0)");

        assertThat(runtimeMapper.selectCompletionCandidates(10))
                .extracting(candidate -> candidate.tenantId() + ":" + candidate.taskId())
                .containsExactly("7:11");
    }

    private long queryLong(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
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
        return """
                CREATE TABLE hyperlink_task_runtime (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  hyperlink_task_id BIGINT NOT NULL UNIQUE, is_enabled BOOLEAN NOT NULL,
                  run_status INT NOT NULL, provision_status INT NOT NULL,
                  current_round_id BIGINT, current_round_no BIGINT, started_at BIGINT,
                  finished_at BIGINT, execution_duration_sec BIGINT DEFAULT 0 NOT NULL,
                  active_since_at BIGINT, actual_concurrency INT DEFAULT 0 NOT NULL,
                  updated_at BIGINT NOT NULL)
                """;
    }

    private String roundSchema() {
        return """
                CREATE TABLE hyperlink_task_round (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  hyperlink_task_id BIGINT NOT NULL, round_no BIGINT NOT NULL,
                  round_status INT NOT NULL, scheduled_at BIGINT NOT NULL,
                  next_dispatch_at BIGINT NOT NULL, assigned_recipient_count INT DEFAULT 0,
                  selected_account_count INT DEFAULT 0, actual_concurrency INT DEFAULT 0,
                  send_total BIGINT DEFAULT 0, dispatch_completed_at BIGINT,
                  started_at BIGINT, last_send_at BIGINT, finished_at BIGINT,
                  version INT NOT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL)
                """;
    }

    private String recipientSchema() {
        return """
                CREATE TABLE hyperlink_task_recipient (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  hyperlink_task_id BIGINT NOT NULL, hyperlink_task_round_id BIGINT,
                  send_status INT NOT NULL, command_id VARCHAR(64), protocol_backend INT,
                  submitted_at BIGINT, next_dispatch_at BIGINT)
                """;
    }

    private String usageSchema() {
        return """
                CREATE TABLE hyperlink_task_account_usage (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  hyperlink_task_id BIGINT NOT NULL, in_flight_count INT DEFAULT 0 NOT NULL)
                """;
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:hyperlink_runtime_round;MODE=MySQL;"
                    + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            source.setUser("sa");
            source.setPassword("");
            return source;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                MybatisPlusInterceptor interceptor) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            Resource[] locations = {
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskRuntimeMapper.xml"),
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskRoundMapper.xml")
            };
            factory.setMapperLocations(locations);
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        HyperlinkTaskRuntimeMapper runtimeMapper(SqlSessionTemplate template) {
            return template.getMapper(HyperlinkTaskRuntimeMapper.class);
        }

        @Bean
        HyperlinkTaskRoundMapper roundMapper(SqlSessionTemplate template) {
            return template.getMapper(HyperlinkTaskRoundMapper.class);
        }
    }
}
