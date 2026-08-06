package com.armada.group.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupExecutionAccountSelectorTest {

    @Mock
    private AccountGroupMembershipMapper mapper;

    @Test
    void findReturnsOnlineMembershipAccountSelectedByMapper() {
        GroupExecutionAccount account = new GroupExecutionAccount(
                7L, null, "acc_7", "acc_7", true);
        when(mapper.selectGroupExecutionAccount(
                10L, AccountLoginStateCode.ONLINE, AccountStateCode.NORMAL)).thenReturn(account);
        GroupExecutionAccountSelector selector = new GroupExecutionAccountSelector(mapper);

        Optional<GroupExecutionAccount> result = selector.find(10L);

        assertThat(result).contains(account);
        assertThat(result.orElseThrow().groupAdmin()).isTrue();
        verify(mapper).selectGroupExecutionAccount(
                10L, AccountLoginStateCode.ONLINE, AccountStateCode.NORMAL);
    }

    @Test
    void requireThrowsDedicatedErrorWhenNoExecutionAccountExists() {
        when(mapper.selectGroupExecutionAccount(
                10L, AccountLoginStateCode.ONLINE, AccountStateCode.NORMAL)).thenReturn(null);
        GroupExecutionAccountSelector selector = new GroupExecutionAccountSelector(mapper);

        assertThatThrownBy(() -> selector.require(10L))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.GROUP_EXECUTOR_UNAVAILABLE.code()))
                .hasMessage("没有在线且仍在该群内的账号");
    }

    @Test
    void pullTaskPromoterCandidatesUseExplicitTenantAndPreserveMapperOrder() {
        GroupExecutionAccount owner = new GroupExecutionAccount(
                906L, "web", "owner-906", "906", true);
        GroupExecutionAccount admin = new GroupExecutionAccount(
                887L, "web", "admin-887", "887", true);
        when(mapper.selectPullTaskAdminPromoterCandidatesByTenant(
                7L, "120363group@g.us", 15L)).thenReturn(List.of(owner, admin));
        GroupExecutionAccountSelector selector = new GroupExecutionAccountSelector(mapper);

        assertThat(selector.findPullTaskAdminPromoterCandidates(
                7L, "120363group@g.us", 15L)).containsExactly(owner, admin);
        verify(mapper).selectPullTaskAdminPromoterCandidatesByTenant(
                7L, "120363group@g.us", 15L);
    }
}
