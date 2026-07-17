package com.armada.group.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
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
        GroupExecutionAccount account = new GroupExecutionAccount(7L, "acc_7");
        when(mapper.selectGroupExecutionAccount(10L, AccountLoginStateCode.ONLINE)).thenReturn(account);
        GroupExecutionAccountSelector selector = new GroupExecutionAccountSelector(mapper);

        Optional<GroupExecutionAccount> result = selector.find(10L);

        assertThat(result).contains(account);
        verify(mapper).selectGroupExecutionAccount(10L, AccountLoginStateCode.ONLINE);
    }

    @Test
    void requireThrowsDedicatedErrorWhenNoExecutionAccountExists() {
        when(mapper.selectGroupExecutionAccount(10L, AccountLoginStateCode.ONLINE)).thenReturn(null);
        GroupExecutionAccountSelector selector = new GroupExecutionAccountSelector(mapper);

        assertThatThrownBy(() -> selector.require(10L))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.GROUP_EXECUTOR_UNAVAILABLE.code()))
                .hasMessage("没有在线且仍在该群内的账号");
    }
}
