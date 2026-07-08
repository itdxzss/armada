package com.armada.account.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.armada.account.mapper.AccountCredentialMapper;
import com.armada.account.mapper.AccountMapper;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountCredential;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountState;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.model.entity.ImportResult;
import com.armada.account.model.vo.AccountIpRegionRow;
import com.armada.account.model.vo.AccountBatchOnlineItemVO;
import com.armada.account.model.vo.AccountBatchOnlineVO;
import com.armada.account.model.vo.AccountOnlineVO;
import com.armada.account.service.AccountOnlineAttemptLogService;
import com.armada.account.service.OnlineAttemptIdGenerator;
import com.armada.platform.country.service.CountryService;
import com.armada.platform.protocol.model.command.CredentialFormat;
import com.armada.platform.protocol.model.command.ProtocolOfflineCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolOnlineCommandRequest;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.platform.proxy.ProxyCredentials;
import com.armada.platform.proxy.ProxyEndpoint;
import com.armada.resource.service.IpProxyAccountAllocation;
import com.armada.resource.service.IpProxyAllocation;
import com.armada.resource.service.IpProxyAllocationRequest;
import com.armada.resource.service.IpProxyService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/**
 * 单账号自动分配代理上线命令服务单测。
 *
 * <p>只验证账号域编排:查账号/凭据/自动分配代理 → 组装轻量协议命令
 * → 写入 outbox。Kafka 发送和协议执行由 platform 后续链路测试覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
class AccountOnlineCommandServiceImplTest {

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private AccountCredentialMapper credentialMapper;

    @Mock
    private AccountStateMapper stateMapper;

    @Mock
    private IpProxyService ipProxyService;

    @Mock
    private CountryService countryService;

    @Mock
    private ProtocolCommandOutboxService protocolCommandOutboxService;

    @Mock
    private OnlineAttemptIdGenerator onlineAttemptIdGenerator;

    @Mock
    private AccountOnlineAttemptLogService accountOnlineAttemptLogService;

    @Mock
    private AccountTakeoverReonlineCooldown takeoverReonlineCooldown;

    @InjectMocks
    private AccountOnlineCommandServiceImpl service;

    @Test
    void online_validAccountCredentialAndAllocatedProxy_enqueuesOutboxCommandAndMapsAcceptedVo() {
        Account account = new Account();
        account.setId(100L);
        account.setWsPhone("8613800138000");
        account.setProtocolAccountId("acc_8613800138000");
        AccountCredential credential = new AccountCredential();
        credential.setAccountId(100L);
        credential.setCredFormat(2);
        credential.setCredsJson("{\"creds\":{},\"keys\":{}}");
        ProxyEndpoint endpoint = new ProxyEndpoint(
                ProxyEndpoint.PROTOCOL_SOCKS5,
                "proxy.internal",
                1080,
                new ProxyCredentials("user", "pass_session-Abc123"),
                "印度");
        when(accountMapper.selectActiveById(100L)).thenReturn(account);
        when(credentialMapper.selectByAccountId(100L)).thenReturn(credential);
        when(accountMapper.selectIpRegionsByAccountIds(List.of(100L), ImportResult.SUCCESS.getCode()))
                .thenReturn(List.of(ipRegionRow(100L, "印度")));
        when(ipProxyService.allocateOnlineEndpoint(new IpProxyAllocationRequest(100L, "印度", true)))
                .thenReturn(new IpProxyAllocation(7L, endpoint, "iproyal"));
        when(onlineAttemptIdGenerator.nextId()).thenReturn("oa_test_single");
        when(protocolCommandOutboxService.enqueueOnlineCommands(any()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(null, List.of("cmd_100"), 1));
        when(stateMapper.markPendingOnline(any(), anyLong())).thenReturn(1);

        AccountOnlineVO result = service.online(100L);

        verify(ipProxyService).allocateOnlineEndpoint(new IpProxyAllocationRequest(100L, "印度", true));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolOnlineCommandRequest>> commandsCaptor = ArgumentCaptor.forClass(List.class);
        verify(protocolCommandOutboxService).enqueueOnlineCommands(commandsCaptor.capture());
        assertThat(commandsCaptor.getValue()).hasSize(1);
        ProtocolOnlineCommandRequest command = commandsCaptor.getValue().get(0);
        assertThat(command.accountId()).isEqualTo(100L);
        assertThat(command.protocolAccountId()).isEqualTo("acc_8613800138000");
        assertThat(command.credentialFormat()).isEqualTo(CredentialFormat.BAILEYS_JSON);
        assertThat(command.proxyId()).isEqualTo(7L);
        assertThat(command.source()).isEqualTo("manual_online");
        assertThat(command.onlineAttemptId()).isEqualTo("oa_test_single");
        assertThat(command.previousOnlineAttemptId()).isNull();
        assertThat(command.protocolBackend()).isEqualTo(ProtocolBackend.WEB);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> pendingIdsCaptor = ArgumentCaptor.forClass(List.class);
        verify(stateMapper).markPendingOnline(pendingIdsCaptor.capture(), anyLong());
        assertThat(pendingIdsCaptor.getValue()).containsExactly(100L);
        verify(ipProxyService, never()).releaseOnlineAllocation(any(), any());

        assertThat(result.accountId()).isEqualTo(100L);
        assertThat(result.protocolAccountId()).isEqualTo("acc_8613800138000");
        assertThat(result.accepted()).isTrue();
        assertThat(result.stateSource()).isEqualTo("OUTBOX");
        assertThat(result.syncedAt()).isNotNull();
        assertThat(result.ownerWorkerId()).isNull();
        assertThat(result.ownerEndpoint()).isNull();
        assertThat(result.currentWorkerId()).isNull();
        assertThat(result.local()).isFalse();
    }

    @Test
    void online_androidProtocolAccount_enqueuesAndroidBackendCommand() {
        Account account = new Account();
        account.setId(100L);
        account.setWsPhone("8613800138000");
        account.setProtocolAccountId("acc_8613800138000");
        account.setProtocolId("ANDROID");
        AccountCredential credential = new AccountCredential();
        credential.setAccountId(100L);
        credential.setCredFormat(2);
        credential.setCredsJson("{\"phone\":\"8613800138000\"}");
        ProxyEndpoint endpoint = new ProxyEndpoint(
                ProxyEndpoint.PROTOCOL_SOCKS5,
                "proxy.internal",
                1080,
                new ProxyCredentials("user", "pass_session-Abc123"),
                "印度");
        when(accountMapper.selectActiveById(100L)).thenReturn(account);
        when(credentialMapper.selectByAccountId(100L)).thenReturn(credential);
        when(accountMapper.selectIpRegionsByAccountIds(List.of(100L), ImportResult.SUCCESS.getCode()))
                .thenReturn(List.of(ipRegionRow(100L, "印度")));
        when(ipProxyService.allocateOnlineEndpoint(new IpProxyAllocationRequest(100L, "印度", true)))
                .thenReturn(new IpProxyAllocation(7L, endpoint, "iproyal"));
        when(onlineAttemptIdGenerator.nextId()).thenReturn("oa_android_single");
        when(protocolCommandOutboxService.enqueueOnlineCommands(any()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(null, List.of("cmd_android"), 1));
        when(stateMapper.markPendingOnline(any(), anyLong())).thenReturn(1);

        service.online(100L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolOnlineCommandRequest>> commandsCaptor = ArgumentCaptor.forClass(List.class);
        verify(protocolCommandOutboxService).enqueueOnlineCommands(commandsCaptor.capture());
        ProtocolOnlineCommandRequest command = commandsCaptor.getValue().get(0);
        assertThat(command.protocolBackend()).isEqualTo(ProtocolBackend.ANDROID);
        assertThat(command.protocolAccountId()).isEqualTo("acc_8613800138000");
        assertThat(command.onlineAttemptId()).isEqualTo("oa_android_single");
    }

    @Test
    void online_success_logsSafeOperationalContextWithoutSecrets() {
        Logger logger = (Logger) LoggerFactory.getLogger(AccountOnlineCommandServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Account account = new Account();
            account.setId(100L);
            account.setProtocolAccountId("acc_8613800138000");
            String credentialJson = "{\"creds\":{},\"keys\":{}}";
            AccountCredential credential = new AccountCredential();
            credential.setAccountId(100L);
            credential.setCredFormat(2);
            credential.setCredsJson(credentialJson);
            ProxyEndpoint endpoint = new ProxyEndpoint(
                    ProxyEndpoint.PROTOCOL_SOCKS5,
                    "proxy.internal",
                    1080,
                    new ProxyCredentials("user", "pass_session-Abc123"),
                    "印度");
            when(accountMapper.selectActiveById(100L)).thenReturn(account);
            when(credentialMapper.selectByAccountId(100L)).thenReturn(credential);
            when(accountMapper.selectIpRegionsByAccountIds(List.of(100L), ImportResult.SUCCESS.getCode()))
                    .thenReturn(List.of(ipRegionRow(100L, "印度")));
            when(ipProxyService.allocateOnlineEndpoint(new IpProxyAllocationRequest(100L, "印度", true)))
                    .thenReturn(new IpProxyAllocation(7L, endpoint, "iproyal"));
            when(onlineAttemptIdGenerator.nextId()).thenReturn("oa_log_single");
            when(protocolCommandOutboxService.enqueueOnlineCommands(any()))
                    .thenReturn(new ProtocolCommandOutboxEnqueueResult(null, List.of("cmd_100"), 1));
            when(stateMapper.markPendingOnline(any(), anyLong())).thenReturn(1);

            service.online(100L);

            List<String> messages = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertThat(messages)
                    .anyMatch(message -> message.contains("账号上线开始 accountId=100"));
            assertThat(messages)
                    .anyMatch(message -> message.contains("账号上线写入 outbox 前准备 command accountId=100 attemptId=oa_log_single allocatedProxyId=7")
                            && message.contains("credentialFormat=BAILEYS_JSON")
                            && message.contains("credentialLength=" + credentialJson.length()));
            assertThat(messages)
                    .anyMatch(message -> message.contains("账号上线 outbox 已受理 accountId=100 allocatedProxyId=7")
                            && message.contains("inserted=1")
                            && message.contains("commandIds=1"));
            assertThat(messages)
                    .noneMatch(message -> message.contains(credentialJson)
                            || message.contains("pass_session-Abc123"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void online_enqueueThrows_releasesAllocatedProxyAndRethrowsOriginalFailure() {
        Account account = onlineAccount();
        AccountCredential credential = onlineCredential();
        ProxyEndpoint endpoint = onlineEndpoint();
        RuntimeException failure = new RuntimeException("outbox unavailable");
        when(accountMapper.selectActiveById(100L)).thenReturn(account);
        when(credentialMapper.selectByAccountId(100L)).thenReturn(credential);
        when(accountMapper.selectIpRegionsByAccountIds(List.of(100L), ImportResult.SUCCESS.getCode()))
                .thenReturn(List.of(ipRegionRow(100L, "印度")));
        when(ipProxyService.allocateOnlineEndpoint(new IpProxyAllocationRequest(100L, "印度", true)))
                .thenReturn(new IpProxyAllocation(7L, endpoint, "iproyal"));
        when(onlineAttemptIdGenerator.nextId()).thenReturn("oa_test_single");
        when(protocolCommandOutboxService.enqueueOnlineCommands(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.online(100L))
                .isSameAs(failure);

        verify(ipProxyService).releaseOnlineAllocation(100L, 7L);
        verify(stateMapper, never()).markPendingOnline(any(), anyLong());
    }

    @Test
    void online_takingOverAccountThrowsValidationBeforeCredentialOrProxyWork() {
        Account account = onlineAccount();
        when(accountMapper.selectActiveById(100L)).thenReturn(account);
        when(stateMapper.selectByAccountId(100L)).thenReturn(takingOverState(100L, AccountLoginStateCode.ONLINE));

        assertThatThrownBy(() -> service.online(100L))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION.code());
                    assertThat(ex.getMessage()).contains("账号抢登中，请先离线");
                });

        verifyNoInteractions(credentialMapper, ipProxyService, protocolCommandOutboxService);
    }

    @Test
    void reonlineAfterProxyFailure_enqueuesOnlineCommandWithAttemptLineageAndProxyFailedSource() {
        Account account = onlineAccount();
        AccountCredential credential = onlineCredential();
        ProxyEndpoint endpoint = onlineEndpoint();
        when(accountMapper.selectActiveById(100L)).thenReturn(account);
        when(credentialMapper.selectByAccountId(100L)).thenReturn(credential);
        when(accountMapper.selectIpRegionsByAccountIds(List.of(100L), ImportResult.SUCCESS.getCode()))
                .thenReturn(List.of(ipRegionRow(100L, "印度")));
        when(ipProxyService.allocateOnlineEndpoint(new IpProxyAllocationRequest(100L, "印度", true)))
                .thenReturn(new IpProxyAllocation(7L, endpoint, "iproyal"));
        when(onlineAttemptIdGenerator.nextId()).thenReturn("oa_retry_1");
        when(accountOnlineAttemptLogService.latestAttemptId(100L)).thenReturn("oa_previous_1");
        when(protocolCommandOutboxService.enqueueOnlineCommands(any()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(null, List.of("cmd_100"), 1));
        when(stateMapper.markPendingOnline(any(), anyLong())).thenReturn(1);

        service.reonlineAfterProxyFailure(100L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolOnlineCommandRequest>> commandsCaptor = ArgumentCaptor.forClass(List.class);
        verify(protocolCommandOutboxService).enqueueOnlineCommands(commandsCaptor.capture());
        ProtocolOnlineCommandRequest command = commandsCaptor.getValue().get(0);
        assertThat(command.accountId()).isEqualTo(100L);
        assertThat(command.protocolAccountId()).isEqualTo("acc_8613800138000");
        assertThat(command.proxyId()).isEqualTo(7L);
        assertThat(command.onlineAttemptId()).isEqualTo("oa_retry_1");
        assertThat(command.previousOnlineAttemptId()).isEqualTo("oa_previous_1");
        assertThat(command.source()).isEqualTo("proxy_failed_reonline");
    }

    @Test
    void reonlineAfterProxyFailure_withFailedAttemptContextUsesEventAttemptWithoutWaitingForDiagnosisLog() {
        Account account = onlineAccount();
        AccountCredential credential = onlineCredential();
        ProxyEndpoint endpoint = onlineEndpoint();
        when(accountMapper.selectActiveById(100L)).thenReturn(account);
        when(credentialMapper.selectByAccountId(100L)).thenReturn(credential);
        when(accountMapper.selectIpRegionsByAccountIds(List.of(100L), ImportResult.SUCCESS.getCode()))
                .thenReturn(List.of(ipRegionRow(100L, "印度")));
        when(ipProxyService.allocateOnlineEndpoint(new IpProxyAllocationRequest(100L, "印度", true)))
                .thenReturn(new IpProxyAllocation(7L, endpoint, "iproyal"));
        when(onlineAttemptIdGenerator.nextId()).thenReturn("oa_retry_1");
        when(protocolCommandOutboxService.enqueueOnlineCommands(any()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(null, List.of("cmd_100"), 1));
        when(stateMapper.markPendingOnline(any(), anyLong())).thenReturn(1);

        service.reonlineAfterProxyFailure(100L, "oa_failed_from_state_event");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolOnlineCommandRequest>> commandsCaptor = ArgumentCaptor.forClass(List.class);
        verify(protocolCommandOutboxService).enqueueOnlineCommands(commandsCaptor.capture());
        ProtocolOnlineCommandRequest command = commandsCaptor.getValue().get(0);
        assertThat(command.previousOnlineAttemptId()).isEqualTo("oa_failed_from_state_event");
        assertThat(command.source()).isEqualTo("proxy_failed_reonline");
        verify(accountOnlineAttemptLogService, never()).latestAttemptId(any());
    }

    @Test
    void onlineBatch_validAccountsCredentialsAndAllocatedProxies_enqueuesOneOutboxBatch() {
        Account accountA = account(100L, "acc_100");
        Account accountB = account(101L, "acc_101");
        AccountCredential credentialA = credential(100L, 2, "{\"creds\":{},\"keys\":{}}");
        AccountCredential credentialB = credential(101L, 3, "{\"login\":\"raw\"}");
        ProxyEndpoint endpointA = onlineEndpoint();
        ProxyEndpoint endpointB = new ProxyEndpoint(
                ProxyEndpoint.PROTOCOL_HTTP,
                "proxy-b.internal",
                8080,
                new ProxyCredentials("user-b", "pass_session-Bbb123"),
                "新加坡");
        when(accountMapper.selectActiveByIds(List.of(100L, 101L))).thenReturn(List.of(accountA, accountB));
        when(credentialMapper.selectByAccountIds(List.of(100L, 101L))).thenReturn(List.of(credentialA, credentialB));
        when(accountMapper.selectIpRegionsByAccountIds(List.of(100L, 101L), ImportResult.SUCCESS.getCode()))
                .thenReturn(List.of(ipRegionRow(100L, "印度"), ipRegionRow(101L, "马来西亚")));
        List<IpProxyAccountAllocation> allocations = List.of(
                new IpProxyAccountAllocation(100L, 7L, endpointA, "iproyal"),
                new IpProxyAccountAllocation(101L, 8L, endpointB, "brightdata"));
        when(ipProxyService.allocateOnlineEndpoints(List.of(
                new IpProxyAllocationRequest(100L, "印度", true),
                new IpProxyAllocationRequest(101L, "马来西亚", true)))).thenReturn(allocations);
        when(onlineAttemptIdGenerator.nextId()).thenReturn("oa_batch_100", "oa_batch_101");
        when(protocolCommandOutboxService.enqueueOnlineCommands(any()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult("batch_1", List.of("cmd_100", "cmd_101"), 2));
        when(stateMapper.markPendingOnline(any(), anyLong())).thenReturn(2);

        AccountBatchOnlineVO result = service.onlineBatch(List.of(100L, 101L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolOnlineCommandRequest>> commandsCaptor = ArgumentCaptor.forClass(List.class);
        verify(protocolCommandOutboxService).enqueueOnlineCommands(commandsCaptor.capture());
        List<ProtocolOnlineCommandRequest> commands = commandsCaptor.getValue();
        assertThat(commands).hasSize(2);
        assertThat(commands).extracting(ProtocolOnlineCommandRequest::protocolAccountId)
                .containsExactly("acc_100", "acc_101");
        assertThat(commands).extracting(ProtocolOnlineCommandRequest::credentialFormat)
                .containsExactly(CredentialFormat.BAILEYS_JSON, CredentialFormat.PARAMS);
        assertThat(commands).extracting(ProtocolOnlineCommandRequest::proxyId)
                .containsExactly(7L, 8L);
        assertThat(commands).extracting(ProtocolOnlineCommandRequest::source)
                .containsExactly("batch_online", "batch_online");
        assertThat(commands).extracting(ProtocolOnlineCommandRequest::onlineAttemptId)
                .containsExactly("oa_batch_100", "oa_batch_101");
        assertThat(commands).extracting(ProtocolOnlineCommandRequest::previousOnlineAttemptId)
                .containsExactly(null, null);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> pendingIdsCaptor = ArgumentCaptor.forClass(List.class);
        verify(stateMapper).markPendingOnline(pendingIdsCaptor.capture(), anyLong());
        assertThat(pendingIdsCaptor.getValue()).containsExactly(100L, 101L);

        verify(ipProxyService, never()).releaseOnlineAllocations(any());
        verify(ipProxyService, never()).releaseOnlineAllocation(any(), any());
        verify(ipProxyService, never()).allocateOnlineEndpoint(any());
        assertThat(result.requested()).isEqualTo(2);
        assertThat(result.submitted()).isEqualTo(2);
        assertThat(result.accepted()).isEqualTo(2);
        assertThat(result.timeout()).isZero();
        assertThat(result.proxyRequired()).isZero();
        assertThat(result.error()).isZero();
        assertThat(result.remote()).isZero();
        assertThat(result.elapsedMs()).isZero();
        assertThat(result.results()).extracting(AccountBatchOnlineItemVO::accountId)
                .containsExactly(100L, 101L);
        assertThat(result.results()).extracting(AccountBatchOnlineItemVO::result)
                .containsExactly("ACCEPTED", "ACCEPTED");
        assertThat(result.remoteRoutes()).isEmpty();
    }

    @Test
    void onlineBatch_containsTakingOverAccountThrowsValidationBeforeCredentialOrProxyWork() {
        Account accountA = account(100L, "acc_100");
        Account accountB = account(101L, "acc_101");
        when(accountMapper.selectActiveByIds(List.of(100L, 101L))).thenReturn(List.of(accountA, accountB));
        when(stateMapper.selectByAccountIds(List.of(100L, 101L)))
                .thenReturn(List.of(takingOverState(101L, AccountLoginStateCode.ONLINE)));

        assertThatThrownBy(() -> service.onlineBatch(List.of(100L, 101L)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION.code());
                    assertThat(ex.getMessage()).contains("账号抢登中，请先离线");
                });

        verifyNoInteractions(credentialMapper, ipProxyService, protocolCommandOutboxService);
    }

    @Test
    void takeoverBatch_allReplacedMarksTakingOverAndEnqueuesTakeoverOnlineBatch() {
        Account accountA = account(100L, "acc_100");
        Account accountB = account(101L, "acc_101");
        AccountCredential credentialA = credential(100L, 2, "{\"creds\":{},\"keys\":{}}");
        AccountCredential credentialB = credential(101L, 2, "{\"creds\":{},\"keys\":{}}");
        when(accountMapper.selectActiveByIds(List.of(100L, 101L))).thenReturn(List.of(accountA, accountB));
        when(stateMapper.selectByAccountIds(List.of(100L, 101L)))
                .thenReturn(List.of(state(100L, AccountStateCode.LOGIN_REPLACED, null),
                        state(101L, AccountStateCode.LOGIN_REPLACED, null)));
        when(stateMapper.markTakingOverByAccountIds(
                eq(List.of(100L, 101L)),
                eq(AccountStateCode.LOGIN_REPLACED),
                eq(AccountStateCode.TAKING_OVER),
                anyLong())).thenReturn(2);
        when(credentialMapper.selectByAccountIds(List.of(100L, 101L))).thenReturn(List.of(credentialA, credentialB));
        when(accountMapper.selectIpRegionsByAccountIds(List.of(100L, 101L), ImportResult.SUCCESS.getCode()))
                .thenReturn(List.of(ipRegionRow(100L, "印度"), ipRegionRow(101L, "马来西亚")));
        when(ipProxyService.allocateOnlineEndpoints(List.of(
                new IpProxyAllocationRequest(100L, "印度", true),
                new IpProxyAllocationRequest(101L, "马来西亚", true))))
                .thenReturn(List.of(
                        new IpProxyAccountAllocation(100L, 7L, onlineEndpoint(), "iproyal"),
                        new IpProxyAccountAllocation(101L, 8L, onlineEndpoint(), "iproyal")));
        when(onlineAttemptIdGenerator.nextId()).thenReturn("oa_takeover_100", "oa_takeover_101");
        when(protocolCommandOutboxService.enqueueOnlineCommands(any()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult("batch_takeover", List.of("cmd_100", "cmd_101"), 2));
        when(stateMapper.markPendingOnline(any(), anyLong())).thenReturn(2);

        AccountBatchOnlineVO result = service.takeoverBatch(List.of(100L, 101L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolOnlineCommandRequest>> commandsCaptor = ArgumentCaptor.forClass(List.class);
        verify(protocolCommandOutboxService).enqueueOnlineCommands(commandsCaptor.capture());
        assertThat(commandsCaptor.getValue()).extracting(ProtocolOnlineCommandRequest::source)
                .containsExactly("login_replaced_takeover", "login_replaced_takeover");
        assertThat(result.accepted()).isEqualTo(2);
    }

    @Test
    void takeoverBatch_containsNonReplacedThrowsValidation() {
        Account accountA = account(100L, "acc_100");
        Account accountB = account(101L, "acc_101");
        when(accountMapper.selectActiveByIds(List.of(100L, 101L))).thenReturn(List.of(accountA, accountB));
        when(stateMapper.selectByAccountIds(List.of(100L, 101L)))
                .thenReturn(List.of(state(100L, AccountStateCode.LOGIN_REPLACED, null),
                        state(101L, AccountStateCode.NORMAL, null)));

        assertThatThrownBy(() -> service.takeoverBatch(List.of(100L, 101L)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION.code());
                    assertThat(ex.getMessage()).contains("当前所选账号存在非被抢登状态，请重新选择");
                });

        verifyNoInteractions(credentialMapper, ipProxyService, protocolCommandOutboxService);
    }

    @Test
    void reonlineForTakeover_currentTakingOverEnqueuesSingleOnlineWithTakeoverSource() {
        Account account = onlineAccount();
        AccountCredential credential = onlineCredential();
        ProxyEndpoint endpoint = onlineEndpoint();
        when(stateMapper.selectByAccountId(100L)).thenReturn(takingOverState(100L, AccountLoginStateCode.OFFLINE));
        when(takeoverReonlineCooldown.tryAcquire(eq(100L), anyLong())).thenReturn(true);
        when(accountMapper.selectActiveById(100L)).thenReturn(account);
        when(credentialMapper.selectByAccountId(100L)).thenReturn(credential);
        when(accountMapper.selectIpRegionsByAccountIds(List.of(100L), ImportResult.SUCCESS.getCode()))
                .thenReturn(List.of(ipRegionRow(100L, "印度")));
        when(ipProxyService.allocateOnlineEndpoint(new IpProxyAllocationRequest(100L, "印度", true)))
                .thenReturn(new IpProxyAllocation(7L, endpoint, "iproyal"));
        when(onlineAttemptIdGenerator.nextId()).thenReturn("oa_takeover_retry");
        when(protocolCommandOutboxService.enqueueOnlineCommands(any()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(null, List.of("cmd_100"), 1));
        when(stateMapper.markPendingOnline(any(), anyLong())).thenReturn(1);

        AccountOnlineVO result = service.reonlineForTakeover(100L, "oa_failed", "offline_takeover");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolOnlineCommandRequest>> commandsCaptor = ArgumentCaptor.forClass(List.class);
        verify(protocolCommandOutboxService).enqueueOnlineCommands(commandsCaptor.capture());
        ProtocolOnlineCommandRequest command = commandsCaptor.getValue().get(0);
        assertThat(command.source()).isEqualTo("offline_takeover");
        assertThat(command.previousOnlineAttemptId()).isNull();
        assertThat(result.accepted()).isTrue();
    }

    @Test
    void reonlineForTakeover_takingOverButOnlineSkipsWithoutOutbox() {
        when(stateMapper.selectByAccountId(100L)).thenReturn(takingOverState(100L, AccountLoginStateCode.ONLINE));

        AccountOnlineVO result = service.reonlineForTakeover(100L, "oa_failed", "login_replaced_takeover");

        assertThat(result.accepted()).isFalse();
        verifyNoInteractions(accountMapper, credentialMapper, ipProxyService, protocolCommandOutboxService);
        verify(takeoverReonlineCooldown, never()).tryAcquire(anyLong(), anyLong());
    }

    @Test
    void reonlineForTakeover_notTakingOverSkipsWithoutOutbox() {
        when(stateMapper.selectByAccountId(100L)).thenReturn(state(100L, AccountStateCode.LOGIN_REPLACED, null));

        AccountOnlineVO result = service.reonlineForTakeover(100L, "oa_failed", "offline_takeover");

        assertThat(result.accepted()).isFalse();
        verifyNoInteractions(accountMapper, credentialMapper, ipProxyService, protocolCommandOutboxService);
    }

    @Test
    void reonlineForTakeover_withinCooldownSkipsWithoutOutbox() {
        when(stateMapper.selectByAccountId(100L)).thenReturn(takingOverState(100L, AccountLoginStateCode.OFFLINE));
        when(takeoverReonlineCooldown.tryAcquire(eq(100L), anyLong())).thenReturn(false);

        AccountOnlineVO result = service.reonlineForTakeover(100L, "oa_failed", "offline_takeover");

        assertThat(result.accepted()).isFalse();
        verifyNoInteractions(accountMapper, credentialMapper, ipProxyService, protocolCommandOutboxService);
    }

    @Test
    void reonlineForTakeover_loginReplacedBypassesCooldownAndEnqueuesOnline() {
        Account account = onlineAccount();
        AccountCredential credential = onlineCredential();
        ProxyEndpoint endpoint = onlineEndpoint();
        when(stateMapper.selectByAccountId(100L)).thenReturn(takingOverState(100L, AccountLoginStateCode.OFFLINE));
        when(accountMapper.selectActiveById(100L)).thenReturn(account);
        when(credentialMapper.selectByAccountId(100L)).thenReturn(credential);
        when(accountMapper.selectIpRegionsByAccountIds(List.of(100L), ImportResult.SUCCESS.getCode()))
                .thenReturn(List.of(ipRegionRow(100L, "印度")));
        when(ipProxyService.allocateOnlineEndpoint(new IpProxyAllocationRequest(100L, "印度", true)))
                .thenReturn(new IpProxyAllocation(7L, endpoint, "iproyal"));
        when(onlineAttemptIdGenerator.nextId()).thenReturn("oa_takeover_retry");
        when(protocolCommandOutboxService.enqueueOnlineCommands(any()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(null, List.of("cmd_100"), 1));
        when(stateMapper.markPendingOnline(any(), anyLong())).thenReturn(1);

        AccountOnlineVO result = service.reonlineForTakeover(100L, "oa_failed", "login_replaced_takeover");

        assertThat(result.accepted()).isTrue();
        verify(takeoverReonlineCooldown, never()).tryAcquire(anyLong(), anyLong());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolOnlineCommandRequest>> commandsCaptor = ArgumentCaptor.forClass(List.class);
        verify(protocolCommandOutboxService).enqueueOnlineCommands(commandsCaptor.capture());
        ProtocolOnlineCommandRequest command = commandsCaptor.getValue().get(0);
        assertThat(command.source()).isEqualTo("login_replaced_takeover");
    }

    @Test
    void onlineBatch_importSmartModeResolvesPhonePrefixAndDisablesOtherRegionFallback() {
        Account accountA = account(100L, "acc_919876543210");
        Account accountB = account(101L, "acc_8613812345678");
        AccountCredential credentialA = credential(100L, 2, "{\"creds\":{},\"keys\":{}}");
        AccountCredential credentialB = credential(101L, 2, "{\"creds\":{},\"keys\":{}}");
        ProxyEndpoint endpointA = onlineEndpoint();
        ProxyEndpoint endpointB = new ProxyEndpoint(
                ProxyEndpoint.PROTOCOL_SOCKS5,
                "proxy-mixed.internal",
                1080,
                new ProxyCredentials("user-b", "pass_session-Bbb123"),
                "混合（不限国家）");
        when(accountMapper.selectActiveByIds(List.of(100L, 101L))).thenReturn(List.of(accountA, accountB));
        when(credentialMapper.selectByAccountIds(List.of(100L, 101L))).thenReturn(List.of(credentialA, credentialB));
        when(accountMapper.selectIpRegionsByAccountIds(List.of(100L, 101L), ImportResult.SUCCESS.getCode()))
                .thenReturn(List.of(
                        ipAllocationRow(100L, "919876543210", null, "smart"),
                        ipAllocationRow(101L, "8613812345678", null, "smart")));
        when(countryService.resolveIpRegionsByPhonePrefix(List.of("919876543210", "8613812345678")))
                .thenReturn(Map.of("919876543210", "印度"));
        List<IpProxyAccountAllocation> allocations = List.of(
                new IpProxyAccountAllocation(100L, 7L, endpointA, "iproyal"),
                new IpProxyAccountAllocation(101L, 8L, endpointB, "iproyal"));
        when(ipProxyService.allocateOnlineEndpoints(List.of(
                new IpProxyAllocationRequest(100L, "印度", false),
                new IpProxyAllocationRequest(101L, "混合（不限国家）", false)))).thenReturn(allocations);
        when(onlineAttemptIdGenerator.nextId()).thenReturn("oa_batch_100", "oa_batch_101");
        when(protocolCommandOutboxService.enqueueOnlineCommands(any()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult("batch_1", List.of("cmd_100", "cmd_101"), 2));
        when(stateMapper.markPendingOnline(any(), anyLong())).thenReturn(2);

        AccountBatchOnlineVO result = service.onlineBatch(List.of(100L, 101L));

        verify(ipProxyService).allocateOnlineEndpoints(List.of(
                new IpProxyAllocationRequest(100L, "印度", false),
                new IpProxyAllocationRequest(101L, "混合（不限国家）", false)));
        assertThat(result.accepted()).isEqualTo(2);
    }

    @Test
    void onlineBatch_importMixedModeUsesMixedRegionOnly() {
        Account account = account(100L, "acc_919876543210");
        AccountCredential credential = credential(100L, 2, "{\"creds\":{},\"keys\":{}}");
        ProxyEndpoint endpoint = new ProxyEndpoint(
                ProxyEndpoint.PROTOCOL_SOCKS5,
                "proxy-mixed.internal",
                1080,
                new ProxyCredentials("user", "pass_session-Abc123"),
                "混合（不限国家）");
        when(accountMapper.selectActiveByIds(List.of(100L))).thenReturn(List.of(account));
        when(credentialMapper.selectByAccountIds(List.of(100L))).thenReturn(List.of(credential));
        when(accountMapper.selectIpRegionsByAccountIds(List.of(100L), ImportResult.SUCCESS.getCode()))
                .thenReturn(List.of(ipAllocationRow(100L, "919876543210", null, "mixed")));
        when(ipProxyService.allocateOnlineEndpoints(List.of(
                new IpProxyAllocationRequest(100L, "混合（不限国家）", false))))
                .thenReturn(List.of(new IpProxyAccountAllocation(100L, 7L, endpoint, "iproyal")));
        when(onlineAttemptIdGenerator.nextId()).thenReturn("oa_batch_100");
        when(protocolCommandOutboxService.enqueueOnlineCommands(any()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult("batch_1", List.of("cmd_100"), 1));
        when(stateMapper.markPendingOnline(any(), anyLong())).thenReturn(1);

        service.onlineBatch(List.of(100L));

        verifyNoInteractions(countryService);
        verify(ipProxyService).allocateOnlineEndpoints(List.of(
                new IpProxyAllocationRequest(100L, "混合（不限国家）", false)));
    }

    @Test
    void offlineBatch_validAccounts_enqueuesOneOfflineOutboxBatchWithoutCredentialOrProxyWork() {
        Account accountA = account(100L, "acc_100");
        Account accountB = account(101L, "acc_101");
        when(accountMapper.selectActiveByIds(List.of(100L, 101L))).thenReturn(List.of(accountA, accountB));
        when(protocolCommandOutboxService.enqueueOfflineCommands(any()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(
                        "batch_offline_1",
                        List.of("cmd_offline_100", "cmd_offline_101"),
                        2));

        AccountBatchOnlineVO result = service.offlineBatch(List.of(100L, 101L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolOfflineCommandRequest>> commandsCaptor = ArgumentCaptor.forClass(List.class);
        verify(protocolCommandOutboxService).enqueueOfflineCommands(commandsCaptor.capture());
        List<ProtocolOfflineCommandRequest> commands = commandsCaptor.getValue();
        assertThat(commands).hasSize(2);
        assertThat(commands).extracting(ProtocolOfflineCommandRequest::accountId)
                .containsExactly(100L, 101L);
        assertThat(commands).extracting(ProtocolOfflineCommandRequest::protocolAccountId)
                .containsExactly("acc_100", "acc_101");
        assertThat(commands).extracting(ProtocolOfflineCommandRequest::source)
                .containsExactly("batch_offline", "batch_offline");

        verifyNoInteractions(credentialMapper, ipProxyService);
        assertThat(result.requested()).isEqualTo(2);
        assertThat(result.submitted()).isEqualTo(2);
        assertThat(result.accepted()).isEqualTo(2);
        assertThat(result.timeout()).isZero();
        assertThat(result.proxyRequired()).isZero();
        assertThat(result.error()).isZero();
        assertThat(result.remote()).isZero();
        assertThat(result.elapsedMs()).isZero();
        assertThat(result.results()).extracting(AccountBatchOnlineItemVO::accountId)
                .containsExactly(100L, 101L);
        assertThat(result.results()).extracting(AccountBatchOnlineItemVO::result)
                .containsExactly("ACCEPTED", "ACCEPTED");
        assertThat(result.remoteRoutes()).isEmpty();
    }

    @Test
    void onlineBatch_enqueueThrows_releasesAllAllocatedProxiesAndRethrowsOriginalFailure() {
        Account accountA = account(100L, "acc_100");
        Account accountB = account(101L, "acc_101");
        AccountCredential credentialA = credential(100L, 2, "{\"creds\":{},\"keys\":{}}");
        AccountCredential credentialB = credential(101L, 2, "{\"creds\":{},\"keys\":{}}");
        RuntimeException failure = new RuntimeException("outbox unavailable");
        when(accountMapper.selectActiveByIds(List.of(100L, 101L))).thenReturn(List.of(accountA, accountB));
        when(credentialMapper.selectByAccountIds(List.of(100L, 101L))).thenReturn(List.of(credentialA, credentialB));
        when(accountMapper.selectIpRegionsByAccountIds(List.of(100L, 101L), ImportResult.SUCCESS.getCode()))
                .thenReturn(List.of(ipRegionRow(100L, "印度"), ipRegionRow(101L, "巴基斯坦")));
        List<IpProxyAccountAllocation> allocations = List.of(
                new IpProxyAccountAllocation(100L, 7L, onlineEndpoint(), "iproyal"),
                new IpProxyAccountAllocation(101L, 8L, onlineEndpoint(), "brightdata"));
        when(ipProxyService.allocateOnlineEndpoints(List.of(
                new IpProxyAllocationRequest(100L, "印度", true),
                new IpProxyAllocationRequest(101L, "巴基斯坦", true)))).thenReturn(allocations);
        when(onlineAttemptIdGenerator.nextId()).thenReturn("oa_batch_100", "oa_batch_101");
        when(protocolCommandOutboxService.enqueueOnlineCommands(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.onlineBatch(List.of(100L, 101L)))
                .isSameAs(failure);

        verify(ipProxyService).releaseOnlineAllocations(allocations);
        verify(ipProxyService, never()).releaseOnlineAllocation(any(), any());
    }

    @Test
    void onlineBatch_planBuildFailsAfterAllocation_releasesAllAllocatedProxiesBeforeRethrow() {
        Account accountA = account(100L, "acc_100");
        Account accountB = account(101L, null);
        AccountCredential credentialA = credential(100L, 2, "{\"creds\":{},\"keys\":{}}");
        AccountCredential credentialB = credential(101L, 2, "{\"creds\":{},\"keys\":{}}");
        List<IpProxyAccountAllocation> allocations = List.of(
                new IpProxyAccountAllocation(100L, 7L, onlineEndpoint(), "iproyal"),
                new IpProxyAccountAllocation(101L, 8L, onlineEndpoint(), "brightdata"));
        when(accountMapper.selectActiveByIds(List.of(100L, 101L))).thenReturn(List.of(accountA, accountB));
        when(credentialMapper.selectByAccountIds(List.of(100L, 101L))).thenReturn(List.of(credentialA, credentialB));
        when(accountMapper.selectIpRegionsByAccountIds(List.of(100L, 101L), ImportResult.SUCCESS.getCode()))
                .thenReturn(List.of(ipRegionRow(100L, "印度"), ipRegionRow(101L, "巴基斯坦")));
        when(ipProxyService.allocateOnlineEndpoints(List.of(
                new IpProxyAllocationRequest(100L, "印度", true),
                new IpProxyAllocationRequest(101L, "巴基斯坦", true)))).thenReturn(allocations);
        when(onlineAttemptIdGenerator.nextId()).thenReturn("oa_plan_100");

        assertThatThrownBy(() -> service.onlineBatch(List.of(100L, 101L)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION.code());
                    assertThat(ex.getMessage()).contains("协议账号 ID 为空");
                });

        verify(ipProxyService).releaseOnlineAllocations(allocations);
        verify(ipProxyService, never()).releaseOnlineAllocation(any(), any());
        verifyNoInteractions(protocolCommandOutboxService);
    }

    @Test
    void reloginOnlineAccountsByProxyIds_onlyReloginsOnlineBoundAccountsAndExcludesDeletedProxies() {
        List<Long> proxyIds = List.of(10L, 11L);
        List<Long> boundAccountIds = List.of(100L, 101L, 102L);
        when(ipProxyService.findBoundAccountIdsByProxyIds(proxyIds)).thenReturn(boundAccountIds);
        when(accountMapper.selectOnlineAccountIdsByIds(boundAccountIds, AccountLoginStateCode.ONLINE))
                .thenReturn(List.of(101L, 100L));
        Account accountA = account(100L, "acc_100");
        Account accountB = account(101L, "acc_101");
        AccountCredential credentialA = credential(100L, 2, "{\"creds\":{},\"keys\":{}}");
        AccountCredential credentialB = credential(101L, 3, "{\"login\":\"raw\"}");
        when(accountMapper.selectActiveByIds(List.of(100L, 101L))).thenReturn(List.of(accountA, accountB));
        when(credentialMapper.selectByAccountIds(List.of(100L, 101L))).thenReturn(List.of(credentialA, credentialB));
        when(accountMapper.selectIpRegionsByAccountIds(List.of(100L, 101L), ImportResult.SUCCESS.getCode()))
                .thenReturn(List.of(ipRegionRow(100L, "印度"), ipRegionRow(101L, "马来西亚")));
        List<IpProxyAccountAllocation> allocations = List.of(
                new IpProxyAccountAllocation(100L, 20L, onlineEndpoint(), "iproyal"),
                new IpProxyAccountAllocation(101L, 21L, onlineEndpoint(), "brightdata"));
        when(ipProxyService.allocateOnlineEndpointsExcludingProxyIds(List.of(
                new IpProxyAllocationRequest(100L, "印度", true),
                new IpProxyAllocationRequest(101L, "马来西亚", true)), proxyIds)).thenReturn(allocations);
        when(onlineAttemptIdGenerator.nextId()).thenReturn("oa_relogin_100", "oa_relogin_101");
        when(protocolCommandOutboxService.enqueueOnlineCommands(any()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult("batch_relogin", List.of("cmd_100", "cmd_101"), 2));
        when(stateMapper.markPendingOnline(any(), anyLong())).thenReturn(2);

        AccountBatchOnlineVO result = service.reloginOnlineAccountsByProxyIds(proxyIds);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolOnlineCommandRequest>> commandsCaptor = ArgumentCaptor.forClass(List.class);
        verify(protocolCommandOutboxService).enqueueOnlineCommands(commandsCaptor.capture());
        List<ProtocolOnlineCommandRequest> commands = commandsCaptor.getValue();
        assertThat(commands).hasSize(2);
        assertThat(commands).extracting(ProtocolOnlineCommandRequest::accountId)
                .containsExactly(100L, 101L);
        assertThat(commands).extracting(ProtocolOnlineCommandRequest::proxyId)
                .containsExactly(20L, 21L);
        assertThat(commands).extracting(ProtocolOnlineCommandRequest::source)
                .containsExactly("ip_delete_relogin", "ip_delete_relogin");
        assertThat(commands).extracting(ProtocolOnlineCommandRequest::onlineAttemptId)
                .containsExactly("oa_relogin_100", "oa_relogin_101");
        assertThat(commands).extracting(ProtocolOnlineCommandRequest::previousOnlineAttemptId)
                .containsExactly(null, null);
        verify(ipProxyService, never()).allocateOnlineEndpoints(any());
        assertThat(result.requested()).isEqualTo(2);
        assertThat(result.accepted()).isEqualTo(2);
    }

    @Test
    void reloginOnlineAccountsByProxyIds_noOnlineBoundAccountsSkipsCredentialProxyAndOutbox() {
        List<Long> proxyIds = List.of(10L);
        List<Long> boundAccountIds = List.of(100L);
        when(ipProxyService.findBoundAccountIdsByProxyIds(proxyIds)).thenReturn(boundAccountIds);
        when(accountMapper.selectOnlineAccountIdsByIds(boundAccountIds, AccountLoginStateCode.ONLINE))
                .thenReturn(List.of());

        AccountBatchOnlineVO result = service.reloginOnlineAccountsByProxyIds(proxyIds);

        verifyNoInteractions(credentialMapper, protocolCommandOutboxService);
        verify(ipProxyService, never()).allocateOnlineEndpoints(any());
        verify(ipProxyService, never()).allocateOnlineEndpointsExcludingProxyIds(any(), any());
        assertThat(result.requested()).isZero();
        assertThat(result.submitted()).isZero();
        assertThat(result.accepted()).isZero();
    }

    @Test
    void onlineBatch_missingCredential_throwsValidationBeforeProxyAllocation() {
        when(accountMapper.selectActiveByIds(List.of(100L, 101L)))
                .thenReturn(List.of(account(100L, "acc_100"), account(101L, "acc_101")));
        when(credentialMapper.selectByAccountIds(List.of(100L, 101L)))
                .thenReturn(List.of(credential(100L, 2, "{\"creds\":{},\"keys\":{}}")));

        assertThatThrownBy(() -> service.onlineBatch(List.of(100L, 101L)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION.code());
                    assertThat(ex.getMessage()).contains("账号凭据不存在");
                });
        verifyNoInteractions(ipProxyService, protocolCommandOutboxService);
    }

    @Test
    void online_missingAccount_throwsNotFoundBeforeCredentialLookup() {
        when(accountMapper.selectActiveById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.online(404L))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.NOT_FOUND.code());
                    assertThat(ex.getMessage()).contains("账号不存在");
                });
        verify(credentialMapper, never()).selectByAccountId(any());
        verifyNoInteractions(ipProxyService, protocolCommandOutboxService);
    }

    @Test
    void online_missingCredential_throwsValidationBeforeProxyLookup() {
        Account account = new Account();
        account.setId(100L);
        account.setProtocolAccountId("acc_8613800138000");
        when(accountMapper.selectActiveById(100L)).thenReturn(account);
        when(credentialMapper.selectByAccountId(100L)).thenReturn(null);

        assertThatThrownBy(() -> service.online(100L))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION.code());
                    assertThat(ex.getMessage()).contains("账号凭据不存在");
                });
        verifyNoInteractions(ipProxyService, protocolCommandOutboxService);
    }

    private static Account onlineAccount() {
        return account(100L, "acc_8613800138000");
    }

    private static Account account(Long accountId, String protocolAccountId) {
        Account account = new Account();
        account.setId(accountId);
        account.setProtocolAccountId(protocolAccountId);
        return account;
    }

    private static AccountCredential onlineCredential() {
        return credential(100L, 2, "{\"creds\":{},\"keys\":{}}");
    }

    private static AccountCredential credential(Long accountId, Integer format, String json) {
        AccountCredential credential = new AccountCredential();
        credential.setAccountId(accountId);
        credential.setCredFormat(format);
        credential.setCredsJson(json);
        return credential;
    }

    private static AccountState state(Long accountId, Integer accountState, Integer muteStatus) {
        AccountState state = new AccountState();
        state.setAccountId(accountId);
        state.setAccountState(accountState);
        state.setMuteStatus(muteStatus);
        return state;
    }

    private static AccountState takingOverState(Long accountId, Integer loginState) {
        AccountState state = state(accountId, AccountStateCode.TAKING_OVER, null);
        state.setLoginState(loginState);
        return state;
    }

    private static AccountIpRegionRow ipRegionRow(Long accountId, String ipRegion) {
        AccountIpRegionRow row = new AccountIpRegionRow();
        row.setAccountId(accountId);
        row.setIpRegion(ipRegion);
        return row;
    }

    private static AccountIpRegionRow ipAllocationRow(Long accountId,
                                                       String wsPhone,
                                                       String ipRegion,
                                                       String ipAllocationMode) {
        AccountIpRegionRow row = ipRegionRow(accountId, ipRegion);
        row.setWsPhone(wsPhone);
        row.setIpAllocationMode(ipAllocationMode);
        return row;
    }

    private static ProxyEndpoint onlineEndpoint() {
        return new ProxyEndpoint(
                ProxyEndpoint.PROTOCOL_SOCKS5,
                "proxy.internal",
                1080,
                new ProxyCredentials("user", "pass_session-Abc123"),
                "印度");
    }
}
