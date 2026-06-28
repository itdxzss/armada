package com.armada.account.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.armada.account.mapper.AccountCredentialMapper;
import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountCredential;
import com.armada.account.model.vo.AccountBatchOnlineItemVO;
import com.armada.account.model.vo.AccountBatchOnlineVO;
import com.armada.account.model.vo.AccountOnlineVO;
import com.armada.platform.protocol.model.command.CredentialFormat;
import com.armada.platform.protocol.model.command.ProtocolOfflineCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolOnlineCommandRequest;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.platform.proxy.ProxyCredentials;
import com.armada.platform.proxy.ProxyEndpoint;
import com.armada.resource.service.IpProxyAccountAllocation;
import com.armada.resource.service.IpProxyAllocation;
import com.armada.resource.service.IpProxyService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.List;
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
    private IpProxyService ipProxyService;

    @Mock
    private ProtocolCommandOutboxService protocolCommandOutboxService;

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
        when(ipProxyService.allocateOnlineEndpoint(100L)).thenReturn(new IpProxyAllocation(7L, endpoint));
        when(protocolCommandOutboxService.enqueueOnlineCommands(any()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(null, List.of("cmd_100"), 1));

        AccountOnlineVO result = service.online(100L);

        verify(ipProxyService).allocateOnlineEndpoint(100L);
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
            when(ipProxyService.allocateOnlineEndpoint(100L)).thenReturn(new IpProxyAllocation(7L, endpoint));
            when(protocolCommandOutboxService.enqueueOnlineCommands(any()))
                    .thenReturn(new ProtocolCommandOutboxEnqueueResult(null, List.of("cmd_100"), 1));

            service.online(100L);

            List<String> messages = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertThat(messages)
                    .anyMatch(message -> message.contains("账号上线开始 accountId=100"));
            assertThat(messages)
                    .anyMatch(message -> message.contains("账号上线写入 outbox 前准备 command accountId=100 allocatedProxyId=7")
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
        when(ipProxyService.allocateOnlineEndpoint(100L)).thenReturn(new IpProxyAllocation(7L, endpoint));
        when(protocolCommandOutboxService.enqueueOnlineCommands(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.online(100L))
                .isSameAs(failure);

        verify(ipProxyService).releaseOnlineAllocation(100L, 7L);
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
        List<IpProxyAccountAllocation> allocations = List.of(
                new IpProxyAccountAllocation(100L, 7L, endpointA),
                new IpProxyAccountAllocation(101L, 8L, endpointB));
        when(ipProxyService.allocateOnlineEndpoints(List.of(100L, 101L))).thenReturn(allocations);
        when(protocolCommandOutboxService.enqueueOnlineCommands(any()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult("batch_1", List.of("cmd_100", "cmd_101"), 2));

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
        List<IpProxyAccountAllocation> allocations = List.of(
                new IpProxyAccountAllocation(100L, 7L, onlineEndpoint()),
                new IpProxyAccountAllocation(101L, 8L, onlineEndpoint()));
        when(ipProxyService.allocateOnlineEndpoints(List.of(100L, 101L))).thenReturn(allocations);
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
                new IpProxyAccountAllocation(100L, 7L, onlineEndpoint()),
                new IpProxyAccountAllocation(101L, 8L, onlineEndpoint()));
        when(accountMapper.selectActiveByIds(List.of(100L, 101L))).thenReturn(List.of(accountA, accountB));
        when(credentialMapper.selectByAccountIds(List.of(100L, 101L))).thenReturn(List.of(credentialA, credentialB));
        when(ipProxyService.allocateOnlineEndpoints(List.of(100L, 101L))).thenReturn(allocations);

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

    private static ProxyEndpoint onlineEndpoint() {
        return new ProxyEndpoint(
                ProxyEndpoint.PROTOCOL_SOCKS5,
                "proxy.internal",
                1080,
                new ProxyCredentials("user", "pass_session-Abc123"),
                "印度");
    }
}
