package com.armada.account.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.converter.AccountConverter;
import com.armada.account.mapper.AccountGroupMapper;
import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.AccountState;
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

    private static AccountState loginState(Long accountId, Integer loginState) {
        AccountState row = new AccountState();
        row.setAccountId(accountId);
        row.setLoginState(loginState);
        return row;
    }
}
