package com.armada.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountState;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.model.vo.AccountStatsVoRow;
import com.armada.testsupport.DbTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * statsSummary 聚合 SQL 真库测试:验 total/online/offline/pendingOnline/restricted/risk/assigned 各列口径。
 * 每个 @Test 在 @Transactional 内执行并回滚,数据互不干扰。
 */
class AccountStatsMapperDbTest extends DbTestBase {

    @Autowired
    AccountMapper accountMapper;

    @Autowired
    AccountStateMapper stateMapper;

    @Autowired
    JdbcTemplate jdbc;

    // ---- 辅助方法 ----

    /**
     * 插入最小合法 Account,返回落库后的实体(id 已回填)。
     */
    private Account insertAccount(String wsPhone, long now) {
        Account a = new Account();
        a.setWsPhone(wsPhone);
        a.setAccountType(1);
        a.setOwnership(1);
        a.setPriority(0);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        accountMapper.insert(a);
        return a;
    }

    /**
     * 为已插入的 Account 插入默认状态行(login_state/account_state/risk_status 全 NULL)。
     */
    private void insertDefaultState(Long accountId, long now) {
        AccountState s = new AccountState();
        s.setAccountId(accountId);
        s.setProxyFailureCount(0);
        s.setPullIntoGroupCount(0);
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        stateMapper.insert(s);
    }

    // ---- 测试用例 ----

    /**
     * 导入 2 个账号(状态全 NULL / dispatched_at NULL):
     * total &gt;= 2,online/offline/banned/risk/assigned 均为 0(NULL CASE = 0)。
     */
    @Test
    void statsSummary_twoAccounts_allNullState_countsZero() {
        long now = System.currentTimeMillis();

        Account a1 = insertAccount("86111" + (now % 100000000L), now);
        Account a2 = insertAccount("86112" + (now % 100000000L), now);
        // 插入默认状态行(所有状态列 NULL)
        insertDefaultState(a1.getId(), now);
        insertDefaultState(a2.getId(), now);

        AccountStatsVoRow result = accountMapper.statsSummary();

        // total 至少包含本次插入的 2 条(事务内可见)
        assertThat(result.getTotal()).isGreaterThanOrEqualTo(2);
        // 全 NULL 状态:online/offline/banned/risk/assigned 均为 0
        assertThat(result.getOnline()).isEqualTo(0L);
        assertThat(result.getOffline()).isEqualTo(0L);
        assertThat(result.getBanned()).isEqualTo(0L);
        assertThat(result.getRisk()).isEqualTo(0L);
        assertThat(result.getAssigned()).isEqualTo(0L);
    }

    /**
     * 插入 3 个账号,分别设置在线/正常离线/NULL:
     * online &gt;= 1,offline &gt;= 1,total &gt;= 3。
     */
    @Test
    void statsSummary_loginStates_onlineOfflineCorrect() {
        long now = System.currentTimeMillis();

        Account aOnline = insertAccount("86121" + (now % 100000000L), now);
        Account aOffline = insertAccount("86122" + (now % 100000000L), now);
        Account aNoState = insertAccount("86123" + (now % 100000000L), now);

        insertDefaultState(aOnline.getId(), now);
        insertDefaultState(aOffline.getId(), now);
        // aNoState 无状态行 → LEFT JOIN NULL

        // 用 JdbcTemplate 写状态列(AccountStateMapper.insert 不写状态列)
        jdbc.update("UPDATE account_state SET login_state = ? WHERE account_id = ?",
                AccountLoginStateCode.ONLINE, aOnline.getId());
        jdbc.update("UPDATE account_state SET account_state = ?, login_state = ? WHERE account_id = ?",
                AccountStateCode.NORMAL, AccountLoginStateCode.OFFLINE, aOffline.getId());

        // 先取当前快照,再对比增量(事务内有其他数据也无影响)
        AccountStatsVoRow before = accountMapper.statsSummary();
        // 本测试操作后,online 应 >= 1,offline >= 1
        assertThat(before.getOnline()).isGreaterThanOrEqualTo(1L);
        assertThat(before.getOffline()).isGreaterThanOrEqualTo(1L);
        assertThat(before.getTotal()).isGreaterThanOrEqualTo(3L);
    }

    /**
     * 插入 1 个账号并设 account_state=3(封禁):banned=1。
     */
    @Test
    void statsSummary_bannedAccount_bannedCountOne() {
        long now = System.currentTimeMillis();

        Account aBanned = insertAccount("86131" + (now % 100000000L), now);
        insertDefaultState(aBanned.getId(), now);
        jdbc.update("UPDATE account_state SET account_state = 3 WHERE account_id = ?", aBanned.getId());

        AccountStatsVoRow result = accountMapper.statsSummary();
        assertThat(result.getBanned()).isGreaterThanOrEqualTo(1L);
    }

    /**
     * 插入 1 个账号并设 risk_status=2(风控中):risk=1。
     */
    @Test
    void statsSummary_riskAccount_riskCountOne() {
        long now = System.currentTimeMillis();

        Account aRisk = insertAccount("86141" + (now % 100000000L), now);
        insertDefaultState(aRisk.getId(), now);
        jdbc.update("UPDATE account_state SET risk_status = 2 WHERE account_id = ?", aRisk.getId());

        AccountStatsVoRow result = accountMapper.statsSummary();
        assertThat(result.getRisk()).isGreaterThanOrEqualTo(1L);
    }

    /**
     * 插入 1 个账号并设 dispatched_at(非 NULL):assigned=1。
     */
    @Test
    void statsSummary_assignedAccount_assignedCountOne() {
        long now = System.currentTimeMillis();

        Account aAssigned = insertAccount("86151" + (now % 100000000L), now);
        insertDefaultState(aAssigned.getId(), now);
        jdbc.update("UPDATE account SET dispatched_at = ? WHERE id = ?", now, aAssigned.getId());

        AccountStatsVoRow result = accountMapper.statsSummary();
        assertThat(result.getAssigned()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void statsSummary_pendingOnlineNormalOfflineAndRestrictedBreakdown() {
        long now = System.currentTimeMillis();
        AccountStatsVoRow before = accountMapper.statsSummary();

        Account normalOffline = insertAccount("86201" + (now % 100000000L), now);
        Account bannedOffline = insertAccount("86202" + (now % 100000000L), now);
        Account pending = insertAccount("86203" + (now % 100000000L), now);
        Account unbound = insertAccount("86204" + (now % 100000000L), now);
        Account muted = insertAccount("86205" + (now % 100000000L), now);
        Account exported = insertAccount("86206" + (now % 100000000L), now);
        Account restricted = insertAccount("86207" + (now % 100000000L), now);

        insertDefaultState(normalOffline.getId(), now);
        insertDefaultState(bannedOffline.getId(), now);
        insertDefaultState(pending.getId(), now);
        insertDefaultState(unbound.getId(), now);
        insertDefaultState(muted.getId(), now);
        insertDefaultState(exported.getId(), now);
        insertDefaultState(restricted.getId(), now);

        jdbc.update("UPDATE account_state SET account_state = ?, login_state = ? WHERE account_id = ?",
                AccountStateCode.NORMAL, AccountLoginStateCode.OFFLINE, normalOffline.getId());
        jdbc.update("UPDATE account_state SET account_state = ?, login_state = ? WHERE account_id = ?",
                AccountStateCode.BANNED, AccountLoginStateCode.OFFLINE, bannedOffline.getId());
        jdbc.update("UPDATE account_state SET login_state = ? WHERE account_id = ?",
                AccountLoginStateCode.PENDING_ONLINE, pending.getId());
        jdbc.update("UPDATE account_state SET account_state = ? WHERE account_id = ?",
                AccountStateCode.UNBOUND, unbound.getId());
        jdbc.update("UPDATE account_state SET account_state = ?, mute_status = ? WHERE account_id = ?",
                AccountStateCode.NORMAL, 1, muted.getId());
        jdbc.update("UPDATE account_state SET account_state = ? WHERE account_id = ?",
                AccountStateCode.EXPORTED, exported.getId());
        jdbc.update("UPDATE account_state SET account_state = 8, login_state = ? WHERE account_id = ?",
                AccountLoginStateCode.OFFLINE, restricted.getId());

        AccountStatsVoRow after = accountMapper.statsSummary();

        assertThat(after.getOffline() - before.getOffline()).isEqualTo(1L);
        assertThat(after.getPendingOnline() - before.getPendingOnline()).isEqualTo(1L);
        assertThat(after.getBanned() - before.getBanned()).isEqualTo(1L);
        assertThat(after.getUnbound() - before.getUnbound()).isEqualTo(1L);
        assertThat(after.getMuted() - before.getMuted()).isEqualTo(1L);
        assertThat(after.getExported() - before.getExported()).isEqualTo(1L);
        assertThat(after.getRestricted() - before.getRestricted()).isEqualTo(1L);
    }

    @Test
    void statsSummary_operableLifecycleOfflineStatesCountAsOffline() {
        long now = System.currentTimeMillis();
        AccountStatsVoRow before = accountMapper.statsSummary();

        Account newOffline = insertAccount("86211" + (now % 100000000L), now);
        Account normalOffline = insertAccount("86212" + (now % 100000000L), now);
        Account replaced = insertAccount("86213" + (now % 100000000L), now);
        Account takingOver = insertAccount("86214" + (now % 100000000L), now);
        Account banned = insertAccount("86215" + (now % 100000000L), now);
        Account exported = insertAccount("86216" + (now % 100000000L), now);
        Account unbound = insertAccount("86217" + (now % 100000000L), now);

        insertDefaultState(newOffline.getId(), now);
        insertDefaultState(normalOffline.getId(), now);
        insertDefaultState(replaced.getId(), now);
        insertDefaultState(takingOver.getId(), now);
        insertDefaultState(banned.getId(), now);
        insertDefaultState(exported.getId(), now);
        insertDefaultState(unbound.getId(), now);

        jdbc.update("UPDATE account_state SET account_state = ?, login_state = ? WHERE account_id = ?",
                AccountStateCode.NEW, AccountLoginStateCode.OFFLINE, newOffline.getId());
        jdbc.update("UPDATE account_state SET account_state = ?, login_state = ? WHERE account_id = ?",
                AccountStateCode.NORMAL, AccountLoginStateCode.OFFLINE, normalOffline.getId());
        jdbc.update("UPDATE account_state SET account_state = ?, login_state = ? WHERE account_id = ?",
                AccountStateCode.LOGIN_REPLACED, AccountLoginStateCode.OFFLINE, replaced.getId());
        jdbc.update("UPDATE account_state SET account_state = ?, login_state = ? WHERE account_id = ?",
                AccountStateCode.TAKING_OVER, AccountLoginStateCode.OFFLINE, takingOver.getId());
        jdbc.update("UPDATE account_state SET account_state = ?, login_state = ? WHERE account_id = ?",
                AccountStateCode.BANNED, AccountLoginStateCode.OFFLINE, banned.getId());
        jdbc.update("UPDATE account_state SET account_state = ?, login_state = ? WHERE account_id = ?",
                AccountStateCode.EXPORTED, AccountLoginStateCode.OFFLINE, exported.getId());
        jdbc.update("UPDATE account_state SET account_state = ?, login_state = ? WHERE account_id = ?",
                AccountStateCode.UNBOUND, AccountLoginStateCode.OFFLINE, unbound.getId());

        AccountStatsVoRow after = accountMapper.statsSummary();

        assertThat(after.getOffline() - before.getOffline()).isEqualTo(4L);
    }
}
