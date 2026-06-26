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
import com.armada.platform.protocol.port.AccountLifecyclePort;
import com.armada.platform.protocol.model.command.CredentialFormat;
import com.armada.platform.protocol.model.command.OnlineCommand;
import com.armada.platform.protocol.model.command.ProxyDescriptor;
import com.armada.platform.protocol.model.result.OnlineAccepted;
import com.armada.platform.protocol.model.result.OnlineRouting;
import com.armada.platform.protocol.model.result.StateSource;
import com.armada.platform.proxy.ProxyCredentials;
import com.armada.platform.proxy.ProxyEndpoint;
import com.armada.platform.proxy.ProxyResolver;
import com.armada.shared.exception.BusinessException;
import java.time.Instant;
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
}
