package com.armada.account.state;

import static org.mockito.Mockito.verifyNoInteractions;

import com.armada.account.mapper.AccountImportDetailMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.service.AccountStateChangedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 账号导入登录结果结算器单测。
 */
@ExtendWith(MockitoExtension.class)
class AccountImportLoginResultSettlerTest {

    @Mock
    private AccountImportDetailMapper detailMapper;

    @Test
    void afterStateChanged_proxyFailedDoesNotSettleBecauseAutoReonlineWillRetry() {
        AccountImportLoginResultSettler settler = new AccountImportLoginResultSettler(detailMapper);
        Account account = new Account();
        account.setId(100L);
        AccountStateChangedEvent event = new AccountStateChangedEvent(
                1L,
                100L,
                "acc_8613800138000",
                "VERIFYING",
                "PROXY_FAILED",
                1782626401000L,
                "PROXY_FAILED",
                null);

        settler.afterStateChanged(account, event, event.occurredAt());

        verifyNoInteractions(detailMapper);
    }
}
