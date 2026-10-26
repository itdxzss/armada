package com.armada.account.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.armada.account.mapper.AccountImportDetailMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountImportLoginResultSettlement;
import com.armada.account.service.AccountStateChangedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 账号导入登录结果结算器单测。
 */
@ExtendWith(MockitoExtension.class)
class AccountImportLoginResultSettlerTest {

    @Mock
    private AccountImportDetailMapper detailMapper;

    @ParameterizedTest
    @CsvSource({
            "OFFLINE, CLIENT_OUTDATED, 405, 2, CLIENT_OUTDATED",
            "NEED_REAUTH, LOGIN_FAILED, 405, 2, CLIENT_OUTDATED",
            "NEED_REAUTH, LOGIN_FAILED, 401, 3, LOGIN_FAILED",
            "NEED_REAUTH, LOGIN_FAILED, 403, 4, FORBIDDEN"
    })
    void settlesOutdatedClientSeparatelyFromInvalidKeys(String to, String semantic, int rawCode,
                                                       int expectedResult, String expectedReason) {
        Account account = new Account();
        account.setId(100L);
        AccountStateChangedEvent event = new AccountStateChangedEvent(1L, 100L,
                "acc_8613800138000", "VERIFYING", to, 2_000L, semantic, rawCode,
                "import", "attempt-405", null);

        new AccountImportLoginResultSettler(detailMapper).afterStateChanged(account, event, 2_000L);

        var captor = ArgumentCaptor.forClass(AccountImportLoginResultSettlement.class);
        verify(detailMapper).settleDispatchedLoginResult(captor.capture());
        assertThat(captor.getValue().getLoginResult()).isEqualTo(expectedResult);
        assertThat(captor.getValue().getLoginReason()).isEqualTo(expectedReason);
    }

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
                null,
                null,
                null, null);

        settler.afterStateChanged(account, event, event.occurredAt());

        verifyNoInteractions(detailMapper);
    }
}
