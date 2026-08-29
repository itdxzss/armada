package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.armada.boot.config.MyBatisConfig;
import com.armada.hyperlink.data.mapper.DataPackageMapper;
import com.armada.hyperlink.data.mapper.DataPackagePhoneMapper;
import com.armada.hyperlink.data.mapper.DataPackageStatMapper;
import com.armada.hyperlink.data.service.DataPackageRecipientClaimService;
import com.armada.hyperlink.data.service.impl.DataPackageRecipientClaimServiceImpl;
import com.armada.hyperlink.task.mapper.HyperlinkTaskAccountUsageMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientClaimMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRoundAccountMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRoundMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRuntimeMapper;
import com.armada.hyperlink.task.model.dto.HyperlinkTaskActionDTO;
import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRuntime;
import com.armada.hyperlink.task.model.enums.HyperlinkProvisionStatus;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskAction;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskMode;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskRunStatus;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskMutationReceiptVO;
import com.armada.hyperlink.task.port.HyperlinkTaskAuditPort;
import com.armada.hyperlink.task.service.HyperlinkCleanupStartService;
import com.armada.hyperlink.task.service.HyperlinkProvisionFactService;
import com.armada.hyperlink.task.service.HyperlinkRecipientCleanupService;
import com.armada.hyperlink.task.service.HyperlinkRoundAccountSelectionService;
import com.armada.hyperlink.task.service.HyperlinkRoundLifecycleService;
import com.armada.hyperlink.task.service.HyperlinkShortLinkGuard;
import com.armada.hyperlink.task.service.HyperlinkTaskActionService;
import com.armada.hyperlink.task.service.HyperlinkTaskQuoteGuardService;
import com.armada.hyperlink.task.service.HyperlinkTaskStoreService;
import com.armada.shared.security.AuthPrincipal;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** MySQL 8.4 对 runtime fence、生命周期锁序和 STOP 精确批次的真实 InnoDB 门禁。 */
@EnabledIfSystemProperty(named = "armada.hyperlink.mysql-it", matches = "true")
@Testcontainers
class HyperlinkRuntimeConcurrencyMySqlTest {
    private static final long TENANT_ID = 7L;
    private static final long TASK_ID = 11L;
    private static final long ROUND_ID = 21L;

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("armada_hyperlink_runtime")
            .withUsername("armada")
            .withPassword("armada")
            .withCommand("--transaction-isolation=READ-COMMITTED");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static HyperlinkTaskRuntimeMapper runtimeMapper;
    private static HyperlinkTaskRoundMapper roundMapper;
    private static HyperlinkTaskRecipientMapper recipientMapper;
    private static HyperlinkTaskRecipientClaimMapper claimMapper;
    private static DataPackagePhoneMapper phoneMapper;
    private static DataPackageStatMapper statMapper;

    @BeforeAll
    static void configureProductionMappers() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(MYSQL.getJdbcUrl());
        dataSource.setUsername(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        createSchema();
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        SqlSessionTemplate template = buildSqlSessionTemplate(dataSource);
        runtimeMapper = template.getMapper(HyperlinkTaskRuntimeMapper.class);
        roundMapper = template.getMapper(HyperlinkTaskRoundMapper.class);
        recipientMapper = template.getMapper(HyperlinkTaskRecipientMapper.class);
        claimMapper = template.getMapper(HyperlinkTaskRecipientClaimMapper.class);
        phoneMapper = template.getMapper(DataPackagePhoneMapper.class);
        statMapper = template.getMapper(DataPackageStatMapper.class);
    }

    @BeforeEach
    void resetRows() {
        jdbc.update("DELETE FROM data_package_phone");
        jdbc.update("DELETE FROM data_package_stat");
        jdbc.update("DELETE FROM hyperlink_task_recipient");
        jdbc.update("DELETE FROM hyperlink_task_recipient_claim");
        jdbc.update("DELETE FROM hyperlink_task_round");
        jdbc.update("DELETE FROM hyperlink_task_runtime");
        jdbc.update("""
                INSERT INTO hyperlink_task_runtime
                  (hyperlink_task_id,tenant_id,is_enabled,run_status,provision_status,
                   current_round_id,current_round_no,actual_concurrency,execution_duration_sec,
                   active_since_at,created_at,updated_at)
                VALUES (11,7,1,1,2,21,1,1,0,1000,1000,1000)
                """);
        jdbc.update("""
                INSERT INTO hyperlink_task_round
                  (id,tenant_id,hyperlink_task_id,round_no,round_status,scheduled_at,
                   next_dispatch_at,assigned_recipient_count,selected_account_count,
                   actual_concurrency,version,created_at,updated_at)
                VALUES (21,7,11,1,10,0,0,0,0,0,1,1000,1000)
                """);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void sharedDispatchFencesCoexistAndRuntimeWriterWaitsUntilBothCommit() throws Exception {
        CountDownLatch bothReadersLocked = new CountDownLatch(2);
        CountDownLatch releaseReaders = new CountDownLatch(1);
        CountDownLatch writerStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<Integer> first = executor.submit(() -> holdSharedFence(
                    bothReadersLocked, releaseReaders));
            Future<Integer> second = executor.submit(() -> holdSharedFence(
                    bothReadersLocked, releaseReaders));
            assertThat(bothReadersLocked.await(5, TimeUnit.SECONDS))
                    .as("两个 FOR SHARE 必须可以同时持有")
                    .isTrue();

            Future<Integer> writer = executor.submit(() -> tenantTransaction(() -> {
                writerStarted.countDown();
                return runtimeMapper.transition(TASK_ID, true,
                        HyperlinkTaskRunStatus.RUNNING.code(), true,
                        HyperlinkTaskRunStatus.PAUSED.code(), 2, 2_000L);
            }));
            assertThat(writerStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> writer.get(300, TimeUnit.MILLISECONDS))
                    .as("runtime UPDATE 必须等待全部共享派发事务退出")
                    .isInstanceOf(TimeoutException.class);

            releaseReaders.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(writer.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(tenantTransaction(() -> runtimeMapper
                    .selectByTaskIdForShare(TENANT_ID, TASK_ID).getRunStatus()))
                    .isEqualTo(HyperlinkTaskRunStatus.PAUSED.code());
        } finally {
            releaseReaders.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void pauseActionAndRoundReplenishFinishWithoutDeadlock() throws Exception {
        CountDownLatch lifecycleHasRuntime = new CountDownLatch(1);
        CountDownLatch actionAttemptsTransition = new CountDownLatch(1);
        HyperlinkTaskRuntimeMapper coordinatedRuntime = mock(
                HyperlinkTaskRuntimeMapper.class, delegatesTo(runtimeMapper));
        doAnswer(invocation -> {
            HyperlinkTaskRuntime runtime = runtimeMapper.selectByTaskIdForUpdate(
                    invocation.getArgument(0), invocation.getArgument(1));
            lifecycleHasRuntime.countDown();
            await(actionAttemptsTransition, "等待 PAUSE 尝试 runtime 写锁超时");
            return runtime;
        }).when(coordinatedRuntime).selectByTaskIdForUpdate(TENANT_ID, TASK_ID);

        HyperlinkTask task = new HyperlinkTask();
        task.setId(TASK_ID);
        task.setTaskType(HyperlinkTaskMode.INSTANT.code());
        task.setConcurrentNum(1);
        task.setMaxUseAccount(1);
        HyperlinkTaskMapper tasks = mock(HyperlinkTaskMapper.class);
        when(tasks.selectById(TASK_ID)).thenReturn(task);
        HyperlinkTaskRoundAccountMapper roundAccounts = mock(HyperlinkTaskRoundAccountMapper.class);
        when(roundAccounts.countAvailableByRoundId(ROUND_ID)).thenReturn(0);
        when(roundAccounts.countByRoundId(ROUND_ID)).thenReturn(0);
        HyperlinkTaskRecipientMapper recipients = mock(HyperlinkTaskRecipientMapper.class);
        when(recipients.countPendingUnassigned(TASK_ID)).thenReturn(1);
        when(recipients.countSendingByRoundId(ROUND_ID)).thenReturn(0);
        HyperlinkRoundAccountSelectionService selection = mock(
                HyperlinkRoundAccountSelectionService.class);
        when(selection.select(any(), any(), anyLong())).thenReturn(0);
        HyperlinkRoundLifecycleService lifecycle = new HyperlinkRoundLifecycleService(
                tasks, coordinatedRuntime, roundMapper, roundAccounts, recipients,
                mock(HyperlinkTaskAccountUsageMapper.class), selection,
                mock(HyperlinkCleanupStartService.class));

        HyperlinkTaskStoreService store = mock(HyperlinkTaskStoreService.class);
        when(store.requireTask(TASK_ID)).thenReturn(task);
        HyperlinkTaskRuntime actionRuntime = new HyperlinkTaskRuntime();
        actionRuntime.setEnabled(true);
        actionRuntime.setRunStatus(HyperlinkTaskRunStatus.RUNNING.code());
        actionRuntime.setProvisionStatus(2);
        when(store.requireRuntime(TASK_ID)).thenReturn(actionRuntime);
        doAnswer(invocation -> {
            actionAttemptsTransition.countDown();
            return runtimeMapper.transition(TASK_ID, true,
                    HyperlinkTaskRunStatus.RUNNING.code(), true,
                    HyperlinkTaskRunStatus.PAUSED.code(), 2,
                    invocation.getArgument(6)) == 1;
        }).when(store).transition(eq(TASK_ID), eq(true),
                eq(HyperlinkTaskRunStatus.RUNNING.code()), eq(true),
                eq(HyperlinkTaskRunStatus.PAUSED.code()), eq(2), anyLong());
        when(store.receipt(TASK_ID)).thenReturn(new HyperlinkTaskMutationReceiptVO(
                TASK_ID, HyperlinkProvisionStatus.READY, true,
                HyperlinkTaskRunStatus.PAUSED.code(), 2, null, null, null));
        HyperlinkTaskActionService action = new HyperlinkTaskActionService(store,
                mock(HyperlinkTaskQuoteGuardService.class),
                mock(HyperlinkProvisionFactService.class), roundMapper,
                mock(HyperlinkCleanupStartService.class), mock(HyperlinkTaskAuditPort.class),
                mock(HyperlinkShortLinkGuard.class),
                mock(com.armada.hyperlink.task.service.HyperlinkProtocolCapacityService.class));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Void> lifecycleFuture = executor.submit(() -> tenantTransaction(() -> {
                lifecycle.advance(TASK_ID);
                return null;
            }));
            assertThat(lifecycleHasRuntime.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Void> actionFuture = executor.submit(() -> tenantTransaction(() -> {
                action.action(TASK_ID, new HyperlinkTaskActionDTO(
                        HyperlinkTaskAction.PAUSE, 1, null), principal());
                return null;
            }));

            lifecycleFuture.get(5, TimeUnit.SECONDS);
            actionFuture.get(5, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("""
                    SELECT run_status FROM hyperlink_task_runtime
                    WHERE tenant_id=7 AND hyperlink_task_id=11
                    """, Integer.class)).isEqualTo(HyperlinkTaskRunStatus.PAUSED.code());
        } finally {
            actionAttemptsTransition.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void stopCleanupProcessesFiveHundredPlusOneBeforeReleasingClaim() {
        insertStopFacts(501, 501);
        DataPackageRecipientClaimService data = dataPackageService();
        HyperlinkRecipientCleanupService cleanup = new HyperlinkRecipientCleanupService(
                claimMapper, recipientMapper, runtimeMapper, data);

        assertThat(tenantTransaction(() -> cleanup.cleanupBatch(TASK_ID))).isFalse();
        assertCleanupState(500, 4, 500, 1);

        assertThat(tenantTransaction(() -> cleanup.cleanupBatch(TASK_ID))).isFalse();
        assertCleanupState(501, 4, 501, 0);

        assertThat(tenantTransaction(() -> cleanup.cleanupBatch(TASK_ID))).isTrue();
        assertCleanupState(501, 5, 501, 0);
    }

    @Test
    void partialPhoneReleaseRollsBackRecipientPhoneStatAndClaimTogether() {
        insertStopFacts(500, 499);
        HyperlinkRecipientCleanupService cleanup = new HyperlinkRecipientCleanupService(
                claimMapper, recipientMapper, runtimeMapper, dataPackageService());

        assertThatThrownBy(() -> tenantTransaction(() -> cleanup.cleanupBatch(TASK_ID)))
                .hasMessageContaining("STOP recipient 与号码池释放数量不一致");

        assertCleanupState(0, 4, 0, 499);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM data_package_phone
                WHERE tenant_id=7 AND data_package_id=31 AND generation=2
                  AND pool_status=2 AND claimed_by_hyperlink_task_id=11
                """, Integer.class)).isEqualTo(499);
    }

    private static int holdSharedFence(CountDownLatch bothReadersLocked,
            CountDownLatch releaseReaders) {
        return tenantTransaction(() -> {
            HyperlinkTaskRuntime runtime = runtimeMapper.selectByTaskIdForShare(TENANT_ID, TASK_ID);
            bothReadersLocked.countDown();
            await(releaseReaders, "等待释放共享 runtime fence 超时");
            return runtime.getRunStatus();
        });
    }

    private static void assertCleanupState(int stopped, int claimStatus,
            int unused, int claimed) {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM hyperlink_task_recipient
                WHERE tenant_id=7 AND hyperlink_task_id=11 AND send_status=6
                """, Integer.class)).isEqualTo(stopped);
        assertThat(jdbc.queryForObject("""
                SELECT claim_status FROM hyperlink_task_recipient_claim
                WHERE tenant_id=7 AND hyperlink_task_id=11
                """, Integer.class)).isEqualTo(claimStatus);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM data_package_phone
                WHERE tenant_id=7 AND data_package_id=31 AND generation=2 AND pool_status=1
                """, Integer.class)).isEqualTo(unused);
        assertThat(jdbc.queryForObject("""
                SELECT claimed_count FROM data_package_stat
                WHERE tenant_id=7 AND data_package_id=31 AND generation=2
                """, Integer.class)).isEqualTo(claimed);
    }

    private static void insertStopFacts(int recipientCount, int phoneCount) {
        jdbc.update("UPDATE hyperlink_task_runtime SET run_status=4 WHERE hyperlink_task_id=11");
        jdbc.update("""
                INSERT INTO hyperlink_task_recipient_claim
                  (id,tenant_id,hyperlink_task_id,data_package_id,data_package_generation,
                   claim_status,version,created_at,updated_at)
                VALUES (41,7,11,31,2,4,1,1000,1000)
                """);
        List<Object[]> recipientRows = new ArrayList<>(recipientCount);
        List<Object[]> phoneRows = new ArrayList<>(phoneCount);
        for (int index = 1; index <= recipientCount; index++) {
            String phone = "551" + String.format("%09d", index);
            recipientRows.add(new Object[] {index, phone});
            if (index <= phoneCount) { phoneRows.add(new Object[] {index, phone}); }
        }
        jdbc.batchUpdate("""
                INSERT INTO hyperlink_task_recipient
                  (id,tenant_id,hyperlink_task_id,data_package_id,data_package_generation,
                   source_import_id,recipient_phone_snapshot,send_status,next_dispatch_at,
                   metrics_projected_status,click_count,created_at,updated_at)
                VALUES (?,7,11,31,2,51,?,1,0,1,0,1000,1000)
                """, recipientRows);
        jdbc.batchUpdate("""
                INSERT INTO data_package_phone
                  (id,tenant_id,data_package_id,generation,source_import_id,phone,pool_status,
                   claimed_by_hyperlink_task_id,claimed_at,created_at,updated_at)
                VALUES (?,7,31,2,51,?,2,11,1000,1000,1000)
                """, phoneRows);
        jdbc.update("""
                INSERT INTO data_package_stat
                  (data_package_id,tenant_id,generation,unused_count,claimed_count,sent_count,
                   delivered_count,retryable_failed_count,unregistered_count,updated_at)
                VALUES (31,7,2,0,?,0,0,0,0,1000)
                """, phoneCount);
    }

    private static DataPackageRecipientClaimService dataPackageService() {
        return new DataPackageRecipientClaimServiceImpl(mock(DataPackageMapper.class),
                phoneMapper, statMapper);
    }

    private static <T> T tenantTransaction(Callable<T> action) {
        return transactions.execute(status -> {
            TenantContext.set(TENANT_ID);
            try {
                return action.call();
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            } finally {
                TenantContext.clear();
            }
        });
    }

    private static void await(CountDownLatch latch, String message) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException(message);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(message, exception);
        }
    }

    private static AuthPrincipal principal() {
        return new AuthPrincipal(88L, TENANT_ID, "tester", "tester",
                "tenant", "tenant", List.of(), List.of());
    }

    private static SqlSessionTemplate buildSqlSessionTemplate(DataSource dataSource)
            throws Exception {
        MyBatisConfig myBatisConfig = new MyBatisConfig();
        MybatisPlusInterceptor interceptor = myBatisConfig.mybatisPlusInterceptor(
                myBatisConfig.tenantLineHandler());
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        factoryBean.setPlugins(interceptor);
        factoryBean.setMapperLocations(
                new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskRuntimeMapper.xml"),
                new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskRoundMapper.xml"),
                new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskRecipientMapper.xml"),
                new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskRecipientClaimMapper.xml"),
                new ClassPathResource("mapper/hyperlink/data/DataPackagePhoneMapper.xml"),
                new ClassPathResource("mapper/hyperlink/data/DataPackageStatMapper.xml"));
        SqlSessionFactory factory = factoryBean.getObject();
        if (factory == null) {
            throw new IllegalStateException("无法创建超链并发 MySQL 测试 SqlSessionFactory");
        }
        return new SqlSessionTemplate(factory);
    }

    private static void createSchema() {
        jdbc.execute("""
                CREATE TABLE hyperlink_task_runtime (
                  hyperlink_task_id BIGINT NOT NULL PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  is_enabled TINYINT NOT NULL,
                  run_status TINYINT NOT NULL,
                  provision_status TINYINT NOT NULL,
                  current_round_id BIGINT NULL,
                  current_round_no BIGINT NOT NULL DEFAULT 0,
                  actual_concurrency INT NOT NULL DEFAULT 0,
                  execution_duration_sec BIGINT NOT NULL DEFAULT 0,
                  started_at BIGINT NULL,
                  active_since_at BIGINT NULL,
                  finished_at BIGINT NULL,
                  failure_code INT NULL,
                  failure_reason VARCHAR(255) NULL,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  KEY idx_runtime_tenant (tenant_id,hyperlink_task_id)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE hyperlink_task_round (
                  id BIGINT NOT NULL PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  hyperlink_task_id BIGINT NOT NULL,
                  round_no BIGINT NOT NULL,
                  round_status TINYINT NOT NULL,
                  scheduled_at BIGINT NOT NULL,
                  next_dispatch_at BIGINT NOT NULL,
                  assigned_recipient_count INT NOT NULL DEFAULT 0,
                  selected_account_count INT NOT NULL DEFAULT 0,
                  actual_concurrency INT NOT NULL DEFAULT 0,
                  send_total BIGINT NOT NULL DEFAULT 0,
                  started_at BIGINT NULL,
                  dispatch_completed_at BIGINT NULL,
                  last_send_at BIGINT NULL,
                  finished_at BIGINT NULL,
                  version INT NOT NULL DEFAULT 1,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  KEY idx_round_task (tenant_id,hyperlink_task_id,round_no)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE hyperlink_task_recipient_claim (
                  id BIGINT NOT NULL PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  hyperlink_task_id BIGINT NOT NULL,
                  data_package_id BIGINT NOT NULL,
                  data_package_generation INT NOT NULL,
                  claim_status TINYINT NOT NULL,
                  version INT NOT NULL DEFAULT 1,
                  finished_at BIGINT NULL,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  KEY idx_claim_task (tenant_id,hyperlink_task_id,id)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE hyperlink_task_recipient (
                  id BIGINT NOT NULL PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  hyperlink_task_id BIGINT NOT NULL,
                  data_package_id BIGINT NULL,
                  data_package_generation INT NULL,
                  source_import_id BIGINT NOT NULL,
                  recipient_phone_snapshot VARCHAR(32) NOT NULL,
                  command_id VARCHAR(64) NULL,
                  send_status TINYINT NOT NULL,
                  next_dispatch_at BIGINT NOT NULL DEFAULT 0,
                  metrics_projected_status TINYINT NOT NULL DEFAULT 1,
                  click_count INT NOT NULL DEFAULT 0,
                  fail_code VARCHAR(64) NULL,
                  fail_reason VARCHAR(255) NULL,
                  failed_at BIGINT NULL,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  KEY idx_recipient_stop
                    (tenant_id,hyperlink_task_id,data_package_id,data_package_generation,
                     send_status,id)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE data_package_phone (
                  id BIGINT NOT NULL PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  data_package_id BIGINT NOT NULL,
                  generation INT NOT NULL,
                  source_import_id BIGINT NOT NULL,
                  phone VARCHAR(32) NOT NULL,
                  country_iso2 CHAR(2) NULL,
                  pool_status TINYINT NOT NULL,
                  claimed_by_hyperlink_task_id BIGINT NULL,
                  claimed_at BIGINT NULL,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  UNIQUE KEY uq_data_phone (tenant_id,data_package_id,generation,phone)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE data_package_stat (
                  data_package_id BIGINT NOT NULL PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  generation INT NOT NULL,
                  unused_count INT NOT NULL DEFAULT 0,
                  claimed_count INT NOT NULL DEFAULT 0,
                  sent_count INT NOT NULL DEFAULT 0,
                  delivered_count INT NOT NULL DEFAULT 0,
                  retryable_failed_count INT NOT NULL DEFAULT 0,
                  unregistered_count INT NOT NULL DEFAULT 0,
                  updated_at BIGINT NOT NULL,
                  reconciled_at BIGINT NULL,
                  UNIQUE KEY uq_data_stat (tenant_id,data_package_id)
                ) ENGINE=InnoDB
                """);
    }
}
