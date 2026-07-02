package com.armada.account.state;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.armada.account.model.entity.Account;
import com.armada.account.service.AccountOnlineCommandService;
import com.armada.account.service.AccountStateChangedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * PROXY_FAILED 自动换 IP 重上线 side effect 单测。
 */
@ExtendWith(MockitoExtension.class)
class ProxyFailedAutoReonlineSideEffectTest {

    @Mock
    private AccountOnlineCommandService onlineCommandService;

    @Test
    void afterStateChanged_proxyFailedTriggersReonline() {
        ProxyFailedAutoReonlineSideEffect sideEffect =
                new ProxyFailedAutoReonlineSideEffect(onlineCommandService);
        Account account = account(100L);
        AccountStateChangedEvent event = new AccountStateChangedEvent(
                1L,
                100L,
                "acc_8613800138000",
                "VERIFYING",
                "PROXY_FAILED",
                1782626401000L,
                "PROXY_FAILED",
                null);

        sideEffect.afterStateChanged(account, event, event.occurredAt());

        verify(onlineCommandService).reonlineAfterProxyFailure(100L);
    }

    @Test
    void afterStateChanged_nonProxyFailedDoesNothing() {
        ProxyFailedAutoReonlineSideEffect sideEffect =
                new ProxyFailedAutoReonlineSideEffect(onlineCommandService);
        Account account = account(100L);
        AccountStateChangedEvent event = new AccountStateChangedEvent(
                1L,
                100L,
                "acc_8613800138000",
                "ONLINE",
                "OFFLINE",
                1782626401000L,
                "OFFLINE",
                null);

        sideEffect.afterStateChanged(account, event, event.occurredAt());

        verify(onlineCommandService, never()).reonlineAfterProxyFailure(100L);
    }

    private static Account account(Long id) {
        Account account = new Account();
        account.setId(id);
        account.setProtocolAccountId("acc_8613800138000");
        return account;
    }
}
