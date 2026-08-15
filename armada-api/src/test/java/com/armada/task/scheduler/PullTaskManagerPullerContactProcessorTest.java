package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStage;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PullTaskManagerPullerContactProcessorTest {

    private final PullTaskManagerPullerContactTransactionService transactions =
            mock(PullTaskManagerPullerContactTransactionService.class);
    private final PullTaskSupplementPullerProcessor supplementProcessor =
            mock(PullTaskSupplementPullerProcessor.class);
    private final PullTaskManagerPullerContactProcessor processor =
            new PullTaskManagerPullerContactProcessor(transactions, supplementProcessor);

    @Test
    @DisplayName("补充拉手指令优先，不触发群设置")
    void supplementLinkJoinRunsBeforeGroupSettings() {
        PullTaskGroupExecution candidate = candidate();
        when(supplementProcessor.processIfPresent(candidate, "worker-1", 1_000L))
                .thenReturn(Optional.of(PullTaskExecutionDispatchResult.DEFERRED));

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        verify(transactions, never()).ensureGroupSettings(candidate, "worker-1", 1_000L);
        verify(transactions, never()).prepare(candidate, "worker-1", 1_000L);
    }

    @Test
    @DisplayName("加人权限尚未确认时返回等待，绝不提前占用拉手")
    void unsatisfiedGroupSettingsDefersBeforePullerAllocation() {
        PullTaskGroupExecution candidate = candidate();
        when(supplementProcessor.processIfPresent(candidate, "worker-1", 1_000L))
                .thenReturn(Optional.empty());
        when(transactions.ensureGroupSettings(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskGroupSettingsGate.waiting(
                        PullTaskExecutionDispatchResult.DEFERRED));

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        // 拉手是稀缺资源，权限没放开就占号会把拉手锁在一条注定失败的执行行上。
        verify(transactions, never()).prepare(candidate, "worker-1", 1_000L);
    }

    @Test
    @DisplayName("加人权限已确认后才准备管理—拉手联系人")
    void satisfiedGroupSettingsProceedsToContactPreparation() {
        PullTaskGroupExecution candidate = candidate();
        when(supplementProcessor.processIfPresent(candidate, "worker-1", 1_000L))
                .thenReturn(Optional.empty());
        when(transactions.ensureGroupSettings(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskGroupSettingsGate.open());
        when(transactions.prepare(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        verify(transactions).prepare(candidate, "worker-1", 1_000L);
    }

    private static PullTaskGroupExecution candidate() {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setId(11L);
        row.setTenantId(7L);
        row.setTaskId(100L);
        row.setExecutionStatus(2);
        row.setStage(PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code());
        row.setGroupJid("120363group@g.us");
        row.setVersion(4);
        row.setLockOwner("worker-1");
        return row;
    }
}
