package com.armada.marketing.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.mapper.MarketingTemplateMapper;
import com.armada.marketing.model.dto.CreateMarketingTaskDTO;
import com.armada.marketing.model.dto.MarketingSelectionDTO;
import com.armada.marketing.model.dto.RestartMarketingTaskDTO;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.model.enums.MarketingTaskStatus;
import com.armada.marketing.model.vo.MarketingTargetCandidateRow;
import com.armada.marketing.service.impl.MarketingAccountTreeRealtimeService;
import com.armada.marketing.service.impl.MarketingAccountOccupancyService;
import com.armada.marketing.service.impl.MarketingTaskServiceImpl;
import com.armada.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 营销任务生命周期门禁单测。
 *
 * <p>状态流转 SQL 由 DbTest 覆盖；本类聚焦启动入口在更新任务状态前必须完成的业务校验。</p>
 */
@ExtendWith(MockitoExtension.class)
class MarketingTaskServiceImplLifecycleTest {

    private static final long TASK_ID = 42L;
    private static final long TEMPLATE_ID = 77L;

    @Mock
    private MarketingTaskMapper taskMapper;

    @Mock
    private MarketingTemplateMapper templateMapper;

    @Mock
    private MarketingTemplateService templateService;

    @Mock
    private MarketingAccountTreeRealtimeService accountTreeRealtimeService;

    @Mock
    private MarketingAccountOccupancyService occupancyService;

    @InjectMocks
    private MarketingTaskServiceImpl service;

    @Test
    void createTask_occupiedAccountGroup_isRejectedBeforeTaskPersistence() {
        doThrow(new BusinessException(
                com.armada.shared.exception.ErrorCode.CONFLICT,
                "该分组正在执行其它营销任务，请等待当前任务结束后再参与新的营销任务。"))
                .when(occupancyService).assertAccountGroupAvailable(anyLong(), anyLong());
        CreateMarketingTaskDTO request = new CreateMarketingTaskDTO(
                "占用门禁任务", 12L, "营销账号组", TEMPLATE_ID, "营销模板", "PENDING",
                1, 30, true, true, false, null,
                java.util.List.of(new MarketingSelectionDTO(31L, "ACCOUNT_DYNAMIC", java.util.List.of())));

        assertThatThrownBy(() -> service.createTask(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("该分组正在执行其它营销任务，请等待当前任务结束后再参与新的营销任务。");

        verify(templateMapper, never()).selectById(anyLong());
        verify(taskMapper, never()).insertTask(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void createTask_immediateTask_acquiresAvailableAccountsAfterTargetsPersisted() {
        AtomicReference<MarketingTask> insertedTask = new AtomicReference<>();
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(template());
        when(taskMapper.selectAccountTargetCandidate(12L, 31L)).thenReturn(accountCandidate());
        doAnswer(invocation -> {
            MarketingTask task = invocation.getArgument(0);
            task.setId(TASK_ID);
            insertedTask.set(task);
            return 1;
        }).when(taskMapper).insertTask(org.mockito.ArgumentMatchers.any());
        when(taskMapper.selectTaskById(TASK_ID)).thenAnswer(invocation -> insertedTask.get());
        CreateMarketingTaskDTO request = new CreateMarketingTaskDTO(
                "立即执行任务", 12L, "营销账号组", TEMPLATE_ID, "营销模板", "IMMEDIATE",
                1, 30, true, true, false, null,
                java.util.List.of(new MarketingSelectionDTO(31L, "ACCOUNT_DYNAMIC", java.util.List.of())));

        service.createTask(request);

        verify(occupancyService).acquireAndLoadTaskAccounts(
                eq(insertedTask.get()), anyLong());
    }

    @Test
    void startTask_deletedTemplate_isRejectedWithoutChangingTaskState() {
        long now = System.currentTimeMillis();
        when(taskMapper.selectTaskById(TASK_ID)).thenReturn(task(
                MarketingTaskStatus.PENDING.code(), now + 60_000L, now + 600_000L));

        assertThatThrownBy(() -> service.startTask(TASK_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("营销模板已删除，任务不可启动");

        verify(templateMapper).selectById(TEMPLATE_ID);
        verify(taskMapper, never()).activateTask(anyLong(), anyInt(), anyInt(), anyLong());
    }

    @Test
    void restartTask_deletedTemplate_isRejectedWithoutChangingTaskState() {
        long now = System.currentTimeMillis();
        when(taskMapper.selectTaskById(TASK_ID)).thenReturn(task(
                MarketingTaskStatus.ENDED.code(), now - 600_000L, now - 60_000L));

        assertThatThrownBy(() -> service.restartTask(
                TASK_ID, new RestartMarketingTaskDTO(now + 60_000L, now + 600_000L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("营销模板已删除，任务不可启动");

        verify(templateMapper).selectById(TEMPLATE_ID);
        verify(taskMapper, never()).restartEndedTask(anyLong(), anyInt(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void startTask_insideExecutionWindow_acquiresAvailableAccounts() {
        long now = System.currentTimeMillis();
        MarketingTask task = task(MarketingTaskStatus.PENDING.code(), now - 60_000L, now + 600_000L);
        when(taskMapper.selectTaskById(TASK_ID)).thenReturn(task);
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(template());
        when(taskMapper.activateTask(eq(TASK_ID), eq(MarketingTaskStatus.PENDING.code()),
                eq(MarketingTaskStatus.SENDING.code()), anyLong())).thenReturn(1);

        service.startTask(TASK_ID);

        verify(occupancyService).acquireAndLoadTaskAccounts(eq(task), anyLong());
    }

    @Test
    void startTask_beforeExecutionWindow_doesNotAcquireAccounts() {
        long now = System.currentTimeMillis();
        MarketingTask task = task(MarketingTaskStatus.PENDING.code(), now + 60_000L, now + 600_000L);
        when(taskMapper.selectTaskById(TASK_ID)).thenReturn(task);
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(template());
        when(taskMapper.activateTask(eq(TASK_ID), eq(MarketingTaskStatus.PENDING.code()),
                eq(MarketingTaskStatus.PENDING.code()), anyLong())).thenReturn(1);

        service.startTask(TASK_ID);

        verify(occupancyService, never()).acquireAndLoadTaskAccounts(
                org.mockito.ArgumentMatchers.any(), anyLong());
    }

    @Test
    void restartTask_insideNewWindow_acquiresAvailableAccounts() {
        long now = System.currentTimeMillis();
        MarketingTask task = task(MarketingTaskStatus.ENDED.code(), now - 600_000L, now - 60_000L);
        when(taskMapper.selectTaskById(TASK_ID)).thenReturn(task);
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(template());
        when(taskMapper.restartEndedTask(eq(TASK_ID), eq(MarketingTaskStatus.SENDING.code()),
                anyLong(), anyLong(), anyLong())).thenReturn(1);

        service.restartTask(TASK_ID, new RestartMarketingTaskDTO(now - 1_000L, now + 600_000L));

        verify(occupancyService).acquireAndLoadTaskAccounts(eq(task), anyLong());
    }

    @Test
    void stopTask_sendingTask_releasesOwnedAccounts() {
        long now = System.currentTimeMillis();
        MarketingTask task = task(MarketingTaskStatus.SENDING.code(), now - 60_000L, now + 600_000L);
        when(taskMapper.selectTaskById(TASK_ID)).thenReturn(task);
        when(taskMapper.stopTask(eq(TASK_ID), anyLong())).thenReturn(1);

        service.stopTask(TASK_ID);

        verify(occupancyService).releaseTaskAccounts(TASK_ID);
    }

    private static MarketingTask task(int status, long taskStartAt, long taskEndAt) {
        MarketingTask task = new MarketingTask();
        task.setId(TASK_ID);
        task.setTenantId(1L);
        task.setMarketingTemplateId(TEMPLATE_ID);
        task.setStatus(status);
        task.setTaskStartAt(taskStartAt);
        task.setTaskEndAt(taskEndAt);
        return task;
    }

    private static MarketingTemplate template() {
        MarketingTemplate template = new MarketingTemplate();
        template.setId(TEMPLATE_ID);
        template.setTemplateName("营销模板");
        return template;
    }

    private static MarketingTargetCandidateRow accountCandidate() {
        MarketingTargetCandidateRow row = new MarketingTargetCandidateRow();
        row.setAccountId(31L);
        row.setAccountPhone("923100000031");
        return row;
    }
}
