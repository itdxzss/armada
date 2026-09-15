package com.armada.account.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.account.mapper.AccountRegistrationMapper;
import com.armada.account.model.dto.AccountRegistrationQuery;
import com.armada.account.model.entity.AccountRegistrationItem;
import com.armada.account.model.entity.AccountRegistrationTask;
import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.support.TransactionTemplate;

/** 在H2 MySQL模式执行真实迁移、XML、租户插件与两个独立事务。 */
@SpringJUnitConfig(AccountRegistrationMapperH2Test.Config.class)
@TestExecutionListeners(listeners = DependencyInjectionTestExecutionListener.class, inheritListeners = false)
class AccountRegistrationMapperH2Test {
    @Autowired DataSource dataSource;
    @Autowired AccountRegistrationMapper mapper;
    @Autowired TransactionTemplate transactions;

    @BeforeEach
    void schema() throws Exception {
        TenantContext.set(7L);
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
            String migration = new ClassPathResource("db/migration/V193__account_sms_registration.sql")
                    .getContentAsString(StandardCharsets.UTF_8);
            for (String sql : migration.split(";")) { if (!sql.isBlank()) { statement.execute(sql); } }
            String cancellationMigration = new ClassPathResource("db/migration/V195__registration_price_cancellation.sql")
                    .getContentAsString(StandardCharsets.UTF_8);
            // H2 不支持 MySQL PREPARE；执行迁移内同一份 ADD COLUMN，另断言幂等守卫。
            assertThat(cancellationMigration).contains("information_schema.columns", "column_name = 'cancel_after'",
                    "PREPARE stmt FROM @sql", "11等待取消");
            var ddl = java.util.regex.Pattern.compile("'(ALTER TABLE account_registration_item ADD COLUMN .*?)', 'SELECT 1'",
                    java.util.regex.Pattern.DOTALL).matcher(cancellationMigration);
            assertThat(ddl.find()).isTrue();
            statement.execute(ddl.group(1).replace("''", "'"));
        }
    }
    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test
    void taskRequestIdIsUniquePerTenantAndPaginationIsTenantScoped() {
        var first = task("same");
        mapper.insertTask(first);
        assertThatThrownBy(() -> mapper.insertTask(task("same"))).isInstanceOf(RuntimeException.class);
        mapper.insertTask(task("second"));
        TenantContext.set(8L);
        mapper.insertTask(task("same"));
        assertThat(mapper.findTask(first.getId())).isNull();
        assertThat(mapper.countTasks()).isEqualTo(1);
        TenantContext.set(7L);
        var query = new AccountRegistrationQuery(); query.setPageSize(1);
        assertThat(mapper.listTasks(query)).hasSize(1);
        query.setPage(2);
        assertThat(mapper.listTasks(query).get(0).getId()).isEqualTo(first.getId());
        TenantContext.clear();
        assertThat(mapper.countTasks()).isZero();
    }

    @Test
    void fixedItemsCountsAndCancelPreservePurchasedRows() {
        var task = task("cancel"); mapper.insertTask(task);
        insertItems(task.getId(), 3);
        var first = mapper.listItems(task.getId()).get(0);
        claim(first, "owner", 100);
        first.setState(3); first.setActivationId("98765"); first.setUpdatedAt(101L);
        assertThat(mapper.updateClaimed(first)).isEqualTo(1);
        mapper.requestCancel(task.getId(), 102);
        assertThat(mapper.cancelPending(task.getId(), 102)).isEqualTo(2);
        var counts = mapper.counts(task.getId());
        assertThat(counts.cancelled()).isEqualTo(2);
        assertThat(counts.processing()).isEqualTo(1);
        assertThat(counts.succeeded()).isZero();
        assertThat(mapper.findItem(first.getId()).getActivationId()).isEqualTo("98765");
        assertThat(mapper.listItems(task.getId())).hasSize(3);
    }

    @Test
    void expiredAndForeignLeaseCannotOverwriteAndActiveWorkWinsGlobally() {
        var task = task("lease"); mapper.insertTask(task); insertItems(task.getId(), 2);
        var item = mapper.listItems(task.getId()).get(1);
        claim(item, "old", 100);
        item.setState(2); item.setUpdatedAt(101L);
        mapper.updateClaimed(item);
        assertThat(mapper.nextWork(101).getId()).isEqualTo(item.getId());
        var replacement = mapper.findItem(item.getId());
        replacement.setLeaseToken("new"); replacement.setLeaseUntil(500L);
        assertThat(mapper.claim(replacement, 250)).isEqualTo(1);
        item.setState(7); item.setUpdatedAt(251L);
        assertThat(mapper.updateClaimed(item)).isZero();
        assertThat(mapper.release(item.getId(), "old")).isZero();
        replacement.setUpdatedAt(501L); replacement.setState(7);
        assertThat(mapper.updateClaimed(replacement)).isZero();
        assertThat(mapper.findItem(item.getId()).getState()).isEqualTo(2);
        TenantContext.set(8L);
        assertThat(mapper.findItem(item.getId())).isNull();
        assertThat(mapper.release(item.getId(), "new")).isZero();
    }

    @Test
    void cancellationWinsBeforePurchasingIntentSoLeasedPendingCannotPurchase() {
        var task = task("race"); mapper.insertTask(task); insertItems(task.getId(), 1);
        var item = mapper.listItems(task.getId()).get(0);
        claim(item, "owner", 100);
        mapper.cancelPending(task.getId(), 101);
        item.setState(2); item.setUpdatedAt(102L);
        assertThat(mapper.updateClaimed(item)).isZero();
        assertThat(mapper.findItem(item.getId()).getState()).isEqualTo(10);
    }

    @Test
    void twoTransactionsContendForSameClaimOnlyOneCanOwnLease() throws Exception {
        var task = task("concurrent"); mapper.insertTask(task); insertItems(task.getId(), 1);
        Long id = mapper.listItems(task.getId()).get(0).getId();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> {
                TenantContext.set(7L);
                try { return transactions.execute(status -> {
                    var item = mapper.findItem(id); claim(item, "first", 100); held.countDown();
                    try { if (!release.await(3, TimeUnit.SECONDS)) { throw new IllegalStateException(); } }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                    return 1;
                }); } finally { TenantContext.clear(); }
            });
            assertThat(held.await(2, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> {
                TenantContext.set(7L);
                try { return transactions.execute(status -> {
                    var item = mapper.findItem(id); item.setLeaseToken("second"); item.setLeaseUntil(250L);
                    return mapper.claim(item, 101);
                }); } finally { TenantContext.clear(); }
            });
            assertThatThrownBy(() -> second.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            assertThat(first.get(3, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(second.get(3, TimeUnit.SECONDS)).isZero();
            assertThat(mapper.findItem(id).getLeaseToken()).isEqualTo("first");
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test
    void transactionRollbackRemovesTaskAndItsFixedRows() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            var task = task("rollback"); mapper.insertTask(task); insertItems(task.getId(), 2);
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(mapper.findByRequestId("rollback")).isNull();
        assertThat(mapper.nextWork(100)).isNull();
    }

    @Test
    void cancellationDelayIsPersistentCountsAsProcessingAndDoesNotBuyMoreFromSameTask() {
        var task = task("cancel-delay"); mapper.insertTask(task); insertItems(task.getId(), 2);
        var first = mapper.listItems(task.getId()).get(0);
        claim(first, "owner", 100);
        first.setState(11); first.setCancelAfter(500L); first.setUpdatedAt(101L);
        first.setActivationId("cancel-delay-activation");
        assertThat(mapper.updateClaimed(first)).isEqualTo(1);
        assertThat(mapper.findItem(first.getId()).getCancelAfter()).isEqualTo(500L);
        assertThat(mapper.counts(task.getId()).processing()).isEqualTo(1);
        assertThat(mapper.nextWork(499)).isNull();
        assertThat(mapper.nextWork(500).getId()).isEqualTo(first.getId());
        TenantContext.set(8L);
        var other = task("other-tenant"); mapper.insertTask(other); insertItems(other.getId(), 1);
        var otherItem = mapper.listItems(other.getId()).get(0);
        assertThat(mapper.nextWork(499).getId()).isEqualTo(otherItem.getId());
        assertThat(mapper.findItem(first.getId())).isNull();
        TenantContext.set(7L);
        mapper.requestCancel(task.getId(), 200);
        mapper.cancelPending(task.getId(), 200);
        assertThat(mapper.nextWork(500).getId()).isEqualTo(first.getId());
        assertThat(mapper.counts(task.getId()).cancelled()).isEqualTo(1);
    }

    private void claim(AccountRegistrationItem item, String token, long now) {
        item.setLeaseToken(token); item.setLeaseUntil(now + 100);
        assertThat(mapper.claim(item, now)).isEqualTo(1);
    }
    private void insertItems(Long taskId, int count) {
        var items = new java.util.ArrayList<AccountRegistrationItem>();
        for (int index = 1; index <= count; index++) {
            var item = new AccountRegistrationItem(); item.setTaskId(taskId); item.setOrdinal(index);
            item.setState(1); item.setCreatedAt(100L); item.setUpdatedAt(100L); items.add(item);
        }
        mapper.insertItems(items);
    }
    private AccountRegistrationTask task(String requestId) {
        var task = new AccountRegistrationTask(); task.setRequestId(requestId); task.setServiceCode("wa");
        task.setCountryId("12"); task.setUnitPrice(new BigDecimal("1.35")); task.setQuantity(3);
        task.setAccountGroupId(90L); task.setAccountType(1); task.setIpAllocationMode("smart");
        task.setCancelRequested(false); task.setCreatedAt(100L); task.setUpdatedAt(100L); return task;
    }

    @Configuration @Import(MyBatisConfig.class)
    static class Config {
        @Bean DataSource dataSource() {
            var source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:registration_mapper;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=4000");
            source.setUser("sa"); source.setPassword(""); return source;
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source, MybatisPlusInterceptor interceptor) throws Exception {
            var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
            var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(configuration);
            factory.setPlugins(interceptor); factory.setMapperLocations(new ClassPathResource("mapper/account/AccountRegistrationMapper.xml"));
            return factory.getObject();
        }
        @Bean AccountRegistrationMapper mapper(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory).getMapper(AccountRegistrationMapper.class);
        }
        @Bean TransactionTemplate transactions(DataSource source) {
            return new TransactionTemplate(new DataSourceTransactionManager(source));
        }
    }
}
