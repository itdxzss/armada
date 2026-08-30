package com.armada.hyperlink.task.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.armada.account.model.entity.Account;
import com.armada.account.service.AccountStateChangedEvent;
import com.armada.hyperlink.task.mapper.HyperlinkTaskAccountUsageMapper;
import org.junit.jupiter.api.Test;

class HyperlinkAccountStateChangedSideEffectTest {
    private final HyperlinkTaskAccountUsageMapper mapper =
            mock(HyperlinkTaskAccountUsageMapper.class);
    private final HyperlinkAccountStateChangedSideEffect sideEffect =
            new HyperlinkAccountStateChangedSideEffect(mapper);

    @Test
    void forbiddenEventFreezesInvalidFactForActiveTasks() {
        sideEffect.afterStateChanged(account(), event("NEED_REAUTH", 403), 5_000L);

        verify(mapper).markActiveByAccountInvalid(
                7L, 1187L, 3, "WA_403", "账号被平台禁用", 5_000L);
    }

    @Test
    void transientOfflineDoesNotInflateMarketingBanRate() {
        sideEffect.afterStateChanged(account(), event("OFFLINE", null), 5_000L);

        verify(mapper, never()).markActiveByAccountInvalid(
                7L, 1187L, 3, "OFFLINE", "账号离线", 5_000L);
    }

    @Test
    void rawForbiddenCodeWithoutNeedReauthDoesNotForgeBanFact() {
        sideEffect.afterStateChanged(account(), event("OFFLINE", 403), 5_000L);

        verify(mapper, never()).markActiveByAccountInvalid(
                7L, 1187L, 3, "WA_403", "账号被平台禁用", 5_000L);
    }

    private static Account account() {
        Account account = new Account();
        account.setId(1187L);
        return account;
    }

    private static AccountStateChangedEvent event(String to, Integer rawCode) {
        return new AccountStateChangedEvent(7L, 1187L, "acc-1187", "ONLINE", to,
                5_000L, to, rawCode, "protocol", null);
    }
}
