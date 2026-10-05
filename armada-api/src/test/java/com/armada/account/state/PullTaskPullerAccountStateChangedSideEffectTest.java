package com.armada.account.state;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.model.entity.Account;
import com.armada.account.service.AccountStateChangedEvent;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import java.util.List;
import com.armada.task.service.PullTaskPullerAccountStateService;
import com.armada.task.service.PullTaskPullerAccountStateService.Unavailability;
import org.junit.jupiter.api.Test;

class PullTaskPullerAccountStateChangedSideEffectTest {

    private final PullTaskPullerAccountStateService pullTasks =
            mock(PullTaskPullerAccountStateService.class);
    private final AccountProtocolLookupService accountLookup =
            mock(AccountProtocolLookupService.class);
    private final PullTaskPullerAccountStateChangedSideEffect sideEffect =
            new PullTaskPullerAccountStateChangedSideEffect(pullTasks, accountLookup);

    @Test
    void offlineSwitchesCurrentTaskPullerWithoutUnbindingAccount() {
        sideEffect.afterStateChanged(account(), event("OFFLINE", null), 5_000L);

        verify(pullTasks).markUnavailable(
                7L, 1187L, Unavailability.OFFLINE, 5_000L);
    }

    @Test
    void forbiddenNeedReauthRemovesBannedPuller() {
        sideEffect.afterStateChanged(account(), event("NEED_REAUTH", 403), 5_000L);

        verify(pullTasks).markUnavailable(
                7L, 1187L, Unavailability.BANNED, 5_000L);
    }

    @Test
    void nonForbiddenNeedReauthRemovesUnboundPuller() {
        sideEffect.afterStateChanged(account(), event("NEED_REAUTH", 401), 5_000L);

        verify(pullTasks).markUnavailable(
                7L, 1187L, Unavailability.UNBOUND, 5_000L);
    }

    @Test
    void loginReplacedRawCodeSwitchesPullerWithoutRemovingIt() {
        sideEffect.afterStateChanged(account(), event("NEED_REAUTH", 440), 5_000L);

        verify(pullTasks).markUnavailable(
                7L, 1187L, Unavailability.OFFLINE, 5_000L);
    }

    @Test
    void onlineRestoresPullerOnlyAfterCurrentEligibilityIsConfirmed() {
        when(accountLookup.findEligiblePullerProtocolRefs(List.of(1187L)))
                .thenReturn(List.of(new ProtocolAccountRef(
                        1187L, ProtocolBackend.ANDROID, "acc_test", "test-phone")));
        sideEffect.afterStateChanged(account(), event("ONLINE", null), 5_000L);

        verify(pullTasks).markOnline(7L, 1187L, 5_000L);
        verify(pullTasks, never()).markUnavailable(
                7L, 1187L, Unavailability.OFFLINE, 5_000L);
    }

    @Test
    void onlineEventDoesNotRestoreAccountThatIsCurrentlyIneligible() {
        sideEffect.afterStateChanged(account(), event("ONLINE", null), 5_000L);

        verify(pullTasks, never()).markOnline(7L, 1187L, 5_000L);
    }

    private static Account account() {
        Account result = new Account();
        result.setId(1187L);
        return result;
    }

    private static AccountStateChangedEvent event(String to, Integer rawCode) {
        return new AccountStateChangedEvent(
                7L, 1187L, "acc_918809345662", "ONLINE", to,
                5_000L, to, rawCode, "protocol", null, null);
    }
}
