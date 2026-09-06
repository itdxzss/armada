package com.armada.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.armada.account.converter.AccountConverterImpl;
import com.armada.account.converter.FullParamsToSixConverter;
import com.armada.account.dispatch.AccountImportOnlineDispatcher;
import com.armada.account.dispatch.AccountImportOnlineDispatchWorker;
import com.armada.account.mapper.AccountCredentialMapper;
import com.armada.account.mapper.AccountGroupMapper;
import com.armada.account.mapper.AccountImportBatchMapper;
import com.armada.account.mapper.AccountImportDetailMapper;
import com.armada.account.mapper.AccountMapper;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.model.dto.DeviceImportDTO;
import com.armada.account.model.dto.DeviceImportDefaults;
import com.armada.account.model.vo.AccountBatchOnlineVO;
import com.armada.account.service.impl.AccountGroupServiceImpl;
import com.armada.account.service.impl.AccountImportRowWriter;
import com.armada.account.service.impl.AccountImportServiceImpl;
import com.armada.account.service.impl.DeviceImportServiceImpl;
import com.armada.boot.config.MyBatisConfig;
import com.armada.boot.security.DeviceIngestTokens;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.armada.testsupport.DeviceImportTestData;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实 Mapper XML、租户插件、行锁和 Spring 事务验证；不连接生产或测试 MySQL。 */
@SpringJUnitConfig(DeviceImportTransactionTest.Config.class)
class DeviceImportTransactionTest {

    @Autowired private DeviceImportService service;
    @Autowired private AccountImportService imports;
    @Autowired private AccountImportDetailMapper details;
    @Autowired private AccountImportOnlineDispatcher dispatcher;
    @Autowired private AccountOnlineCommandService online;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private TransactionTemplate transaction;
    private DeviceImportDefaults defaults;
    private static final String PHONE = "999000000001";

    @BeforeEach
    void setUp() {
        new ResourceDatabasePopulator(new ClassPathResource("device-import-schema.sql")).execute(dataSource);
        reset(online);
        defaults = defaults(7, 11);
        TenantContext.set(7L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void commitsOriginalPayloadAndSixCredentialIntoExistingQueue() throws Exception {
        String payload = DeviceImportTestData.payload(PHONE);
        var accepted = service.importAccount(new DeviceImportDTO(PHONE, payload), defaults);
        assertThat(accepted.onlinePhase()).isEqualTo("QUEUED");
        assertRowCounts(1);
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM account", Long.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT protocol_id FROM account", String.class)).isEqualTo("ANDROID");
        assertThat(jdbc.queryForObject("SELECT device_os FROM account", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT account_type FROM account", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT declared_account_type FROM account", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT account_group_id FROM account", Long.class)).isEqualTo(11);
        assertThat(jdbc.queryForObject("SELECT import_format FROM account_import_batch", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT ip_allocation_mode FROM account_import_batch", String.class)).isEqualTo("mixed");
        assertThat(jdbc.queryForObject("SELECT cred_format FROM account_credential", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT online_phase FROM account_import_detail", Integer.class)).isEqualTo(1);
        String raw = jdbc.queryForObject("SELECT raw_payload FROM account_import_detail", String.class);
        assertThat(sameContent(raw, payload)).isTrue();
        var runtime = new ObjectMapper().readTree(jdbc.queryForObject("SELECT creds_json FROM account_credential", String.class));
        assertThat(runtime.size()).isEqualTo(6);
        assertThat(runtime.has("phone_id")).isTrue();
        assertThat(runtime.has("clientStaticPrivateKey")).isFalse();
        assertThat(jdbc.queryForObject("SELECT created_at FROM account", Long.class)).isGreaterThan(1_000_000_000_000L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"account_state", "account_credential", "account_import_detail", "account_import_batch"})
    void laterWriteFailureRollsBackAllFiveTables(String table) {
        String check = table.equals("account_import_batch") ? "imported_rows = 0" : "account_id < 0";
        jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT injected_failure CHECK (" + check + ")");
        assertThatThrownBy(() -> service.importAccount(request(), defaults))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .satisfies(error -> assertThat(error.getMessage().contains("injected_failure")).isTrue());
        assertRowCounts(0);
    }

    @Test
    void adminIosImportStillPreservesNativeRuntimeCredential() throws Exception {
        var json = new ObjectMapper();
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(DeviceImportTestData.payload(PHONE));
        node.put("phone", PHONE);
        node.put("jid", PHONE + "@s.whatsapp.net");
        node.put("platform", "smb_ios");
        node.put("lid", "");
        node.put("registrationID", 1);
        node.put("signPreKeyID", 1);
        // 无效测试材料只满足格式检查，不是设备凭据。
        for (String key : List.of("clientStaticPublicKey", "clientStaticPrivateKey", "identityPublicKey",
                "identityPrivateKey", "signPreKeyPublicKey", "signPreKeyPrivateKey", "edgeRoutingInfo")) {
            node.put(key, java.util.Base64.getEncoder().encodeToString(new byte[32]));
        }
        node.put("signPreKeySignature", java.util.Base64.getEncoder().encodeToString(new byte[64]));
        String payload = node.toString();
        assertThat(imports.importAccounts(defaults.metadata(), null, payload).importedRows()).isEqualTo(1);
        assertRowCounts(1);
        assertThat(jdbc.queryForObject("SELECT cred_format FROM account_credential", Integer.class)).isEqualTo(4);
        assertThat(sameContent(jdbc.queryForObject("SELECT raw_payload FROM account_import_detail", String.class), payload)).isTrue();
        var runtime = json.readTree(jdbc.queryForObject("SELECT creds_json FROM account_credential", String.class));
        assertThat(runtime.has("signPreKeyPrivateKey")).isTrue();
        assertThat(runtime.has("static_pri_key")).isFalse();
    }

    @Test
    void duplicateOfflineAndOnlineAccountsNeverOverwriteOrLeaveFailedBatch() throws Exception {
        service.importAccount(request(), defaults);
        String before = jdbc.queryForObject("SELECT creds_json FROM account_credential", String.class);
        for (int state : new int[]{0, 1}) {
            jdbc.update("UPDATE account_import_detail SET online_phase = 3");
            jdbc.update("UPDATE account_state SET login_state = ?", state);
            assertThatThrownBy(() -> service.importAccount(request(), defaults))
                    .isInstanceOfSatisfying(BusinessException.class,
                            ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.code()));
            assertRowCounts(1);
            assertThat(sameContent(before, jdbc.queryForObject("SELECT creds_json FROM account_credential", String.class))).isTrue();
        }
    }

    @Test
    void pendingDetailBlocksDeletedAccountButSettledDeletedAccountCanBeReimported() {
        service.importAccount(request(), defaults);
        jdbc.update("UPDATE account SET deleted_at = 100");
        for (int phase : new int[]{1, 2}) {
            jdbc.update("UPDATE account_import_detail SET online_phase = ?", phase);
            assertThatThrownBy(() -> service.importAccount(request(), defaults)).isInstanceOf(BusinessException.class);
            assertRowCounts(1);
        }
        jdbc.update("UPDATE account_import_detail SET online_phase = 3");
        service.importAccount(request(), defaults);
        assertRowCounts(2);
    }

    @Test
    void groupAndPendingQueriesEnforceTenantIsolation() {
        assertThatThrownBy(() -> service.importAccount(request(), defaults(7, 12))).isInstanceOf(BusinessException.class);
        assertRowCounts(0);
        service.importAccount(request(), defaults);
        TenantContext.set(8L);
        assertThat(details.existsPendingByPhone(PHONE, 1, 1, 2)).isFalse();
        service.importAccount(request(), defaults(8, 12));
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT tenant_id) FROM account", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE tenant_id=8 AND account_group_id=12", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateWaitsForUniqueKeyThenReturnsConflictAndRollsBack() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> first = pool.submit(() -> {
                TenantContext.set(7L);
                try {
                    transaction.executeWithoutResult(status -> {
                        service.importAccount(request(), defaults);
                        inserted.countDown();
                        await(release);
                    });
                } finally {
                    TenantContext.clear();
                }
            });
            assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Integer> second = pool.submit(() -> {
                TenantContext.set(7L);
                try {
                    service.importAccount(request(), defaults);
                    return 200;
                } catch (BusinessException ex) {
                    return ex.getCode();
                } finally {
                    TenantContext.clear();
                }
            });
            awaitDatabaseLock();
            assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(ErrorCode.CONFLICT.code());
            assertRowCounts(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void existingDispatcherUsesRealTenantLockAndOnlyAdvancesAcceptedRows() {
        service.importAccount(request(), defaults);
        when(online.onlineBatch(anyList())).thenReturn(new AccountBatchOnlineVO(1, 1, 1, 0, 0, 0, 0, 0, List.of(), List.of()));
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT online_phase FROM account_import_detail", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT dispatch_attempts FROM account_import_detail", Integer.class)).isEqualTo(1);
        assertThat(TenantContext.get()).isEqualTo(7L);
        jdbc.update("UPDATE account_import_detail SET online_phase = 1");
        when(online.onlineBatch(anyList())).thenReturn(new AccountBatchOnlineVO(1, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of()));
        assertThat(dispatcher.dispatchOnce()).isZero();
        assertThat(jdbc.queryForObject("SELECT online_phase FROM account_import_detail", Integer.class)).isEqualTo(1);
    }

    private void awaitDatabaseLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            // H2 唯一索引等待不一定填 BLOCKER_ID，结合正在执行的真实 INSERT 和 Future 超时证明等待。
            int blocked = jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS "
                    + "WHERE BLOCKER_ID IS NOT NULL OR LOWER(EXECUTING_STATEMENT) LIKE 'insert into account (%'", Integer.class);
            if (blocked > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("第二个真实数据库事务未进入锁等待");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("测试事务等待超时");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("测试事务被中断");
        }
    }

    private void assertRowCounts(int count) {
        for (String table : List.of("account", "account_state", "account_credential", "account_import_batch", "account_import_detail")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).as(table).isEqualTo(count);
        }
    }

    private static DeviceImportDTO request() {
        return new DeviceImportDTO(PHONE, DeviceImportTestData.payload(PHONE));
    }

    private static DeviceImportDefaults defaults(long tenantId, long groupId) {
        String token = DeviceImportTestData.token();
        return new DeviceIngestTokens(DeviceImportTestData.clients(token, tenantId, groupId)).resolve(token).orElseThrow();
    }

    private static boolean sameContent(String actual, String expected) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return MessageDigest.isEqual(digest.digest(actual.getBytes(StandardCharsets.UTF_8)),
                digest.digest(expected.getBytes(StandardCharsets.UTF_8)));
    }

    @Configuration
    @EnableTransactionManagement
    @Import({MyBatisConfig.class, AccountConverterImpl.class, FullParamsToSixConverter.class,
            AccountImportParser.class, AccountGroupServiceImpl.class, AccountImportRowWriter.class,
            AccountImportServiceImpl.class, DeviceImportServiceImpl.class,
            AccountImportOnlineDispatchWorker.class, AccountImportOnlineDispatcher.class})
    static class Config {
        @Bean DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:device_import;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=8000");
            return source;
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean TransactionTemplate transactionTemplate(PlatformTransactionManager manager) { return new TransactionTemplate(manager); }
        @Bean AccountOnlineCommandService online() { return mock(AccountOnlineCommandService.class); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source, MybatisPlusInterceptor plugin) throws Exception {
            MybatisConfiguration config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            config.setUseGeneratedKeys(true);
            config.setLogImpl(NoLoggingImpl.class);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setConfiguration(config);
            factory.setPlugins(plugin);
            factory.setMapperLocations(new ClassPathResource("mapper/account/AccountMapper.xml"),
                    new ClassPathResource("mapper/account/AccountStateMapper.xml"),
                    new ClassPathResource("mapper/account/AccountCredentialMapper.xml"),
                    new ClassPathResource("mapper/account/AccountGroupMapper.xml"),
                    new ClassPathResource("mapper/account/AccountImportBatchMapper.xml"),
                    new ClassPathResource("mapper/account/AccountImportDetailMapper.xml"));
            return factory.getObject();
        }
        @Bean SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
        @Bean AccountMapper accounts(SqlSessionTemplate sql) { return sql.getMapper(AccountMapper.class); }
        @Bean AccountStateMapper states(SqlSessionTemplate sql) { return sql.getMapper(AccountStateMapper.class); }
        @Bean AccountCredentialMapper credentials(SqlSessionTemplate sql) { return sql.getMapper(AccountCredentialMapper.class); }
        @Bean AccountGroupMapper groups(SqlSessionTemplate sql) { return sql.getMapper(AccountGroupMapper.class); }
        @Bean AccountImportBatchMapper batches(SqlSessionTemplate sql) { return sql.getMapper(AccountImportBatchMapper.class); }
        @Bean AccountImportDetailMapper details(SqlSessionTemplate sql) { return sql.getMapper(AccountImportDetailMapper.class); }
    }
}
