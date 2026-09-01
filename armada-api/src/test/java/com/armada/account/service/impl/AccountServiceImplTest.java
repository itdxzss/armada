package com.armada.account.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.converter.AccountConverter;
import com.armada.account.mapper.AccountGroupMapper;
import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.dto.AccountQuery;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountDeleteGateRow;
import com.armada.account.model.entity.AccountGroup;
import com.armada.account.model.entity.AccountState;
import com.armada.account.model.vo.AccountListVoRow;
import com.armada.account.model.vo.AccountMarketingOccupancyTaskRow;
import com.armada.account.model.vo.AccountStatsVO;
import com.armada.account.model.vo.AccountStatsVoRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountServiceImplTest {

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private AccountGroupMapper accountGroupMapper;

    @Mock
    private AccountConverter accountConverter;

    @Test
    void listAccountsResolvesSameOccupancyTaskOnceForCurrentPage() {
        AccountQuery query = new AccountQuery();
        AccountListVoRow first = occupiedAccount(1L, 99L);
        AccountListVoRow second = occupiedAccount(2L, 99L);
        AccountMarketingOccupancyTaskRow task = new AccountMarketingOccupancyTaskRow();
        task.setTaskId(99L);
        task.setOccupancyOverrideType("PAUSED");
        when(accountMapper.countPage(query)).thenReturn(2L);
        when(accountMapper.selectPage(query)).thenReturn(List.of(first, second));
        when(accountGroupMapper.selectMarketingOccupancyTasksByIds(List.of(99L)))
                .thenReturn(List.of(task));
        AccountServiceImpl service = new AccountServiceImpl(accountMapper, accountGroupMapper, accountConverter);

        service.listAccounts(query);

        verify(accountGroupMapper, times(1)).selectMarketingOccupancyTasksByIds(List.of(99L));
        verify(accountConverter).toAccountListVO(first, "PAUSED");
        verify(accountConverter).toAccountListVO(second, "PAUSED");
    }

    @Test
    void listAccountsTreatsAccountWithoutOccupancyTaskAsFree() {
        AccountQuery query = new AccountQuery();
        AccountListVoRow row = new AccountListVoRow();
        row.setId(1L);
        when(accountMapper.countPage(query)).thenReturn(1L);
        when(accountMapper.selectPage(query)).thenReturn(List.of(row));
        AccountServiceImpl service = new AccountServiceImpl(accountMapper, accountGroupMapper, accountConverter);

        service.listAccounts(query);

        verify(accountGroupMapper, never()).selectMarketingOccupancyTasksByIds(anyList());
        verify(accountConverter).toAccountListVO(row, "FREE");
    }

    @Test
    void listAccountsResolvesAdvancedOccupancyFilterBeforeAccountPage() {
        AccountQuery query = new AccountQuery();
        query.setMarketingOccupancyType("GROUP_PULL_MARKETING");
        when(accountGroupMapper.selectMarketingOccupancyGroupIds(query)).thenReturn(List.of(21L, 22L));
        when(accountMapper.countPage(query)).thenReturn(0L);
        AccountServiceImpl service = new AccountServiceImpl(accountMapper, accountGroupMapper, accountConverter);

        service.listAccounts(query);

        assertThat(query.getResolvedOccupancyGroupIds()).containsExactly(21L, 22L);
        verify(accountMapper).countPage(query);
    }

    @Test
    void listAccountsShortCircuitsWhenAdvancedOccupancyFilterMatchesNoGroup() {
        AccountQuery query = new AccountQuery();
        query.setOccupiedTaskKeyword("不存在的任务");
        when(accountGroupMapper.selectMarketingOccupancyGroupIds(query)).thenReturn(List.of());
        AccountServiceImpl service = new AccountServiceImpl(accountMapper, accountGroupMapper, accountConverter);

        assertThat(service.listAccounts(query).total()).isZero();

        verify(accountMapper, never()).countPage(query);
        verify(accountMapper, never()).selectPage(query);
    }

    @Test
    void migrateGroupRejectsMovingOutOfActiveBuilderGroup() {
        Account account = account(1L, 10L);
        when(accountMapper.selectActiveByIds(List.of(1L))).thenReturn(List.of(account));
        when(accountGroupMapper.selectByIds(List.of(10L, 20L)))
                .thenReturn(List.of(group(10L, null), group(20L, null)));
        when(accountGroupMapper.countActiveBuilderGroupReferences(List.of(10L))).thenReturn(1);
        AccountServiceImpl service = new AccountServiceImpl(accountMapper, accountGroupMapper, accountConverter);

        assertThatThrownBy(() -> service.migrateGroup(List.of(1L), 20L))
                .isInstanceOf(com.armada.shared.exception.BusinessException.class)
                .hasMessageContaining("建群账号分组");

        verify(accountMapper, never()).migrateGroupIfAvailable(anyList(), any(), anyLong(), anyLong());
    }

    @Test
    void migrateGroupAllowsMovingIntoActiveBuilderGroup() {
        Account account = account(1L, 10L);
        when(accountMapper.selectActiveByIds(List.of(1L))).thenReturn(List.of(account));
        when(accountGroupMapper.selectByIds(List.of(10L, 20L)))
                .thenReturn(List.of(group(10L, null), group(20L, null)));
        when(accountGroupMapper.countActiveBuilderGroupReferences(List.of(10L))).thenReturn(0);
        when(accountMapper.migrateGroupIfAvailable(eq(List.of(1L)), eq(10L), eq(20L), anyLong()))
                .thenReturn(1);
        AccountServiceImpl service = new AccountServiceImpl(accountMapper, accountGroupMapper, accountConverter);

        service.migrateGroup(List.of(1L), 20L);

        verify(accountMapper).migrateGroupIfAvailable(eq(List.of(1L)), eq(10L), eq(20L), anyLong());
    }

    @Test
    void migrateGroupRejectsMarketingLockedSourceOrTarget() {
        Account sourceLockedAccount = account(1L, 10L);
        when(accountMapper.selectActiveByIds(List.of(1L))).thenReturn(List.of(sourceLockedAccount));
        when(accountGroupMapper.selectByIds(List.of(10L, 20L)))
                .thenReturn(List.of(group(10L, 81L), group(20L, null)));
        AccountServiceImpl service = new AccountServiceImpl(accountMapper, accountGroupMapper, accountConverter);

        assertThatThrownBy(() -> service.migrateGroup(List.of(1L), 20L))
                .isInstanceOf(com.armada.shared.exception.BusinessException.class)
                .hasMessageContaining("营销任务占用");

        Account freeAccount = account(2L, 30L);
        when(accountMapper.selectActiveByIds(List.of(2L))).thenReturn(List.of(freeAccount));
        when(accountGroupMapper.selectByIds(List.of(20L, 30L)))
                .thenReturn(List.of(group(20L, 82L), group(30L, null)));
        assertThatThrownBy(() -> service.migrateGroup(List.of(2L), 20L))
                .isInstanceOf(com.armada.shared.exception.BusinessException.class)
                .hasMessageContaining("营销任务占用");
    }

    @Test
    void migrateGroupUpdatesEachOriginalSourceWithItsOwnCondition() {
        when(accountMapper.selectActiveByIds(List.of(1L, 2L, 3L)))
                .thenReturn(List.of(account(1L, 10L), account(2L, 11L), account(3L, 20L)));
        when(accountGroupMapper.selectByIds(List.of(10L, 11L, 20L)))
                .thenReturn(List.of(group(10L, null), group(11L, null), group(20L, null)));
        when(accountGroupMapper.countActiveBuilderGroupReferences(List.of(10L, 11L))).thenReturn(0);
        when(accountMapper.migrateGroupIfAvailable(eq(List.of(1L)), eq(10L), eq(20L), anyLong()))
                .thenReturn(1);
        when(accountMapper.migrateGroupIfAvailable(eq(List.of(2L)), eq(11L), eq(20L), anyLong()))
                .thenReturn(1);
        AccountServiceImpl service = new AccountServiceImpl(accountMapper, accountGroupMapper, accountConverter);

        service.migrateGroup(List.of(1L, 2L, 3L), 20L);

        verify(accountMapper).migrateGroupIfAvailable(eq(List.of(1L)), eq(10L), eq(20L), anyLong());
        verify(accountMapper).migrateGroupIfAvailable(eq(List.of(2L)), eq(11L), eq(20L), anyLong());
    }

    @Test
    void migrateGroupRejectsWhenAnyConditionalUpdateMisses() {
        when(accountMapper.selectActiveByIds(List.of(1L, 2L)))
                .thenReturn(List.of(account(1L, 10L), account(2L, 11L)));
        when(accountGroupMapper.selectByIds(List.of(10L, 11L, 20L)))
                .thenReturn(List.of(group(10L, null), group(11L, null), group(20L, null)));
        when(accountGroupMapper.countActiveBuilderGroupReferences(List.of(10L, 11L))).thenReturn(0);
        when(accountMapper.migrateGroupIfAvailable(eq(List.of(1L)), eq(10L), eq(20L), anyLong()))
                .thenReturn(1);
        when(accountMapper.migrateGroupIfAvailable(eq(List.of(2L)), eq(11L), eq(20L), anyLong()))
                .thenReturn(0);
        AccountServiceImpl service = new AccountServiceImpl(accountMapper, accountGroupMapper, accountConverter);

        assertThatThrownBy(() -> service.migrateGroup(List.of(1L, 2L), 20L))
                .isInstanceOf(com.armada.shared.exception.BusinessException.class)
                .hasMessageContaining("状态已变化");
    }

    @Test
    void migrateGroupConditionallyMovesUnassignedAccount() {
        when(accountMapper.selectActiveByIds(List.of(1L))).thenReturn(List.of(account(1L, null)));
        when(accountGroupMapper.selectByIds(List.of(20L))).thenReturn(List.of(group(20L, null)));
        when(accountMapper.migrateGroupIfAvailable(eq(List.of(1L)), isNull(), eq(20L), anyLong()))
                .thenReturn(1);
        AccountServiceImpl service = new AccountServiceImpl(accountMapper, accountGroupMapper, accountConverter);

        service.migrateGroup(List.of(1L), 20L);

        verify(accountMapper).migrateGroupIfAvailable(eq(List.of(1L)), isNull(), eq(20L), anyLong());
    }

    @Test
    void getStatsIncludesRestrictedAccountStateInRestrictedTotal() {
        AccountStatsVoRow row = new AccountStatsVoRow();
        row.setTotal(30);
        row.setBanned(1);
        row.setUnbound(2);
        row.setMuted(3);
        row.setExported(4);
        row.setRestricted(5);
        row.setAssigned(9);
        when(accountMapper.statsSummary()).thenReturn(row);

        AccountStatsVO result = new AccountServiceImpl(accountMapper, accountGroupMapper, accountConverter).getStats();

        assertThat(result.restricted()).isEqualTo(5);
        assertThat(result.restrictedTotal()).isEqualTo(15);
        assertThat(result.unassigned()).isEqualTo(21);
    }

    @Test
    void getLoginStatesByIdsReturnsCurrentStatesIncludingUnreportedState() {
        AccountState online = loginState(11L, 1);
        AccountState unreported = loginState(12L, null);
        when(accountMapper.selectActiveLoginStatesByIds(List.of(11L, 12L)))
                .thenReturn(List.of(online, unreported));
        AccountServiceImpl service = new AccountServiceImpl(accountMapper, accountGroupMapper, accountConverter);

        Map<Long, Integer> states = service.getLoginStatesByIds(List.of(11L, 12L));

        assertThat(states).containsEntry(11L, 1);
        assertThat(states).containsKey(12L);
        assertThat(states.get(12L)).isNull();
    }

    @Test
    void getLoginStatesByIdsShortCircuitsEmptyInput() {
        AccountServiceImpl service = new AccountServiceImpl(accountMapper, accountGroupMapper, accountConverter);

        assertThat(service.getLoginStatesByIds(List.of())).isEmpty();
        verify(accountMapper, never()).selectActiveLoginStatesByIds(anyList());
    }

    @Test
    void batchDeleteAllowsUndispatchedLoginReplacedAccount() {
        AccountDeleteGateRow row = new AccountDeleteGateRow();
        row.setId(61L);
        row.setAccountState(6);
        row.setDispatchedAt(null);
        when(accountMapper.selectStatesByIds(List.of(61L))).thenReturn(List.of(row));
        when(accountMapper.batchSoftDelete(eq(List.of(61L)), anyLong())).thenReturn(1);
        AccountServiceImpl service = new AccountServiceImpl(
                accountMapper, accountGroupMapper, accountConverter);

        service.batchDelete(List.of(61L));

        verify(accountMapper).batchSoftDelete(eq(List.of(61L)), anyLong());
    }

    @Test
    void batchDeleteRejectsDispatchedLoginReplacedAccount() {
        AccountDeleteGateRow row = new AccountDeleteGateRow();
        row.setId(62L);
        row.setAccountState(6);
        row.setDispatchedAt(9_000L);
        when(accountMapper.selectStatesByIds(List.of(62L))).thenReturn(List.of(row));
        AccountServiceImpl service = new AccountServiceImpl(
                accountMapper, accountGroupMapper, accountConverter);

        assertThatThrownBy(() -> service.batchDelete(List.of(62L)))
                .isInstanceOf(com.armada.shared.exception.BusinessException.class)
                .hasMessageContaining("被抢登");

        verify(accountMapper, never()).batchSoftDelete(anyList(), anyLong());
    }

    private static AccountState loginState(Long accountId, Integer loginState) {
        AccountState row = new AccountState();
        row.setAccountId(accountId);
        row.setLoginState(loginState);
        return row;
    }

    private static AccountListVoRow occupiedAccount(Long accountId, Long taskId) {
        AccountListVoRow row = new AccountListVoRow();
        row.setId(accountId);
        row.setMarketingOccupancyType(2);
        row.setMarketingOccupancyTaskId(taskId);
        return row;
    }

    private static Account account(Long accountId, Long groupId) {
        Account account = new Account();
        account.setId(accountId);
        account.setAccountGroupId(groupId);
        return account;
    }

    private static AccountGroup group(Long groupId, Long occupancyTaskId) {
        AccountGroup group = new AccountGroup();
        group.setId(groupId);
        group.setMarketingOccupancyTaskId(occupancyTaskId);
        return group;
    }
}
