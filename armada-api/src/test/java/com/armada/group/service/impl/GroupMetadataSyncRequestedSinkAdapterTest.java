package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import com.armada.group.service.GroupMetadataSyncTaskService;
import com.armada.platform.kafka.consumer.account.ProtocolGroupMetadataSyncRequestedEvent;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.DataScopeContext;
import com.armada.shared.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 协议群详情同步请求到群任务的 adapter 单测。 */
class GroupMetadataSyncRequestedSinkAdapterTest {

    private final GroupLinkMapper groupLinkMapper = Mockito.mock(GroupLinkMapper.class);
    private final AccountMapper accountMapper = Mockito.mock(AccountMapper.class);
    private final GroupMetadataSyncTaskService taskService = Mockito.mock(GroupMetadataSyncTaskService.class);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
        DataScopeContext.clear();
    }

    @Test
    void mapsValidatedEventWithinItsTenant() {
        Account account = new Account();
        account.setId(22L);
        account.setOwnerUserId(1001L);
        account.setProtocolAccountId("acc_web_22");
        when(accountMapper.selectActiveById(22L)).thenReturn(account);
        when(groupLinkMapper.selectActiveIdByGroupJid(
                "120363001@g.us", 1001L)).thenReturn(101L);
        GroupMetadataSyncRequestedSinkAdapter adapter =
                new GroupMetadataSyncRequestedSinkAdapter(
                        groupLinkMapper, accountMapper, taskService);

        adapter.handleGroupMetadataSyncRequested(new ProtocolGroupMetadataSyncRequestedEvent(
                "evt-1", 7L, 22L, "acc_web_22", "120363001@g.us",
                "PARTICIPANT_CHANGED", 1_000L, "wa_group_participants_update", "web-1"));

        verify(taskService).enqueue(101L, GroupMetadataSyncTrigger.PARTICIPANT_CHANGED, 1_000L);
        assertThat(TenantContext.get()).isNull();
        assertThat(DataScopeContext.current()).isEmpty();
    }

    @Test
    void staleProtocolBindingDoesNotEnqueueAnotherUsersGroup() {
        Account account = account("acc-current", 1001L);
        when(accountMapper.selectActiveById(22L)).thenReturn(account);
        GroupMetadataSyncRequestedSinkAdapter adapter =
                new GroupMetadataSyncRequestedSinkAdapter(
                        groupLinkMapper, accountMapper, taskService);

        adapter.handleGroupMetadataSyncRequested(event());

        verify(groupLinkMapper, never()).selectActiveIdByGroupJid(any(), anyLong());
        verify(taskService, never()).enqueue(anyLong(), any(), anyLong());
        assertThat(DataScopeContext.current()).isEmpty();
    }

    @Test
    void historicalUnownedAccountIsRejected() {
        when(accountMapper.selectActiveById(22L)).thenReturn(account("acc_web_22", null));
        GroupMetadataSyncRequestedSinkAdapter adapter =
                new GroupMetadataSyncRequestedSinkAdapter(
                        groupLinkMapper, accountMapper, taskService);

        assertThatThrownBy(() -> adapter.handleGroupMetadataSyncRequested(event()))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.ACCESS_DENIED.code());

        verify(taskService, never()).enqueue(anyLong(), any(), anyLong());
        assertThat(DataScopeContext.current()).isEmpty();
    }

    private static ProtocolGroupMetadataSyncRequestedEvent event() {
        return new ProtocolGroupMetadataSyncRequestedEvent(
                "evt-1", 7L, 22L, "acc_web_22", "120363001@g.us",
                "PARTICIPANT_CHANGED", 1_000L, "wa_group_participants_update", "web-1");
    }

    private static Account account(String protocolAccountId, Long ownerUserId) {
        Account account = new Account();
        account.setId(22L);
        account.setOwnerUserId(ownerUserId);
        account.setProtocolAccountId(protocolAccountId);
        return account;
    }
}
