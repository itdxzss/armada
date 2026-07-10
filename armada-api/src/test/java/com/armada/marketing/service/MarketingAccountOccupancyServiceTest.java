package com.armada.marketing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.marketing.mapper.MarketingAccountOccupancyMapper;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.vo.MarketingAccountOccupancyOwnerRow;
import com.armada.marketing.service.impl.MarketingAccountOccupancyService;
import com.armada.shared.exception.BusinessException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 普通营销账号占用领域规则单测。
 */
@ExtendWith(MockitoExtension.class)
class MarketingAccountOccupancyServiceTest {

    @Mock
    private MarketingAccountOccupancyMapper mapper;

    @InjectMocks
    private MarketingAccountOccupancyService service;

    @Test
    void acquireAndLoadTaskAccounts_returnsCurrentOwnersByAccount() {
        MarketingTask task = new MarketingTask();
        task.setId(91L);
        MarketingAccountOccupancyOwnerRow current = owner(31L, 91L, "当前任务", 2_000L);
        MarketingAccountOccupancyOwnerRow other = owner(32L, 92L, "其它任务", 3_000L);
        when(mapper.selectOwnersByTaskAccounts(91L)).thenReturn(List.of(current, other));

        Map<Long, MarketingAccountOccupancyOwnerRow> owners = service.acquireAndLoadTaskAccounts(task, 1_000L);

        verify(mapper).deleteStale(1_000L);
        verify(mapper).insertAvailableTaskAccounts(91L, 1_000L);
        assertThat(owners).containsEntry(31L, current).containsEntry(32L, other);
    }

    @Test
    void lockTaskAccountsOrThrow_otherOwnerRejectsWithExactTaskName() {
        MarketingTask task = task(91L, 2);
        MarketingAccountOccupancyOwnerRow other = owner(31L, 92L, "夏季营销", 3_000L);
        when(mapper.selectOwnersByTaskAccounts(91L)).thenReturn(List.of(other));

        assertThatThrownBy(() -> service.lockTaskAccountsOrThrow(task, 1_000L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("该账号正在被任务【夏季营销】占用，请先关闭原任务后再使用。");
    }

    @Test
    void lockTaskAccountsOrThrow_allAccountsOwnedByCurrentTaskSucceeds() {
        MarketingTask task = task(91L, 1);
        MarketingAccountOccupancyOwnerRow current = owner(31L, 91L, "当前任务", 2_000L);
        when(mapper.selectOwnersByTaskAccounts(91L)).thenReturn(List.of(current));

        Map<Long, MarketingAccountOccupancyOwnerRow> owners =
                service.lockTaskAccountsOrThrow(task, 1_000L);

        assertThat(owners).containsOnlyKeys(31L);
        verify(mapper).insertAvailableTaskAccounts(91L, 1_000L);
    }

    @Test
    void lockTaskAccountsOrThrow_missingOwnerRejectsWholeCreation() {
        MarketingTask task = task(91L, 2);
        MarketingAccountOccupancyOwnerRow current = owner(31L, 91L, "当前任务", 2_000L);
        when(mapper.selectOwnersByTaskAccounts(91L)).thenReturn(List.of(current));

        assertThatThrownBy(() -> service.lockTaskAccountsOrThrow(task, 1_000L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("营销账号锁定失败，请刷新后重试");
    }

    @Test
    void occupiedAttemptMessage_knownOwner_containsTaskAndReleaseTime() {
        MarketingAccountOccupancyOwnerRow owner = owner(
                31L, 91L, "夏季营销", 1_783_684_800_000L);

        assertThat(service.occupiedAttemptMessage(owner))
                .isEqualTo("账号已被营销任务【夏季营销】占用，预计于【2026-07-10 20:00:00】释放，本轮未发送。");
    }

    @Test
    void occupiedAttemptMessage_unknownOwner_usesGenericMessage() {
        assertThat(service.occupiedAttemptMessage(null))
                .isEqualTo("账号正在被其它营销任务占用，本轮未发送。");
    }

    private static MarketingAccountOccupancyOwnerRow owner(Long accountId,
                                                            Long taskId,
                                                            String taskName,
                                                            Long taskEndAt) {
        MarketingAccountOccupancyOwnerRow row = new MarketingAccountOccupancyOwnerRow();
        row.setAccountId(accountId);
        row.setMarketingTaskId(taskId);
        row.setTaskName(taskName);
        row.setTaskEndAt(taskEndAt);
        row.setOccupiedAt(1_000L);
        return row;
    }

    private static MarketingTask task(Long taskId, int selectedAccountCount) {
        MarketingTask task = new MarketingTask();
        task.setId(taskId);
        task.setTenantId(1L);
        task.setSelectedAccountCount(selectedAccountCount);
        return task;
    }
}
