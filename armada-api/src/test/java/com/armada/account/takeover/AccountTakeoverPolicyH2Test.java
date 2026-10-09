package com.armada.account.takeover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.account.mapper.AccountCreatorDeletionMapper;
import com.armada.account.mapper.AccountTakeoverBreakerMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountState;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.testsupport.CreatorDeletionH2Schema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
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
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/** 使用真实 Mapper、租户插件和事务验证被挤熔断及自动抢登准入闭合。 */
@SpringJUnitConfig(AccountTakeoverPolicyH2Test.TestConfig.class)
@TestExecutionListeners(listeners = DependencyInjectionTestExecutionListener.class, inheritListeners = false)
class AccountTakeoverPolicyH2Test {

    private static final long TENANT_ID = 7L;
    private static final long ACCOUNT_ID = 100L;

    @Autowired private DataSource source;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private AccountTakeoverBreaker breaker;
    @Autowired private AccountTakeoverPolicy policy;
    @Autowired private AccountCreatorReservationLookup reservations;
    @Autowired private AccountAutoTakeoverProperties properties;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(transactionManager);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("""
                CREATE TABLE account (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, deleted_at BIGINT,
                  creator_deletion_identity_phone VARCHAR(32)
                )
                """);
        jdbc.execute(CreatorDeletionH2Schema.DDL);
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V215__account_takeover_breaker.sql"));
        var ddl = Pattern.compile("CREATE TABLE IF NOT EXISTS account_takeover_breaker.*?\\)\\s*ENGINE",
                Pattern.DOTALL).matcher(migration);
        assertThat(ddl.find()).isTrue();
        jdbc.execute(ddl.group().replaceFirst("\\s*ENGINE$", ""));
        String dedupMigration = Files.readString(Path.of(
                "src/main/resources/db/migration/V216__account_takeover_kick_dedup.sql"));
        var dedupDdl = Pattern.compile("'ALTER TABLE account_takeover_breaker.*?',\\s*'SELECT 1'",
                Pattern.DOTALL).matcher(dedupMigration);
        assertThat(dedupDdl.find()).isTrue();
        String quotedDdl = dedupDdl.group();
        jdbc.execute(quotedDdl.substring(1, quotedDdl.lastIndexOf("',")).replace("''", "'"));
        jdbc.update("INSERT INTO account VALUES (100,7,NULL,'12345678900'),(200,8,NULL,'12345678900')");
        properties.setEnabled(true);
        properties.setBreakerWindowMs(600_000L);
        properties.setBreakerMaxKicks(10);
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void ninthKickContinuesAndTenthTrips() {
        for (int kick = 1; kick <= 9; kick++) {
            assertThat(breaker.recordKick(account(ACCOUNT_ID, TENANT_ID), 1_000L + kick))
                    .isEqualTo(AccountTakeoverBreaker.KickResult.CONTINUE);
        }
        assertThat(breaker.recordKick(account(ACCOUNT_ID, TENANT_ID), 1_010L))
                .isEqualTo(AccountTakeoverBreaker.KickResult.TRIPPED);
        assertThat(kicks()).isEqualTo(10);
        assertThat(breaker.isTripped(ACCOUNT_ID)).isTrue();
        assertThat(jdbc.queryForObject("SELECT tripped_at FROM account_takeover_breaker", Long.class))
                .isEqualTo(1_010L);
    }

    @Test
    void expiredUntrippedWindowStartsAtOneAtTheExactBoundary() {
        breaker.recordKick(account(ACCOUNT_ID, TENANT_ID), 1_000L);
        breaker.recordKick(account(ACCOUNT_ID, TENANT_ID), 1_001L);
        breaker.recordKick(account(ACCOUNT_ID, TENANT_ID), 601_000L);

        assertThat(kicks()).isOne();
        assertThat(jdbc.queryForObject("SELECT window_started_at FROM account_takeover_breaker", Long.class))
                .isEqualTo(601_000L);
        assertThat(breaker.isTripped(ACCOUNT_ID)).isFalse();
    }

    @Test
    void replayOfPreviousWindowKickNeitherCountsNorReopensTheNewWindow() {
        breaker.recordKick(account(ACCOUNT_ID, TENANT_ID), 1_000L);
        breaker.recordKick(account(ACCOUNT_ID, TENANT_ID), 601_000L);
        var newWindow = jdbc.queryForMap("SELECT * FROM account_takeover_breaker");

        assertThat(breaker.recordKick(account(ACCOUNT_ID, TENANT_ID), 1_000L))
                .isEqualTo(AccountTakeoverBreaker.KickResult.CONTINUE);

        assertThat(kicks()).isOne();
        assertThat(jdbc.queryForMap("SELECT * FROM account_takeover_breaker")).isEqualTo(newWindow);
    }

    @Test
    void manualResetKeepsTheDedupWatermarkWithoutRecountingTheLastKick() {
        breaker.recordKick(account(ACCOUNT_ID, TENANT_ID), 1_000L);
        breaker.reset(List.of(ACCOUNT_ID), 2_000L);

        assertThat(breaker.recordKick(account(ACCOUNT_ID, TENANT_ID), 1_000L))
                .isEqualTo(AccountTakeoverBreaker.KickResult.CONTINUE);

        assertThat(kicks()).isZero();
        assertThat(jdbc.queryForObject("SELECT window_started_at FROM account_takeover_breaker", Long.class)).isNull();
    }

    @Test
    void duplicateKickKeepsTheExistingTrippedDecisionAndRow() {
        trip();
        var tripped = jdbc.queryForMap("SELECT * FROM account_takeover_breaker");

        assertThat(breaker.recordKick(account(ACCOUNT_ID, TENANT_ID), 1_010L))
                .isEqualTo(AccountTakeoverBreaker.KickResult.TRIPPED);

        assertThat(jdbc.queryForMap("SELECT * FROM account_takeover_breaker")).isEqualTo(tripped);
    }

    @Test
    void missingKickTimeDoesNotChangeAnExistingTripAndReturnsContinue() {
        trip();
        var tripped = jdbc.queryForMap("SELECT * FROM account_takeover_breaker");

        assertThat(breaker.recordKick(account(ACCOUNT_ID, TENANT_ID), null))
                .isEqualTo(AccountTakeoverBreaker.KickResult.CONTINUE);

        assertThat(jdbc.queryForMap("SELECT * FROM account_takeover_breaker")).isEqualTo(tripped);
    }

    @Test
    void usesConfiguredWindowAndKickThreshold() {
        properties.setBreakerWindowMs(50L);
        properties.setBreakerMaxKicks(2);
        breaker.recordKick(account(ACCOUNT_ID, TENANT_ID), 1_000L);
        assertThat(breaker.recordKick(account(ACCOUNT_ID, TENANT_ID), 1_050L))
                .isEqualTo(AccountTakeoverBreaker.KickResult.CONTINUE);
        assertThat(breaker.recordKick(account(ACCOUNT_ID, TENANT_ID), 1_051L))
                .isEqualTo(AccountTakeoverBreaker.KickResult.TRIPPED);
        assertThat(kicks()).isEqualTo(2);
    }

    @Test
    void trippedBreakerNeverRecoversJustBecauseTheWindowExpired() {
        trip();
        assertThat(breaker.recordKick(account(ACCOUNT_ID, TENANT_ID), 900_000L))
                .isEqualTo(AccountTakeoverBreaker.KickResult.TRIPPED);
        assertThat(breaker.isTripped(ACCOUNT_ID)).isTrue();
        assertThat(jdbc.queryForObject("SELECT tripped_at FROM account_takeover_breaker", Long.class))
                .isEqualTo(1_010L);
    }

    @Test
    void manualResetClearsWindowCounterAndTripThenAllowsANewWindow() {
        trip();
        policy.reset(List.of(ACCOUNT_ID), 2_000L);
        assertThat(kicks()).isZero();
        assertThat(jdbc.queryForObject("SELECT window_started_at FROM account_takeover_breaker", Long.class)).isNull();
        assertThat(breaker.isTripped(ACCOUNT_ID)).isFalse();
        breaker.recordKick(account(ACCOUNT_ID, TENANT_ID), 2_001L);
        assertThat(kicks()).isOne();
    }

    @Test
    void tenantPluginPreventsForeignReadsAndResets() {
        trip();
        TenantContext.set(8L);
        assertThat(breaker.isTripped(ACCOUNT_ID)).isFalse();
        breaker.reset(List.of(ACCOUNT_ID), 2_000L);
        breaker.recordKick(account(200L, 8L), 2_000L);
        TenantContext.set(TENANT_ID);
        assertThat(breaker.isTripped(ACCOUNT_ID)).isTrue();
        assertThat(jdbc.queryForObject("SELECT kick_count FROM account_takeover_breaker WHERE tenant_id=8",
                Integer.class)).isOne();
    }

    @Test
    void failedOuterTransactionRollsBackTheKickAndItsInitialRow() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            breaker.recordKick(account(ACCOUNT_ID, TENANT_ID), 1_000L);
            throw new IllegalStateException("force state transaction rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_takeover_breaker", Integer.class)).isZero();
    }

    @Test
    void concurrentCopiesOfFirstKickWaitForTheAccountLockAndCountOnlyOnce() throws Exception {
        var executor = Executors.newFixedThreadPool(10);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            futures.add(executor.submit(() -> {
                TenantContext.set(TENANT_ID);
                try {
                    transactions.executeWithoutResult(status -> {
                        breaker.recordKick(account(ACCOUNT_ID, TENANT_ID), 1_001L);
                        locked.countDown();
                        await(release);
                    });
                } finally { TenantContext.clear(); }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            for (int kick = 2; kick <= 10; kick++) {
                futures.add(executor.submit(() -> {
                    TenantContext.set(TENANT_ID);
                    try { breaker.recordKick(account(ACCOUNT_ID, TENANT_ID), 1_001L); }
                    finally { TenantContext.clear(); }
                }));
            }
            assertThatThrownBy(() -> futures.get(1).get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            release.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
            assertThat(kicks()).isOne();
            assertThat(breaker.isTripped(ACCOUNT_ID)).isFalse();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_takeover_breaker", Integer.class)).isOne();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4, 5, 8, 9})
    void terminalLifecycleStaysStickyWithoutRecordingAKick(int lifecycle) {
        assertThat(policy.onLoginReplaced(account(ACCOUNT_ID, TENANT_ID), state(lifecycle), 1_000L))
                .isEqualTo(AccountTakeoverPolicy.ReplacedDecision.KEEP_LIFECYCLE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_takeover_breaker", Integer.class)).isZero();
    }

    @Test
    void desiredOfflineAndMuteStopTakingOverWithoutCounting() {
        AccountState stopped = state(AccountStateCode.TAKING_OVER);
        stopped.setDesiredLoginState(AccountLoginStateCode.OFFLINE);
        AccountState muted = state(AccountStateCode.TAKING_OVER);
        muted.setMuteStatus(1);
        for (AccountState excluded : List.of(stopped, muted)) {
            assertThat(policy.onLoginReplaced(account(ACCOUNT_ID, TENANT_ID), excluded, 1_000L))
                    .isEqualTo(AccountTakeoverPolicy.ReplacedDecision.LOGIN_REPLACED);
        }
        assertThat(policy.desiredOffline(stopped)).isTrue();
        assertThat(policy.canReonline(ACCOUNT_ID, stopped)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_takeover_breaker", Integer.class)).isZero();
    }

    @Test
    void newAndUnknownLifecycleWaitForCompensationWithoutCounting() {
        AccountState unknown = state(AccountStateCode.NEW);
        unknown.setAccountState(null);
        for (AccountState candidate : List.of(state(AccountStateCode.NEW), unknown)) {
            assertThat(policy.onLoginReplaced(account(ACCOUNT_ID, TENANT_ID), candidate, 1_000L))
                    .isEqualTo(AccountTakeoverPolicy.ReplacedDecision.LOGIN_REPLACED);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_takeover_breaker", Integer.class)).isZero();
    }

    @Test
    void ordinaryHealthyAccountBecomesTakingOverAndTrippedAccountFallsBack() {
        assertThat(policy.onLoginReplaced(account(ACCOUNT_ID, TENANT_ID), state(AccountStateCode.NORMAL), 1_000L))
                .isEqualTo(AccountTakeoverPolicy.ReplacedDecision.TAKING_OVER);
        assertThat(kicks()).isOne();
        trip();
        assertThat(policy.onLoginReplaced(account(ACCOUNT_ID, TENANT_ID), state(AccountStateCode.TAKING_OVER), 2_000L))
                .isEqualTo(AccountTakeoverPolicy.ReplacedDecision.LOGIN_REPLACED);
        assertThat(policy.canReonline(ACCOUNT_ID, state(AccountStateCode.TAKING_OVER))).isFalse();
    }

    @Test
    void reservedIdentityCountsKicksButUsesOnlyItsOwningExecutionRecovery() {
        reservation("RESERVED");
        var found = reservations.find(ACCOUNT_ID).orElseThrow();
        assertThat(found.tenantId()).isEqualTo(8L);
        assertThat(found.taskId()).isEqualTo(80L);
        assertThat(found.groupExecutionId()).isEqualTo(800L);
        assertThat(reservations.find(200L)).isEmpty();
        assertThat(policy.onLoginReplaced(account(ACCOUNT_ID, TENANT_ID), state(AccountStateCode.NORMAL), 1_000L))
                .isEqualTo(AccountTakeoverPolicy.ReplacedDecision.LOGIN_REPLACED);
        assertThat(kicks()).isOne();
        assertThat(policy.canReonline(ACCOUNT_ID, state(AccountStateCode.TAKING_OVER))).isFalse();
        assertThat(policy.canAutoTakeover(ACCOUNT_ID, state(AccountStateCode.LOGIN_REPLACED))).isFalse();
    }

    @Test
    void deletingIdentityNeverCountsKicksOrAllowsAutomaticRecovery() {
        reservation("DELETING");
        assertThat(policy.onLoginReplaced(account(ACCOUNT_ID, TENANT_ID), state(AccountStateCode.NORMAL), 1_000L))
                .isEqualTo(AccountTakeoverPolicy.ReplacedDecision.LOGIN_REPLACED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_takeover_breaker", Integer.class)).isZero();
        assertThat(policy.canReonline(ACCOUNT_ID, state(AccountStateCode.TAKING_OVER))).isFalse();
    }

    @Test
    void autoTakeoverRequiresReplacedOfflineUnmutedUntrippedStateAndDesiredOnline() {
        AccountState candidate = state(AccountStateCode.LOGIN_REPLACED);
        assertThat(policy.canAutoTakeover(ACCOUNT_ID, candidate)).isTrue();
        candidate.setLoginState(AccountLoginStateCode.PENDING_ONLINE);
        assertThat(policy.canAutoTakeover(ACCOUNT_ID, candidate)).isFalse();
        candidate.setLoginState(AccountLoginStateCode.OFFLINE);
        candidate.setMuteStatus(1);
        assertThat(policy.canAutoTakeover(ACCOUNT_ID, candidate)).isFalse();
        candidate.setMuteStatus(null);
        candidate.setDesiredLoginState(AccountLoginStateCode.OFFLINE);
        assertThat(policy.canAutoTakeover(ACCOUNT_ID, candidate)).isFalse();
        assertThat(policy.canAutoTakeover(ACCOUNT_ID, state(AccountStateCode.NORMAL))).isFalse();
        trip();
        assertThat(policy.canAutoTakeover(ACCOUNT_ID, state(AccountStateCode.LOGIN_REPLACED))).isFalse();
    }

    @Test
    void disabledPolicyPreservesLegacyDecisionsAndDoesNotWriteOrResetBreaker() {
        trip();
        properties.setEnabled(false);
        AccountState stopped = state(AccountStateCode.TAKING_OVER);
        stopped.setDesiredLoginState(AccountLoginStateCode.OFFLINE);
        assertThat(policy.isEnabled()).isFalse();
        assertThat(policy.onLoginReplaced(account(ACCOUNT_ID, TENANT_ID), stopped, 2_000L))
                .isEqualTo(AccountTakeoverPolicy.ReplacedDecision.TAKING_OVER);
        assertThat(policy.onLoginReplaced(account(ACCOUNT_ID, TENANT_ID), state(AccountStateCode.BANNED), 2_000L))
                .isEqualTo(AccountTakeoverPolicy.ReplacedDecision.LOGIN_REPLACED);
        assertThat(policy.canReonline(ACCOUNT_ID, stopped)).isTrue();
        assertThat(policy.canAutoTakeover(ACCOUNT_ID, state(AccountStateCode.LOGIN_REPLACED))).isFalse();
        policy.reset(List.of(ACCOUNT_ID), 2_000L);
        assertThat(kicks()).isEqualTo(10);
        assertThat(breaker.isTripped(ACCOUNT_ID)).isTrue();
    }

    private void trip() {
        breaker.reset(List.of(ACCOUNT_ID), 900L);
        for (int kick = 1; kick <= 10; kick++) {
            breaker.recordKick(account(ACCOUNT_ID, TENANT_ID), 1_000L + kick);
        }
    }

    private int kicks() {
        return jdbc.queryForObject("SELECT kick_count FROM account_takeover_breaker WHERE tenant_id=7 AND account_id=100",
                Integer.class);
    }

    private void reservation(String lifecycle) {
        jdbc.update("""
                INSERT INTO account_creator_deletion
                  (account_id,tenant_id,task_id,group_execution_id,identity_hash,creator_phone,lifecycle)
                VALUES (200,8,80,800,'identity','12345678900',?)
                """, lifecycle);
    }

    private static Account account(long id, long tenantId) {
        Account account = new Account();
        account.setId(id);
        account.setTenantId(tenantId);
        return account;
    }

    private static AccountState state(int lifecycle) {
        AccountState state = new AccountState();
        state.setAccountId(ACCOUNT_ID);
        state.setAccountState(lifecycle);
        state.setLoginState(AccountLoginStateCode.OFFLINE);
        return state;
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    @Configuration
    @EnableTransactionManagement
    @Import({AccountTakeoverBreaker.class, AccountTakeoverPolicy.class, AccountCreatorReservationLookup.class})
    static class TestConfig {
        @Bean DataSource dataSource() { return PullTaskNormalLinkH2Support.dataSource("takeoverPolicy"); }
        @Bean AccountAutoTakeoverProperties properties() { return new AccountAutoTakeoverProperties(); }
        @Bean PlatformTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            MyBatisConfig config = new MyBatisConfig();
            return PullTaskNormalLinkH2Support.sqlSessionFactory(source,
                    config.mybatisPlusInterceptor(config.tenantLineHandler()),
                    "mapper/account/AccountTakeoverBreakerMapper.xml", "mapper/account/AccountCreatorDeletionMapper.xml");
        }
        @Bean SqlSessionTemplate session(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
        @Bean AccountTakeoverBreakerMapper breakerMapper(SqlSessionTemplate session) {
            return session.getMapper(AccountTakeoverBreakerMapper.class);
        }
        @Bean AccountCreatorDeletionMapper reservationMapper(SqlSessionTemplate session) {
            return session.getMapper(AccountCreatorDeletionMapper.class);
        }
    }
}
