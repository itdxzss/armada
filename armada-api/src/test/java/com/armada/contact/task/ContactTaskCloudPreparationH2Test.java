package com.armada.contact.task;

import com.armada.account.contact.model.StatusAudienceResolution;
import com.armada.account.contact.model.StatusAudienceView;
import com.armada.account.contact.mapper.AccountStatusAudienceMapper;
import com.armada.account.contact.mapper.AccountContactMapper;
import com.armada.account.contact.service.CloudStatusAudienceCollector;
import com.armada.account.contact.service.CloudStatusAudienceService;
import com.armada.account.selection.mapper.AccountFilterSelectionMapper;
import com.armada.account.service.impl.AccountMessagingAudienceServiceImpl;
import com.armada.platform.protocol.port.ContactPort;
import com.armada.platform.protocol.model.result.CloudContactsPage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.armada.account.selection.model.SelectedAccount;
import com.armada.account.service.AccountMessagingAudienceService;
import com.armada.boot.config.MyBatisConfig;
import com.armada.contact.task.mapper.ContactFriendTaskAccountMapper;
import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.mapper.ContactFriendTaskRecipientMapper;
import com.armada.contact.task.model.entity.ContactFriendTask;
import com.armada.contact.task.scheduler.ContactTaskLifecycleWorker;
import com.armada.contact.task.service.ContactTaskCloudPreparationService;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.awaitility.Awaitility.await;

/** 使用真实 Mapper、租户插件及事务验证名单固化、并发、回滚与任务收尾。 */
class ContactTaskCloudPreparationH2Test {
    private final SelectedAccount sender = new SelectedAccount(20L, "100000", "android", "account");
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private ContactFriendTaskMapper tasks;
    private ContactFriendTaskAccountMapper accounts;
    private ContactFriendTaskRecipientMapper recipients;
    private AccountMessagingAudienceService audiences;
    private ContactTaskCloudPreparationService service;
    private AccountStatusAudienceMapper cloudSnapshots;

    @BeforeEach
    void setUp() throws Exception {
        TenantContext.set(7L);
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V163__contact_friend_task.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V181__account_status_audience.sql"));
        }
        // V165/V183 的 MySQL PREPARE 语法不受 H2 支持；在测试库补齐相同的运行时字段和 JID 唯一键。
        jdbc.execute("ALTER TABLE contact_friend_task ADD current_round_no BIGINT DEFAULT 0");
        jdbc.execute("ALTER TABLE contact_friend_task_account ADD stop_reason VARCHAR(255)");
        jdbc.execute("ALTER TABLE contact_friend_task_recipient ALTER COLUMN contact_phone DROP NOT NULL");
        jdbc.execute("ALTER TABLE contact_friend_task_recipient ADD round_no BIGINT");
        jdbc.execute("ALTER TABLE contact_friend_task_recipient ADD command_id VARCHAR(64)");
        jdbc.execute("ALTER TABLE contact_friend_task_recipient ADD delivered_at BIGINT");
        jdbc.execute("ALTER TABLE contact_friend_task_recipient ADD read_at BIGINT");
        jdbc.execute("CREATE UNIQUE INDEX uq_preparation_jid ON contact_friend_task_recipient(tenant_id, task_id, task_account_id, contact_jid)");
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        MyBatisConfig mybatis = new MyBatisConfig();
        factory.setPlugins(mybatis.mybatisPlusInterceptor(mybatis.tenantLineHandler()));
        factory.setMapperLocations(new ClassPathResource("mapper/contact/ContactFriendTaskMapper.xml"),
                new ClassPathResource("mapper/contact/ContactFriendTaskAccountMapper.xml"),
                new ClassPathResource("mapper/contact/ContactFriendTaskRecipientMapper.xml"),
                new ClassPathResource("mapper/account/AccountStatusAudienceMapper.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        tasks = session.getMapper(ContactFriendTaskMapper.class);
        accounts = session.getMapper(ContactFriendTaskAccountMapper.class);
        recipients = session.getMapper(ContactFriendTaskRecipientMapper.class);
        cloudSnapshots = session.getMapper(AccountStatusAudienceMapper.class);
        audiences = mock(AccountMessagingAudienceService.class);
        service = new ContactTaskCloudPreparationService(audiences, tasks, accounts, recipients);
        jdbc.update("INSERT INTO contact_friend_task(id, tenant_id, name, message_type, content, created_at, updated_at, run_status, max_sends_per_account) VALUES(1,7,'task',1,'hi',1,1,1,2)");
        jdbc.update("INSERT INTO contact_friend_task_account(id, tenant_id, task_id, account_id, state, created_at, updated_at) VALUES(10,7,1,20,'PREPARING',1,1)");
        when(audiences.selectSendableByIds(List.of(20L))).thenReturn(List.of(sender));
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @Test
    void backgroundScanFindsDueTenantsWhileTaskExecutionRemainsIsolated() {
        jdbc.update("UPDATE contact_friend_task SET next_round_at=10 WHERE id=1");
        jdbc.update("INSERT INTO contact_friend_task(id,tenant_id,name,message_type,content,created_at,updated_at,run_status,next_round_at,is_enabled,task_start_at) VALUES(2,8,'scheduled',1,'hi',1,1,0,10,1,10)");
        TenantContext.clear();
        assertThat(tasks.selectDueRunningTasks(100L, 20)).extracting(ContactFriendTask::getTenantId).containsExactly(7L);
        assertThat(tasks.selectDueScheduledTasks(100L, 20)).extracting(ContactFriendTask::getTenantId).containsExactly(8L);
        assertThat(tasks.selectById(1L)).isNull();
        TenantContext.set(7L);
        assertThat(tasks.selectById(1L)).isNotNull();
        assertThat(tasks.selectById(2L)).isNull();
        assertThat(tasks.updateRunStatus(2L, 0, 1, 100L, 100L)).isZero();
    }

    private void prepare() {
        tx.executeWithoutResult(status -> service.prepare(tasks.selectByIdForUpdate(1L), 1000L));
    }

    private void cloud(String status, String... jids) {
        when(audiences.resolveContactAudience(any(), anyLong())).thenReturn(new StatusAudienceResolution(
                new StatusAudienceView(status, "CLOUD_LID", jids.length, 900L, "CLOUD_FETCH_FAILED", "采集失败"),
                List.of(jids)));
    }

    @Test
    void committedPreparationCollectsCloudPagesThenFreezesTargetsButRollbackDoesNotFetch() {
        ContactPort port = mock(ContactPort.class);
        when(port.cloudPage(any())).thenReturn(new CloudContactsPage(List.of("123456@lid"), "v1", "", false));
        var selection = mock(AccountFilterSelectionMapper.class);
        when(selection.selectSendableByIds(List.of(20L), AccountFilterSelectionMapper.ACCOUNT_STATE_NORMAL,
                AccountFilterSelectionMapper.ACCOUNT_STATE_EXPORTED)).thenReturn(List.of(sender));
        var cloudService = new CloudStatusAudienceService(cloudSnapshots,
                new CloudStatusAudienceCollector(port), new ObjectMapper());
        var accountService = new AccountMessagingAudienceServiceImpl(selection, mock(AccountContactMapper.class),
                cloudSnapshots, cloudService);
        service = new ContactTaskCloudPreparationService(accountService, tasks, accounts, recipients);
        try {
            tx.executeWithoutResult(status -> {
                service.prepare(tasks.selectByIdForUpdate(1L), 1000L);
                status.setRollbackOnly();
            });
            verifyNoInteractions(port);
            assertThat(cloudSnapshots.selectByAccountId(20L)).isNull();
            prepare();
            assertThat(accounts.countPreparing(1L)).isOne();
            assertThat(recipients.countByAccount(1L, 10L)).isZero();
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                TenantContext.set(7L);
                assertThat(cloudSnapshots.selectByAccountId(20L).getSyncStatus()).isEqualTo(2);
            });
            prepare();
            assertThat(accounts.countPreparing(1L)).isZero();
            assertThat(recipients.selectPage(1L, 10L, 0, 20)).extracting(row -> row.getContactJid())
                    .containsExactly("123456@lid");
            verify(port, times(1)).cloudPage(any());
        } finally { cloudService.shutdown(); }
    }

    @Test
    void completeCloudLidsAreCappedDeduplicatedAndFrozenWithoutPhoneOrAddressBook() {
        cloud("READY", "123456@lid", "123456@lid", "654321@lid", "999999@lid");
        prepare();
        prepare();

        var rows = recipients.selectPage(1L, 10L, 0, 20);
        assertThat(rows).extracting(row -> row.getContactJid()).containsExactly("123456@lid", "654321@lid");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getContactPhone()).isNull();
            assertThat(row.getContactNamed()).isZero();
            assertThat(row.getSendStatus()).isEqualTo("PENDING");
        });
        assertThat(accounts.selectById(10L).getState()).isEqualTo("PENDING");
        assertThat(tasks.selectById(1L).getTotalSendNum()).isEqualTo(2);
        assertThat(tasks.selectById(1L).getUsedAccountCount()).isOne();
        verify(audiences, times(1)).resolveContactAudience(sender, 1L);
    }

    @Test
    void syncingNeverUsesPartialTargetsOrCompletesTask() {
        cloud("SYNCING", "123456@lid");
        prepare();
        tx.executeWithoutResult(status -> new ContactTaskLifecycleWorker(tasks, accounts, recipients,
                Clock.systemUTC()).completeDrainedTask(7L, 1L));
        assertThat(recipients.countByAccount(1L, 10L)).isZero();
        assertThat(accounts.countPreparing(1L)).isOne();
        assertThat(tasks.selectById(1L).getRunStatus()).isOne();
    }

    @Test
    void failedAndEmptyListsFinishWithoutSendOrAutomaticReexpansion() {
        cloud("FAILED");
        prepare();
        assertThat(accounts.selectById(10L).getState()).isEqualTo("FAILED");
        assertThat(accounts.selectById(10L).getStopReason()).isEqualTo("采集失败");
        cloud("READY", "123456@lid");
        prepare();
        assertThat(recipients.countByAccount(1L, 10L)).isZero();
        tx.executeWithoutResult(status -> new ContactTaskLifecycleWorker(tasks, accounts, recipients,
                Clock.systemUTC()).completeDrainedTask(7L, 1L));
        assertThat(tasks.selectById(1L).getRunStatus()).isEqualTo(2);
        assertThat(tasks.selectById(1L).getInvalidAccountNum()).isOne();
    }

    @Test
    void emptyCloudListHasVisibleSkipReason() {
        cloud("EMPTY");
        prepare();
        assertThat(accounts.selectById(10L).getState()).isEqualTo("SKIPPED");
        assertThat(accounts.selectById(10L).getStopReason()).isEqualTo("没有可发送好友（已排除账号自身）");
        assertThat(recipients.countByAccount(1L, 10L)).isZero();
    }

    @Test
    void emptyReadyAudienceIsSkippedWithoutCreatingSendRecipients() {
        cloud("READY");
        prepare();
        assertThat(accounts.selectById(10L).getState()).isEqualTo("SKIPPED");
        assertThat(accounts.selectById(10L).getStopReason()).isEqualTo("没有可发送好友（已排除账号自身）");
        assertThat(recipients.countByAccount(1L, 10L)).isZero();
    }

    @Test
    void preparationAndRecipientRowsRollBackTogether() {
        cloud("READY", "123456@lid");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            service.prepare(tasks.selectByIdForUpdate(1L), 1000L);
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(accounts.selectById(10L).getState()).isEqualTo("PREPARING");
        assertThat(recipients.countByAccount(1L, 10L)).isZero();
        assertThat(tasks.selectById(1L).getTotalSendNum()).isZero();
    }

    @Test
    void preparingQueriesAndUpdatesRespectTenant() {
        jdbc.update("INSERT INTO contact_friend_task_account(id, tenant_id, task_id, account_id, state, need_send_num, created_at, updated_at) VALUES(11,8,1,21,'PENDING',99,1,1)");
        tasks.refreshExpansionTotals(1L, 1000L);
        assertThat(tasks.selectById(1L).getTotalSendNum()).isZero();
        assertThat(tasks.selectById(1L).getUsedAccountCount()).isZero();
        TenantContext.set(8L);
        assertThat(accounts.selectPreparing(1L)).isEmpty();
        assertThat(accounts.countPreparing(1L)).isZero();
        assertThat(tasks.selectByIdForUpdate(1L)).isNull();
        assertThat(tasks.refreshExpansionTotals(1L, 99L)).isZero();
        TenantContext.set(7L);
        var row = accounts.selectById(10L);
        row.setState("FAILED");
        TenantContext.set(8L);
        assertThat(accounts.finishPreparation(row)).isZero();
        TenantContext.set(7L);
        assertThat(accounts.selectById(10L).getState()).isEqualTo("PREPARING");
    }

    @Test
    void concurrentRoundWaitsForTaskLockAndCannotDuplicateTargets() throws Exception {
        cloud("READY", "123456@lid");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                TenantContext.set(7L);
                try {
                    tx.executeWithoutResult(status -> {
                        ContactFriendTask task = tasks.selectByIdForUpdate(1L);
                        locked.countDown();
                        try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                        catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
                        service.prepare(task, 1000L);
                    });
                } finally { TenantContext.clear(); }
            });
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                TenantContext.set(7L);
                try { secondStarted.countDown(); prepare(); }
                finally { TenantContext.clear(); }
            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertThat(recipients.countByAccount(1L, 10L)).isOne();
            verify(audiences, times(1)).resolveContactAudience(sender, 1L);
        } finally { release.countDown(); executor.shutdownNow(); }
    }
}
