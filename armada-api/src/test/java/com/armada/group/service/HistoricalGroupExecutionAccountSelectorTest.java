package com.armada.group.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HistoricalGroupExecutionAccountSelectorTest {

    @Mock
    private AccountGroupMembershipMapper mapper;

    @Test
    void requiresHistoricalGroupScopeAndSelectsOnlineAdministrator() {
        when(mapper.existsHistoricalGroupByAccountGroup(12L, "120363admin@g.us"))
                .thenReturn(true);
        when(mapper.selectHistoricalGroupExecutionAccount(
                12L,
                "120363admin@g.us"))
                .thenReturn(new GroupExecutionAccount(
                        7L, "ANDROID", "android-7", "8613800000007", true));
        HistoricalGroupExecutionAccountSelector selector =
                new HistoricalGroupExecutionAccountSelector(mapper);

        GroupExecutionAccount result = selector.require(12L, "120363admin@g.us");

        assertThat(result.protocolRef().backend()).isEqualTo(ProtocolBackend.ANDROID);
        verify(mapper).selectHistoricalGroupExecutionAccount(
                12L,
                "120363admin@g.us");
    }

    @Test
    void rejectsGroupOutsideAccountGroupHistoryBeforeSelectingAccount() {
        when(mapper.existsHistoricalGroupByAccountGroup(12L, "outside@g.us"))
                .thenReturn(false);
        HistoricalGroupExecutionAccountSelector selector =
                new HistoricalGroupExecutionAccountSelector(mapper);

        assertThatThrownBy(() -> selector.require(12L, "outside@g.us"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于账号组历史群");
    }
}
