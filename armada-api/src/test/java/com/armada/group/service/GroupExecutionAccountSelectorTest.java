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
import java.util.List;
import java.util.Optional;
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
        GroupExecutionAccount account = account(7L, "923310000001", true);
        when(mapper.selectGroupExecutionAccounts(
                10L, AccountLoginStateCode.ONLINE, AccountStateCode.NORMAL, 4))
                .thenReturn(List.of(account));
        GroupExecutionAccountSelector selector = new GroupExecutionAccountSelector(mapper);

        Optional<GroupExecutionAccount> result = selector.find(10L, 0);

        assertThat(result).contains(account);
        assertThat(result.orElseThrow().groupAdmin()).isTrue();
        verify(mapper).selectGroupExecutionAccounts(
                10L, AccountLoginStateCode.ONLINE, AccountStateCode.NORMAL, 4);
    }

    @Test
    void findRotatesBoundedCandidatesByCompletedAttemptCount() {
        GroupExecutionAccount first = account(7L, "923310000001", true);
        GroupExecutionAccount second = account(8L, "923310000002", false);
        when(mapper.selectGroupExecutionAccounts(
                10L, AccountLoginStateCode.ONLINE, AccountStateCode.NORMAL, 4))
                .thenReturn(List.of(first, second));
        GroupExecutionAccountSelector selector = new GroupExecutionAccountSelector(mapper);

        assertThat(selector.find(10L, 0)).contains(first);
        assertThat(selector.find(10L, 1)).contains(second);
        assertThat(selector.find(10L, 2)).contains(first);
    }

    @Test
    void findCandidatesReturnsEveryBoundedOnlineInGroupAccountInMapperOrder() {
        GroupExecutionAccount first = account(7L, "923310000001", true);
        GroupExecutionAccount second = account(8L, "923310000002", false);
        when(mapper.selectGroupExecutionAccounts(
                10L, AccountLoginStateCode.ONLINE, AccountStateCode.NORMAL, 4))
                .thenReturn(List.of(first, second));
        GroupExecutionAccountSelector selector = new GroupExecutionAccountSelector(mapper);

        assertThat(selector.findCandidates(10L)).containsExactly(first, second);
    }

    @Test
    void findAdminByPhonesNormalizesPhonesAndRotatesFreshAdmins() {
        GroupExecutionAccount first = account(7L, "923310000001", false);
        GroupExecutionAccount second = account(8L, "923310000002", false);
        when(mapper.selectGroupExecutionAccountsByPhones(
                10L,
                List.of("923310000001", "923310000002"),
                AccountLoginStateCode.ONLINE,
                AccountStateCode.NORMAL,
                4)).thenReturn(List.of(first, second));
        GroupExecutionAccountSelector selector = new GroupExecutionAccountSelector(mapper);

        assertThat(selector.findAdminByPhones(
                10L, List.of(" 923310000002 ", "+92 3310000001", ""), 1))
                .contains(second);
    }

    @Test
    void findAdminByPhonesRejectsLidOnlyIdentityWithoutQueryingAccounts() {
        GroupExecutionAccountSelector selector = new GroupExecutionAccountSelector(mapper);

        assertThat(selector.findAdminByPhones(10L, List.of("123456789012345@lid"), 0))
                .isEmpty();

        verify(mapper, org.mockito.Mockito.never()).selectGroupExecutionAccountsByPhones(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void requireThrowsDedicatedErrorWhenNoExecutionAccountExists() {
        when(mapper.selectGroupExecutionAccounts(
                10L, AccountLoginStateCode.ONLINE, AccountStateCode.NORMAL, 4))
                .thenReturn(List.of());
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

    private static GroupExecutionAccount account(long id, String phone, boolean admin) {
        return new GroupExecutionAccount(id, "WEB", "acc_" + phone, phone, admin);
    }
}
