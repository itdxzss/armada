package com.armada.account.registration;

import static org.assertj.core.api.Assertions.assertThat;
import com.armada.account.model.enums.AccountRegistrationState;
import org.junit.jupiter.api.Test;

/** 只有协议实际上线才成功；采购未知绝不能重新入队。 */
class AccountRegistrationStateTest {
    @Test
    void onlyActualOnlineStateIsSuccessAndUnknownIsTerminal() {
        assertThat(AccountRegistrationState.WAITING_ONLINE.isTerminal()).isFalse();
        assertThat(AccountRegistrationState.IMPORTING.isTerminal()).isFalse();
        assertThat(AccountRegistrationState.UNKNOWN.isTerminal()).isTrue();
        assertThat(AccountRegistrationState.SUCCEEDED.isTerminal()).isTrue();
        assertThat(AccountRegistrationState.fromCode(2)).isEqualTo(AccountRegistrationState.PURCHASING);
    }
}
