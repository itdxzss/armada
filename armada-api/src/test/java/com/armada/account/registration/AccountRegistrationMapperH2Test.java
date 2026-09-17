package com.armada.account.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.account.mapper.AccountRegistrationMapper;
import com.armada.account.mapper.DeviceRegistrationPermitMapper;
import com.armada.account.model.dto.DeviceRegistrationPermit;
import com.armada.account.service.impl.DeviceRegistrationPermitStore;
import com.armada.account.service.impl.AccountRegistrationStore;
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
    @Autowired DeviceRegistrationPermitMapper permits;
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
            String deviceMigration = new ClassPathResource("db/migration/V196__ios_device_registration.sql")
                    .getContentAsString(StandardCharsets.UTF_8);
            var additions = java.util.regex.Pattern.compile("'(ALTER TABLE account_registration_task ADD COLUMN .*?)', 'SELECT 1'",
                    java.util.regex.Pattern.DOTALL).matcher(deviceMigration);
            int columns = 0;
            while (additions.find()) { statement.execute(additions.group(1).replace("''", "'")); columns++; }
            assertThat(columns).isEqualTo(3);
            for (String line : deviceMigration.lines().filter(value -> value.startsWith("ALTER TABLE")).toList()) {
                statement.execute(line);
            }
            String providerMigration = new ClassPathResource("db/migration/V197__device_registration_provider.sql")
                    .getContentAsString(StandardCharsets.UTF_8);
            assertThat(providerMigration).contains("information_schema.columns", "column_name = 'provider_id'", "PREPARE stmt FROM @sql");
            var providerDDL = java.util.regex.Pattern.compile("'(ALTER TABLE account_registration_task ADD COLUMN .*?)', 'SELECT 1'",
                    java.util.regex.Pattern.DOTALL).matcher(providerMigration);
            assertThat(providerDDL.find()).isTrue();
            statement.execute(providerDDL.group(1).replace("''", "'"));
            assertThat(providerDDL.find()).isFalse();
            String permitMigration = new ClassPathResource("db/migration/V198__device_registration_permit.sql")
                    .getContentAsString(StandardCharsets.UTF_8);
            statement.execute(permitMigration);
            String retryMigration = new ClassPathResource("db/migration/V199__registration_purchase_retry.sql")
                    .getContentAsString(StandardCharsets.UTF_8);
            assertThat(retryMigration).contains("information_schema.columns", "column_name = 'purchase_attempts'",
                    "column_name = 'next_purchase_at'", "PREPARE stmt FROM @sql");
            var retryDDL = java.util.regex.Pattern.compile("'(ALTER TABLE account_registration_item ADD COLUMN .*?)', 'SELECT 1'",
                    java.util.regex.Pattern.DOTALL).matcher(retryMigration);
            int retryColumns = 0;
            while (retryDDL.find()) { statement.execute(retryDDL.group(1).replace("''", "'")); retryColumns++; }
            assertThat(retryColumns).isEqualTo(2);
            applyFailureMigration(statement);
        }
    }
    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void currentPermitIsTenantScopedAndReplacesFinishedFailure() {
        var store = new DeviceRegistrationPermitStore(permits, mapper, new AccountRegistrationStore(mapper, null));
        var first = devicePermit("first");
        transactions.executeWithoutResult(status -> store.prepare(first));
        assertThat(permits.find("phone").providerId()).isEqualTo("196");
        TenantContext.set(8L); assertThat(permits.find("phone")).isNull();
        TenantContext.set(7L);
        transactions.executeWithoutResult(status -> store.createDevice(first, "wa"));
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> store.prepare(devicePermit("next"))))
                .isInstanceOf(com.armada.shared.exception.BusinessException.class);
        var task = mapper.findByRequestId("first"); var item = mapper.listItems(task.getId()).get(0);
        claim(item, "failure", 100); item.setState(8); item.setUpdatedAt(101L); mapper.updateClaimed(item);
        transactions.executeWithoutResult(status -> store.prepare(devicePermit("next")));
        assertThat(permits.find("phone").replacesRequestId()).isEqualTo("first");
        assertThat(mapper.listItems(task.getId()).get(0).getState()).isEqualTo(8);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> store.createDevice(first, "wa")))
                .isInstanceOf(com.armada.shared.exception.BusinessException.class);
        assertThat(mapper.findByRequestId("next")).isNull();
    }

    @Test void changingMerchantOnSamePermitCannotCreateAnotherPurchase() {
        var store = new DeviceRegistrationPermitStore(permits, mapper, new AccountRegistrationStore(mapper, null));
        var first = devicePermit("first");
        transactions.executeWithoutResult(status -> store.prepare(first));
        var selected = new DeviceRegistrationPermit(7, "phone", "first", "187", first.unitPrice(), first.expiresAt(), "62", null);
        transactions.executeWithoutResult(status -> store.createDevice(selected, "wa"));
        assertThat(mapper.findByRequestId("first").getProviderId()).isEqualTo("62");
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> store.createDevice(first, "wa")))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(mapper.listItems(mapper.findByRequestId("first").getId())).hasSize(1);
    }

    @Test void permitReplacementWaitsForPurchaseIntentAndThenRejectsActiveTask() throws Exception {
        var store = new DeviceRegistrationPermitStore(permits, mapper, new AccountRegistrationStore(mapper, null));
        var first = devicePermit("first");
        transactions.executeWithoutResult(status -> store.prepare(first));
        var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = pool.submit(() -> {
                TenantContext.set(7L);
                try { return transactions.execute(status -> {
                    permits.lock("phone"); held.countDown();
                    try { if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException(); }
                    catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
                    store.createDevice(first, "wa"); return true;
                }); } finally { TenantContext.clear(); }
            });
            assertThat(held.await(2, TimeUnit.SECONDS)).isTrue();
            var replace = pool.submit(() -> {
                TenantContext.set(7L);
                try { return transactions.execute(status -> store.prepare(devicePermit("next"))); }
                finally { TenantContext.clear(); }
            });
            assertThatThrownBy(() -> replace.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown(); assertThat(start.get(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> replace.get(3, TimeUnit.SECONDS)).hasCauseInstanceOf(com.armada.shared.exception.BusinessException.class);
            assertThat(permits.find("phone").requestId()).isEqualTo("first");
            assertThat(mapper.findByRequestId("next")).isNull();
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void phoneCreatesWithoutAdminAndRepeatedIntentKeepsOneTask() {
        var store = new DeviceRegistrationPermitStore(permits, mapper, new AccountRegistrationStore(mapper, null));
        var first = transactions.execute(status -> store.prepareFromDevice(devicePermit("phone-first")));
        assertThat(first.providerId()).isNull();
        transactions.executeWithoutResult(status -> store.createDevice(first, "wa"));
        var same = transactions.execute(status -> store.prepareFromDevice(devicePermit("phone-first")));
        assertThat(same.requestId()).isEqualTo(first.requestId());
        assertThat(mapper.listItems(mapper.findByRequestId(first.requestId()).getId())).hasSize(1);
        var resume = transactions.execute(status -> store.prepareFromDevice(devicePermit("second-click")));
        assertThat(resume.requestId()).isEqualTo(first.requestId());
        assertThat(mapper.findByRequestId("second-click")).isNull();
    }

    @Test void phoneReplacesFinishedTaskAndRejectsHistoricalReplay() {
        var store = new DeviceRegistrationPermitStore(permits, mapper, new AccountRegistrationStore(mapper, null));
        var first = transactions.execute(status -> store.prepareFromDevice(devicePermit("phone-first")));
        transactions.executeWithoutResult(status -> store.createDevice(first, "wa"));
        var item = mapper.listItems(mapper.findByRequestId(first.requestId()).getId()).get(0);
        claim(item, "failure", 100); item.setState(8); item.setUpdatedAt(101L); mapper.updateClaimed(item);
        var next = transactions.execute(status -> store.prepareFromDevice(devicePermit("phone-next")));
        assertThat(next.replacesRequestId()).isEqualTo(first.requestId());
        transactions.executeWithoutResult(status -> store.createDevice(next, "wa"));
        assertThat(transactions.execute(status -> store.prepareFromDevice(devicePermit("phone-first"))).requestId())
                .isEqualTo(next.requestId());
        var allocated = mapper.listItems(mapper.findByRequestId(next.requestId()).getId()).get(0);
        claim(allocated, "allocation", 100); allocated.setState(9); allocated.setFailureCode("REGISTRATION_TIMEOUT");
        allocated.setPhoneNumber("12025550123"); allocated.setActivationId("order"); allocated.setUpdatedAt(101L);
        mapper.updateClaimed(allocated);
        var afterTimeout = transactions.execute(status -> store.prepareFromDevice(devicePermit("after-timeout")));
        assertThat(afterTimeout.requestId()).isEqualTo("after-timeout");
        assertThat(afterTimeout.replacesRequestId()).isEqualTo(next.requestId());
        assertThat(mapper.findByRequestId(next.requestId()).getId()).isEqualTo(allocated.getTaskId());
        assertThat(mapper.listItems(allocated.getTaskId()).get(0).getActivationId()).isEqualTo("order");
        TenantContext.set(8L);
        assertThat(permits.find("phone")).isNull();
    }

    @Test void phoneRenewsUnusedExpiredPermitWithoutChangingIntent() {
        var store = new DeviceRegistrationPermitStore(permits, mapper, new AccountRegistrationStore(mapper, null));
        var expired = new DeviceRegistrationPermit(7, "phone", "expired", "187", new BigDecimal("0.88"), 1, null, null);
        transactions.executeWithoutResult(status -> permits.insert(expired, 1));
        var renewed = transactions.execute(status -> store.prepareFromDevice(devicePermit("expired")));
        assertThat(renewed.expiresAt()).isGreaterThan(System.currentTimeMillis());
        assertThat(renewed.requestId()).isEqualTo("expired");
        transactions.executeWithoutResult(status -> store.createDevice(renewed, "wa"));
    }

    private DeviceRegistrationPermit devicePermit(String request) {
        return new DeviceRegistrationPermit(7, "phone", request, "187", new BigDecimal("0.88"), Long.MAX_VALUE, "196", null);
    }

    @Test
    void deviceMigrationAddsOnlyRegistrationExecutionFacts() throws Exception {
        String migration = new ClassPathResource("db/migration/V196__ios_device_registration.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(migration).contains("information_schema.columns", "execution_mode", "device_id", "purchase_before");
        assertThat(migration).doesNotContain("CREATE TABLE", "DROP TABLE");
    }

    @Test
    void deviceWaitDoesNotBlockCobaltAndSuccessfulDeviceIsNeverScheduled() {
        var device = task("device"); device.setExecutionMode(2); device.setDeviceId("phone");
        device.setPurchaseBefore(99999L); device.setAccountGroupId(null); device.setIpAllocationMode(null);
        mapper.insertTask(device); insertItems(device.getId(), 1);
        var item = mapper.listItems(device.getId()).get(0); claim(item, "phone", 100);
        item.setState(3); item.setStartedAt(100L); item.setUpdatedAt(101L); mapper.updateClaimed(item);
        assertThat(mapper.findTask(device.getId()).getDeviceId()).isEqualTo("phone");
        assertThat(mapper.findTask(device.getId()).getPurchaseBefore()).isEqualTo(99999L);
        assertThat(mapper.countTasks()).isZero();
        assertThat(mapper.nextWork(1000)).isNull();
        assertThat(mapper.nextWork(1500101).getId()).isEqualTo(item.getId());
        item.setState(12); item.setUpdatedAt(102L); mapper.updateClaimed(item);
        assertThat(mapper.nextWork(1500101)).isNull();
        TenantContext.set(8L);
        assertThat(mapper.findByRequestId("device")).isNull();
    }

    @Test
    void deviceStoreCreatesExactlyOneItemAndDuplicatePermitRollsBack() {
        var store = new com.armada.account.service.impl.AccountRegistrationStore(mapper, null);
        var permit = new com.armada.account.model.dto.DeviceRegistrationPermit(7, "phone", "one-permit", "12", new BigDecimal("1.35"), 9999, "222", null);
        transactions.executeWithoutResult(status -> store.createDevice(permit, "wa"));
        var persisted = mapper.findByRequestId("one-permit");
        assertThat(persisted.getQuantity()).isEqualTo(1); assertThat(persisted.getAccountGroupId()).isNull();
        assertThat(persisted.getIpAllocationMode()).isNull(); assertThat(persisted.getExecutionMode()).isEqualTo(2);
        assertThat(persisted.getProviderId()).isEqualTo("222");
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> store.createDevice(permit, "wa")))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(mapper.listItems(persisted.getId())).hasSize(1);
        TenantContext.set(8L); assertThat(mapper.listItems(persisted.getId())).isEmpty();
    }

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

    @Test void retryDueTimeIsPersistentAndDoesNotBlockOtherItems() {
        var task = task("retry"); mapper.insertTask(task); insertItems(task.getId(), 2);
        var items = mapper.listItems(task.getId()); var first = items.get(0);
        assertThat(first.getPurchaseAttempts()).isZero();
        claim(first, "retry-owner", 100);
        first.setPurchaseAttempts(4); first.setNextPurchaseAt(5101L); first.setUpdatedAt(101L);
        assertThat(mapper.updateClaimed(first)).isEqualTo(1);
        mapper.release(first.getId(), "retry-owner");
        assertThat(mapper.findItem(first.getId()).getPurchaseAttempts()).isEqualTo(4);
        assertThat(mapper.findItem(first.getId()).getNextPurchaseAt()).isEqualTo(5101L);
        assertThat(mapper.nextWork(5100).getId()).isEqualTo(items.get(1).getId());
        assertThat(mapper.nextWork(5101).getId()).isEqualTo(first.getId());
        TenantContext.set(8L); assertThat(mapper.findItem(first.getId())).isNull();
        TenantContext.set(7L); mapper.requestCancel(task.getId(), 5200); mapper.cancelPending(task.getId(), 5200);
        assertThat(mapper.nextWork(5300)).isNull();
    }

    @Test void cancelDuringHttpStillSchedulesUnallocatedRetryForCancellation() {
        var task = task("cancel-in-flight"); mapper.insertTask(task); insertItems(task.getId(), 1);
        var item = mapper.listItems(task.getId()).get(0); claim(item, "http", 100);
        item.setState(2); item.setUpdatedAt(101L); mapper.updateClaimed(item);
        mapper.requestCancel(task.getId(), 102); mapper.cancelPending(task.getId(), 102);
        item.setState(1); item.setPurchaseAttempts(1); item.setNextPurchaseAt(5103L); item.setUpdatedAt(103L);
        assertThat(mapper.updateClaimed(item)).isEqualTo(1); mapper.release(item.getId(), "http");
        assertThat(mapper.nextWork(5103).getId()).isEqualTo(item.getId());
        assertThat(mapper.findTask(task.getId()).getCancelRequested()).isTrue();
    }

    private void applyFailureMigration(Statement statement) throws Exception {
        String migration = new ClassPathResource("db/migration/V200__device_registration_failure.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(migration).contains("information_schema.columns", "column_name = 'failure_kind'",
                "column_name = 'failure_detail'", "PREPARE stmt FROM @sql");
        var ddl = java.util.regex.Pattern.compile("'(ALTER TABLE account_registration_item ADD COLUMN .*?)', 'SELECT 1'",
                java.util.regex.Pattern.DOTALL).matcher(migration);
        int columns = 0;
        while (ddl.find()) { statement.execute(ddl.group(1).replace("''", "'")); columns++; }
        assertThat(columns).isEqualTo(2);
    }

    @Test void nativeFailureRoundTripsThroughRealMapperAndDuplicateResultPreservesFirstFailure() {
        var task = task("native-failure"); task.setExecutionMode(2); task.setDeviceId("phone");
        task.setPurchaseBefore(Long.MAX_VALUE); mapper.insertTask(task); insertItems(task.getId(), 1);
        var item = mapper.listItems(task.getId()).get(0);
        long now = System.currentTimeMillis(); claim(item, "prepare", now);
        item.setState(3); item.setPhoneNumber("12025550123"); item.setActivationId("failure-order");
        item.setUpdatedAt(now); assertThat(mapper.updateClaimed(item)).isEqualTo(1); mapper.release(item.getId(), "prepare");
        var sms = org.mockito.Mockito.mock(com.armada.platform.sms.grizzly.GrizzlySmsClient.class);
        var service = new com.armada.account.service.impl.DeviceRegistrationServiceImpl(mapper, null, null, sms);
        var permit = new DeviceRegistrationPermit(7, "phone", "native-failure", "12", task.getUnitPrice(), Long.MAX_VALUE, null, null);
        var failure = new com.armada.account.model.dto.DeviceRegistrationResultDTO("native-failure", "12025550123", "FAILED",
                com.armada.account.model.enums.DeviceRegistrationFailureKind.NUMBER, "blocked", "目前无法登录");
        var first = service.result(permit, failure);
        assertThat(first.state()).isEqualTo("FAILED");
        var saved = mapper.findItem(item.getId());
        assertThat(saved.getFailureKind()).isEqualTo(1);
        assertThat(saved.getFailureCode()).isEqualTo("blocked");
        assertThat(saved.getFailureDetail()).isEqualTo("目前无法登录");
        assertThat(saved.getActivationId()).isEqualTo("failure-order");
        assertThat(saved.getLeaseToken()).isNull();
        assertThat(service.result(permit, failure)).isEqualTo(first);
        assertThat(service.inspect(permit)).isEqualTo(first);
        assertThat(mapper.findItem(item.getId()).getUpdatedAt()).isEqualTo(saved.getUpdatedAt());
        // 旧 WAITING_CODE 快照不能在首次失败后获得租约并覆盖已保存的终态。
        item.setLeaseToken("stale"); item.setLeaseUntil(now + 10000);
        assertThat(mapper.claim(item, System.currentTimeMillis())).isZero();
        TenantContext.set(8L);
        assertThat(mapper.findItem(item.getId())).isNull();
        assertThatThrownBy(() -> service.result(permit, failure)).isInstanceOf(com.armada.shared.exception.BusinessException.class);
        org.mockito.Mockito.verifyNoInteractions(sms);
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
            factory.setPlugins(interceptor); factory.setMapperLocations(new ClassPathResource("mapper/account/AccountRegistrationMapper.xml"),
                    new ClassPathResource("mapper/account/DeviceRegistrationPermitMapper.xml"));
            return factory.getObject();
        }
        @Bean AccountRegistrationMapper mapper(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory).getMapper(AccountRegistrationMapper.class);
        }
        @Bean DeviceRegistrationPermitMapper permits(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory).getMapper(DeviceRegistrationPermitMapper.class);
        }
        @Bean TransactionTemplate transactions(DataSource source) {
            return new TransactionTemplate(new DataSourceTransactionManager(source));
        }
    }
}
