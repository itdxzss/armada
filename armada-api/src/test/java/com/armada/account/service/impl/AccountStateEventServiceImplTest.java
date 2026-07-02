package com.armada.account.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountState;
import com.armada.account.service.AccountStateChangedEvent;
import com.armada.account.state.AccountStateChangedSideEffect;
import com.armada.resource.service.IpProxyService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 账号状态事件服务纯单测。
 */
@ExtendWith(MockitoExtension.class)
class AccountStateEventServiceImplTest {

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private AccountStateMapper stateMapper;

    @Mock
    private IpProxyService ipProxyService;

    @Mock
    private AccountStateChangedSideEffect sideEffect;

    @Test
    void applyStateChanged_proxyFailedMarksBoundIpUnavailableBeforeSideEffects() {
        Account account = new Account();
        account.setId(100L);
        account.setProtocolAccountId("acc_8613800138000");
        AccountState currentState = new AccountState();
        currentState.setAccountId(100L);
        currentState.setLastStateSyncTime(1_000L);
        AccountStateChangedEvent event = new AccountStateChangedEvent(
                1L,
                100L,
                "acc_8613800138000",
                "VERIFYING",
                "PROXY_FAILED",
                2_000L,
                "PROXY_FAILED",
                null);
        when(accountMapper.selectActiveById(100L)).thenReturn(account);
        when(stateMapper.selectByAccountId(100L)).thenReturn(currentState);

        AccountStateEventServiceImpl service = new AccountStateEventServiceImpl(
                accountMapper,
                stateMapper,
                ipProxyService,
                List.of(sideEffect));

        service.applyStateChanged(event);

        InOrder inOrder = inOrder(stateMapper, ipProxyService, sideEffect);
        inOrder.verify(stateMapper).updateLoginState(any(AccountState.class));
        inOrder.verify(ipProxyService).markBoundProxyUnavailableByAccount(100L, 2_000L, "PROXY_FAILED");
        inOrder.verify(sideEffect).afterStateChanged(eq(account), eq(event), eq(2_000L));
        verify(ipProxyService, never()).releaseByAccount(100L);
    }

    @Test
    void applyStateChanged_proxyFailedSemanticMarksBoundIpUnavailableBeforeSideEffects() {
        Account account = new Account();
        account.setId(100L);
        account.setProtocolAccountId("acc_8613800138000");
        AccountState currentState = new AccountState();
        currentState.setAccountId(100L);
        currentState.setLastStateSyncTime(1_000L);
        AccountStateChangedEvent event = new AccountStateChangedEvent(
                1L,
                100L,
                "acc_8613800138000",
                "VERIFYING",
                "OFFLINE",
                2_000L,
                "PROXY_FAILED",
                null);
        when(accountMapper.selectActiveById(100L)).thenReturn(account);
        when(stateMapper.selectByAccountId(100L)).thenReturn(currentState);

        AccountStateEventServiceImpl service = new AccountStateEventServiceImpl(
                accountMapper,
                stateMapper,
                ipProxyService,
                List.of(sideEffect));

        service.applyStateChanged(event);

        InOrder inOrder = inOrder(stateMapper, ipProxyService, sideEffect);
        inOrder.verify(stateMapper).updateLoginState(any(AccountState.class));
        inOrder.verify(ipProxyService).markBoundProxyUnavailableByAccount(100L, 2_000L, "PROXY_FAILED");
        inOrder.verify(sideEffect).afterStateChanged(eq(account), eq(event), eq(2_000L));
        verify(ipProxyService, never()).releaseByAccount(100L);
    }
}
