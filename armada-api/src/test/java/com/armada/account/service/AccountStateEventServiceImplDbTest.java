package com.armada.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountState;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.testsupport.DbTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 账号状态事件服务真库测试。
 *
 * <p>覆盖协议层 {@code account.state_changed} 事件到 account_state 子表的核心状态收敛。
 * 本测试不启动 Kafka listener,只验证账号域落库服务和 MyBatis XML。</p>
 */
class AccountStateEventServiceImplDbTest extends DbTestBase {

    @Autowired
    private AccountStateEventService service;

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private AccountStateMapper stateMapper;

    @Test
    void applyStateChanged_online_updatesLoginStateAndStateSource() {
        long now = System.currentTimeMillis();
        Account account = insertAccount("86182" + (now % 10_000_000L), now);
        insertDefaultState(account.getId(), now);

        service.applyStateChanged(new AccountStateChangedEvent(
                account.getProtocolAccountId(),
                "RECONNECTING",
                "ONLINE",
                now + 1_000L,
                "RECONNECTING",
                null));

        AccountState state = stateMapper.selectByAccountId(account.getId());
        assertThat(state.getLoginState()).isEqualTo(AccountLoginStateCode.ONLINE);
        assertThat(state.getAccountState()).isNull();
        assertThat(state.getLastStateSyncTime()).isEqualTo(now + 1_000L);
        assertThat(state.getStateSource()).isEqualTo("RECONNECTING");
    }

    @Test
    void applyStateChanged_needReauthForbidden_marksBannedAndOffline() {
        long now = System.currentTimeMillis();
        Account account = insertAccount("86183" + (now % 10_000_000L), now);
        insertDefaultState(account.getId(), now);

        service.applyStateChanged(new AccountStateChangedEvent(
                account.getProtocolAccountId(),
                "ONLINE",
                "NEED_REAUTH",
                now + 2_000L,
                "NEED_REAUTH",
                403));

        AccountState state = stateMapper.selectByAccountId(account.getId());
        assertThat(state.getLoginState()).isEqualTo(AccountLoginStateCode.OFFLINE);
        assertThat(state.getAccountState()).isEqualTo(AccountStateCode.BANNED);
        assertThat(state.getBlockReason()).isEqualTo("FORBIDDEN");
        assertThat(state.getLastStateSyncTime()).isEqualTo(now + 2_000L);
        assertThat(state.getStateSource()).isEqualTo("BANNED");
    }

    @Test
    void applyStateChanged_staleEvent_doesNotRollbackNewerState() {
        long now = System.currentTimeMillis();
        Account account = insertAccount("86184" + (now % 10_000_000L), now);
        insertDefaultState(account.getId(), now);

        service.applyStateChanged(new AccountStateChangedEvent(
                account.getProtocolAccountId(),
                "RECONNECTING",
                "ONLINE",
                now + 2_000L,
                "RECONNECTING",
                null));
        service.applyStateChanged(new AccountStateChangedEvent(
                account.getProtocolAccountId(),
                "ONLINE",
                "NEED_REAUTH",
                now + 1_000L,
                "NEED_REAUTH",
                403));

        AccountState state = stateMapper.selectByAccountId(account.getId());
        assertThat(state.getLoginState()).isEqualTo(AccountLoginStateCode.ONLINE);
        assertThat(state.getAccountState()).isNull();
        assertThat(state.getBlockReason()).isNull();
        assertThat(state.getLastStateSyncTime()).isEqualTo(now + 2_000L);
        assertThat(state.getStateSource()).isEqualTo("RECONNECTING");
    }

    private Account insertAccount(String wsPhone, long now) {
        Account account = new Account();
        account.setWsPhone(wsPhone);
        account.setAccountType(1);
        account.setOwnership(1);
        account.setPriority(0);
        account.setProtocolAccountId("acc_" + wsPhone);
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        accountMapper.insert(account);
        return account;
    }

    private void insertDefaultState(Long accountId, long now) {
        AccountState state = new AccountState();
        state.setAccountId(accountId);
        state.setProxyFailureCount(0);
        state.setPullIntoGroupCount(0);
        state.setCreatedAt(now);
        state.setUpdatedAt(now);
        stateMapper.insert(state);
    }
}
