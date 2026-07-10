package com.armada.marketing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
    void assertAccountGroupAvailable_noCurrentOwner_passesAfterStaleCleanup() {
        long now = 1_789_059_600_000L;

        assertThatCode(() -> service.assertAccountGroupAvailable(12L, now)).doesNotThrowAnyException();

        verify(mapper).deleteStale(now);
        verify(mapper).selectFirstOwnerByAccountGroupId(12L);
    }

    @Test
    void assertAccountGroupAvailable_knownOwner_usesTaskAndReleaseTime() {
        long now = 1_789_059_600_000L;
        MarketingAccountOccupancyOwnerRow owner = owner(31L, 91L, "夏季营销", 1_783_684_800_000L);
        when(mapper.selectFirstOwnerByAccountGroupId(12L)).thenReturn(owner);

        assertThatThrownBy(() -> service.assertAccountGroupAvailable(12L, now))
                .isInstanceOf(BusinessException.class)
                .hasMessage("该分组已被营销任务【夏季营销】占用，预计于【2026-07-10 20:00:00】释放，请稍后重试。");
    }

    @Test
    void assertAccountGroupAvailable_missingReleaseTime_usesGenericMessage() {
        long now = 1_789_059_600_000L;
        when(mapper.selectFirstOwnerByAccountGroupId(12L))
                .thenReturn(owner(31L, 91L, "夏季营销", null));

        assertThatThrownBy(() -> service.assertAccountGroupAvailable(12L, now))
                .isInstanceOf(BusinessException.class)
                .hasMessage("该分组正在执行其它营销任务，请等待当前任务结束后再参与新的营销任务。");
    }

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
}
