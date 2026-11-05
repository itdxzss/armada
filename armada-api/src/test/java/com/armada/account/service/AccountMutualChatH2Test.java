package com.armada.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountMutualChatMapper;
import com.armada.account.model.dto.AccountMutualChatCreateDTO;
import com.armada.account.model.dto.AccountMutualChatQuery;
import com.armada.account.model.dto.AccountMutualChatWork;
import com.armada.account.service.impl.AccountMutualChatServiceImpl;
import com.armada.admin.service.CurrentIdentityService;
import com.armada.boot.config.MyBatisConfig;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.result.MessageSendEnqueueItem;
import com.armada.platform.protocol.model.result.MessageSendEnqueueResult;
import com.armada.account.mapper.AccountMutualContactMapper;
import org.mockito.ArgumentCaptor;
import com.armada.platform.protocol.port.MessageSendPort;
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
class AccountMutualChatH2Test {
    private JdbcTemplate jdbc;
    private AccountMutualChatMapper mapper;
    private AccountMutualChatServiceImpl service;
    private AccountMutualChatTransactions runner;
    private TransactionTemplate tx;
    private CurrentIdentityService identity;
    private MessageSendPort outbox;
    private final AuthPrincipal admin = principal(10, 7, true);

    @BeforeEach
    void setup() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:mutual_chat;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute(com.armada.testsupport.CreatorDeletionH2Schema.DDL);
        try (var connection = ds.getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection, new ClassPathResource("db/migration/V227__account_mutual_chat.sql"));
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
        factory.setMapperLocations(new ClassPathResource("mapper/account/AccountMutualChatMapper.xml"),
                new ClassPathResource("mapper/account/AccountMutualContactMapper.xml"));
        var session = new SqlSessionTemplate(factory.getObject());
        mapper = session.getMapper(AccountMutualChatMapper.class);
        var accounts = session.getMapper(AccountMutualContactMapper.class);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        service = new AccountMutualChatServiceImpl(mapper,
                org.mapstruct.factory.Mappers.getMapper(
                        com.armada.account.converter.AccountMutualChatConverter.class), new AccountMutualGroupPlanner(accounts));
        outbox = mock(MessageSendPort.class);
        when(outbox.enqueue(any())).thenAnswer(inv -> new MessageSendEnqueueResult(
                ((List<MessageSendCommand>) inv.getArgument(0)).stream()
                    .map(c -> MessageSendEnqueueItem.accepted(c.commandId())).toList()));
        identity = mock(CurrentIdentityService.class);
        when(identity.load(anyLong(), anyLong())).thenReturn(Optional.of(admin));
        runner = new AccountMutualChatTransactions(mapper, outbox, identity, accounts);
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
        long id = create(1);
        assertThat(mapper.stats(id).total()).isEqualTo(12);
        assertThat(mapper.stats(id).pending()).isEqualTo(12);
        assertThat(service.list(new AccountMutualChatQuery(), admin).total()).isEqualTo(1);
        assertThat(service.create(request("same-request-key-0001", 1), admin).id()).isEqualTo(id);
        assertThat(mapper.countItems(id, new AccountMutualChatQuery())).isEqualTo(12);
    }
    @Test
    void onlineGrabStatesParticipateButBannedDoesNot() {
        jdbc.update("UPDATE account_state SET account_state=6 WHERE account_id=1");
        jdbc.update("UPDATE account_state SET account_state=7 WHERE account_id=2");
        jdbc.update("UPDATE account_state SET account_state=3 WHERE account_id=3");
        long id = create(1);
        assertThat(mapper.stats(id).total()).isEqualTo(8);
        tx.executeWithoutResult(s -> runner.dispatch(new AccountMutualChatWork(7L, id, 1L), 1000));
        verify(outbox, times(1)).enqueue(any());
    }
    @Test
    void invalidatesPreviewAndIsolatesTenantAndOwner() {
        var req = request("preview-stale-key-01", 1);
        jdbc.update("UPDATE account_state SET login_state=2 WHERE account_id=1");
        assertThatThrownBy(() -> tx.execute(s -> service.create(req, admin))).hasMessageContaining("重新预览");
        long id = create(1);
        assertThatThrownBy(() -> service.detail(id, principal(20, 7, false))).hasMessageContaining("无权访问");
        TenantContext.set(8L);
        assertThat(mapper.task(id)).isNull();
        assertThat(mapper.stats(id).total()).isZero();
        assertThat(mapper.items(id, new AccountMutualChatQuery())).isEmpty();
        assertThatThrownBy(() -> service.detail(id, principal(10, 8, true))).hasMessageContaining("不存在");
    }
    @Test
    void serializesActorAcrossTasksAndRetainsOnlyFailedDirectionForRetry() {
        long id = create(3);
        var work = new AccountMutualChatWork(7L, id, 1L);
        tx.executeWithoutResult(s -> runner.dispatch(work, 1000));
        tx.executeWithoutResult(s -> runner.dispatch(work, 1000));
        verify(outbox, times(1)).enqueue(any());
        var sent = mapper.items(id, new AccountMutualChatQuery())
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
        tx.executeWithoutResult(s -> runner.dispatch(new AccountMutualChatWork(7L, id, 3L), resultAt));
        var reverse = mapper.items(id, new AccountMutualChatQuery())
                              .stream()
                              .filter(i -> i.getStatus() == 2)
                              .findFirst()
                              .orElseThrow();
        apply(id, reverse.getId(), reverse.getCommandId(), 3L, "FAILED", true);
        assertThat(mapper.stats(id).oneWayPairs()).isEqualTo(1);
        assertThat(tx.<Integer>execute(s -> service.retry(id, true, admin))).isEqualTo(1);
        assertThat(mapper.item(sent.getId()).getStatus()).isEqualTo(3);
        assertThat(mapper.item(reverse.getId()).getStatus()).isEqualTo(1);
    }
    @Test
    void stopDoesNotLoseLateResult() {
        long id = create(1);
        tx.executeWithoutResult(s -> runner.dispatch(new AccountMutualChatWork(7L, id, 1L), 1000));
        var sent = mapper.items(id, new AccountMutualChatQuery())
                           .stream()
                           .filter(i -> i.getStatus() == 2)
                           .findFirst()
                           .orElseThrow();
        tx.executeWithoutResult(s -> service.stop(id, admin));
        assertThat(mapper.stats(id).canceled()).isEqualTo(11);
        tx.executeWithoutResult(s -> runner.expire(id, 1001, 2000));
        assertThat(mapper.item(sent.getId()).getStatus()).isEqualTo(5);
        apply(id, sent.getId(), sent.getCommandId(), 1L, "SUCCESS", false);
        assertThat(mapper.stats(id).success()).isEqualTo(1);
        assertThat(mapper.task(id).getStatus()).isEqualTo(2);
    }
    @Test
    void unknownIsManuallyRetriedWithNewCommandAndOldReceiptIgnored() {
        long id = create(1);
        var work = new AccountMutualChatWork(7L, id, 1L);
        tx.executeWithoutResult(s -> runner.dispatch(work, 1000));
        var sent = mapper.items(id, new AccountMutualChatQuery())
                           .stream()
                           .filter(i -> i.getStatus() == 2)
                           .findFirst()
                           .orElseThrow();
        tx.executeWithoutResult(s -> service.stop(id, admin));
        tx.executeWithoutResult(s -> runner.expire(id, 1001, 2000));
        assertThat(tx.<Integer>execute(s -> service.retry(id, true, admin))).isEqualTo(12);
        assertThat(mapper.item(sent.getId()).getStatus()).isEqualTo(1);
        assertThat(mapper.task(id).getStatus()).isEqualTo(1);
        apply(id, sent.getId(), sent.getCommandId(), 1L, "SUCCESS", false); // 旧命令晚到回执不能结算重派前状态。
        assertThat(mapper.item(sent.getId()).getStatus()).isEqualTo(1);
        long ready = mapper.item(sent.getId()).getNextExecuteAt();
        tx.executeWithoutResult(s -> runner.dispatch(work, Math.max(ready, 2000)));
        var resent = mapper.item(sent.getId());
        assertThat(resent.getStatus()).isEqualTo(2);
        assertThat(resent.getAttemptNo()).isEqualTo(2);
        assertThat(resent.getCommandId()).isNotEqualTo(sent.getCommandId());
    }
    @Test
    void unknownResultDoesNotBlockActorFromNextDirection() {
        long id = create(1);
        var work = new AccountMutualChatWork(7L, id, 1L);
        tx.executeWithoutResult(s -> runner.dispatch(work, 1000));
        tx.executeWithoutResult(s -> runner.expire(id, 1001, 2000));
        assertThat(mapper.stats(id).unknown()).isEqualTo(1);
        assertThat(mapper.scan(32, 3000)).anyMatch(w -> w.actorId() == 1L);
        tx.executeWithoutResult(s -> runner.dispatch(work, 3000));
        verify(outbox, times(2)).enqueue(any());
    }
    @Test
    void sameTaskDispatchesEveryIdleActorInParallel() {
        for (long id = 6; id <= 12; id++) {
            jdbc.update("INSERT INTO account VALUES(?,7,10,12,?,?,'WEB',NULL)", id, "9190000001" + id, "acc_" + id);
            jdbc.update("INSERT INTO account_state VALUES(?,7,2,1,1,NULL)", id);
        }
        long id = create(1);
        for (long actor = 1; actor <= 12; actor++) {
            long a = actor;
            tx.executeWithoutResult(s -> runner.dispatch(new AccountMutualChatWork(7L, id, a), 1000));
        }
        assertThat(mapper.stats(id).submitted()).isEqualTo(12);
    }
    @Test
    void rollbackRemovesBothTaskAndDirectionsAndPagingIsSql() {
        tx.executeWithoutResult(s -> {
            create(1);
            s.setRollbackOnly();
        });
        assertThat(mapper.countTasks(null)).isZero();
        long id = create(1);
        var q = new AccountMutualChatQuery();
        q.setPageSize(2);
        q.setPage(2);
        assertThat(mapper.items(id, q)).hasSize(2);
        assertThat(mapper.scan(32, 1000)).hasSize(5);
        assertThat(mapper.expired(0, 32)).isEmpty();
    }
    @Test
    void concurrentTasksShareActorLockAndResumeAfterDefiniteResult() throws Exception {
        long first = create(1);
        long second = tx.execute(s -> service.create(request("second-request-key-01", 1), admin)).id();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var gate = new java.util.concurrent.CountDownLatch(1);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (long task : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    try {
                        gate.await();
                        TenantContext.set(7L);
                        tx.executeWithoutResult(s -> runner.dispatch(new AccountMutualChatWork(7L, task, 1L), 1000));
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
                                    .concat(mapper.items(first, new AccountMutualChatQuery()).stream(),
                                            mapper.items(second, new AccountMutualChatQuery()).stream())
                                    .filter(i -> i.getStatus() == 2)
                                    .findFirst()
                                    .orElseThrow();
            apply(submitted.getTaskId(), submitted.getId(), submitted.getCommandId(), 1L, "SUCCESS", false);
            long remaining = submitted.getTaskId() == first ? second : first;
            tx.executeWithoutResult(s
                    -> runner.dispatch(
                            new AccountMutualChatWork(7L, remaining, 1L), System.currentTimeMillis() + 1001));
            verify(outbox, times(2)).enqueue(any());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void dispatchesFrozenTextInBothDirectionsAndAggregatesAllPairs() {
        long task = create(10);
        var query = new AccountMutualChatQuery(); query.setPageSize(100);
        var rows = mapper.items(task, query);
        assertThat(rows).hasSize(12);
        assertThat(rows).filteredOn(i -> i.getDirection() == 1).hasSize(6);
        assertThat(rows).filteredOn(i -> i.getDirection() == 2).hasSize(6);
        assertThat(mapper.task(task).getLeftText()).isEqualTo("A text");
        // 移组不影响冻结范围或文案。
        jdbc.update("UPDATE account SET account_group_id=12 WHERE id=1");
        for (var row : rows) {
            tx.executeWithoutResult(s -> runner.dispatch(new AccountMutualChatWork(7L, task, row.getActorId()),
                    System.currentTimeMillis() + 60_000));
            var sent = mapper.item(row.getId());
            assertThat(sent.getStatus()).isEqualTo(2);
            apply(task, sent.getId(), sent.getCommandId(), sent.getActorId(), "SUCCESS", false);
            apply(task, sent.getId(), sent.getCommandId(), sent.getActorId(), "SUCCESS", false);
        }
        @SuppressWarnings("unchecked") ArgumentCaptor<List<MessageSendCommand>> captured = ArgumentCaptor.forClass(List.class);
        verify(outbox, times(12)).enqueue(captured.capture());
        for (var batch : captured.getAllValues()) {
            assertThat(batch).hasSize(1);
            var c = batch.getFirst(); var row = mapper.byCommand(c.commandId());
            assertThat(c.payload().content().text()).isEqualTo(row.getDirection() == 1 ? "A text" : "B text");
            assertThat(c.sendIntervalMs()).isZero(); assertThat(c.notBeforeAt()).isZero();
            assertThat(c.target().kind()).isEqualTo(MessageSendCommand.TargetKind.PRIVATE);
        }
        assertThat(mapper.stats(task).success()).isEqualTo(12);
        assertThat(mapper.stats(task).mutualPairs()).isEqualTo(6);
        assertThat(mapper.task(task).getStatus()).isEqualTo(3);
    }

    @Test
    void failedUnknownAndCanceledRetryWithNewCommandsAndIgnoreOldResults() {
        long task = create(1);
        tx.executeWithoutResult(s -> runner.dispatch(new AccountMutualChatWork(7L, task, 1L), 1000));
        tx.executeWithoutResult(s -> runner.dispatch(new AccountMutualChatWork(7L, task, 3L), 1000));
        var rows = mapper.items(task, new AccountMutualChatQuery()).stream().filter(i -> i.getStatus() == 2).toList();
        apply(task, rows.get(0).getId(), rows.get(0).getCommandId(), rows.get(0).getActorId(), "FAILED", false);
        apply(task, rows.get(1).getId(), rows.get(1).getCommandId(), rows.get(1).getActorId(), "UNKNOWN", false);
        tx.executeWithoutResult(s -> service.stop(task, admin));
        assertThat(mapper.stats(task).canceled()).isEqualTo(10);
        assertThat(tx.<Integer>execute(s -> service.retry(task, true, admin))).isEqualTo(12);
        for (var old : rows) {
            tx.executeWithoutResult(s -> runner.dispatch(new AccountMutualChatWork(7L, task, old.getActorId()),
                    System.currentTimeMillis() + 2000));
            var resent = mapper.item(old.getId());
            assertThat(resent.getCommandId()).isNotEqualTo(old.getCommandId());
            assertThat(resent.getAttemptNo()).isEqualTo(2);
            apply(task, old.getId(), old.getCommandId(), old.getActorId(), "SUCCESS", false);
            assertThat(mapper.item(old.getId()).getStatus()).isEqualTo(2);
        }
        tx.executeWithoutResult(s -> runner.dispatch(new AccountMutualChatWork(7L, task, 2L), System.currentTimeMillis()+2000));
        assertThat(mapper.stats(task).submitted()).isEqualTo(3);
    }

    @Test
    void rejectsOutdatedIdentityOfflineOwnershipAndRevokedPermission() {
        for (String mutation : List.of("UPDATE account_state SET login_state=2 WHERE account_id=1",
                "UPDATE account SET protocol_account_id='changed' WHERE id=1",
                "UPDATE account SET ws_phone='19998887777' WHERE id=3")) {
            long task = create(1);
            jdbc.update(mutation);
            tx.executeWithoutResult(s -> runner.dispatch(new AccountMutualChatWork(7L, task, 1L), 1000));
            assertThat(mapper.stats(task).failed()).isEqualTo(1);
            jdbc.update("UPDATE account_state SET login_state=1 WHERE account_id=1");
            jdbc.update("UPDATE account SET protocol_account_id='acc_1' WHERE id=1");
            jdbc.update("UPDATE account SET ws_phone='919000000003' WHERE id=3");
            jdbc.update("DELETE FROM account_mutual_chat_item"); jdbc.update("DELETE FROM account_mutual_chat_task");
        }
        verify(outbox, times(0)).enqueue(any());
    }

    @Test
    void enqueueRejectionFailsAndExceptionRollsBackSubmission() {
        long task = create(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("local outbox unavailable")).when(outbox).enqueue(any());
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> runner.dispatch(new AccountMutualChatWork(7L, task, 1L),1000)))
                .hasMessageContaining("outbox unavailable");
        assertThat(mapper.stats(task).pending()).isEqualTo(12);
        org.mockito.Mockito.doAnswer(inv -> new MessageSendEnqueueResult(List.of(
                MessageSendEnqueueItem.rejected(((List<MessageSendCommand>)inv.getArgument(0)).getFirst().commandId(), "BACKEND_UNAVAILABLE", "unavailable")))).when(outbox).enqueue(any());
        tx.executeWithoutResult(s -> runner.dispatch(new AccountMutualChatWork(7L, task, 1L),1000));
        assertThat(mapper.stats(task).failed()).isEqualTo(1);
        assertThat(tx.<Integer>execute(s -> service.retry(task, true, admin))).isEqualTo(1);
    }

    @Test
    void rejectsChangedTextUnderSameRequestAndInvalidConfiguration() {
        create(10);
        var req = request("same-request-key-0001",10);
        assertThatThrownBy(() -> service.create(new AccountMutualChatCreateDTO(11L,12L,10,req.requestId(),
                req.previewToken(),"changed","B text"),admin)).hasMessageContaining("不能修改");
        for (int interval : new int[]{0,3601})
            assertThatThrownBy(() -> service.preview(new AccountMutualChatCreateDTO(11L,12L,interval,null,null,"a","b"),admin))
                    .hasMessageContaining("间隔");
        for (String text : List.of(" ","a".repeat(1001)))
            assertThatThrownBy(() -> service.preview(new AccountMutualChatCreateDTO(11L,12L,10,null,null,text,"b"),admin))
                    .hasMessageContaining("文案");
    }

    @Test
    void revalidatesOwnerAndRevokedPermission() {
        long task = create(1);
        when(identity.load(anyLong(), anyLong())).thenReturn(Optional.of(principal(10,7,false)));
        jdbc.update("UPDATE account SET owner_user_id=20 WHERE id=1");
        tx.executeWithoutResult(s -> runner.dispatch(new AccountMutualChatWork(7L, task, 1L),1000));
        assertThat(mapper.items(task,new AccountMutualChatQuery())).anyMatch(i -> "ACCOUNT_ACCESS_CHANGED".equals(i.getReasonCode()));
        when(identity.load(anyLong(), anyLong())).thenReturn(Optional.empty());
        tx.executeWithoutResult(s -> runner.dispatch(new AccountMutualChatWork(7L, task, 2L),1000));
        assertThat(mapper.items(task,new AccountMutualChatQuery())).anyMatch(i -> "ACCESS_REVOKED".equals(i.getReasonCode()));
        verify(outbox,times(0)).enqueue(any());
    }

    @Test
    void rejectsCrossOwnerAndCrossTenantReadsStopAndRetry() {
        long task = create(1);
        var foreignOwner = principal(20,7,false);
        assertThat(service.list(new AccountMutualChatQuery(),foreignOwner).total()).isZero();
        assertThatThrownBy(() -> service.items(task,new AccountMutualChatQuery(),foreignOwner)).hasMessageContaining("无权访问");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> service.stop(task,foreignOwner))).hasMessageContaining("无权访问");
        assertThatThrownBy(() -> tx.execute(s -> service.retry(task,true,foreignOwner))).hasMessageContaining("无权访问");
        TenantContext.set(8L);
        var otherTenant = principal(20,8,true);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> service.stop(task,otherTenant))).hasMessageContaining("无权访问");
        assertThatThrownBy(() -> tx.execute(s -> service.retry(task,true,otherTenant))).hasMessageContaining("无权访问");
        TenantContext.set(7L); assertThat(mapper.stats(task).pending()).isEqualTo(12);
    }

    @Test
    void verifiesEveryReceiptIdentityBeforeWriteAndRestoresTenant() {
        long task = create(1);
        tx.executeWithoutResult(s -> runner.dispatch(new AccountMutualChatWork(7L,task,1L),1000));
        var sent = mapper.items(task,new AccountMutualChatQuery()).stream().filter(i -> i.getStatus()==2).findFirst().orElseThrow();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var good = json.createObjectNode().put("tenantId",7).put("mutualChatTaskId",task).put("mutualChatItemId",sent.getId())
                .put("accountId",1).put("protocolAccountId","acc_1").put("commandId",sent.getCommandId())
                .put("source",AccountMutualChatPolicy.SOURCE).put("targetKind","PRIVATE")
                .put("jid",sent.getTargetPhone()+"@s.whatsapp.net").put("success",true).put("messageId","wamid.good");
        for (String key : List.of("tenantId","mutualChatTaskId","mutualChatItemId","accountId","protocolAccountId","commandId","jid","targetKind")) {
            var bad = good.deepCopy();
            if (bad.get(key).isNumber()) bad.put(key,9999); else bad.put(key,"mismatch");
            tx.executeWithoutResult(s -> runner.apply(json.convertValue(bad,ProtocolMessageSendResultReportedEvent.class)));
            assertThat(mapper.item(sent.getId()).getStatus()).isEqualTo(2);
            assertThat(TenantContext.get()).isEqualTo(7L);
        }
        TenantContext.set(8L);
        tx.executeWithoutResult(s -> runner.apply(json.convertValue(good,ProtocolMessageSendResultReportedEvent.class)));
        assertThat(TenantContext.get()).isEqualTo(8L);
        TenantContext.set(7L);assertThat(mapper.item(sent.getId()).getStatus()).isEqualTo(3);
    }

    @Test
    void unknownRequiresRiskConfirmationAndLateDefiniteFailureConverges() {
        long task = create(1);
        tx.executeWithoutResult(s -> runner.dispatch(new AccountMutualChatWork(7L,task,1L),1000));
        var sent = mapper.items(task,new AccountMutualChatQuery()).stream().filter(i -> i.getStatus()==2).findFirst().orElseThrow();
        apply(task,sent.getId(),sent.getCommandId(),1L,"UNKNOWN",false);
        assertThatThrownBy(() -> tx.execute(s -> service.retry(task,false,admin))).hasMessageContaining("重复发送风险");
        assertThat(mapper.stats(task).unknown()).isEqualTo(1);
        apply(task,sent.getId(),sent.getCommandId(),1L,"FAILED",false);
        assertThat(mapper.stats(task).failed()).isEqualTo(1);
        assertThat(tx.<Integer>execute(s -> service.retry(task,false,admin))).isEqualTo(1);
    }

    @Test
    void rateLimitsBackOffAtLeastSixtySeconds() {
        long task = create(1);
        org.mockito.Mockito.doAnswer(inv -> new MessageSendEnqueueResult(List.of(
                MessageSendEnqueueItem.rejected(((List<MessageSendCommand>)inv.getArgument(0)).getFirst().commandId(),"RATE_LIMITED","slow")))).when(outbox).enqueue(any());
        tx.executeWithoutResult(s -> runner.dispatch(new AccountMutualChatWork(7L,task,1L),1000));
        var failed = mapper.items(task,new AccountMutualChatQuery()).stream().filter(i -> i.getStatus()==4).findFirst().orElseThrow();
        assertThat(failed.getNextExecuteAt()-failed.getResultAt()).isGreaterThanOrEqualTo(60_000);
        assertThat(mapper.scan(32,60_999)).noneMatch(w -> w.actorId()==1L);
        assertThat(mapper.scan(32,61_000)).anyMatch(w -> w.actorId()==1L);
    }

    @Test
    void rejectsMoreThanFiveThousandMessagesAtPreview() {
        for(long i=10;i<106;i++) {
            jdbc.update("INSERT INTO account VALUES(?,7,10,?,?,?,'WEB',NULL)",i,i<58?11:12,"1999000"+i,"acc_"+i);
            jdbc.update("INSERT INTO account_state VALUES(?,7,2,1,1,NULL)",i);
        }
        assertThatThrownBy(() -> request("large-request-key-01",10)).hasMessageContaining("5000");
    }

    @Test
    void competingTaskWaitsForActorTransactionAndDoesNotDoubleEnqueue() throws Exception {
        long first = create(1);
        long second = tx.execute(status -> service.create(request("concurrent-request-02",1),admin)).id();
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var started = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(inv -> {
            locked.countDown();
            if (!release.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test lock timeout");
            return new MessageSendEnqueueResult(List.of(MessageSendEnqueueItem.accepted(
                    ((List<MessageSendCommand>)inv.getArgument(0)).getFirst().commandId())));
        }).when(outbox).enqueue(any());
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var owner = pool.submit(() -> {
                TenantContext.set(7L);
                try { tx.executeWithoutResult(status -> runner.dispatch(new AccountMutualChatWork(7L,first,1L),1000)); }
                finally { TenantContext.clear(); }
            });
            assertThat(locked.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var waiter = pool.submit(() -> {
                TenantContext.set(7L); started.countDown();
                try { tx.executeWithoutResult(status -> runner.dispatch(new AccountMutualChatWork(7L,second,1L),1000)); }
                finally { TenantContext.clear(); }
            });
            assertThat(started.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> waiter.get(150,java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            owner.get(5,java.util.concurrent.TimeUnit.SECONDS); waiter.get(5,java.util.concurrent.TimeUnit.SECONDS);
            verify(outbox,times(1)).enqueue(any());
            assertThat(mapper.stats(first).submitted()).isEqualTo(1);
            assertThat(mapper.stats(second).submitted()).isZero();
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    private long create(int interval) {
        return tx.execute(s -> service.create(request("same-request-key-0001", interval), admin)).id();
    }
    private AccountMutualChatCreateDTO request(String key, int interval) {
        var req = new AccountMutualChatCreateDTO(11L, 12L, interval, key, null, " A text ", " B text ");
        return new AccountMutualChatCreateDTO(11L, 12L, interval, key, service.preview(req, admin).previewToken(), " A text ", " B text ");
    }
    private void apply(long task, long item, String command, long actor, String outcome, boolean ignored) {
        var data = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        data.put("tenantId", 7).put("mutualChatTaskId", task).put("mutualChatItemId", item)
                .put("accountId", actor).put("protocolAccountId", "acc_" + actor).put("commandId", command)
                .put("source", AccountMutualChatPolicy.SOURCE).put("targetKind", "PRIVATE")
                .put("jid", mapper.item(item).getTargetPhone() + "@s.whatsapp.net")
                .put("outcome", outcome).put("success", "SUCCESS".equals(outcome));
        if ("SUCCESS".equals(outcome)) data.put("messageId", "wamid-" + command);
        else data.put("reasonCode", "UNKNOWN".equals(outcome) ? "SEND_RESULT_UNKNOWN" : "SEND_FAILED");
        var event = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(data, ProtocolMessageSendResultReportedEvent.class);
        tx.executeWithoutResult(s -> new AccountMutualChatResultSink(runner).handleSendResultReported(event));
    }
    private static AuthPrincipal principal(long user, long tenant, boolean admin) {
        return new AuthPrincipal(user, tenant, "user", "user", "t", "t", admin ? List.of("TENANT_ADMIN") : List.of(),
                List.of("tenant:account:view", "tenant:account:edit"));
    }
}
