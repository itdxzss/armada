package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.shared.tenant.TenantContext;
import com.armada.group.service.GroupFolderService;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.enums.PullTaskCreationMode;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PullTaskClosingTransactionServiceTest {

    private final PullTaskMapper taskMapper = mock(PullTaskMapper.class);
    private final PullTaskGroupExecutionMapper executionMapper =
            mock(PullTaskGroupExecutionMapper.class);
    private final PullTaskGroupAccountMapper accountMapper = mock(PullTaskGroupAccountMapper.class);
    private final PullTaskStandardSettingMapper settingMapper =
            mock(PullTaskStandardSettingMapper.class);
    private final PullTaskParentCompletionService parentCompletionService =
            mock(PullTaskParentCompletionService.class);
    private final GroupFolderService groupFolderService = mock(GroupFolderService.class);
    private final PullTaskClosingTransactionService service =
            new PullTaskClosingTransactionService(
                    taskMapper, executionMapper, accountMapper, settingMapper,
                    parentCompletionService,
                    groupFolderService);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void closesLastExecutionAndCompletesParentTaskWithCas() {
        PullTaskGroupExecution candidate = candidate();
        PullTask parent = parent();
        when(taskMapper.selectLifecycle(100L)).thenReturn(parent);
        when(settingMapper.selectByTaskId(100L)).thenReturn(folderSetting());
        when(executionMapper.transitionClaimed(
                any(PullTaskGroupExecution.class),
                org.mockito.ArgumentMatchers.eq(PullTaskExecutionStage.CLOSING.code())))
                .thenReturn(1);

        assertThat(service.close(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        ArgumentCaptor<PullTaskGroupExecution> captor =
                ArgumentCaptor.forClass(PullTaskGroupExecution.class);
        verify(executionMapper).transitionClaimed(
                captor.capture(), org.mockito.ArgumentMatchers.eq(
                        PullTaskExecutionStage.CLOSING.code()));
        assertThat(captor.getValue().getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.COMPLETED.code());
        assertThat(captor.getValue().getFinishedAt()).isEqualTo(1_000L);
        verify(accountMapper).releaseAllPullersOfExecution(11L, 1_000L);
        verify(groupFolderService).moveToUsed(901L);
        verify(parentCompletionService).completeIfTerminalByExecutionId(11L, 1_000L);
    }

    @Test
    void persistsCreatorLeaveResultWithoutChangingExecutionSuccess() {
        PullTaskGroupExecution candidate = candidate();
        candidate.setCreatorLeaveResult(5);
        candidate.setCreatorLeaveReason("管理权限转移失败，未执行群主退群");
        when(taskMapper.selectLifecycle(100L)).thenReturn(parent());
        when(settingMapper.selectByTaskId(100L)).thenReturn(folderSetting());
        when(executionMapper.transitionClaimed(
                any(PullTaskGroupExecution.class),
                org.mockito.ArgumentMatchers.eq(PullTaskExecutionStage.CLOSING.code())))
                .thenReturn(1);

        assertThat(service.close(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        ArgumentCaptor<PullTaskGroupExecution> captor =
                ArgumentCaptor.forClass(PullTaskGroupExecution.class);
        verify(executionMapper).transitionClaimed(
                captor.capture(), org.mockito.ArgumentMatchers.eq(
                        PullTaskExecutionStage.CLOSING.code()));
        assertThat(captor.getValue().getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.COMPLETED.code());
        assertThat(captor.getValue().getCreatorLeaveResult()).isEqualTo(5);
        assertThat(captor.getValue().getCreatorLeaveReason()).contains("权限转移失败");
    }

    @Test
    void pastedManualLinkWithoutSourceFolderStaysInItsCurrentFolder() {
        PullTaskGroupExecution candidate = candidate();
        when(taskMapper.selectLifecycle(100L)).thenReturn(parent());
        when(executionMapper.transitionClaimed(
                any(PullTaskGroupExecution.class),
                org.mockito.ArgumentMatchers.eq(PullTaskExecutionStage.CLOSING.code())))
                .thenReturn(1);

        assertThat(service.close(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        verifyNoInteractions(groupFolderService);
    }

    private static PullTask parent() {
        PullTask row = new PullTask();
        row.setId(100L);
        row.setTaskType(PullTaskType.STANDARD);
        row.setMode("NORMAL_LINK");
        row.setCreationMode(PullTaskCreationMode.PASTED_LINK);
        row.setStatus("EXECUTING");
        row.setVersion(9);
        return row;
    }

    private static PullTaskStandardSetting folderSetting() {
        PullTaskStandardSetting row = new PullTaskStandardSetting();
        row.setTaskId(100L);
        row.setSourceGroupFolderId(18L);
        return row;
    }

    private static PullTaskGroupExecution candidate() {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setId(11L);
        row.setTenantId(7L);
        row.setTaskId(100L);
        row.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        row.setStage(PullTaskExecutionStage.CLOSING.code());
        row.setVersion(6);
        row.setLockOwner("worker-1");
        row.setGroupJid("120363group@g.us");
        row.setGroupLinkId(901L);
        return row;
    }
}
