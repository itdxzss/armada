package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.dto.PullTaskExecutionLease;
import com.armada.task.model.dto.PullTaskExecutionSlotClaim;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实 Mapper 的名额竞争、事务回滚与租户隔离；InnoDB 范围锁另以 SQL 形状约束。 */
@SpringJUnitConfig(PullTaskExecutionSlotMapperTest.Config.class)
@TestExecutionListeners(listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskExecutionSlotMapperTest {

    @Autowired private DataSource dataSource;
    @Autowired private PullTaskMapper mapper;
    @Autowired private PlatformTransactionManager transactionManager;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() throws Exception {
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(transactionManager);
        jdbc.update("""
                INSERT INTO pull_task
                (id,tenant_id,task_type,task_name,mode,status,version,config_json,created_at,updated_at)
                VALUES (100,7,'STANDARD','slot-test','NORMAL_LINK','EXECUTING',1,'{}',100,100)
                """);
        for (int seq = 1; seq <= 3; seq++) {
            jdbc.update("""
                    INSERT INTO pull_task_group_execution
                    (id,tenant_id,task_id,seq,source_file_index,source_file_name,execution_status,
                     stage,version,lock_owner,lock_expires_at,created_at,updated_at)
                    VALUES (?,7,100,?,?,'synthetic.txt',1,2,1,'worker',10000,100,100)
                    """, seq, seq, seq);
        }
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void slotVersionUpdateMustNotReadExecutionTableOrTakeRangeLocks() throws Exception {
        String xml = Files.readString(Path.of("src/main/resources/mapper/task/PullTaskMapper.xml"));
        var updates = java.util.regex.Pattern.compile("(?s)<update\\b[^>]*>.*?</update>").matcher(xml);
        while (updates.find()) {
            assertThat(updates.group())
                    .as("父任务 UPDATE 不得跨表查询执行行，避免统计范围锁阻挡封群后继 INSERT")
                    .doesNotContain("pull_task_group_execution");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void startingAndRecoveringRowsShareTheSameLimit(int waitingStatus) {
        jdbc.update("UPDATE pull_task_group_execution SET execution_status=?", waitingStatus);
        assertThat(start(1, waitingStatus, 1, 1)).isEqualTo(1);
        assertThat(start(2, waitingStatus, 2, 1)).isZero();
        assertThat(running()).isEqualTo(1);
        assertThat(version()).isEqualTo(2);
    }

    @Test
    void largerLimitStillAllowsMultipleRowsWithoutExceedingIt() {
        assertThat(start(1, 1, 1, 2)).isEqualTo(1);
        assertThat(start(2, 1, 2, 2)).isEqualTo(1);
        assertThat(start(3, 1, 3, 2)).isZero();
        assertThat(running()).isEqualTo(2);
    }

    @Test
    void staleParentVersionCannotAuthorizeAnotherStartAfterConcurrentCommit() {
        var stale = claim(2, 1, 1, 1);
        assertThat(start(1, 1, 1, 1)).isEqualTo(1);
        Integer acquired = transaction.execute(status -> mapper.acquireExecutionSlot(stale));
        assertThat(acquired).isZero();
        assertThat(running()).isEqualTo(1);
    }

    @Test
    void aStartCommittedBetweenSnapshotCheckAndVersionUpdateRejectsTheEarlierCheck() throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        try {
            Integer acquired = transaction.execute(status -> {
                var checked = claim(2, 1, 1, 1);
                assertThat(mapper.countAvailableExecutionSlot(checked)).isEqualTo(1);
                var winner = pool.submit(() -> {
                    TenantContext.set(7L);
                    try {
                        return start(1, 1, 1, 1);
                    } finally {
                        TenantContext.clear();
                    }
                });
                try {
                    assertThat(winner.get(5, TimeUnit.SECONDS)).isEqualTo(1);
                } catch (Exception ex) {
                    throw new IllegalStateException("并发启动未能完成", ex);
                }
                // 不能因先前检查通过就直接启动；必须用同一个父版本拒绝这次陈旧授权。
                return mapper.advanceExecutionSlotVersion(checked);
            });
            assertThat(acquired).isZero();
        } finally {
            pool.shutdownNow();
        }
        assertThat(running()).isEqualTo(1);
        assertThat(version()).isEqualTo(2);
    }

    @Test
    void candidateAndParentGuardsAreRetained() {
        jdbc.update("UPDATE pull_task_group_execution SET lock_expires_at=500 WHERE id=1");
        assertThat(start(1, 1, 1, 1)).isZero();
        jdbc.update("UPDATE pull_task_group_execution SET execution_status=5 WHERE id=2");
        assertThat(start(2, 1, 1, 1)).isZero();
        jdbc.update("UPDATE pull_task SET status='PAUSED'");
        assertThat(start(3, 1, 1, 1)).isZero();
        assertThat(version()).isEqualTo(1);
    }

    @Test
    void anotherTenantCannotInspectOrAcquireTheSlot() {
        TenantContext.set(8L);
        assertThat(start(1, 1, 1, 1)).isZero();
        assertThat(version()).isEqualTo(1);
    }

    @Test
    void rollbackPreservesTheCandidateAndAllowsRetryWithTheSameIdentity() {
        transaction.executeWithoutResult(status -> {
            assertThat(mapper.acquireExecutionSlot(claim(1, 1, 1, 1))).isEqualTo(1);
            jdbc.update("UPDATE pull_task_group_execution SET execution_status=2 WHERE id=1");
            status.setRollbackOnly();
        });
        assertThat(version()).isEqualTo(1);
        assertThat(running()).isZero();
        assertThat(start(1, 1, 1, 1)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pull_task_group_execution", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void aConcurrentStarterWaitsForCommitThenRejectsItsStaleVersion() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch slotHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch contenderReady = new CountDownLatch(1);
        try {
            var winner = pool.submit(() -> {
                TenantContext.set(7L);
                try {
                    return transaction.execute(status -> {
                        int acquired = mapper.acquireExecutionSlot(claim(1, 1, 1, 1));
                        assertThat(acquired).isEqualTo(1);
                        jdbc.update("UPDATE pull_task_group_execution SET execution_status=2 WHERE id=1");
                        slotHeld.countDown();
                        await(release);
                        return acquired;
                    });
                } finally {
                    TenantContext.clear();
                }
            });
            assertThat(slotHeld.await(5, TimeUnit.SECONDS)).isTrue();
            var contender = pool.submit(() -> {
                TenantContext.set(7L);
                try {
                    contenderReady.countDown();
                    return start(2, 1, 1, 1);
                } finally {
                    TenantContext.clear();
                }
            });
            assertThat(contenderReady.await(5, TimeUnit.SECONDS)).isTrue();
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> contender.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            assertThat(winner.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(contender.get(5, TimeUnit.SECONDS)).isZero();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(running()).isEqualTo(1);
        assertThat(version()).isEqualTo(2);
    }

    private int start(long executionId, int waitingStatus, int expectedVersion, int limit) {
        return transaction.execute(status -> {
            int acquired = mapper.acquireExecutionSlot(claim(executionId, waitingStatus, expectedVersion, limit));
            if (acquired == 1) {
                jdbc.update("UPDATE pull_task_group_execution SET execution_status=2 WHERE id=?", executionId);
            }
            return acquired;
        });
    }

    private PullTaskExecutionSlotClaim claim(long id, int status, int version, int limit) {
        return new PullTaskExecutionSlotClaim(
                new PullTaskExecutionSlotClaim.Candidate(100, id, status, 2,
                        new PullTaskExecutionLease("worker", 1)),
                new PullTaskExecutionSlotClaim.Parent(version, "STANDARD", "NORMAL_LINK", "EXECUTING"),
                new PullTaskExecutionSlotClaim.Policy(2, limit), 600);
    }

    private int running() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM pull_task_group_execution WHERE execution_status=2",
                Integer.class);
    }

    private int version() {
        return jdbc.queryForObject("SELECT version FROM pull_task WHERE id=100", Integer.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("slot transaction barrier timed out");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class Config {
        @Bean DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_execution_slot_regression");
        }
        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(dataSource, interceptor,
                    "mapper/task/PullTaskMapper.xml");
        }
        @Bean PullTaskMapper mapper(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory).getMapper(PullTaskMapper.class);
        }
    }
}
