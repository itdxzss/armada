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

    private ExecutorService executor;

    @BeforeEach
    void setUp() throws SQLException {
        executor = Executors.newFixedThreadPool(2);
        execute("DROP ALL OBJECTS");
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
                    new ClassPathResource("mapper/hyperlink/data/DataPackagePhoneMapper.xml")
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
        DataPackagePhoneMapper phoneMapper(SqlSessionTemplate template) {
            return template.getMapper(DataPackagePhoneMapper.class);
        }
    }
}
