package com.armada.account.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountState;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.service.AccountStateChangedEvent;
import com.armada.account.state.AccountStateChangedSideEffect;
import com.armada.resource.service.IpProxyService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    void applyStateChanged_loginReplacedMarksAccountAsReplacedAndOffline() {
        Account account = account();
        AccountState currentState = currentState(AccountStateCode.NORMAL, 1_000L);
        AccountStateChangedEvent event = event("ONLINE", "LOGIN_REPLACED",
                2_000L, "LOGIN_REPLACED", 440, "batch_online", "oa_440");
        when(accountMapper.selectActiveById(100L)).thenReturn(account);
        when(stateMapper.selectByAccountId(100L)).thenReturn(currentState);

        service().applyStateChanged(event);

        ArgumentCaptor<AccountState> rowCaptor = ArgumentCaptor.forClass(AccountState.class);
        verify(stateMapper).updateLifecycleState(rowCaptor.capture());
        AccountState row = rowCaptor.getValue();
        assertThat(row.getLoginState()).isEqualTo(AccountLoginStateCode.OFFLINE);
        assertThat(row.getAccountState()).isEqualTo(AccountStateCode.LOGIN_REPLACED);
        assertThat(row.getStateSource()).isEqualTo("LOGIN_REPLACED");
        verify(ipProxyService).releaseByAccount(100L);
    }

    @Test
    void applyStateChanged_needReauthRaw440KeepsBackwardCompatibilityAsLoginReplaced() {
        Account account = account();
        AccountState currentState = currentState(AccountStateCode.NORMAL, 1_000L);
        AccountStateChangedEvent event = event("ONLINE", "NEED_REAUTH",
                2_000L, "NEED_REAUTH", 440, "batch_online", "oa_440");
        when(accountMapper.selectActiveById(100L)).thenReturn(account);
        when(stateMapper.selectByAccountId(100L)).thenReturn(currentState);

        service().applyStateChanged(event);

        ArgumentCaptor<AccountState> rowCaptor = ArgumentCaptor.forClass(AccountState.class);
        verify(stateMapper).updateLifecycleState(rowCaptor.capture());
        AccountState row = rowCaptor.getValue();
        assertThat(row.getLoginState()).isEqualTo(AccountLoginStateCode.OFFLINE);
        assertThat(row.getAccountState()).isEqualTo(AccountStateCode.LOGIN_REPLACED);
        assertThat(row.getStateSource()).isEqualTo("LOGIN_REPLACED");
    }

    @Test
    void applyStateChanged_takingOverOnlineKeepsTakingOverAccountState() {
        Account account = account();
        AccountState currentState = currentState(AccountStateCode.TAKING_OVER, 1_000L);
        AccountStateChangedEvent event = event("VERIFYING", "ONLINE",
                2_000L, "ONLINE_CONFIRMED", null, "login_replaced_takeover", "oa_takeover");
        when(accountMapper.selectActiveById(100L)).thenReturn(account);
        when(stateMapper.selectByAccountId(100L)).thenReturn(currentState);

        service().applyStateChanged(event);

        ArgumentCaptor<AccountState> rowCaptor = ArgumentCaptor.forClass(AccountState.class);
        verify(stateMapper).updateLoginAndAccountState(rowCaptor.capture());
        AccountState row = rowCaptor.getValue();
        assertThat(row.getLoginState()).isEqualTo(AccountLoginStateCode.ONLINE);
        assertThat(row.getAccountState()).isEqualTo(AccountStateCode.TAKING_OVER);
        verify(stateMapper, never()).markOnlineNormalState(any(AccountState.class));
    }

    @Test
    void applyStateChanged_verifyingMarksLoginStatePendingOnline() {
        Account account = account();
        AccountState currentState = currentState(AccountStateCode.NORMAL, 1_000L);
        AccountStateChangedEvent event = event("OFFLINE", "VERIFYING",
                2_000L, "ws_open", null, "batch_online", "oa_verifying");
        when(accountMapper.selectActiveById(100L)).thenReturn(account);
        when(stateMapper.selectByAccountId(100L)).thenReturn(currentState);

        service().applyStateChanged(event);

        ArgumentCaptor<AccountState> rowCaptor = ArgumentCaptor.forClass(AccountState.class);
        verify(stateMapper).updateLoginState(rowCaptor.capture());
        AccountState row = rowCaptor.getValue();
        assertThat(row.getLoginState()).isEqualTo(AccountLoginStateCode.PENDING_ONLINE);
        assertThat(row.getStateSource()).isEqualTo("ws_open");
        verify(ipProxyService, never()).releaseByAccount(100L);
    }

    @Test
    void applyStateChanged_takingOverBatchOfflineStopsTakeover() {
        Account account = account();
        AccountState currentState = currentState(AccountStateCode.TAKING_OVER, 1_000L);
        AccountStateChangedEvent event = event("ONLINE", "OFFLINE",
                2_000L, "OFFLINE", null, "batch_offline", "oa_stop");
        when(accountMapper.selectActiveById(100L)).thenReturn(account);
        when(stateMapper.selectByAccountId(100L)).thenReturn(currentState);

        service().applyStateChanged(event);

        ArgumentCaptor<AccountState> rowCaptor = ArgumentCaptor.forClass(AccountState.class);
        verify(stateMapper).updateLoginAndAccountState(rowCaptor.capture());
        AccountState row = rowCaptor.getValue();
        assertThat(row.getLoginState()).isEqualTo(AccountLoginStateCode.OFFLINE);
        assertThat(row.getAccountState()).isEqualTo(AccountStateCode.LOGIN_REPLACED);
        verify(ipProxyService).releaseByAccount(100L);
    }

    @Test
    void applyStateChanged_takingOverOrdinaryOfflineKeepsTakeover() {
        Account account = account();
        AccountState currentState = currentState(AccountStateCode.TAKING_OVER, 1_000L);
        AccountStateChangedEvent event = event("ONLINE", "OFFLINE",
                2_000L, "OFFLINE", null, "batch_online", "oa_retry");
        when(accountMapper.selectActiveById(100L)).thenReturn(account);
        when(stateMapper.selectByAccountId(100L)).thenReturn(currentState);

        service().applyStateChanged(event);

        ArgumentCaptor<AccountState> rowCaptor = ArgumentCaptor.forClass(AccountState.class);
        verify(stateMapper).updateLoginAndAccountState(rowCaptor.capture());
        AccountState row = rowCaptor.getValue();
        assertThat(row.getLoginState()).isEqualTo(AccountLoginStateCode.OFFLINE);
        assertThat(row.getAccountState()).isEqualTo(AccountStateCode.TAKING_OVER);
        verify(ipProxyService).releaseByAccount(100L);
    }

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
                null,
                null,
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

    private AccountStateEventServiceImpl service() {
        return new AccountStateEventServiceImpl(
                accountMapper,
                stateMapper,
                ipProxyService,
                List.of(sideEffect));
    }

    private static Account account() {
        Account account = new Account();
        account.setId(100L);
        account.setProtocolAccountId("acc_8613800138000");
        return account;
    }

    private static AccountState currentState(Integer accountState, long lastStateSyncTime) {
        AccountState state = new AccountState();
        state.setAccountId(100L);
        state.setAccountState(accountState);
        state.setLastStateSyncTime(lastStateSyncTime);
        return state;
    }

    private static AccountStateChangedEvent event(String from,
                                                  String to,
                                                  Long occurredAt,
                                                  String semantic,
                                                  Integer rawCode,
                                                  String source,
                                                  String onlineAttemptId) {
        return new AccountStateChangedEvent(
                1L,
                100L,
                "acc_8613800138000",
                from,
                to,
                occurredAt,
                semantic,
                rawCode,
                source,
                onlineAttemptId);
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
                null,
                null,
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
