package com.armada.task.mapper;

import com.armada.task.model.dto.JoinTaskRetryTransition;
import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 Mapper、租户插件及独立事务验证回调普通读和条件迁移。 */
class JoinTaskResultConcurrencyH2Test {
    private JdbcTemplate jdbc;
    private JoinTaskResultMapper mapper;
    private TransactionTemplate tx;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:join_result_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=1500");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE join_task_result (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT, join_task_id BIGINT, account_id BIGINT,
                  status VARCHAR(16), dispatch_state VARCHAR(16), command_id VARCHAR(64), attempt_no INT,
                  group_jid VARCHAR(64), reason VARCHAR(255), joined_at BIGINT, next_execute_at BIGINT,
                  updated_at BIGINT, admin_status INT DEFAULT 0, admin_next_execute_at BIGINT)
                """);
        jdbc.update("""
                INSERT INTO join_task_result
                  (id,tenant_id,join_task_id,account_id,status,dispatch_state,command_id,attempt_no,updated_at)
                VALUES (1,7,10,30,'PENDING','SUBMITTED','cmd-1',1,100)
                """);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setConfiguration(configuration);
        factory.setDataSource(ds);
        MyBatisConfig config = new MyBatisConfig();
        factory.setPlugins(config.mybatisPlusInterceptor(config.tenantLineHandler()));
        factory.setMapperLocations(new ClassPathResource("mapper/task/JoinTaskResultMapper.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(JoinTaskResultMapper.class);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        TenantContext.set(7L);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void callbackReadDoesNotWaitForAnotherTransactionsWriteLock() {
        var executor = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(status -> {
                jdbc.update("UPDATE join_task_result SET updated_at=200 WHERE id=1");
                var read = executor.submit(() -> {
                    TenantContext.set(7L);
                    try {
                        return tx.execute(ignored -> mapper.selectSubmitted(1L, "cmd-1", 1));
                    } finally {
                        TenantContext.clear();
                    }
                });
                try {
                    assertThat(read.get(1, TimeUnit.SECONDS)).isNotNull();
                } catch (Exception ex) {
                    throw new AssertionError("回调读取不能等待另一事务释放写锁", ex);
                }
                status.setRollbackOnly();
            });
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentCallbacksOnlyOneCanFinishTheCurrentAttempt() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var bothRead = new CyclicBarrier(2);
        java.util.concurrent.Callable<Integer> complete = () -> {
            TenantContext.set(7L);
            try {
                return tx.execute(status -> {
                    assertThat(mapper.selectSubmitted(1L, "cmd-1", 1)).isNotNull();
                    try {
                        bothRead.await(3, TimeUnit.SECONDS);
                    } catch (Exception ex) {
                        throw new AssertionError(ex);
                    }
                    return mapper.markTerminalSuccess(1L, "123@g.us", 300L, "cmd-1", 1);
                });
            } finally {
                TenantContext.clear();
            }
        };
        try {
            var first = executor.submit(complete);
            var second = executor.submit(complete);
            assertThat(java.util.List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(0, 1);
            assertThat(jdbc.queryForObject("SELECT status FROM join_task_result WHERE id=1", String.class))
                    .isEqualTo("SUCCESS");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void staleAttemptCannotFinishOrRetryANewerSubmittedCommand() {
        assertThat(mapper.selectSubmitted(1L, "cmd-1", 1)).isNotNull();
        jdbc.update("UPDATE join_task_result SET command_id='cmd-2',attempt_no=2 WHERE id=1");
        assertThat(mapper.markTerminalSuccess(1L, "123@g.us", 300L, "cmd-1", 1)).isZero();
        assertThat(mapper.markTerminalFailure(1L, "OLD_FAILURE", 300L, "cmd-1", 1)).isZero();
        assertThat(mapper.markRetry(new JoinTaskRetryTransition(1L, "OLD_RETRY", 500L, 300L, "cmd-1", 1))).isZero();
        TenantContext.set(8L);
        assertThat(mapper.selectSubmitted(1L, "cmd-2", 2)).isNull();
        assertThat(mapper.markTerminalSuccess(1L, "123@g.us", 300L, "cmd-2", 2)).isZero();
        TenantContext.set(7L);
        assertThat(mapper.selectSubmitted(1L, "cmd-2", 2)).isNotNull();
    }

    @Test
    void currentAttemptCanBeRescheduledOnlyOnce() {
        var transition = new JoinTaskRetryTransition(1L, "TEMPORARY_FAILURE", 500L, 300L, "cmd-1", 1);
        assertThat(mapper.markRetry(transition)).isOne();
        assertThat(mapper.markRetry(transition)).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT status, dispatch_state, command_id, attempt_no, next_execute_at
                FROM join_task_result WHERE id=1
                """))
                .containsEntry("status", "PENDING")
                .containsEntry("dispatch_state", "WAITING")
                .containsEntry("command_id", null)
                .containsEntry("attempt_no", 1)
                .containsEntry("next_execute_at", 500L);
    }

    @Test
    void failedTransactionLeavesTheResultAvailableForRedelivery() {
        tx.executeWithoutResult(status -> {
            assertThat(mapper.markTerminalSuccess(1L, "123@g.us", 300L, "cmd-1", 1)).isEqualTo(1);
            status.setRollbackOnly();
        });
        assertThat(mapper.selectSubmitted(1L, "cmd-1", 1)).isNotNull();
        assertThat(mapper.markTerminalSuccess(1L, "123@g.us", 400L, "cmd-1", 1)).isEqualTo(1);
    }
}
