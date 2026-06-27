package com.armada.account.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountOnlinePlan;
import com.armada.platform.protocol.model.command.BatchOnlineCommand;
import com.armada.platform.protocol.port.AccountLifecyclePort;
import com.armada.platform.protocol.model.command.CredentialFormat;
import com.armada.platform.protocol.model.command.OnlineCommand;
import com.armada.platform.protocol.model.command.ProxyDescriptor;
import com.armada.platform.protocol.model.result.BatchOnlineAccepted;
import com.armada.platform.protocol.model.result.BatchOnlineItemResult;
import com.armada.platform.protocol.model.result.BatchOnlineResultStatus;
import com.armada.platform.protocol.model.result.BatchOnlineSummary;
import com.armada.platform.protocol.model.result.OnlineAccepted;
import com.armada.platform.protocol.model.result.OnlineRouting;
import com.armada.platform.protocol.model.result.StateSource;
import com.armada.platform.proxy.ProxyCredentials;
import com.armada.platform.proxy.ProxyEndpoint;
import com.armada.platform.proxy.ProxyResolver;
import com.armada.shared.exception.BusinessException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 账号上线编排单测:只验证账号域到协议端口的组装/委托,不触碰真实协议层或数据库。
 */
@ExtendWith(MockitoExtension.class)
class AccountOnlineServiceImplTest {

    @Mock
    private ProxyResolver proxyResolver;

    @Mock
    private AccountLifecyclePort accountLifecyclePort;

    @InjectMocks
    private AccountOnlineServiceImpl service;

    @Test
    void online_resolvesProxyAndDelegatesCommandToProtocolPort() {
        ProxyEndpoint endpoint = new ProxyEndpoint(
                ProxyEndpoint.PROTOCOL_SOCKS5,
                "proxy.internal",
                1080,
                new ProxyCredentials("user", "pass_session-abc123"),
                "US");
        ProxyDescriptor descriptor = new ProxyDescriptor(
                "socks5",
                "socks5://user:pass_session-abc123@proxy.internal:1080",
                "abc123",
                "US");
        AccountOnlinePlan plan = new AccountOnlinePlan(
                "acc_8613800138000",
                CredentialFormat.BAILEYS_JSON,
                "{\"creds\":{},\"keys\":{}}",
                endpoint);
        OnlineAccepted accepted = new OnlineAccepted(
                "acc_8613800138000",
                true,
                StateSource.MANUAL_REFRESH,
                Instant.parse("2026-06-26T10:15:30Z"),
                new OnlineRouting("worker-a", null, "worker-a", true));
        when(proxyResolver.resolve(endpoint)).thenReturn(descriptor);
        when(accountLifecyclePort.online(plan.protocolAccountId(), new OnlineCommand(
                plan.credentialFormat(), plan.credentialJson(), descriptor))).thenReturn(accepted);

        OnlineAccepted result = service.online(plan);

        ArgumentCaptor<OnlineCommand> commandCaptor = ArgumentCaptor.forClass(OnlineCommand.class);
        assertThat(result).isSameAs(accepted);
        verify(proxyResolver).resolve(endpoint);
        verify(accountLifecyclePort).online(eq(plan.protocolAccountId()), commandCaptor.capture());
        OnlineCommand command = commandCaptor.getValue();
        assertThat(command.format()).isEqualTo(CredentialFormat.BAILEYS_JSON);
        assertThat(command.credentialJson()).isEqualTo("{\"creds\":{},\"keys\":{}}");
        assertThat(command.proxy()).isSameAs(descriptor);
    }

    @Test
    void online_rejectsMissingCredentialBeforeResolvingProxy() {
        AccountOnlinePlan plan = new AccountOnlinePlan(
                "acc_8613800138000",
                CredentialFormat.PARAMS,
                " ",
                new ProxyEndpoint(ProxyEndpoint.PROTOCOL_HTTP, "proxy.internal", 8080,
                        new ProxyCredentials("user", "pass_session-abc123"), "SG"));

        assertThatThrownBy(() -> service.online(plan))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账号凭据不能为空");
        verifyNoInteractions(proxyResolver);
        verify(accountLifecyclePort, never()).online(anyString(), any());
    }

    @Test
    void onlineBatch_resolvesAllProxiesAndDelegatesOneBatchCommandToProtocolPort() {
        ProxyEndpoint endpointA = new ProxyEndpoint(
                ProxyEndpoint.PROTOCOL_SOCKS5,
                "proxy-a.internal",
                1080,
                new ProxyCredentials("user-a", "pass_session-a"),
                "IN");
        ProxyEndpoint endpointB = new ProxyEndpoint(
                ProxyEndpoint.PROTOCOL_HTTP,
                "proxy-b.internal",
                8080,
                new ProxyCredentials("user-b", "pass_session-b"),
                "SG");
        ProxyDescriptor proxyA = new ProxyDescriptor("socks5", "socks5://user-a:pass_session-a@proxy-a.internal:1080", "a", "IN");
        ProxyDescriptor proxyB = new ProxyDescriptor("http", "http://user-b:pass_session-b@proxy-b.internal:8080", "b", "SG");
        AccountOnlinePlan planA = new AccountOnlinePlan(
                "acc_100",
                CredentialFormat.BAILEYS_JSON,
                "{\"creds\":{},\"keys\":{}}",
                endpointA);
        AccountOnlinePlan planB = new AccountOnlinePlan(
                "acc_101",
                CredentialFormat.PARAMS,
                "{\"login\":\"raw\"}",
                endpointB);
        BatchOnlineAccepted accepted = new BatchOnlineAccepted(
                Instant.parse("2026-06-27T10:00:00Z"),
                80L,
                new BatchOnlineSummary(2, 2, 0, 2, 0, 0, 0),
                List.of(
                        new BatchOnlineItemResult("acc_100", BatchOnlineResultStatus.ACCEPTED, null, null),
                        new BatchOnlineItemResult("acc_101", BatchOnlineResultStatus.ACCEPTED, null, null)),
                List.of());
        when(proxyResolver.resolve(endpointA)).thenReturn(proxyA);
        when(proxyResolver.resolve(endpointB)).thenReturn(proxyB);
        when(accountLifecyclePort.onlineBatch(any(BatchOnlineCommand.class))).thenReturn(accepted);

        BatchOnlineAccepted result = service.onlineBatch(List.of(planA, planB), 60_000);

        assertThat(result).isSameAs(accepted);
        ArgumentCaptor<BatchOnlineCommand> commandCaptor = ArgumentCaptor.forClass(BatchOnlineCommand.class);
        verify(accountLifecyclePort).onlineBatch(commandCaptor.capture());
        BatchOnlineCommand command = commandCaptor.getValue();
        assertThat(command.maxWaitMs()).isEqualTo(60_000);
        assertThat(command.items()).hasSize(2);
        assertThat(command.items()).extracting(item -> item.protocolAccountId())
                .containsExactly("acc_100", "acc_101");
        assertThat(command.items().get(0).command().proxy()).isSameAs(proxyA);
        assertThat(command.items().get(1).command().proxy()).isSameAs(proxyB);
    }
}
