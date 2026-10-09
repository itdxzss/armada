package com.armada.account.takeover;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountCreatorDeletionMapper;
import com.armada.account.mapper.AccountCredentialMapper;
import com.armada.account.mapper.AccountMapper;
import com.armada.account.mapper.AccountOnlineAttemptLogMapper;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.mapper.AccountTakeoverBreakerMapper;
import com.armada.account.service.AccountOnlineCommandService;
import com.armada.account.service.OnlineAttemptIdGenerator;
import com.armada.account.service.impl.AccountOnlineAttemptLogServiceImpl;
import com.armada.account.service.impl.AccountOnlineCommandServiceImpl;
import com.armada.account.service.impl.AccountTakeoverReonlineCooldown;
import com.armada.boot.config.MyBatisConfig;
import com.armada.platform.country.mapper.CountryMapper;
import com.armada.platform.country.service.impl.CountryServiceImpl;
import com.armada.platform.kafka.config.NormalGroupCreationKafkaProperties;
import com.armada.platform.kafka.config.ProtocolAccountCommandProperties;
import com.armada.platform.kafka.config.ProtocolAndroidCommandProperties;
import com.armada.platform.kafka.config.ProtocolCommandDispatcherProperties;
import com.armada.platform.kafka.config.ProtocolMasterCommandProperties;
import com.armada.platform.kafka.dispatch.ProtocolCommandDispatchTrigger;
import com.armada.platform.protocol.mapper.ProtocolCommandOutboxMapper;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.platform.protocol.service.impl.ProtocolCommandOutboxServiceImpl;
import com.armada.platform.proxy.ProxyEndpoint;
import com.armada.resource.service.IpProxyAccountAllocation;
import com.armada.resource.service.IpProxyAllocation;
import com.armada.resource.service.IpProxyAllocationRequest;
import com.armada.resource.service.IpProxyService;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.mapper.PullTaskNormalLinkSchema;
import com.armada.testsupport.CreatorDeletionH2Schema;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.NameMatchTransactionAttributeSource;
import org.springframework.transaction.interceptor.RuleBasedTransactionAttribute;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** 抢登回归共享真实 Mapper、租户插件、事务与 outbox；仅代理池服务使用外部依赖替身。 */
public final class AccountTakeoverH2Support {

    public final DataSource dataSource;
    public final JdbcTemplate jdbc;
    public final SqlSessionFactory sqlSessionFactory;
    public final DataSourceTransactionManager transactionManager;
    public final TransactionTemplate transactions;
    public final AccountMapper accounts;
    public final AccountStateMapper states;
    public final AccountAutoTakeoverProperties properties;
    public final AccountTakeoverBreaker breaker;
    public final AccountCreatorReservationLookup reservations;
    public final AccountTakeoverPolicy policy;
    public final IpProxyService ipProxyMock;
    public final IpProxyService ipProxyService;
    public final AccountOnlineCommandService online;
    public final ProtocolCommandOutboxService outbox;

    /** 创建独立 H2 库，所有账号访问默认使用租户 1。 */
    public AccountTakeoverH2Support() throws Exception {
        dataSource = PullTaskNormalLinkH2Support.dataSource("takeover" + System.nanoTime());
        jdbc = new JdbcTemplate(dataSource);
        createSchema();
        MyBatisConfig config = new MyBatisConfig();
        var interceptor = config.mybatisPlusInterceptor(config.tenantLineHandler());
        interceptor.addInnerInterceptor(new AccountProxySnapshotH2Dialect());
        sqlSessionFactory = PullTaskNormalLinkH2Support.sqlSessionFactory(dataSource,
                interceptor,
                "mapper/account/AccountMapper.xml", "mapper/account/AccountStateMapper.xml",
                "mapper/account/AccountCredentialMapper.xml", "mapper/account/AccountCreatorDeletionMapper.xml",
                "mapper/account/AccountTakeoverBreakerMapper.xml", "mapper/account/AccountOnlineAttemptLogMapper.xml",
                "mapper/platform/country/CountryMapper.xml",
                "mapper/platform/protocol/ProtocolCommandOutboxMapper.xml");
        var sessions = new SqlSessionTemplate(sqlSessionFactory);
        transactionManager = new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(transactionManager);
        accounts = sessions.getMapper(AccountMapper.class);
        states = sessions.getMapper(AccountStateMapper.class);
        properties = new AccountAutoTakeoverProperties();
        breaker = transactional(new AccountTakeoverBreaker(
                sessions.getMapper(AccountTakeoverBreakerMapper.class), properties), AccountTakeoverBreaker.class);
        reservations = new AccountCreatorReservationLookup(sessions.getMapper(AccountCreatorDeletionMapper.class));
        policy = new AccountTakeoverPolicy(properties, breaker, reservations);
        ipProxyMock = mock(IpProxyService.class);
        ipProxyService = transactionalProxyPool(ipProxyMock);
        when(ipProxyMock.allocateOnlineEndpoint(any())).thenReturn(new IpProxyAllocation(
                70L, new ProxyEndpoint(ProxyEndpoint.PROTOCOL_HTTP, "127.0.0.1", 8080, null, "测试"), "test"));
        when(ipProxyMock.allocateOnlineEndpoints(anyList())).thenAnswer(invocation -> {
            List<IpProxyAllocationRequest> requests = invocation.getArgument(0);
            return requests.stream().map(request -> new IpProxyAccountAllocation(request.accountId(), 70L,
                    new ProxyEndpoint(ProxyEndpoint.PROTOCOL_HTTP, "127.0.0.1", 8080, null, "测试"), "test")).toList();
        });
        var dispatchProperties = new ProtocolCommandDispatcherProperties();
        dispatchProperties.setImmediateEnabled(false);
        // 关闭真实 Kafka 触发器的立即发送分支；所有 outbox SQL 仍正常执行并提交。
        var trigger = new ProtocolCommandDispatchTrigger(null, Runnable::run, dispatchProperties, null);
        outbox = transactional(new ProtocolCommandOutboxServiceImpl(
                sessions.getMapper(ProtocolCommandOutboxMapper.class), new ObjectMapper(), trigger,
                new ProtocolAccountCommandProperties(), new ProtocolMasterCommandProperties(),
                new ProtocolAndroidCommandProperties(), new NormalGroupCreationKafkaProperties()),
                ProtocolCommandOutboxService.class);
        online = transactional(new AccountOnlineCommandServiceImpl(accounts,
                sessions.getMapper(AccountCredentialMapper.class), states, ipProxyService,
                new CountryServiceImpl(sessions.getMapper(CountryMapper.class)), outbox,
                new OnlineAttemptIdGenerator(), new AccountOnlineAttemptLogServiceImpl(
                        sessions.getMapper(AccountOnlineAttemptLogMapper.class)),
                new AccountTakeoverReonlineCooldown(), policy), AccountOnlineCommandService.class);
        TenantContext.set(1L);
    }

    /** 创建租户 1 的普通测试账号及凭据。 */
    public void account(long id, int state, int login, Integer desired, Integer mute) {
        jdbc.update("""
                INSERT INTO account(id,tenant_id,ws_phone,protocol_account_id,protocol_id,
                  account_type,declared_account_type,device_os)
                VALUES (?,1,?,?,'ANDROID',1,1,1)
                """, id, "1550000000" + id, "account-" + id);
        jdbc.update("""
                INSERT INTO account_state(tenant_id,account_id,account_state,login_state,desired_login_state,
                  mute_status,last_state_sync_time,offline_since,created_at,updated_at)
                VALUES (1,?,?,?,?,?,1000,?,1000,1000)
                """, id, state, login, desired, mute, login == 1 ? null : 1_000L);
        jdbc.update("INSERT INTO account_credential(account_id,tenant_id,cred_format,creds_json) VALUES (?,1,1,'{}')", id);
    }

    /** 为账号创建任务 11、执行行 111 的专属预留。 */
    public void reserve(long accountId) {
        jdbc.update("""
                INSERT INTO account_creator_deletion(account_id,tenant_id,task_id,group_execution_id,
                  identity_hash,creator_phone,protocol_account_id,lifecycle,created_at,updated_at)
                VALUES (?,1,11,111,?,?,?,'RESERVED',1000,1000)
                """, accountId, "identity-" + accountId, "1550000000" + accountId, "account-" + accountId);
    }

    /** 对真实服务应用 Spring 声明式事务代理。 */
    public <T> T transactional(Object target, Class<T> type) {
        var proxy = new ProxyFactory(target);
        if (!type.isInterface()) {
            proxy.setProxyTargetClass(true);
        }
        proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return type.cast(proxy.getProxy());
    }

    private IpProxyService transactionalProxyPool(IpProxyService target) {
        var attributes = new NameMatchTransactionAttributeSource();
        attributes.addTransactionalMethod("allocate*", new RuleBasedTransactionAttribute());
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactionManager, attributes));
        return (IpProxyService) proxy.getProxy();
    }

    private void createSchema() throws Exception {
        jdbc.execute("""
                CREATE TABLE account(id BIGINT PRIMARY KEY,tenant_id BIGINT NOT NULL,ws_phone VARCHAR(32),
                  protocol_account_id VARCHAR(128),protocol_id VARCHAR(16),account_group_id BIGINT,
                  account_type INT,declared_account_type INT,device_os INT,deleted_at BIGINT)
                """);
        CreatorDeletionH2Schema.installIdentityIndex(dataSource);
        jdbc.execute(CreatorDeletionH2Schema.DDL);
        jdbc.execute("""
                CREATE TABLE account_state(id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,account_id BIGINT NOT NULL,account_state INT,login_state INT,
                  desired_login_state INT,mute_status INT,offline_since BIGINT,last_state_sync_time BIGINT,
                  state_source VARCHAR(64),block_reason VARCHAR(255),invalidated_at BIGINT,
                  truth_ip VARCHAR(45),proxy_country VARCHAR(64),proxy_source VARCHAR(64),
                  created_at BIGINT,updated_at BIGINT,UNIQUE(tenant_id,account_id))
                """);
        jdbc.execute("CREATE TABLE account_credential(id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
                + "tenant_id BIGINT,account_id BIGINT,cred_format INT,creds_json VARCHAR(4096),deleted_at BIGINT)");
        jdbc.execute("CREATE TABLE account_import_detail(id BIGINT,tenant_id BIGINT,account_id BIGINT,"
                + "batch_id BIGINT,ws_phone VARCHAR(32),online_phase INT,parse_result INT)");
        jdbc.execute("CREATE TABLE account_import_batch(id BIGINT,tenant_id BIGINT,ip_region VARCHAR(64),"
                + "ip_allocation_mode VARCHAR(32),deleted_at BIGINT)");
        jdbc.execute(PullTaskNormalLinkSchema.protocolCommandOutbox());
        try (var stream = new ClassPathResource("db/migration/V215__account_takeover_breaker.sql").getInputStream()) {
            String migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            int start = migration.indexOf("CREATE TABLE IF NOT EXISTS account_takeover_breaker");
            jdbc.execute(migration.substring(start, migration.indexOf(" ENGINE=", start)));
        }
        try (var stream = new ClassPathResource("db/migration/V216__account_takeover_kick_dedup.sql").getInputStream()) {
            String migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            int start = migration.indexOf("'ALTER TABLE account_takeover_breaker") + 1;
            int end = migration.indexOf("',", start);
            jdbc.execute(migration.substring(start, end).replace("''", "'"));
        }
    }
}
