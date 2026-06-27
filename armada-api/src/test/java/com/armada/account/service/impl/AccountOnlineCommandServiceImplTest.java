package com.armada.account.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountCredential;
import com.armada.account.model.vo.AccountBatchOnlineItemVO;
import com.armada.account.model.vo.AccountBatchOnlineVO;
import com.armada.account.model.vo.AccountOnlineVO;
import com.armada.account.service.AccountOnlinePlan;
import com.armada.account.service.AccountOnlineService;
import com.armada.platform.protocol.model.command.CredentialFormat;
import com.armada.platform.protocol.model.result.BatchOnlineAccepted;
import com.armada.platform.protocol.model.result.BatchOnlineItemResult;
import com.armada.platform.protocol.model.result.BatchOnlineResultStatus;
import com.armada.platform.protocol.model.result.BatchOnlineSummary;
import com.armada.platform.protocol.model.result.OnlineAccepted;
import com.armada.platform.protocol.model.result.OnlineRouting;
import com.armada.platform.protocol.model.result.StateSource;
import com.armada.platform.proxy.ProxyCredentials;
import com.armada.platform.proxy.ProxyEndpoint;
import com.armada.resource.service.IpProxyAccountAllocation;
import com.armada.resource.service.IpProxyAllocation;
import com.armada.resource.service.IpProxyService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.time.Instant;
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
 * <p>只验证账号域编排:查账号/凭据/自动分配代理 → 组装 {@link AccountOnlinePlan}
 * → 调现有上线服务。协议 HTTP 行为由底层 adapter 测试覆盖。</p>
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
    private AccountOnlineService accountOnlineService;

    @InjectMocks
    private AccountOnlineCommandServiceImpl service;

    @Test
    void online_validAccountCredentialAndAllocatedProxy_delegatesPlanAndMapsAcceptedVo() {
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
        Instant syncedAt = Instant.parse("2026-06-26T10:15:30Z");
        OnlineAccepted accepted = new OnlineAccepted(
                "acc_8613800138000",
                true,
                StateSource.MANUAL_REFRESH,
                syncedAt,
                new OnlineRouting("worker-a", null, "worker-a", true));
        when(accountMapper.selectActiveById(100L)).thenReturn(account);
        when(credentialMapper.selectByAccountId(100L)).thenReturn(credential);
        when(ipProxyService.allocateOnlineEndpoint(100L)).thenReturn(new IpProxyAllocation(7L, endpoint));
        when(accountOnlineService.online(any(AccountOnlinePlan.class))).thenReturn(accepted);

        AccountOnlineVO result = service.online(100L);

        verify(ipProxyService).allocateOnlineEndpoint(100L);
        ArgumentCaptor<AccountOnlinePlan> planCaptor = ArgumentCaptor.forClass(AccountOnlinePlan.class);
        verify(accountOnlineService).online(planCaptor.capture());
        AccountOnlinePlan plan = planCaptor.getValue();
        assertThat(plan.protocolAccountId()).isEqualTo("acc_8613800138000");
        assertThat(plan.credentialFormat()).isEqualTo(CredentialFormat.BAILEYS_JSON);
        assertThat(plan.credentialJson()).isEqualTo("{\"creds\":{},\"keys\":{}}");
        assertThat(plan.proxyEndpoint()).isSameAs(endpoint);
        verify(ipProxyService, never()).releaseOnlineAllocation(any(), any());

        assertThat(result.accountId()).isEqualTo(100L);
        assertThat(result.protocolAccountId()).isEqualTo("acc_8613800138000");
        assertThat(result.accepted()).isTrue();
        assertThat(result.stateSource()).isEqualTo("MANUAL_REFRESH");
        assertThat(result.syncedAt()).isEqualTo(syncedAt.toEpochMilli());
        assertThat(result.ownerWorkerId()).isEqualTo("worker-a");
        assertThat(result.ownerEndpoint()).isNull();
        assertThat(result.currentWorkerId()).isEqualTo("worker-a");
        assertThat(result.local()).isTrue();
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
            OnlineAccepted accepted = new OnlineAccepted(
                    "acc_8613800138000",
                    true,
                    StateSource.MANUAL_REFRESH,
                    Instant.parse("2026-06-26T10:15:30Z"),
                    new OnlineRouting("worker-a", null, "worker-a", true));
            when(accountMapper.selectActiveById(100L)).thenReturn(account);
            when(credentialMapper.selectByAccountId(100L)).thenReturn(credential);
            when(ipProxyService.allocateOnlineEndpoint(100L)).thenReturn(new IpProxyAllocation(7L, endpoint));
            when(accountOnlineService.online(any(AccountOnlinePlan.class))).thenReturn(accepted);

            service.online(100L);

            List<String> messages = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertThat(messages)
                    .anyMatch(message -> message.contains("账号上线开始 accountId=100"));
            assertThat(messages)
                    .anyMatch(message -> message.contains("账号上线调用协议层 accountId=100 allocatedProxyId=7")
                            && message.contains("credentialFormat=BAILEYS_JSON")
                            && message.contains("credentialLength=" + credentialJson.length()));
            assertThat(messages)
                    .anyMatch(message -> message.contains("账号上线协议层返回 accountId=100 allocatedProxyId=7 accepted=true")
                            && message.contains("stateSource=MANUAL_REFRESH")
                            && message.contains("ownerWorkerId=worker-a")
                            && message.contains("local=true"));
            assertThat(messages)
                    .noneMatch(message -> message.contains(credentialJson)
                            || message.contains("pass_session-Abc123"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void online_protocolThrows_releasesAllocatedProxyAndRethrowsOriginalFailure() {
        Account account = onlineAccount();
        AccountCredential credential = onlineCredential();
        ProxyEndpoint endpoint = onlineEndpoint();
        RuntimeException failure = new RuntimeException("protocol unavailable");
        when(accountMapper.selectActiveById(100L)).thenReturn(account);
        when(credentialMapper.selectByAccountId(100L)).thenReturn(credential);
        when(ipProxyService.allocateOnlineEndpoint(100L)).thenReturn(new IpProxyAllocation(7L, endpoint));
        when(accountOnlineService.online(any(AccountOnlinePlan.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.online(100L))
                .isSameAs(failure);

        verify(ipProxyService).releaseOnlineAllocation(100L, 7L);
    }

    @Test
    void online_protocolReturnsNotAccepted_releasesAllocatedProxyAndReturnsRejectedVo() {
        Account account = onlineAccount();
        AccountCredential credential = onlineCredential();
        ProxyEndpoint endpoint = onlineEndpoint();
        OnlineAccepted accepted = new OnlineAccepted(
                "acc_8613800138000",
                false,
                StateSource.MANUAL_REFRESH,
                Instant.parse("2026-06-26T10:15:30Z"),
                new OnlineRouting("worker-a", null, "worker-a", true));
        when(accountMapper.selectActiveById(100L)).thenReturn(account);
        when(credentialMapper.selectByAccountId(100L)).thenReturn(credential);
        when(ipProxyService.allocateOnlineEndpoint(100L)).thenReturn(new IpProxyAllocation(7L, endpoint));
        when(accountOnlineService.online(any(AccountOnlinePlan.class))).thenReturn(accepted);

        AccountOnlineVO result = service.online(100L);

        assertThat(result.accepted()).isFalse();
        verify(ipProxyService).releaseOnlineAllocation(100L, 7L);
    }

    @Test
    void onlineBatch_validAccountsCredentialsAndAllocatedProxies_delegatesOneBatchAndReleasesRejectedItems() {
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
        BatchOnlineAccepted accepted = new BatchOnlineAccepted(
                Instant.parse("2026-06-27T10:00:00Z"),
                80L,
                new BatchOnlineSummary(2, 2, 0, 1, 1, 0, 0),
                List.of(
                        new BatchOnlineItemResult("acc_100", BatchOnlineResultStatus.ACCEPTED, null, null),
                        new BatchOnlineItemResult("acc_101", BatchOnlineResultStatus.TIMEOUT, 5000, null)),
                List.of());
        when(accountMapper.selectActiveByIds(List.of(100L, 101L))).thenReturn(List.of(accountA, accountB));
        when(credentialMapper.selectByAccountIds(List.of(100L, 101L))).thenReturn(List.of(credentialA, credentialB));
        List<IpProxyAccountAllocation> allocations = List.of(
                new IpProxyAccountAllocation(100L, 7L, endpointA),
                new IpProxyAccountAllocation(101L, 8L, endpointB));
        when(ipProxyService.allocateOnlineEndpoints(List.of(100L, 101L))).thenReturn(allocations);
        when(accountOnlineService.onlineBatch(any(), eq(60_000))).thenReturn(accepted);

        AccountBatchOnlineVO result = service.onlineBatch(List.of(100L, 101L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AccountOnlinePlan>> plansCaptor = ArgumentCaptor.forClass(List.class);
        verify(accountOnlineService).onlineBatch(plansCaptor.capture(), eq(60_000));
        List<AccountOnlinePlan> plans = plansCaptor.getValue();
        assertThat(plans).hasSize(2);
        assertThat(plans).extracting(AccountOnlinePlan::protocolAccountId)
                .containsExactly("acc_100", "acc_101");
        assertThat(plans).extracting(AccountOnlinePlan::credentialFormat)
                .containsExactly(CredentialFormat.BAILEYS_JSON, CredentialFormat.PARAMS);
        assertThat(plans.get(0).proxyEndpoint()).isSameAs(endpointA);
        assertThat(plans.get(1).proxyEndpoint()).isSameAs(endpointB);

        verify(ipProxyService).releaseOnlineAllocations(List.of(allocations.get(1)));
        verify(ipProxyService, never()).releaseOnlineAllocation(any(), any());
        verify(ipProxyService, never()).allocateOnlineEndpoint(any());
        assertThat(result.requested()).isEqualTo(2);
        assertThat(result.submitted()).isEqualTo(2);
        assertThat(result.accepted()).isEqualTo(1);
        assertThat(result.timeout()).isEqualTo(1);
        assertThat(result.results()).extracting(AccountBatchOnlineItemVO::accountId)
                .containsExactly(100L, 101L);
        assertThat(result.results()).extracting(AccountBatchOnlineItemVO::result)
                .containsExactly("ACCEPTED", "TIMEOUT");
    }

    @Test
    void onlineBatch_protocolThrows_releasesAllAllocatedProxiesAndRethrowsOriginalFailure() {
        Account accountA = account(100L, "acc_100");
        Account accountB = account(101L, "acc_101");
        AccountCredential credentialA = credential(100L, 2, "{\"creds\":{},\"keys\":{}}");
        AccountCredential credentialB = credential(101L, 2, "{\"creds\":{},\"keys\":{}}");
        RuntimeException failure = new RuntimeException("protocol unavailable");
        when(accountMapper.selectActiveByIds(List.of(100L, 101L))).thenReturn(List.of(accountA, accountB));
        when(credentialMapper.selectByAccountIds(List.of(100L, 101L))).thenReturn(List.of(credentialA, credentialB));
        List<IpProxyAccountAllocation> allocations = List.of(
                new IpProxyAccountAllocation(100L, 7L, onlineEndpoint()),
                new IpProxyAccountAllocation(101L, 8L, onlineEndpoint()));
        when(ipProxyService.allocateOnlineEndpoints(List.of(100L, 101L))).thenReturn(allocations);
        when(accountOnlineService.onlineBatch(any(), eq(60_000))).thenThrow(failure);

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
        verifyNoInteractions(accountOnlineService);
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
        verifyNoInteractions(ipProxyService, accountOnlineService);
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
        verifyNoInteractions(ipProxyService, accountOnlineService);
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
        verifyNoInteractions(ipProxyService, accountOnlineService);
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
