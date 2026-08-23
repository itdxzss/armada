package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.shared.response.PageResult;
import com.armada.task.mapper.PullTaskGroupMarketingSummaryMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskStandardReadMapper;
import com.armada.task.model.dto.PullTaskStandardAggregateCriteria;
import com.armada.task.model.dto.PullTaskFilter;
import com.armada.task.model.dto.PullTaskQuery;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupMarketingSummary;
import com.armada.task.model.enums.PullTaskListAction;
import com.armada.task.model.enums.PullTaskCreationMode;
import com.armada.task.model.enums.PullTaskResourceShortageType;
import com.armada.task.model.enums.PullTaskType;
import com.armada.task.model.vo.PullTaskListVO;
import com.armada.task.model.vo.PullTaskStandardTaskAggregate;
import com.armada.task.service.impl.PullTaskListServiceImpl;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 拉群任务统一列表统计映射和操作集合测试。 */
class PullTaskListServiceTest {

    private final PullTaskMapper taskMapper = mock(PullTaskMapper.class);
    private final PullTaskGroupMarketingSummaryMapper summaryMapper =
            mock(PullTaskGroupMarketingSummaryMapper.class);
    private final PullTaskStandardReadMapper standardReadMapper =
            mock(PullTaskStandardReadMapper.class);
    private final PullTaskListService service =
            new PullTaskListServiceImpl(taskMapper, summaryMapper, standardReadMapper);

    @Test
    void mapsMarketingFormulasAndKeepsMissingSummaryUnknown() {
        PullTaskQuery query = new PullTaskQuery();
        PullTask marketing = task(12L, PullTaskType.GROUP_MARKETING, "EXECUTING");
        marketing.setLastBusinessExecutedAt(8_000L);
        PullTask standard = task(11L, PullTaskType.STANDARD, "WAIT_START");
        standard.setMode("NORMAL_LINK");
        standard.setCreationMode(PullTaskCreationMode.NEW_GROUP);
        standard.setCreatedAt(1_000L);
        standard.setUpdatedAt(2_000L);
        PullTaskGroupMarketingSummary summary = summary(12L);
        PullTaskFilter filter = query.toFilter();
        when(taskMapper.countPage(filter)).thenReturn(2L);
        when(taskMapper.selectPage(filter, 0, 10)).thenReturn(List.of(marketing, standard));
        when(summaryMapper.selectByTaskIds(List.of(12L))).thenReturn(List.of(summary));
        when(standardReadMapper.selectTaskAggregates(
                PullTaskStandardAggregateCriteria.fromEnums(List.of(11L))))
                .thenReturn(List.of(standardAggregate(11L)));

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
        assertThat(standardRow.groupProgress().processedGroupCount()).isEqualTo(2);
        assertThat(standardRow.creationMode()).isEqualTo(PullTaskCreationMode.NEW_GROUP);
        assertThat(standardRow.pullResult().joinedSuccessCount()).isEqualTo(7);
        assertThat(standardRow.pullResult().failedCount()).isEqualTo(2);
        assertThat(standardRow.marketingProgress()).isNull();
        assertThat(standardRow.messageStats()).isNull();
        assertThat(standardRow.exceptionStats().pullerShortageGroupCount()).isEqualTo(1);
        assertThat(standardRow.resourceStats().remainingTargetCount()).isEqualTo(1);
        assertThat(standardRow.createdAt()).isEqualTo(1_000L);
        assertThat(standardRow.lastExecutedAt()).isEqualTo(9_000L);
        assertThat(standardRow.allowedActions())
                .containsExactly(PullTaskListAction.DETAIL,
                        PullTaskListAction.START, PullTaskListAction.DELETE);
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

    @Test
    void normalLinkActionsFollowTaskLifecycleState() {
        PullTask waitStart = normalLinkTask(21L, "WAIT_START");
        PullTask executing = normalLinkTask(22L, "EXECUTING");
        PullTask paused = normalLinkTask(23L, "PAUSED");
        PullTask waitGroup = normalLinkTask(26L, "WAIT_GROUP_RESOURCE");
        PullTask completed = normalLinkTask(24L, "COMPLETED");
        PullTask ended = normalLinkTask(25L, "ENDED");
        PullTaskQuery query = new PullTaskQuery();
        PullTaskFilter filter = query.toFilter();
        when(taskMapper.countPage(filter)).thenReturn(6L);
        when(taskMapper.selectPage(filter, 0, 10))
                .thenReturn(List.of(waitStart, executing, paused, waitGroup, completed, ended));

        List<PullTaskListVO> rows = service.list(query).list();

        assertThat(rows.get(0).allowedActions()).containsExactly(
                PullTaskListAction.DETAIL, PullTaskListAction.START, PullTaskListAction.DELETE);
        assertThat(rows.get(1).allowedActions()).containsExactly(
                PullTaskListAction.DETAIL, PullTaskListAction.PAUSE, PullTaskListAction.END);
        assertThat(rows.get(2).allowedActions()).containsExactly(
                PullTaskListAction.DETAIL, PullTaskListAction.RESUME, PullTaskListAction.END);
        assertThat(rows.get(3).allowedActions()).containsExactly(
                PullTaskListAction.DETAIL, PullTaskListAction.RESUME, PullTaskListAction.END);
        assertThat(rows.get(4).allowedActions()).containsExactly(
                PullTaskListAction.DETAIL, PullTaskListAction.DELETE);
        assertThat(rows.get(5).allowedActions()).containsExactly(
                PullTaskListAction.DETAIL, PullTaskListAction.DELETE);
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

    private static PullTask normalLinkTask(Long id, String status) {
        PullTask task = task(id, PullTaskType.STANDARD, status);
        task.setMode("NORMAL_LINK");
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

    private static PullTaskStandardTaskAggregate standardAggregate(Long taskId) {
        PullTaskStandardTaskAggregate row = new PullTaskStandardTaskAggregate();
        row.setTaskId(taskId);
        row.setTotalGroupCount(5);
        row.setCompletedGroupCount(2);
        row.setFailedGroupCount(0);
        row.setAbandonedGroupCount(0);
        row.setExecutingGroupCount(2);
        row.setWaitingGroupCount(1);
        row.setManagerShortageGroupCount(0);
        row.setPullerShortageGroupCount(1);
        row.setStationShortageGroupCount(0);
        row.setTotalMemberCount(10);
        row.setUnconsumedMemberCount(1);
        row.setSubmittedMemberCount(0);
        row.setSuccessfulMemberCount(7);
        row.setFailedMemberCount(2);
        row.setUnknownMemberCount(0);
        row.setCanceledMemberCount(0);
        row.setAvailablePullerCount(3);
        row.setLastExecutedAt(9_000L);
        return row;
    }
}
