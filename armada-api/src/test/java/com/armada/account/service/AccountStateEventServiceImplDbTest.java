package com.armada.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.model.dto.AccountImportDTO;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountImportLoginResult;
import com.armada.account.model.entity.AccountImportOnlinePhase;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountState;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.model.vo.AccountImportBatchVO;
import com.armada.resource.mapper.IpProxyMapper;
import com.armada.resource.model.IpProxyStatus;
import com.armada.resource.model.ProxyOwnership;
import com.armada.resource.model.ProxyProtocol;
import com.armada.resource.model.entity.IpProxy;
import com.armada.shared.tenant.TenantContext;
import com.armada.testsupport.DbTestBase;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

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

    @Autowired
    private AccountImportService importService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void applyStateChanged_online_updatesLoginStateAndStateSource() {
        long now = System.currentTimeMillis();
        Account account = insertAccount("86182" + (now % 10_000_000L), now);
        insertDefaultState(account.getId(), now);

        service.applyStateChanged(event(account, "RECONNECTING", "ONLINE",
                now + 1_000L, "RECONNECTING", null));

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

        service.applyStateChanged(event(account, "ONLINE", "NEED_REAUTH",
                now + 2_000L, "NEED_REAUTH", 403));

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

        service.applyStateChanged(event(account, "RECONNECTING", "ONLINE",
                now + 2_000L, "RECONNECTING", null));
        service.applyStateChanged(event(account, "ONLINE", "NEED_REAUTH",
                now + 1_000L, "NEED_REAUTH", 403));

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

        service.applyStateChanged(event(account, "ONLINE", "OFFLINE",
                now + 2_000L, "OFFLINE", null));

        AccountState state = stateMapper.selectByAccountId(account.getId());
        assertThat(state.getLoginState()).isEqualTo(AccountLoginStateCode.OFFLINE);
        IpProxy released = ipProxyMapper.selectActiveById(proxy.getId());
        assertThat(released.getStatus()).isEqualTo(IpProxyStatus.IDLE.code());
        assertThat(released.getBoundAccountId()).isNull();
        assertThat(released.getBoundAt()).isNull();
    }

    @Test
    void applyStateChanged_withoutExistingTenantContext_restoresTenantFromEvent() {
        long now = System.currentTimeMillis();
        Account account = insertAccount("86187" + (now % 10_000_000L), now);
        insertDefaultState(account.getId(), now);

        TenantContext.clear();
        service.applyStateChanged(event(account, "VERIFYING", "NEED_REAUTH",
                now + 2_000L, "NEED_REAUTH", 401));
        assertThat(TenantContext.get()).isNull();

        TenantContext.set(TEST_TENANT_ID);
        AccountState state = stateMapper.selectByAccountId(account.getId());
        assertThat(state.getLoginState()).isEqualTo(AccountLoginStateCode.OFFLINE);
        assertThat(state.getAccountState()).isEqualTo(AccountStateCode.UNBOUND);
        assertThat(state.getLastStateSyncTime()).isEqualTo(now + 2_000L);
        assertThat(state.getStateSource()).isEqualTo("UNBOUND");
    }

    @Test
    void applyStateChanged_online_settlesDispatchedImportDetailSuccess() {
        long now = System.currentTimeMillis();
        String wsPhone = "86186" + (now % 10_000_000L);
        AccountImportBatchVO batch = importOneAccount(wsPhone);
        Account account = accountMapper.selectActiveByWsPhone(wsPhone);
        long dispatchedAt = now + 1_000L;
        long eventAt = now + 2_000L;

        int prepared = jdbcTemplate.update(
                """
                UPDATE account_import_detail
                SET online_phase = ?, online_dispatched_at = ?, dispatch_attempts = dispatch_attempts + 1
                WHERE batch_id = ? AND account_id = ?
                """,
                AccountImportOnlinePhase.DISPATCHED, dispatchedAt, batch.id(), account.getId());
        assertThat(prepared).isEqualTo(1);

        service.applyStateChanged(event(account, "CONNECTING", "ONLINE",
                eventAt, "CONNECTED", null));

        Map<String, Object> detail = importDetail(batch.id(), account.getId());
        assertThat(((Number) detail.get("online_phase")).intValue()).isEqualTo(AccountImportOnlinePhase.SETTLED);
        assertThat(((Number) detail.get("login_result")).intValue()).isEqualTo(AccountImportLoginResult.SUCCESS);
        assertThat(((Number) detail.get("login_settled_at")).longValue()).isEqualTo(eventAt);
        assertThat(detail.get("login_reason")).isNull();
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

    private static AccountStateChangedEvent event(Account account,
                                                  String from,
                                                  String to,
                                                  Long occurredAt,
                                                  String semantic,
                                                  Integer rawCode) {
        return new AccountStateChangedEvent(
                TEST_TENANT_ID,
                account.getId(),
                account.getProtocolAccountId(),
                from,
                to,
                occurredAt,
                semantic,
                rawCode);
    }

    private AccountImportBatchVO importOneAccount(String wsPhone) {
        String json = "[{\"wid\":\"" + wsPhone
                + "\",\"registrationId\":1,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}]";
        var meta = new AccountImportDTO(null, 2, 1, 2, "印度", "state-event", null);
        AccountImportBatchVO batch = importService.importAccounts(meta, null, json);
        assertThat(batch.importedRows()).isEqualTo(1);
        return batch;
    }

    private Map<String, Object> importDetail(Long batchId, Long accountId) {
        return jdbcTemplate.queryForMap(
                """
                SELECT online_phase, login_result, login_settled_at, login_reason
                FROM account_import_detail
                WHERE batch_id = ? AND account_id = ?
                """,
                batchId, accountId);
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
