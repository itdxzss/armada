package com.armada.group.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountGroupService;
import com.armada.account.model.entity.AccountGroup;
import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HistoricalGroupExecutionAccountSelectorTest {

    @Mock
    private AccountGroupMembershipMapper mapper;
    @Mock
    private AccountGroupService accountGroupService;

    @Test
    void requiresHistoricalGroupScopeAndSelectsOnlineAdministrator() {
        when(accountGroupService.requireExisting(12L)).thenReturn(ownedGroup());
        when(mapper.existsHistoricalGroupByAccountGroup(12L, "120363admin@g.us"))
                .thenReturn(true);
        when(mapper.selectHistoricalGroupExecutionAccount(
                12L,
                "120363admin@g.us"))
                .thenReturn(new GroupExecutionAccount(
                        7L, "ANDROID", "android-7", "8613800000007", true));
        HistoricalGroupExecutionAccountSelector selector =
                new HistoricalGroupExecutionAccountSelector(mapper, accountGroupService);

        GroupExecutionAccount result = selector.require(12L, "120363admin@g.us");

        assertThat(result.protocolRef().backend()).isEqualTo(ProtocolBackend.ANDROID);
        verify(mapper).selectHistoricalGroupExecutionAccount(
                12L,
                "120363admin@g.us");
        verify(accountGroupService).requireExisting(12L);
    }

    @Test
    void rejectsGroupOutsideAccountGroupHistoryBeforeSelectingAccount() {
        when(accountGroupService.requireExisting(12L)).thenReturn(ownedGroup());
        when(mapper.existsHistoricalGroupByAccountGroup(12L, "outside@g.us"))
                .thenReturn(false);
        HistoricalGroupExecutionAccountSelector selector =
                new HistoricalGroupExecutionAccountSelector(mapper, accountGroupService);

        assertThatThrownBy(() -> selector.require(12L, "outside@g.us"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于账号组历史群");
    }

    @Test
    void rejectsForeignAccountGroupBeforeReadingHistoricalMemberships() {
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.NOT_FOUND, "账号组不存在"))
                .when(accountGroupService).requireExisting(12L);
        HistoricalGroupExecutionAccountSelector selector =
                new HistoricalGroupExecutionAccountSelector(mapper, accountGroupService);

        assertThatThrownBy(() -> selector.require(12L, "120363admin@g.us"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账号组不存在");

        verify(mapper, never()).existsHistoricalGroupByAccountGroup(12L, "120363admin@g.us");
    }

    @Test
    void rejectsHistoricalUnownedAccountGroupBeforeProtocolSelection() {
        AccountGroup unowned = new AccountGroup();
        unowned.setId(12L);
        when(accountGroupService.requireExisting(12L)).thenReturn(unowned);
        HistoricalGroupExecutionAccountSelector selector =
                new HistoricalGroupExecutionAccountSelector(mapper, accountGroupService);

        assertThatThrownBy(() -> selector.require(12L, "120363admin@g.us"))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.ACCESS_DENIED.code()));

        verify(mapper, never()).existsHistoricalGroupByAccountGroup(12L, "120363admin@g.us");
    }

    private static AccountGroup ownedGroup() {
        AccountGroup group = new AccountGroup();
        group.setId(12L);
        group.setOwnerUserId(11L);
        return group;
    }
}
