package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.hyperlink.data.mapper.DataPackagePhoneMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientClaimMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
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
import org.springframework.core.io.Resource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 三类超链批处理行锁的真实 H2 执行、SKIP LOCKED 和显式租户边界测试。 */
@SpringJUnitConfig(HyperlinkTenantLockMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class HyperlinkTenantLockMapperH2Test {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private HyperlinkTaskRecipientClaimMapper claimMapper;

    @Autowired
    private HyperlinkTaskRecipientMapper recipientMapper;

    @Autowired
    private DataPackagePhoneMapper phoneMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private com.armada.hyperlink.task.mapper.HyperlinkTaskAccountUsageMapper usageMapper;
    @Autowired
    private com.armada.hyperlink.task.mapper.HyperlinkTaskRoundAccountMapper roundAccounts;

    private ExecutorService executor;

    @BeforeEach
    void setUp() throws SQLException {
        executor = Executors.newFixedThreadPool(2);
        execute("DROP ALL OBJECTS");
        execute("CREATE TABLE protocol_command_outbox (tenant_id BIGINT, command_id VARCHAR(128), created_at BIGINT)");
        execute("""
                CREATE TABLE hyperlink_task_recipient_claim (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  hyperlink_task_id BIGINT NOT NULL, claim_status INT)
                """);
        execute("""
                CREATE TABLE hyperlink_task_recipient (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  hyperlink_task_id BIGINT NOT NULL, send_status INT,
                  command_id VARCHAR(64), hyperlink_task_round_id BIGINT,
                  next_dispatch_at BIGINT, account_id BIGINT,
                  protocol_backend INT, submitted_at BIGINT)
                """);
        execute("""
                CREATE TABLE data_package_phone (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  data_package_id BIGINT, generation INT, source_import_id BIGINT,
                  phone VARCHAR(32), country_iso2 VARCHAR(2), pool_status INT,
                  claimed_by_hyperlink_task_id BIGINT, claimed_at BIGINT,
                  created_at BIGINT, updated_at BIGINT)
                """);
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V205__hyperlink_recipient_sender_rejection.sql"))
                .execute(dataSource);
        execute("INSERT INTO hyperlink_task_recipient_claim VALUES (1,7,100,2),(2,8,100,2)");
        execute("INSERT INTO hyperlink_task_recipient VALUES "
                + "(1,7,100,1,NULL,NULL,0,NULL,NULL,NULL),"
                + "(2,8,100,1,NULL,NULL,0,NULL,NULL,NULL),"
                + "(3,7,101,2,'hl:7:101:3',9,5,51,1,1)");
        execute("INSERT INTO data_package_phone VALUES "
                + "(1,7,200,1,1,'551','BR',1,NULL,NULL,1,1),"
                + "(2,8,200,1,1,'552','BR',1,NULL,NULL,1,1),"
                + "(3,7,200,1,1,'553','BR',2,100,1,1,1),"
                + "(4,8,200,1,1,'554','BR',2,100,1,1,1)");
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void eachLockQueryExecutesAgainstOnlyTheExplicitTenant() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            assertThat(claimMapper.selectByTaskId(7L, 100L).getTenantId()).isEqualTo(7L);
            assertThat(recipientMapper.lockPending(7L, 100L, 9L, 10L).getTenantId()).isEqualTo(7L);
            assertThat(phoneMapper.lockNextClaimable(7L, 200L, 1, 0L, 10L, 10))
                    .extracting(row -> row.getTenantId()).containsExactly(7L);
            assertThat(phoneMapper.lockOwnedBatch(7L, 100L, 200L, 1, 10))
                    .extracting(row -> row.getTenantId()).containsExactly(7L);
        });
    }

    @Test
    void recipientSkipLockedDoesNotReturnRowHeldByAnotherTransaction() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    assertThat(recipientMapper.lockPending(7L, 100L, 9L, 10L)).isNotNull();
                    locked.countDown();
                    await(release);
                }));

        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
        var skipped = new TransactionTemplate(transactionManager).execute(
                status -> recipientMapper.lockPending(7L, 100L, 9L, 10L));
        assertThat(skipped).isNull();
        release.countDown();
        holder.get(5, TimeUnit.SECONDS);
    }

    @Test
    void accountSendingCurrentReadCountsAcrossTasksAndSeparatesTenants() throws SQLException {
        for (int offset = 0; offset < 19; offset++) {
            long id = 10L + offset;
            long taskId = offset % 2 == 0 ? 100L : 102L;
            execute("INSERT INTO hyperlink_task_recipient VALUES ("
                    + id + ",7," + taskId + ",2,NULL,9,5,51,1,1)");
        }
        execute("INSERT INTO hyperlink_task_recipient VALUES "
                + "(50,8,102,2,NULL,9,5,51,1,1)");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        List<Long> tenantSeven = transaction.execute(
                status -> recipientMapper.lockSendingIdsByAccount(7L, 51L, 20));
        assertThat(tenantSeven)
                .hasSize(20)
                .contains(3L, 10L, 28L);
        List<Long> tenantEight = transaction.execute(
                status -> recipientMapper.lockSendingIdsByAccount(8L, 51L, 20));
        assertThat(tenantEight)
                .containsExactly(50L);

        execute("UPDATE hyperlink_task_recipient SET send_status=3 WHERE id=3");
        List<Long> afterTerminal = transaction.execute(
                status -> recipientMapper.lockSendingIdsByAccount(7L, 51L, 20));
        assertThat(afterTerminal)
                .hasSize(19)
                .doesNotContain(3L);
    }

    @Test
    void reconciliationCandidateIncludesAccountForSameCommandHolderRenewal() {
        assertThat(recipientMapper.selectReconciliationCandidates(10L, 10))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.tenantId()).isEqualTo(7L);
                    assertThat(candidate.taskId()).isEqualTo(101L);
                    assertThat(candidate.recipientId()).isEqualTo(3L);
                    assertThat(candidate.accountId()).isEqualTo(51L);
                    assertThat(candidate.commandId()).isEqualTo("hl:7:101:3");
                });
    }

    @Test
    void rejectedPairSkipsAForSenderOneButAllowsBAndOtherSenders() throws SQLException {
        execute("INSERT INTO hyperlink_task_recipient VALUES (4,7,100,1,NULL,NULL,0,NULL,NULL,NULL)");
        com.armada.shared.tenant.TenantContext.set(7L);
        try {
            var rejected = new com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipient();
            rejected.setTenantId(7L);
            rejected.setId(1L);
            rejected.setAccountId(51L);
            rejected.setFailCode("WA_ACK_REJECTED_463");
            rejected.setUpdatedAt(10L);
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertThat(recipientMapper.rememberRejectedSender(rejected)).isEqualTo(1);
                assertThat(recipientMapper.rememberRejectedSender(rejected)).isZero();
                assertThat(recipientMapper.lockPending(7L, 100L, 51L, 10L).getId()).isEqualTo(4L);
                assertThat(recipientMapper.lockPending(7L, 100L, 52L, 10L).getId()).isEqualTo(1L);
                assertThat(recipientMapper.lockPending(8L, 100L, 51L, 10L).getId()).isEqualTo(2L);
            });
            execute("DELETE FROM hyperlink_task_recipient WHERE id=4");
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertThat(recipientMapper.lockPending(7L, 100L, 51L, 10L)).isNull();
                assertThat(recipientMapper.lockPending(7L, 100L, 52L, 10L).getId()).isEqualTo(1L);
            });
        } finally {
            com.armada.shared.tenant.TenantContext.clear();
        }
    }

    @Test
    void fourRejectedSendersStillAllowTheFifthSenderForTheSameTarget() throws SQLException {
        com.armada.shared.tenant.TenantContext.set(7L);
        try {
            for (long account = 51; account <= 54; account++) {
                execute("INSERT INTO hyperlink_recipient_sender_rejection VALUES (7,1," + account
                        + ",'WA_ACK_REJECTED_463',1)");
            }
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                for (long account = 51; account <= 54; account++) {
                    assertThat(recipientMapper.lockPending(7L, 100L, account, 10L)).isNull();
                }
                assertThat(recipientMapper.lockPending(7L, 100L, 55L, 10L).getId()).isEqualTo(1L);
                assertThat(recipientMapper.lockPending(8L, 100L, 51L, 10L).getId()).isEqualTo(2L);
            });
        } finally {
            com.armada.shared.tenant.TenantContext.clear();
        }
    }

    @Test
    void dispatchSkipsRejectedSendersBeforeLimitAndRotatesOnlyWhenNoOtherTargetRemains() throws SQLException {
        execute("CREATE TABLE hyperlink_task_account_usage (id BIGINT PRIMARY KEY, tenant_id BIGINT, "
                + "hyperlink_task_id BIGINT, account_id BIGINT, usage_status INT, next_send_at BIGINT, "
                + "in_flight_count INT, success_limit INT, successful_send_count INT, reserved_success_slot_count INT)");
        execute("CREATE TABLE hyperlink_task_round_account (id BIGINT PRIMARY KEY, tenant_id BIGINT, "
                + "hyperlink_task_id BIGINT, hyperlink_task_round_id BIGINT, task_account_usage_id BIGINT, "
                + "account_id BIGINT, assignment_status INT, selection_no INT, released_at BIGINT, updated_at BIGINT)");
        for (long account = 1; account <= 25; account++) {
            execute("INSERT INTO hyperlink_task_account_usage VALUES (" + account + ",7,100," + account + ",1,0,0,0,0,0)");
            execute("INSERT INTO hyperlink_task_round_account VALUES (" + account + ",7,100,9," + account + "," + account + ",1," + account + ",NULL,0)");
            if (account < 25) {
                execute("INSERT INTO hyperlink_recipient_sender_rejection VALUES (7,1," + account + ",'WA_ACK_REJECTED_463',1)");
            }
        }
        com.armada.shared.tenant.TenantContext.set(7L);
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertThat(usageMapper.selectAvailable(100L, 9L, 10L, 20, 20))
                        .extracting(row -> row.getAccountId()).containsExactly(25L);
                assertThat(roundAccounts.releaseRejectedPairsOnly(9L, 10L)).isEqualTo(24);
                assertThat(roundAccounts.countAvailableByRoundId(9L)).isEqualTo(1);
            });
            execute("UPDATE hyperlink_task_round_account SET assignment_status=1");
            execute("INSERT INTO hyperlink_task_recipient VALUES (4,7,100,1,NULL,NULL,0,NULL,NULL,NULL)");
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertThat(roundAccounts.releaseRejectedPairsOnly(9L, 11L)).isZero();
                assertThat(recipientMapper.lockPending(7L, 100L, 1L, 10L).getId()).isEqualTo(4L);
            });
            execute("DELETE FROM hyperlink_task_recipient WHERE id=4");
            execute("UPDATE hyperlink_task_account_usage SET in_flight_count=1 WHERE id=1");
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    assertThat(roundAccounts.releaseRejectedPairsOnly(9L, 12L)).isEqualTo(23));
        } finally {
            com.armada.shared.tenant.TenantContext.clear();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待释放行锁超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待释放行锁被中断", exception);
        }
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
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:hyperlink_tenant_locks;MODE=MySQL;"
                    + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=2000");
            source.setUser("sa");
            source.setPassword("");
            return source;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(
                DataSource dataSource, MybatisPlusInterceptor interceptor) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            Resource[] locations = {
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskRecipientClaimMapper.xml"),
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskRecipientMapper.xml"),
                    new ClassPathResource("mapper/hyperlink/data/DataPackagePhoneMapper.xml"),
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskAccountUsageMapper.xml"),
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskRoundAccountMapper.xml")
            };
            factory.setMapperLocations(locations);
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

        @Bean
        HyperlinkTaskRecipientMapper recipientMapper(SqlSessionTemplate template) {
            return template.getMapper(HyperlinkTaskRecipientMapper.class);
        }

        @Bean
        com.armada.hyperlink.task.mapper.HyperlinkTaskAccountUsageMapper usageMapper(SqlSessionTemplate template) {
            return template.getMapper(com.armada.hyperlink.task.mapper.HyperlinkTaskAccountUsageMapper.class);
        }

        @Bean
        com.armada.hyperlink.task.mapper.HyperlinkTaskRoundAccountMapper roundAccounts(SqlSessionTemplate template) {
            return template.getMapper(com.armada.hyperlink.task.mapper.HyperlinkTaskRoundAccountMapper.class);
        }

        @Bean
        DataPackagePhoneMapper phoneMapper(SqlSessionTemplate template) {
            return template.getMapper(DataPackagePhoneMapper.class);
        }
    }
}
