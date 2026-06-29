package com.armada.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountState;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.resource.mapper.IpProxyMapper;
import com.armada.resource.model.IpProxyStatus;
import com.armada.resource.model.ProxyOwnership;
import com.armada.resource.model.ProxyProtocol;
import com.armada.resource.model.entity.IpProxy;
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

    @Autowired
    private IpProxyMapper ipProxyMapper;

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

    @Test
    void applyStateChanged_offline_releasesBoundIpImmediately() {
        long now = System.currentTimeMillis();
        Account account = insertAccount("86185" + (now % 10_000_000L), now);
        insertDefaultState(account.getId(), now);
        IpProxy proxy = newIdleProxy(now);
        ipProxyMapper.insert(proxy);
        ipProxyMapper.markUsingAndBind(
                proxy.getId(),
                account.getId(),
                IpProxyStatus.IDLE.code(),
                IpProxyStatus.IN_USE.code(),
                now + 1);

        service.applyStateChanged(new AccountStateChangedEvent(
                account.getProtocolAccountId(),
                "ONLINE",
                "OFFLINE",
                now + 2_000L,
                "OFFLINE",
                null));

        AccountState state = stateMapper.selectByAccountId(account.getId());
        assertThat(state.getLoginState()).isEqualTo(AccountLoginStateCode.OFFLINE);
        IpProxy released = ipProxyMapper.selectActiveById(proxy.getId());
        assertThat(released.getStatus()).isEqualTo(IpProxyStatus.IDLE.code());
        assertThat(released.getBoundAccountId()).isNull();
        assertThat(released.getBoundAt()).isNull();
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

    private static IpProxy newIdleProxy(long suffix) {
        IpProxy proxy = new IpProxy();
        proxy.setHost("state-event-proxy-" + suffix + ".internal");
        proxy.setPort(1080);
        proxy.setProtocol(ProxyProtocol.SOCKS5.code());
        proxy.setUsername("stateUser" + suffix);
        proxy.setPassword("statePass_session-Abc123" + suffix);
        proxy.setRegion("印度");
        proxy.setStatus(IpProxyStatus.IDLE.code());
        proxy.setSource("dbtest");
        proxy.setOwnership(ProxyOwnership.OWNED.code());
        proxy.setCreatedAt(suffix);
        proxy.setUpdatedAt(suffix);
        return proxy;
    }
}
