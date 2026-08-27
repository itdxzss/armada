package com.armada.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.model.dto.AccountGroupQuery;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountGroup;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountState;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.model.vo.AccountGroupVoRow;
import com.armada.testsupport.DbTestBase;
import com.armada.shared.security.DataScope;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * AccountGroupMapper 真库测试:验 insert/软删/复活/countAccountsByGroupId 流程。
 * 每个 @Test 在 @Transactional 内运行并回滚,数据互不干扰。
 */
class AccountGroupMapperDbTest extends DbTestBase {

    @Autowired
    AccountGroupMapper mapper;

    @Autowired
    AccountMapper accountMapper;

    @Autowired
    AccountStateMapper stateMapper;

    @Autowired
    JdbcTemplate jdbc;

    private AccountGroup build(String name) {
        return build(name, 0, 1_700_000_000_000L);
    }

    private AccountGroup build(String name, int systemBuiltin, long createdAt) {
        AccountGroup g = new AccountGroup();
        g.setName(name);
        g.setRemark("r");
        g.setSystemBuiltin(systemBuiltin);
        g.setCreatedAt(createdAt);
        g.setUpdatedAt(createdAt);
        return g;
    }

    private Account insertAccountInGroup(String wsPhone, Long groupId, long now) {
        Account account = new Account();
        account.setWsPhone(wsPhone);
        account.setAccountType(1);
        account.setOwnership(1);
        account.setAccountGroupId(groupId);
        account.setPriority(0);
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        accountMapper.insert(account);
        insertDefaultState(account.getId(), now);
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

    @Test
    void insert_then_selectActiveByName() {
        AccountGroup g = build("分组A");
        mapper.insert(g);
        assertThat(g.getId()).isNotNull();
        assertThat(mapper.selectActiveByNameForOwner("分组A", null)).isNotNull();
        assertThat(mapper.selectDeletedByNameForOwner("分组A", null)).isNull();
    }

    @Test
    void softDelete_then_reviveByName() {
        AccountGroup g = build("分组B");
        mapper.insert(g);
        mapper.softDeleteByIds(List.of(g.getId()), 1_700_000_000_001L);
        assertThat(mapper.selectActiveByNameForOwner("分组B", null)).isNull();
        assertThat(mapper.selectDeletedByNameForOwner("分组B", null)).isNotNull();
        mapper.reviveById(g.getId());
        assertThat(mapper.selectActiveByNameForOwner("分组B", null)).isNotNull();
    }

    @Test
    void countAccountsByGroupId_zero_whenEmpty() {
        AccountGroup g = build("空组");
        mapper.insert(g);
        assertThat(mapper.countAccountsByGroupId(g.getId())).isEqualTo(0L);
    }

    @Test
    void selectByIdsForUpdate_executesValidSqlAndKeepsCurrentTenantBoundary() {
        long now = System.currentTimeMillis();
        AccountGroup currentTenantGroup = build("锁查询当前租户-" + now, 0, now);
        mapper.insert(currentTenantGroup);
        String otherTenantName = "锁查询其他租户-" + now;
        jdbc.update("""
                INSERT INTO account_group
                    (tenant_id, name, remark, system_builtin, created_at, updated_at)
                VALUES (?, ?, 'other', 0, ?, ?)
                """, TEST_TENANT_ID + 1, otherTenantName, now, now);
        Long otherTenantGroupId = jdbc.queryForObject(
                "SELECT id FROM account_group WHERE tenant_id = ? AND name = ?",
                Long.class,
                TEST_TENANT_ID + 1,
                otherTenantName);

        List<AccountGroup> groups = mapper.selectByIdsForUpdate(
                List.of(currentTenantGroup.getId(), otherTenantGroupId));

        assertThat(groups).extracting(AccountGroup::getId)
                .containsExactly(currentTenantGroup.getId());
    }

    @Test
    void selectPage_ordersSystemBuiltinFirst_thenCreatedAtDesc() {
        AccountGroup system = build("排序系统默认分组", 1, 1_700_000_000_000L);
        AccountGroup older = build("排序普通旧分组", 0, 1_700_000_000_100L);
        AccountGroup newer = build("排序普通新分组", 0, 1_700_000_000_200L);
        mapper.insert(system);
        mapper.insert(older);
        mapper.insert(newer);

        AccountGroupQuery query = new AccountGroupQuery();
        query.applyDataScope(DataScope.all(1L));
        query.setKeyword("排序");
        query.setPageSize(10);
        List<AccountGroupVoRow> rows = mapper.selectPage(query);

        assertThat(rows)
                .extracting(AccountGroupVoRow::getName)
                .containsExactly("排序系统默认分组", "排序普通新分组", "排序普通旧分组");
    }

    @Test
    void selectPage_restrictedCount_matchesAccountStatsRestrictedTotalLogic() {
        long now = System.currentTimeMillis();
        AccountGroup group = build("异常统计分组-" + now, 0, now);
        mapper.insert(group);

        Account banned = insertAccountInGroup("86301" + (now % 100000000L), group.getId(), now);
        Account unbound = insertAccountInGroup("86302" + (now % 100000000L), group.getId(), now);
        Account muted = insertAccountInGroup("86303" + (now % 100000000L), group.getId(), now);
        Account exported = insertAccountInGroup("86304" + (now % 100000000L), group.getId(), now);
        Account risk = insertAccountInGroup("86305" + (now % 100000000L), group.getId(), now);
        Account normal = insertAccountInGroup("86306" + (now % 100000000L), group.getId(), now);
        Account bannedMuted = insertAccountInGroup("86307" + (now % 100000000L), group.getId(), now);

        jdbc.update("UPDATE account_state SET account_state = ?, login_state = ? WHERE account_id = ?",
                AccountStateCode.BANNED, AccountLoginStateCode.OFFLINE, banned.getId());
        jdbc.update("UPDATE account_state SET account_state = ? WHERE account_id = ?",
                AccountStateCode.UNBOUND, unbound.getId());
        jdbc.update("UPDATE account_state SET account_state = ?, mute_status = ? WHERE account_id = ?",
                AccountStateCode.NORMAL, 1, muted.getId());
        jdbc.update("UPDATE account_state SET account_state = ? WHERE account_id = ?",
                AccountStateCode.EXPORTED, exported.getId());
        jdbc.update("UPDATE account_state SET account_state = ?, risk_status = ? WHERE account_id = ?",
                AccountStateCode.NORMAL, 2, risk.getId());
        jdbc.update("UPDATE account_state SET account_state = ?, login_state = ? WHERE account_id = ?",
                AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE, normal.getId());
        jdbc.update("UPDATE account_state SET account_state = ?, mute_status = ? WHERE account_id = ?",
                AccountStateCode.BANNED, 1, bannedMuted.getId());

        AccountGroupQuery query = new AccountGroupQuery();
        query.setId(group.getId());
        query.setPageSize(10);
        query.applyDataScope(DataScope.all(1L));

        AccountGroupVoRow row = mapper.selectPage(query).get(0);

        assertThat(row.getAccountCount()).isEqualTo(7L);
        assertThat(row.getOnlineCount()).isEqualTo(1L);
        assertThat(row.getRiskCount()).isEqualTo(1L);
        assertThat(row.getBannedCount()).isEqualTo(2L);
        assertThat(row.getRestrictedCount()).isEqualTo(6L);
    }
}
