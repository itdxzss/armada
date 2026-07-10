package com.armada.marketing.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.mapper.MarketingTemplateMapper;
import com.armada.marketing.model.dto.RestartMarketingTaskDTO;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.enums.MarketingTaskStatus;
import com.armada.marketing.service.impl.MarketingAccountTreeRealtimeService;
import com.armada.marketing.service.impl.MarketingTaskServiceImpl;
import com.armada.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    @InjectMocks
    private MarketingTaskServiceImpl service;

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
}
