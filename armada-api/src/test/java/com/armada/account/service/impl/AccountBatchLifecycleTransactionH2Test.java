package com.armada.account.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.armada.account.mapper.AccountCredentialMapper;
import com.armada.account.mapper.AccountMapper;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.model.vo.AccountBatchCommandResultVO;
import com.armada.account.service.AccountOnlineAttemptLogService;
import com.armada.account.service.AccountOnlineCommandService;
import com.armada.account.service.OnlineAttemptIdGenerator;
import com.armada.boot.config.MyBatisConfig;
import com.armada.platform.country.service.CountryService;
import com.armada.platform.kafka.config.NormalGroupCreationKafkaProperties;
import com.armada.platform.kafka.config.ProtocolAccountCommandProperties;
import com.armada.platform.kafka.config.ProtocolAndroidCommandProperties;
import com.armada.platform.kafka.config.ProtocolCommandDispatcherProperties;
import com.armada.platform.kafka.config.ProtocolMasterCommandProperties;
import com.armada.platform.kafka.dispatch.ProtocolCommandDispatcher;
import com.armada.platform.kafka.dispatch.ProtocolCommandDispatchTrigger;
import com.armada.platform.protocol.mapper.ProtocolCommandOutboxMapper;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.platform.protocol.model.enums.ProtocolCommandOutboxStatus;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.platform.protocol.service.impl.ProtocolCommandOutboxServiceImpl;
import com.armada.resource.service.IpProxyService;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.mapper.PullTaskNormalLinkSchema;
import com.armada.testsupport.CreatorDeletionH2Schema;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** 通过真实 Mapper、租户插件和 Spring 事务验证部分受限账号不会污染批量上下线结果。 */
class AccountBatchLifecycleTransactionH2Test {

    private JdbcTemplate db;
    private AccountBatchLifecycleServiceImpl service;
    private ProtocolCommandDispatcher dispatcher;
    private IpProxyService ipProxyService;
    private final List<Long> committedAccountsObservedAtDispatch = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        DataSource source = PullTaskNormalLinkH2Support.dataSource("batchLifecycle" + System.nanoTime());
        db = new JdbcTemplate(source);
        createSchema(source);
        MyBatisConfig config = new MyBatisConfig();
        var factory = PullTaskNormalLinkH2Support.sqlSessionFactory(source,
                config.mybatisPlusInterceptor(config.tenantLineHandler()),
                "mapper/account/AccountMapper.xml", "mapper/account/AccountStateMapper.xml",
                "mapper/account/AccountCredentialMapper.xml",
                "mapper/platform/protocol/ProtocolCommandOutboxMapper.xml");
        var session = new SqlSessionTemplate(factory);
        AccountMapper accounts = session.getMapper(AccountMapper.class);
        AccountStateMapper states = session.getMapper(AccountStateMapper.class);
        var transactionManager = new DataSourceTransactionManager(source);
        dispatcher = mock(ProtocolCommandDispatcher.class);
        doAnswer(invocation -> {
            // 使用另一物理连接读取，证明触发发送时 Outbox 已提交。
            try (var connection = source.getConnection();
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT aggregate_id FROM protocol_command_outbox "
                         + "WHERE command_type='account.offline.requested' ORDER BY aggregate_id")) {
                while (rows.next()) {
                    committedAccountsObservedAtDispatch.add(rows.getLong(1));
                }
            }
            return null;
        }).when(dispatcher).dispatchInsertedRows(anyList());
        var trigger = new ProtocolCommandDispatchTrigger(dispatcher, Runnable::run,
                new ProtocolCommandDispatcherProperties(), mock(TaskScheduler.class));
        ProtocolCommandOutboxService outbox = transactional(new ProtocolCommandOutboxServiceImpl(
                session.getMapper(ProtocolCommandOutboxMapper.class), new ObjectMapper(), trigger,
                new ProtocolAccountCommandProperties(), new ProtocolMasterCommandProperties(),
                new ProtocolAndroidCommandProperties(), new NormalGroupCreationKafkaProperties()),
                transactionManager, ProtocolCommandOutboxService.class);
        ipProxyService = mock(IpProxyService.class);
        AccountOnlineCommandService commands = transactional(new AccountOnlineCommandServiceImpl(
                accounts, session.getMapper(AccountCredentialMapper.class), states, ipProxyService,
                mock(CountryService.class), outbox, mock(OnlineAttemptIdGenerator.class),
                mock(AccountOnlineAttemptLogService.class), mock(AccountTakeoverReonlineCooldown.class),
                mock(com.armada.account.takeover.AccountTakeoverPolicy.class)),
                transactionManager, AccountOnlineCommandService.class);
        service = new AccountBatchLifecycleServiceImpl(accounts, commands);
        TenantContext.set(7L);
        account(1L, 7L);
        account(2L, 7L);
        account(3L, 8L);
        reserve(1L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"RESERVED", "DELETING", "DELETED"})
    void mixedOfflineCommitsOnlyEligibleAccountAfterRejectedTransactionRollsBack(String lifecycle) {
        db.update("UPDATE account_creator_deletion SET lifecycle=? WHERE account_id=1", lifecycle);
        pendingOnline(1L, 7L);
        pendingOnline(2L, 7L);
        pendingOnline(3L, 8L);

        AccountBatchCommandResultVO result = service.offlineByIds(List.of(1L, 2L));

        assertThat(result.requested()).isEqualTo(2);
        assertThat(result.submitted()).isEqualTo(2);
        assertThat(result.accepted()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.batchErrors()).singleElement().asString().contains("1", "预留");
        assertThat(desiredState(1L)).isEqualTo(AccountLoginStateCode.ONLINE);
        assertThat(desiredState(2L)).isEqualTo(AccountLoginStateCode.OFFLINE);
        assertThat(desiredState(3L)).isEqualTo(AccountLoginStateCode.ONLINE);
        assertThat(onlineCommandStatus(1L)).isEqualTo(ProtocolCommandOutboxStatus.PENDING.code());
        assertThat(onlineCommandStatus(2L)).isEqualTo(ProtocolCommandOutboxStatus.CANCELED.code());
        assertThat(onlineCommandStatus(3L)).isEqualTo(ProtocolCommandOutboxStatus.PENDING.code());
        assertThat(db.queryForList("SELECT aggregate_id FROM protocol_command_outbox "
                + "WHERE command_type='account.offline.requested'", Long.class)).containsExactly(2L);
        assertThat(committedAccountsObservedAtDispatch).containsExactly(2L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolCommandOutbox>> dispatched = ArgumentCaptor.forClass(List.class);
        verify(dispatcher).dispatchInsertedRows(dispatched.capture());
        assertThat(dispatched.getValue()).extracting(ProtocolCommandOutbox::getAggregateId).containsExactly(2L);
        verifyNoMoreInteractions(dispatcher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RESERVED", "DELETING", "DELETED"})
    void allRestrictedOfflineLeavesDesiredStateAndOldOnlineCommandUntouched(String lifecycle) {
        db.update("UPDATE account_creator_deletion SET lifecycle=? WHERE account_id=1", lifecycle);
        pendingOnline(1L, 7L);

        AccountBatchCommandResultVO result = service.offlineByIds(List.of(1L));

        assertThat(result.accepted()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.batchErrors()).singleElement().asString().contains("1", "预留");
        assertThat(desiredState(1L)).isEqualTo(AccountLoginStateCode.ONLINE);
        assertThat(onlineCommandStatus(1L)).isEqualTo(ProtocolCommandOutboxStatus.PENDING.code());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM protocol_command_outbox", Integer.class)).isEqualTo(1);
        verifyNoInteractions(dispatcher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELETING", "DELETED"})
    void deletingOnlineReportsAccountRejectionBeforeProxyAllocation(String lifecycle) {
        db.update("UPDATE account_creator_deletion SET lifecycle=? WHERE account_id=1", lifecycle);
        db.update("UPDATE account_state SET login_state=?,desired_login_state=? WHERE account_id=1",
                AccountLoginStateCode.OFFLINE, AccountLoginStateCode.OFFLINE);
        db.update("INSERT INTO account_credential(account_id,tenant_id) VALUES (1,7)");

        AccountBatchCommandResultVO result = service.onlineByIds(List.of(1L));

        assertThat(result.requested()).isEqualTo(1);
        assertThat(result.submitted()).isEqualTo(1);
        assertThat(result.accepted()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.batchErrors()).singleElement().asString().contains("账号 1", "注销");
        assertThat(desiredState(1L)).isEqualTo(AccountLoginStateCode.OFFLINE);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM protocol_command_outbox", Integer.class)).isZero();
        verifyNoInteractions(dispatcher, ipProxyService);
    }

    private Integer desiredState(long accountId) {
        return db.queryForObject("SELECT desired_login_state FROM account_state WHERE account_id=?",
                Integer.class, accountId);
    }

    private Integer onlineCommandStatus(long accountId) {
        return db.queryForObject("SELECT status FROM protocol_command_outbox WHERE command_id=?",
                Integer.class, "old-online-" + accountId);
    }

    private void account(long id, long tenantId) {
        db.update("INSERT INTO account(id,tenant_id,ws_phone,protocol_account_id,protocol_id) "
                + "VALUES (?,?,?,?,?)", id, tenantId, "1550000000" + id, "account-" + id, "ANDROID");
        db.update("INSERT INTO account_state(account_id,tenant_id,account_state,login_state,"
                + "desired_login_state,updated_at) VALUES (?,?,?,?,?,1)",
                id, tenantId, AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE, AccountLoginStateCode.ONLINE);
    }

    private void reserve(long accountId) {
        db.update("INSERT INTO account_creator_deletion(account_id,tenant_id,task_id,group_execution_id,"
                + "identity_hash,creator_phone,protocol_account_id,lifecycle) VALUES (?,7,64,6401,?,?,?,'RESERVED')",
                accountId, "identity-" + accountId, "1550000000" + accountId, "account-" + accountId);
    }

    private void pendingOnline(long accountId, long tenantId) {
        db.update("INSERT INTO protocol_command_outbox(tenant_id,command_id,command_type,aggregate_type,"
                + "aggregate_id,protocol_account_id,status) VALUES (?,?,'account.online.requested','ACCOUNT',?,?,?)",
                tenantId, "old-online-" + accountId, accountId, "account-" + accountId,
                ProtocolCommandOutboxStatus.PENDING.code());
    }

    private void createSchema(DataSource source) {
        db.execute("CREATE TABLE account(id BIGINT PRIMARY KEY,tenant_id BIGINT,ws_phone VARCHAR(32),"
                + "protocol_account_id VARCHAR(128),protocol_id VARCHAR(16),account_group_id BIGINT,deleted_at BIGINT)");
        db.execute("CREATE TABLE account_state(account_id BIGINT PRIMARY KEY,tenant_id BIGINT,"
                + "account_state INT,login_state INT,desired_login_state INT,updated_at BIGINT)");
        db.execute("CREATE TABLE account_group(id BIGINT,tenant_id BIGINT,deleted_at BIGINT)");
        db.execute("CREATE TABLE ip_proxy(id BIGINT,tenant_id BIGINT,bound_account_id BIGINT,status INT,deleted_at BIGINT)");
        db.execute("CREATE TABLE account_credential(account_id BIGINT,tenant_id BIGINT,deleted_at BIGINT)");
        db.execute("CREATE TABLE account_import_detail(account_id BIGINT,tenant_id BIGINT,online_phase INT)");
        db.execute(CreatorDeletionH2Schema.DDL);
        CreatorDeletionH2Schema.installIdentityIndex(source);
        db.execute(PullTaskNormalLinkSchema.protocolCommandOutbox());
    }

    private static <T> T transactional(Object target, DataSourceTransactionManager manager, Class<T> type) {
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return type.cast(proxy.getProxy());
    }
}
