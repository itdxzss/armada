package com.armada.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountMutualContactMapper;
import com.armada.account.model.dto.AccountMutualContactCreateDTO;
import com.armada.account.model.dto.AccountMutualContactQuery;
import com.armada.account.model.dto.AccountMutualContactWork;
import com.armada.account.service.impl.AccountMutualContactServiceImpl;
import com.armada.admin.service.CurrentIdentityService;
import com.armada.boot.config.MyBatisConfig;
import com.armada.platform.kafka.consumer.group.ProtocolMutualContactResult;
import com.armada.platform.protocol.service.ProtocolMutualContactCommandService;
import com.armada.shared.security.AuthPrincipal;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

/** Flyway DDL、真实 Mapper/租户插件和 Spring 事务覆盖完整任务方向生命周期。 */
class AccountMutualContactH2Test {
    private JdbcTemplate jdbc;
    private AccountMutualContactMapper mapper;
    private AccountMutualContactServiceImpl service;
    private AccountMutualContactTransactions runner;
    private TransactionTemplate tx;
    private ProtocolMutualContactCommandService outbox;
    private final AuthPrincipal admin = principal(10, 7, true);

    @BeforeEach
    void setup() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:mutual;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP ALL OBJECTS");
        try (var connection = ds.getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection, new ClassPathResource("db/migration/V202__account_mutual_contacts.sql"));
        }
        jdbc.execute("CREATE TABLE account_group(id BIGINT PRIMARY KEY,tenant_id BIGINT,name VARCHAR(100),deleted_at "
                + "BIGINT)");
        jdbc.execute("CREATE TABLE account(id BIGINT PRIMARY KEY,tenant_id BIGINT,owner_user_id "
                + "BIGINT,account_group_id BIGINT,ws_phone VARCHAR(32),protocol_account_id "
                + "VARCHAR(64),protocol_id VARCHAR(16),deleted_at BIGINT)");
        jdbc.execute("CREATE TABLE account_state(account_id BIGINT PRIMARY KEY,tenant_id BIGINT,account_state "
                + "INT,login_state INT,risk_status INT,mute_status INT)");
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(configuration);
        var config = new MyBatisConfig();
        factory.setPlugins(config.mybatisPlusInterceptor(config.tenantLineHandler()));
        factory.setMapperLocations(new ClassPathResource("mapper/account/AccountMutualContactMapper.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(AccountMutualContactMapper.class);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        service = new AccountMutualContactServiceImpl(mapper,
                org.mapstruct.factory.Mappers.getMapper(
                        com.armada.account.converter.AccountMutualContactConverter.class));
        outbox = mock(ProtocolMutualContactCommandService.class);
        when(outbox.enqueue(any())).thenAnswer(inv -> UUID.randomUUID().toString());
        var identity = mock(CurrentIdentityService.class);
        when(identity.load(anyLong(), anyLong())).thenReturn(Optional.of(admin));
        runner = new AccountMutualContactTransactions(mapper, outbox, identity);
        TenantContext.set(7L);
        jdbc.update("INSERT INTO account_group VALUES(11,7,'A',NULL),(12,7,'B',NULL),(13,8,'foreign',NULL)");
        for (long id = 1; id <= 5; id++) {
            jdbc.update("INSERT INTO account VALUES(?,7,10,?,?,?, ?,NULL)", id, id <= 2 ? 11 : 12, "91900000000" + id,
                    "acc_" + id, id % 2 == 0 ? "WEB" : "ANDROID");
            jdbc.update("INSERT INTO account_state VALUES(?,7,2,1,1,NULL)", id);
        }
    }
    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }
    @Test
    void createsSixPairsTwelveDirectionsAndIdempotentRequest() {
        long id = create(0);
        assertThat(mapper.stats(id).total()).isEqualTo(12);
        assertThat(mapper.stats(id).pending()).isEqualTo(12);
        assertThat(service.list(new AccountMutualContactQuery(), admin).total()).isEqualTo(1);
        assertThat(service.create(request("same-request-key-0001", 0), admin).id()).isEqualTo(id);
        assertThat(mapper.countItems(id, new AccountMutualContactQuery())).isEqualTo(12);
    }
    @Test
    void invalidatesPreviewAndIsolatesTenantAndOwner() {
        var req = request("preview-stale-key-01", 0);
        jdbc.update("UPDATE account_state SET login_state=2 WHERE account_id=1");
        assertThatThrownBy(() -> tx.execute(s -> service.create(req, admin))).hasMessageContaining("重新预览");
        long id = create(0);
        assertThatThrownBy(() -> service.detail(id, principal(20, 7, false))).hasMessageContaining("无权访问");
        TenantContext.set(8L);
        assertThat(mapper.task(id)).isNull();
        assertThat(mapper.stats(id).total()).isZero();
        assertThat(mapper.items(id, new AccountMutualContactQuery())).isEmpty();
        assertThatThrownBy(() -> service.detail(id, principal(10, 8, true))).hasMessageContaining("不存在");
    }
    @Test
    void serializesActorAcrossTasksAndRetainsOnlyFailedDirectionForRetry() {
        long id = create(3);
        var work = new AccountMutualContactWork(7L, id, 1L);
        tx.executeWithoutResult(s -> runner.dispatch(work, 1000));
        tx.executeWithoutResult(s -> runner.dispatch(work, 1000));
        verify(outbox, times(1)).enqueue(any());
        var sent = mapper.items(id, new AccountMutualContactQuery())
                           .stream()
                           .filter(i -> i.getStatus() == 2)
                           .findFirst()
                           .orElseThrow();
        apply(id, sent.getId(), sent.getCommandId(), 1L, "SUCCESS", false);
        apply(id, sent.getId(), sent.getCommandId(), 1L, "FAILED", true); // 重复矛盾回执不能降级成功。
        assertThat(mapper.item(sent.getId()).getStatus()).isEqualTo(3);
        long resultAt = mapper.item(sent.getId()).getResultAt();
        tx.executeWithoutResult(s -> runner.dispatch(work, resultAt + 2999));
        verify(outbox, times(1)).enqueue(any());
        tx.executeWithoutResult(s -> runner.dispatch(new AccountMutualContactWork(7L, id, 3L), resultAt));
        var reverse = mapper.items(id, new AccountMutualContactQuery())
                              .stream()
                              .filter(i -> i.getStatus() == 2)
                              .findFirst()
                              .orElseThrow();
        apply(id, reverse.getId(), reverse.getCommandId(), 3L, "FAILED", true);
        assertThat(mapper.stats(id).oneWayPairs()).isEqualTo(1);
        assertThat(tx.<Integer>execute(s -> service.retry(id, admin))).isEqualTo(1);
        assertThat(mapper.item(sent.getId()).getStatus()).isEqualTo(3);
        assertThat(mapper.item(reverse.getId()).getStatus()).isEqualTo(1);
    }
    @Test
    void stopDoesNotLoseLateResultAndUnknownIsNeverRetried() {
        long id = create(0);
        tx.executeWithoutResult(s -> runner.dispatch(new AccountMutualContactWork(7L, id, 1L), 1000));
        var sent = mapper.items(id, new AccountMutualContactQuery())
                           .stream()
                           .filter(i -> i.getStatus() == 2)
                           .findFirst()
                           .orElseThrow();
        tx.executeWithoutResult(s -> service.stop(id, admin));
        assertThat(mapper.stats(id).canceled()).isEqualTo(11);
        tx.executeWithoutResult(s -> runner.expire(id, 1001, 2000));
        assertThat(mapper.item(sent.getId()).getStatus()).isEqualTo(5);
        assertThat(tx.<Integer>execute(s -> service.retry(id, admin))).isZero();
        apply(id, sent.getId(), sent.getCommandId(), 1L, "SUCCESS", false);
        assertThat(mapper.stats(id).success()).isEqualTo(1);
        assertThat(mapper.task(id).getStatus()).isEqualTo(2);
    }
    @Test
    void rollbackRemovesBothTaskAndDirectionsAndPagingIsSql() {
        tx.executeWithoutResult(s -> {
            create(0);
            s.setRollbackOnly();
        });
        assertThat(mapper.countTasks(null)).isZero();
        long id = create(0);
        var q = new AccountMutualContactQuery();
        q.setPageSize(2);
        q.setPage(2);
        assertThat(mapper.items(id, q)).hasSize(2);
        assertThat(mapper.scan(32, 1000)).hasSize(5);
        assertThat(mapper.expired(0, 32)).isEmpty();
    }
    @Test
    void concurrentTasksShareActorLockAndResumeAfterDefiniteResult() throws Exception {
        long first = create(0);
        long second = tx.execute(s -> service.create(request("second-request-key-01", 0), admin)).id();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var gate = new java.util.concurrent.CountDownLatch(1);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (long task : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    try {
                        gate.await();
                        TenantContext.set(7L);
                        tx.executeWithoutResult(s -> runner.dispatch(new AccountMutualContactWork(7L, task, 1L), 1000));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    } finally {
                        TenantContext.clear();
                    }
                }));
            }
            gate.countDown();
            for (var future : futures) future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            verify(outbox, times(1)).enqueue(any());
            assertThat(mapper.stats(first).submitted() + mapper.stats(second).submitted()).isEqualTo(1);
            var submitted = java.util.stream.Stream
                                    .concat(mapper.items(first, new AccountMutualContactQuery()).stream(),
                                            mapper.items(second, new AccountMutualContactQuery()).stream())
                                    .filter(i -> i.getStatus() == 2)
                                    .findFirst()
                                    .orElseThrow();
            apply(submitted.getTaskId(), submitted.getId(), submitted.getCommandId(), 1L, "SUCCESS", false);
            long remaining = submitted.getTaskId() == first ? second : first;
            tx.executeWithoutResult(s
                    -> runner.dispatch(
                            new AccountMutualContactWork(7L, remaining, 1L), System.currentTimeMillis() + 1));
            verify(outbox, times(2)).enqueue(any());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void hydrationUsesFrozenRecipientAndRejectsOldCommand() {
        long id = create(0);
        tx.executeWithoutResult(s -> runner.dispatch(new AccountMutualContactWork(7L, id, 1L), 1000));
        var sent = mapper.items(id, new AccountMutualContactQuery())
                           .stream()
                           .filter(i -> i.getStatus() == 2)
                           .findFirst()
                           .orElseThrow();
        var row = new com.armada.platform.protocol.model.entity.ProtocolCommandOutbox();
        row.setTenantId(7L);
        row.setAggregateType("ACCOUNT_MUTUAL_CONTACT_ITEM");
        row.setAggregateId(sent.getId());
        row.setCommandType("contact.save.requested");
        row.setCommandId(sent.getCommandId());
        row.setProtocolAccountId(sent.getProtocolAccountId());
        row.setProtocolBackend(sent.getProtocolBackend());
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var ref = json.createObjectNode()
                          .put("tenantId", 7)
                          .put("taskId", id)
                          .put("itemId", sent.getId())
                          .put("source", "account_group_mutual_contact");
        var hydrator = new com.armada.account.service.impl.AccountMutualContactPayloadHydrator(mapper, json);
        TenantContext.set(8L);
        assertThat(hydrator.supports(row)).isTrue();
        var payload = hydrator.hydrate(row, ref);
        assertThat(payload.path("contact").asText()).isEqualTo(sent.getTargetPhone());
        assertThat(payload.path("name").asText()).isEqualTo(sent.getTargetPhone());
        assertThat(payload.has("pullTaskId")).isFalse();
        assertThat(TenantContext.get()).isEqualTo(8L);
        row.setCommandId("old-command");
        assertThatThrownBy(() -> hydrator.hydrate(row, ref)).hasMessageContaining("mismatch");
        assertThat(TenantContext.get()).isEqualTo(8L);
    }

    private long create(int interval) {
        return tx.execute(s -> service.create(request("same-request-key-0001", interval), admin)).id();
    }
    private AccountMutualContactCreateDTO request(String key, int interval) {
        var req = new AccountMutualContactCreateDTO(11L, 12L, interval, key, null);
        return new AccountMutualContactCreateDTO(11L, 12L, interval, key, service.preview(req, admin).previewToken());
    }
    private void apply(long task, long item, String command, long actor, String outcome, boolean retryable) {
        tx.executeWithoutResult(s
                -> runner.apply(new ProtocolMutualContactResult(
                        7L, task, item, actor, "acc_" + actor, command, 1, outcome, "", retryable)));
    }
    private static AuthPrincipal principal(long user, long tenant, boolean admin) {
        return new AuthPrincipal(user, tenant, "user", "user", "t", "t", admin ? List.of("TENANT_ADMIN") : List.of(),
                List.of("tenant:account:view", "tenant:account:edit"));
    }
}
