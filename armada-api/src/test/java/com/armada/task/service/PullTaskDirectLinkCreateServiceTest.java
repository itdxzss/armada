package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.AuthPrincipal;
import com.armada.task.mapper.PullTaskDirectLinkCreateMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.dto.PullTaskDirectLinkCreateDTO;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.service.impl.PullTaskDirectLinkCreateService;
import com.armada.task.service.impl.PullTaskDirectLinkCreateTransactionService;
import com.armada.task.service.impl.PullTaskDirectLinkPlanner;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 幂等重放、创建提交后的启动及数据包权限边界。 */
class PullTaskDirectLinkCreateServiceTest {
    private final PullTaskDirectLinkPlanner planner = mock(PullTaskDirectLinkPlanner.class);
    private final PullTaskDirectLinkCreateTransactionService transactions = mock(PullTaskDirectLinkCreateTransactionService.class);
    private final PullTaskDirectLinkCreateMapper tasks = mock(PullTaskDirectLinkCreateMapper.class);
    private final PullTaskStandardSettingMapper settings = mock(PullTaskStandardSettingMapper.class);
    private final PullTaskStandardStartService start = mock(PullTaskStandardStartService.class);
    private final PullTaskDirectLinkCreateService service = new PullTaskDirectLinkCreateService(planner, transactions, tasks, settings, start);
    private final AuthPrincipal principal = new AuthPrincipal(2L, 7L, "operator", "操作员", "t", "租户", List.of(), List.of());

    @Test
    void duplicateDuringSourceProbeReturnsCommittedTask() {
        var existing = task("WAIT_START");
        when(tasks.selectByRequest(anyLong(), anyString())).thenReturn(null, existing);
        when(planner.plan(any(), any())).thenThrow(new BusinessException(ErrorCode.VALIDATION, "可用群链接不足"));
        var setting = new PullTaskStandardSetting();
        setting.setAutoStart(0);
        when(settings.selectByTaskId(1L)).thenReturn(setting);

        assertThat(service.create(request(List.of()), List.of(), principal).id()).isEqualTo(1L);

        verifyNoInteractions(transactions, start);
    }

    @Test
    void retryUsesPersistedAutoStartAndSkipsSourceReparse() {
        when(tasks.selectByRequest(anyLong(), anyString())).thenReturn(task("WAIT_START"), task("EXECUTING"));
        var setting = new PullTaskStandardSetting();
        setting.setAutoStart(1);
        when(settings.selectByTaskId(1L)).thenReturn(setting);

        assertThat(service.create(request(List.of()), List.of(), principal).status()).isEqualTo("EXECUTING");

        verify(start).start(1L);
        verifyNoInteractions(planner, transactions);
    }

    @Test
    void concurrentAutoStartReturnsTaskStartedByTheSameRequest() {
        var started = task("EXECUTING");
        started.setStartedAt(100L);
        when(tasks.selectByRequest(anyLong(), anyString())).thenReturn(task("WAIT_START"), started);
        var setting = new PullTaskStandardSetting();
        setting.setAutoStart(1);
        when(settings.selectByTaskId(1L)).thenReturn(setting);
        doThrow(new BusinessException(ErrorCode.CONFLICT, "任务状态已变化，请刷新后重试"))
                .when(start).start(1L);

        assertThat(service.create(request(List.of()), List.of(), principal).status()).isEqualTo("EXECUTING");

        verifyNoInteractions(planner, transactions);
    }

    @Test
    void autoStartFailureIsNotHiddenWhenTaskStillWaitsToStart() {
        when(tasks.selectByRequest(anyLong(), anyString())).thenReturn(task("WAIT_START"));
        var setting = new PullTaskStandardSetting();
        setting.setAutoStart(1);
        when(settings.selectByTaskId(1L)).thenReturn(setting);
        doThrow(new BusinessException(ErrorCode.CONFLICT, "执行配置已变化"))
                .when(start).start(1L);

        assertThatThrownBy(() -> service.create(request(List.of()), List.of(), principal))
                .isInstanceOf(BusinessException.class).hasMessage("执行配置已变化");
    }

    @Test
    void packageSelectionRequiresExistingPackageViewPermission() {
        assertThatThrownBy(() -> service.create(request(List.of(81L)), List.of(), principal))
                .hasMessageContaining("数据包查看权限");
        verifyNoInteractions(planner, transactions, tasks);
    }

    private PullTask task(String status) {
        var task = new PullTask();
        task.setId(1L);
        task.setTaskName("新模式");
        task.setStatus(status);
        task.setGroupCount(1);
        task.setExpectedPullCount(2);
        return task;
    }

    private PullTaskDirectLinkCreateDTO request(List<Long> packages) {
        return new PullTaskDirectLinkCreateDTO(UUID.randomUUID().toString(), "新模式", null, 0,
                null, "link", packages, 1, 2, 3, 5, 15, 2, 0, 1, 12L, null, null);
    }
}
