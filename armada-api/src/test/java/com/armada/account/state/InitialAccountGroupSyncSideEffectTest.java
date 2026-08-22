package com.armada.account.state;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.armada.account.model.entity.Account;
import com.armada.account.service.AccountGroupSyncCommandService;
import com.armada.account.service.AccountStateChangedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 首次上线群全量同步副作用单测。 */
class InitialAccountGroupSyncSideEffectTest {

    private final AccountGroupSyncCommandService syncCommands =
            Mockito.mock(AccountGroupSyncCommandService.class);
    private final InitialAccountGroupSyncSideEffect sideEffect =
            new InitialAccountGroupSyncSideEffect(syncCommands);

    @Test
    void onlineRequestsInitialBaselineButReconnectDecisionRemainsInDatabase() {
        Account account = account();

        sideEffect.afterStateChanged(account, event("ONLINE"), 2_000L);

        verify(syncCommands).enqueueInitialBaselineSync(account, 2_000L);
    }

    @Test
    void nonOnlineStateDoesNotRequestGroupSnapshot() {
        Account account = account();

        sideEffect.afterStateChanged(account, event("OFFLINE"), 2_000L);

        verify(syncCommands, never()).enqueueInitialBaselineSync(account, 2_000L);
    }

    private static Account account() {
        Account account = new Account();
        account.setId(101L);
        account.setProtocolAccountId("acc_101");
        account.setProtocolId("ANDROID");
        return account;
    }

    private static AccountStateChangedEvent event(String target) {
        return new AccountStateChangedEvent(
                7L, 101L, "acc_101", "VERIFYING", target, 2_000L,
                target, null, "batch_online", "attempt-1");
    }
}
