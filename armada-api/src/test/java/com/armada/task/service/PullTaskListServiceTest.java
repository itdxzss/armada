package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.shared.response.PageResult;
import com.armada.task.mapper.PullTaskGroupMarketingSummaryMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.model.dto.PullTaskFilter;
import com.armada.task.model.dto.PullTaskQuery;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupMarketingSummary;
import com.armada.task.model.enums.PullTaskListAction;
import com.armada.task.model.enums.PullTaskResourceShortageType;
import com.armada.task.model.enums.PullTaskType;
import com.armada.task.model.vo.PullTaskListVO;
import com.armada.task.service.impl.PullTaskListServiceImpl;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 拉群任务统一列表统计映射和操作集合测试。 */
class PullTaskListServiceTest {

    private final PullTaskMapper taskMapper = mock(PullTaskMapper.class);
    private final PullTaskGroupMarketingSummaryMapper summaryMapper =
            mock(PullTaskGroupMarketingSummaryMapper.class);
    private final PullTaskListService service =
            new PullTaskListServiceImpl(taskMapper, summaryMapper);

    @Test
    void mapsMarketingFormulasAndKeepsMissingSummaryUnknown() {
        PullTaskQuery query = new PullTaskQuery();
        PullTask marketing = task(12L, PullTaskType.GROUP_MARKETING, "EXECUTING");
        marketing.setLastBusinessExecutedAt(8_000L);
        PullTask standard = task(11L, PullTaskType.STANDARD, "WAIT_START");
        standard.setCreatedAt(1_000L);
        standard.setUpdatedAt(2_000L);
        PullTaskGroupMarketingSummary summary = summary(12L);
        PullTaskFilter filter = query.toFilter();
        when(taskMapper.countPage(filter)).thenReturn(2L);
        when(taskMapper.selectPage(filter, 0, 10)).thenReturn(List.of(marketing, standard));
        when(summaryMapper.selectByTaskIds(List.of(12L))).thenReturn(List.of(summary));

        PageResult<PullTaskListVO> result = service.list(query);

        assertThat(result.total()).isEqualTo(2);
        PullTaskListVO marketingRow = result.list().get(0);
        assertThat(marketingRow.groupProgress().processedGroupCount()).isEqualTo(68);
        assertThat(marketingRow.pullResult().effectiveSuccessRate())
                .isEqualByComparingTo(new BigDecimal("72.6"));
        assertThat(marketingRow.resourceStats().shortages())
                .extracting(PullTaskListVO.ResourceShortage::type)
                .containsExactly(PullTaskResourceShortageType.PULLER);
        assertThat(marketingRow.lastExecutedAt()).isEqualTo(8_000L);
        assertThat(marketingRow.allowedActions()).containsExactly(PullTaskListAction.DETAIL);

        PullTaskListVO standardRow = result.list().get(1);
        assertThat(standardRow.groupProgress()).isNull();
        assertThat(standardRow.pullResult()).isNull();
        assertThat(standardRow.marketingProgress()).isNull();
        assertThat(standardRow.messageStats()).isNull();
        assertThat(standardRow.exceptionStats()).isNull();
        assertThat(standardRow.resourceStats()).isNull();
        assertThat(standardRow.lastExecutedAt()).isNull();
        assertThat(standardRow.allowedActions())
                .containsExactly(PullTaskListAction.DETAIL, PullTaskListAction.DELETE);
        verify(summaryMapper).selectByTaskIds(List.of(12L));
    }

    @Test
    void returnsEmptyPageWithoutLoadingRowsOrSummaries() {
        PullTaskQuery query = new PullTaskQuery();
        PullTaskFilter filter = query.toFilter();
        when(taskMapper.countPage(filter)).thenReturn(0L);

        PageResult<PullTaskListVO> result = service.list(query);

        assertThat(result.list()).isEmpty();
        assertThat(result.total()).isZero();
        verify(taskMapper, never()).selectPage(filter, 0, 10);
        verify(summaryMapper, never()).selectByTaskIds(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void draftMarketingTaskAllowsDeleteWithoutAdvertisingExecutorActions() {
        PullTaskQuery query = new PullTaskQuery();
        PullTask task = task(15L, PullTaskType.GROUP_MARKETING, "DRAFT");
        PullTaskFilter filter = query.toFilter();
        when(taskMapper.countPage(filter)).thenReturn(1L);
        when(taskMapper.selectPage(filter, 0, 10)).thenReturn(List.of(task));
        when(summaryMapper.selectByTaskIds(List.of(15L))).thenReturn(List.of());

        PullTaskListVO row = service.list(query).list().get(0);

        assertThat(row.allowedActions())
                .containsExactly(PullTaskListAction.DETAIL, PullTaskListAction.DELETE);
    }

    private static PullTask task(Long id, PullTaskType type, String status) {
        PullTask task = new PullTask();
        task.setId(id);
        task.setTaskName("任务" + id);
        task.setMode("OLD_LINK");
        task.setTaskType(type);
        task.setStatus(status);
        task.setGroupCount(5);
        task.setExpectedPullCount(10_000);
        return task;
    }

    private static PullTaskGroupMarketingSummary summary(Long taskId) {
        PullTaskGroupMarketingSummary summary = new PullTaskGroupMarketingSummary();
        summary.setTaskId(taskId);
        summary.setTargetGroupCount(100);
        summary.setTransferSuccessCount(50);
        summary.setTransferPendingCloseCount(10);
        summary.setTransferPartialCount(5);
        summary.setTransferFailedCount(3);
        summary.setTransferRunningCount(20);
        summary.setTransferWaitingCount(12);
        summary.setPlannedTargetCount(12_000);
        summary.setEffectiveTargetCount(10_000);
        summary.setJoinedSuccessCount(7_260);
        summary.setAlreadyInGroupCount(100);
        summary.setPrivacyRestrictedCount(50);
        summary.setInvalidNumberCount(20);
        summary.setUnregisteredCount(10);
        summary.setPullResultUnknownCount(5);
        summary.setRemainingTargetCount(2_555);
        summary.setMarketingRunningCount(3);
        summary.setMarketingCompletedCount(4);
        summary.setMessageSuccessCount(40);
        summary.setMessageFailedCount(2);
        summary.setMessageUnknownCount(0);
        summary.setAbnormalGroupCount(6);
        summary.setPullerShortageGroupCount(2);
        summary.setBannedAccountCount(1);
        summary.setAvailablePullerCount(9);
        summary.setPullerShortage(true);
        return summary;
    }
}
