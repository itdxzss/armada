package com.armada.group.service;

import com.armada.group.service.GroupExecutableAccountStates;
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
                10L, AccountLoginStateCode.ONLINE, GroupExecutableAccountStates.executable(), 4))
                .thenReturn(List.of(account));
        GroupExecutionAccountSelector selector = new GroupExecutionAccountSelector(mapper);

        Optional<GroupExecutionAccount> result = selector.find(10L, 0);

        assertThat(result).contains(account);
        assertThat(result.orElseThrow().groupAdmin()).isTrue();
        verify(mapper).selectGroupExecutionAccounts(
                10L, AccountLoginStateCode.ONLINE, GroupExecutableAccountStates.executable(), 4);
    }

    @Test
    void findRotatesBoundedCandidatesByCompletedAttemptCount() {
        GroupExecutionAccount first = account(7L, "923310000001", true);
        GroupExecutionAccount second = account(8L, "923310000002", false);
        when(mapper.selectGroupExecutionAccounts(
                10L, AccountLoginStateCode.ONLINE, GroupExecutableAccountStates.executable(), 4))
                .thenReturn(List.of(first, second));
        GroupExecutionAccountSelector selector = new GroupExecutionAccountSelector(mapper);

        assertThat(selector.find(10L, 0)).contains(first);
        assertThat(selector.find(10L, 1)).contains(second);
        assertThat(selector.find(10L, 2)).isEmpty();
    }

    @Test
    void findCandidatesReturnsEveryBoundedOnlineInGroupAccountInMapperOrder() {
        GroupExecutionAccount first = account(7L, "923310000001", true);
        GroupExecutionAccount second = account(8L, "923310000002", false);
        when(mapper.selectGroupExecutionAccounts(
                10L, AccountLoginStateCode.ONLINE, GroupExecutableAccountStates.executable(), 4))
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
                GroupExecutableAccountStates.executable(),
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
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void requireThrowsDedicatedErrorWhenNoExecutionAccountExists() {
        when(mapper.selectGroupExecutionAccounts(
                10L, AccountLoginStateCode.ONLINE, GroupExecutableAccountStates.executable(), 4))
                .thenReturn(List.of());
        GroupExecutionAccountSelector selector = new GroupExecutionAccountSelector(mapper);

        assertThatThrownBy(() -> selector.require(10L))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.GROUP_EXECUTOR_UNAVAILABLE.code()))
                .hasMessage("没有在线且仍在该群内的账号");
    }

    @Test
    void requireOwnerReturnsExactOnlineGroupOwnerSelectedByMapper() {
        GroupExecutionAccount owner = account(9L, "923310000009", true);
        when(mapper.selectGroupOwnerExecutionAccount(
                10L, AccountLoginStateCode.ONLINE, GroupExecutableAccountStates.executable()))
                .thenReturn(owner);
        GroupExecutionAccountSelector selector = new GroupExecutionAccountSelector(mapper);

        assertThat(selector.requireOwner(10L)).isEqualTo(owner);
        verify(mapper).selectGroupOwnerExecutionAccount(
                10L, AccountLoginStateCode.ONLINE, GroupExecutableAccountStates.executable());
    }

    @Test
    void requireOwnerThrowsDedicatedErrorInsteadOfFallingBackToAnotherMember() {
        when(mapper.selectGroupOwnerExecutionAccount(
                10L, AccountLoginStateCode.ONLINE, GroupExecutableAccountStates.executable()))
                .thenReturn(null);
        GroupExecutionAccountSelector selector = new GroupExecutionAccountSelector(mapper);

        assertThatThrownBy(() -> selector.requireOwner(10L))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.GROUP_EXECUTOR_UNAVAILABLE.code()))
                .hasMessage("没有在线且仍在该群内的群主账号");
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

    @Test
    void pullTaskAdminDiscoveryCandidatesAreCappedAtFiveHundred() {
        List<GroupExecutionAccount> candidates = java.util.stream.LongStream.rangeClosed(1, 501)
                .mapToObj(id -> new GroupExecutionAccount(
                        id, "web", "acc-" + id, Long.toString(900_000L + id), false))
                .toList();
        when(mapper.selectPullTaskAdminDiscoveryCandidatesByTenant(
                7L, "120363group@g.us", 15L, 500)).thenReturn(candidates);
        GroupExecutionAccountSelector selector = new GroupExecutionAccountSelector(mapper);

        assertThat(selector.findPullTaskAdminDiscoveryCandidates(
                7L, "120363group@g.us", 15L)).hasSize(500);
    }

    @Test
    void findAdminReturnsEmptyWhenGroupHasNoOnlineAdminSoCallerCanSkipTheProtocolCall() {
        when(mapper.selectGroupAdminExecutionAccounts(
                10L, AccountLoginStateCode.ONLINE, GroupExecutableAccountStates.executable(), 1))
                .thenReturn(List.of());
        GroupExecutionAccountSelector selector = new GroupExecutionAccountSelector(mapper);

        assertThat(selector.findAdmin(10L)).isEmpty();
    }

    @Test
    void findAdminSelectsTheGroupAdminCandidateReturnedByMapper() {
        GroupExecutionAccount admin = account(7L, "923310000001", true);
        when(mapper.selectGroupAdminExecutionAccounts(
                10L, AccountLoginStateCode.ONLINE, GroupExecutableAccountStates.executable(), 1))
                .thenReturn(List.of(admin));
        GroupExecutionAccountSelector selector = new GroupExecutionAccountSelector(mapper);

        assertThat(selector.findAdmin(10L)).contains(admin);
    }

    @Test
    void requireAdminReturnsAvailableGroupAdminWithoutRequiringOwner() {
        GroupExecutionAccount admin = account(7L, "923310000001", true);
        when(mapper.selectGroupAdminExecutionAccounts(
                10L, AccountLoginStateCode.ONLINE, GroupExecutableAccountStates.executable(), 1))
                .thenReturn(List.of(admin));
        GroupExecutionAccountSelector selector = new GroupExecutionAccountSelector(mapper);

        assertThat(selector.requireAdmin(10L)).isEqualTo(admin);
        verify(mapper).selectGroupAdminExecutionAccounts(
                10L, AccountLoginStateCode.ONLINE, GroupExecutableAccountStates.executable(), 1);
        verify(mapper, org.mockito.Mockito.never()).selectGroupOwnerExecutionAccount(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void requireAdminThrowsInsteadOfFallingBackToOrdinaryMember() {
        when(mapper.selectGroupAdminExecutionAccounts(
                10L, AccountLoginStateCode.ONLINE, GroupExecutableAccountStates.executable(), 1))
                .thenReturn(List.of());
        GroupExecutionAccountSelector selector = new GroupExecutionAccountSelector(mapper);

        assertThatThrownBy(() -> selector.requireAdmin(10L))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.GROUP_EXECUTOR_UNAVAILABLE.code()))
                .hasMessage("没有在线且仍在该群内的管理员账号");
        verify(mapper, org.mockito.Mockito.never()).selectGroupExecutionAccounts(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    private static GroupExecutionAccount account(long id, String phone, boolean admin) {
        return new GroupExecutionAccount(id, "WEB", "acc_" + phone, phone, admin);
    }
}
