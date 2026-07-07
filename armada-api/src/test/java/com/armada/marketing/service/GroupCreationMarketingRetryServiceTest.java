package com.armada.marketing.service;

import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.marketing.mapper.GroupCreationMarketingTaskMapper;
import com.armada.marketing.model.entity.GroupCreationMarketingItem;
import com.armada.marketing.model.entity.GroupCreationMarketingTask;
import com.armada.marketing.model.enums.GroupCreationMarketingItemStatus;
import com.armada.marketing.model.support.GroupCreationMarketingRetryHistory;
import com.armada.marketing.model.vo.GroupCreationMarketingAccountCandidate;
import com.armada.marketing.service.impl.GroupCreationMarketingRetryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroupCreationMarketingRetryServiceTest {

    @Mock
    private GroupCreationMarketingTaskMapper mapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void protocolFailureResetsOneItemToPendingWithNextAvailableAccount() {
        GroupCreationMarketingRetryService service = newService();
        GroupCreationMarketingItem item = item(7L, "acc_7");
        GroupCreationMarketingTask task = task();
        GroupCreationMarketingAccountCandidate replacement = account(9L, "acc_9");
        when(mapper.selectFirstAvailableAccountCandidateByGroupIdExcluding(eq(8L), eq(List.of(7L))))
                .thenReturn(replacement);
        when(mapper.resetItemForAccountRetry(eq(11L), eq(9L), eq("phone-9"), eq("acc_9"),
                eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()), org.mockito.ArgumentMatchers.isNull(),
                eq(GroupCreationMarketingItemStatus.PENDING.code()), eq(1000L),
                org.mockito.ArgumentMatchers.any(), eq(1000L))).thenReturn(1);
        ArgumentCaptor<String> historyJson = ArgumentCaptor.forClass(String.class);

        boolean retried = service.resetItemForAccountRetry(
                item, task, GroupCreationMarketingRetryService.STAGE_GROUP_CREATE,
                "GROUP_CREATE_FAILED", "rate-overlimit", 1000L);

        assertThat(retried).isTrue();
        verify(mapper).resetItemForAccountRetry(eq(11L), eq(9L), eq("phone-9"), eq("acc_9"),
                eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()), org.mockito.ArgumentMatchers.isNull(),
                eq(GroupCreationMarketingItemStatus.PENDING.code()), eq(1000L), historyJson.capture(), eq(1000L));
        GroupCreationMarketingRetryHistory history =
                GroupCreationMarketingRetryHistory.parse(objectMapper, historyJson.getValue());
        assertThat(history.attemptedAccountIds()).containsExactly(7L);
        assertThat(history.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.stage()).isEqualTo(GroupCreationMarketingRetryService.STAGE_GROUP_CREATE);
            assertThat(entry.reasonCode()).isEqualTo("GROUP_CREATE_FAILED");
            assertThat(entry.reasonMessage()).isEqualTo("rate-overlimit");
        });
        verify(mapper, never()).markItemNoAvailableAccount(anyLong(), eq("NO_AVAILABLE_ACCOUNT"), eq("没有可用账号"),
                eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any(), anyLong());
    }

    @Test
    void protocolFailureMarksNoAvailableAccountWhenAllOnlineCandidatesWereTried() {
        GroupCreationMarketingRetryService service = newService();
        GroupCreationMarketingItem item = item(7L, "acc_7");
        GroupCreationMarketingTask task = task();
        when(mapper.selectFirstAvailableAccountCandidateByGroupIdExcluding(eq(8L), eq(List.of(7L))))
                .thenReturn(null);
        when(mapper.markItemNoAvailableAccount(eq(11L), eq("NO_AVAILABLE_ACCOUNT"), eq("没有可用账号"),
                eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any(), eq(1000L))).thenReturn(1);

        boolean retried = service.resetItemForAccountRetry(
                item, task, GroupCreationMarketingRetryService.STAGE_GROUP_CREATE,
                "GROUP_CREATE_FAILED", "rate-overlimit", 1000L);

        assertThat(retried).isFalse();
        verify(mapper).markItemNoAvailableAccount(eq(11L), eq("NO_AVAILABLE_ACCOUNT"), eq("没有可用账号"),
                eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any(), eq(1000L));
        verify(mapper, never()).resetItemForAccountRetry(anyLong(), anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()),
                org.mockito.ArgumentMatchers.isNull(), eq(GroupCreationMarketingItemStatus.PENDING.code()),
                anyLong(), org.mockito.ArgumentMatchers.any(), anyLong());
    }

    @Test
    void claimStageRetryReplacesAccountWithoutResettingItemStatus() {
        GroupCreationMarketingRetryService service = newService();
        GroupCreationMarketingItem item = item(7L, "acc_7");
        GroupCreationMarketingTask task = task();
        GroupCreationMarketingAccountCandidate replacement = account(9L, "acc_9");
        when(mapper.selectFirstAvailableAccountCandidateByGroupIdExcluding(eq(8L), eq(List.of(7L))))
                .thenReturn(replacement);
        when(mapper.updateItemAccountForClaimRetry(eq(11L), eq(9L), eq("phone-9"), eq("acc_9"),
                org.mockito.ArgumentMatchers.any(), eq(1000L))).thenReturn(1);

        GroupCreationMarketingAccountCandidate selected = service.replaceClaimedItemAccountForRetry(
                item, task, GroupCreationMarketingRetryService.STAGE_ACCOUNT_CHECK,
                "ACCOUNT_OFFLINE", "账号离线", 1000L);

        assertThat(selected).isSameAs(replacement);
        assertThat(item.getAccountId()).isEqualTo(9L);
        assertThat(item.getAccountPhone()).isEqualTo("phone-9");
        assertThat(item.getProtocolAccountId()).isEqualTo("acc_9");
        verify(mapper).updateItemAccountForClaimRetry(eq(11L), eq(9L), eq("phone-9"), eq("acc_9"),
                org.mockito.ArgumentMatchers.any(), eq(1000L));
        verify(mapper, never()).resetItemForAccountRetry(anyLong(), anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()),
                org.mockito.ArgumentMatchers.isNull(), eq(GroupCreationMarketingItemStatus.PENDING.code()),
                anyLong(), org.mockito.ArgumentMatchers.any(), anyLong());
    }

    @Test
    void marketingSendFailureRetryGuardsCurrentCommandId() {
        GroupCreationMarketingRetryService service = newService();
        GroupCreationMarketingItem item = item(7L, "acc_7");
        GroupCreationMarketingTask task = task();
        GroupCreationMarketingAccountCandidate replacement = account(9L, "acc_9");
        when(mapper.selectFirstAvailableAccountCandidateByGroupIdExcluding(eq(8L), eq(List.of(7L))))
                .thenReturn(replacement);
        when(mapper.resetItemForAccountRetry(eq(11L), eq(9L), eq("phone-9"), eq("acc_9"),
                eq(GroupCreationMarketingItemStatus.MARKETING_SENDING.code()), eq("cmd_1"),
                eq(GroupCreationMarketingItemStatus.PENDING.code()), eq(1000L),
                org.mockito.ArgumentMatchers.any(), eq(1000L))).thenReturn(1);

        boolean retried = service.resetMarketingSendingItemForAccountRetry(
                item, task, "cmd_1", "SEND_FAILED", "rate limited", 1000L);

        assertThat(retried).isTrue();
        verify(mapper).resetItemForAccountRetry(eq(11L), eq(9L), eq("phone-9"), eq("acc_9"),
                eq(GroupCreationMarketingItemStatus.MARKETING_SENDING.code()), eq("cmd_1"),
                eq(GroupCreationMarketingItemStatus.PENDING.code()), eq(1000L),
                org.mockito.ArgumentMatchers.any(), eq(1000L));
    }

    @Test
    void marketingSendFailureNoAvailableAccountUsesCommandGuard() {
        GroupCreationMarketingRetryService service = newService();
        GroupCreationMarketingItem item = item(7L, "acc_7");
        GroupCreationMarketingTask task = task();
        when(mapper.selectFirstAvailableAccountCandidateByGroupIdExcluding(eq(8L), eq(List.of(7L))))
                .thenReturn(null);
        when(mapper.markItemNoAvailableAccount(eq(11L), eq("NO_AVAILABLE_ACCOUNT"), eq("没有可用账号"),
                eq(GroupCreationMarketingItemStatus.MARKETING_SENDING.code()), eq("cmd_1"),
                org.mockito.ArgumentMatchers.any(), eq(1000L))).thenReturn(1);

        boolean retried = service.resetMarketingSendingItemForAccountRetry(
                item, task, "cmd_1", "SEND_FAILED", "rate limited", 1000L);

        assertThat(retried).isFalse();
        verify(mapper).markItemNoAvailableAccount(eq(11L), eq("NO_AVAILABLE_ACCOUNT"), eq("没有可用账号"),
                eq(GroupCreationMarketingItemStatus.MARKETING_SENDING.code()), eq("cmd_1"),
                org.mockito.ArgumentMatchers.any(), eq(1000L));
    }

    @Test
    void noAvailableAccountConflictThrowsWhenItemWasChangedConcurrently() {
        GroupCreationMarketingRetryService service = newService();
        GroupCreationMarketingItem item = item(7L, "acc_7");
        GroupCreationMarketingTask task = task();
        when(mapper.selectFirstAvailableAccountCandidateByGroupIdExcluding(eq(8L), eq(List.of(7L))))
                .thenReturn(null);
        when(mapper.markItemNoAvailableAccount(eq(11L), eq("NO_AVAILABLE_ACCOUNT"), eq("没有可用账号"),
                eq(GroupCreationMarketingItemStatus.MARKETING_SENDING.code()), eq("cmd_1"),
                org.mockito.ArgumentMatchers.any(), eq(1000L))).thenReturn(0);

        assertThatThrownBy(() -> service.resetMarketingSendingItemForAccountRetry(
                item, task, "cmd_1", "SEND_FAILED", "rate limited", 1000L))
                .hasMessageContaining("建群营销执行项状态已变化");
    }

    private GroupCreationMarketingRetryService newService() {
        return new GroupCreationMarketingRetryService(mapper, objectMapper);
    }

    private static GroupCreationMarketingItem item(Long accountId, String protocolAccountId) {
        GroupCreationMarketingItem item = new GroupCreationMarketingItem();
        item.setId(11L);
        item.setTaskId(22L);
        item.setAccountId(accountId);
        item.setAccountPhone("phone-" + accountId);
        item.setProtocolAccountId(protocolAccountId);
        return item;
    }

    private static GroupCreationMarketingTask task() {
        GroupCreationMarketingTask task = new GroupCreationMarketingTask();
        task.setId(22L);
        task.setAccountGroupId(8L);
        return task;
    }

    private static GroupCreationMarketingAccountCandidate account(Long accountId, String protocolAccountId) {
        GroupCreationMarketingAccountCandidate account = new GroupCreationMarketingAccountCandidate();
        account.setAccountId(accountId);
        account.setAccountPhone("phone-" + accountId);
        account.setProtocolAccountId(protocolAccountId);
        account.setAccountState(AccountStateCode.NORMAL);
        account.setLoginState(AccountLoginStateCode.ONLINE);
        account.setRiskStatus(1);
        return account;
    }
}
