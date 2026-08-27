package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.platform.kafka.consumer.group.ProtocolGroupJoinResultReportedEvent;
import com.armada.platform.kafka.consumer.group.ProtocolJoinTaskGroupJoinCorrelation;
import com.armada.platform.kafka.consumer.group.ProtocolPullTaskGroupJoinCorrelation;
import com.armada.task.model.dto.JoinTaskResultReportedEvent;
import com.armada.task.model.dto.PullTaskManagerJoinCallback;
import com.armada.task.model.enums.PullTaskManagerJoinProtocolOutcome;
import com.armada.task.service.impl.ProtocolGroupJoinResultRouter;
import com.armada.task.service.impl.TaskResultOwnerScopeRunner;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProtocolGroupJoinResultRouterTest {

    private final JoinTaskResultService joinTaskService = mock(JoinTaskResultService.class);
    private final PullTaskManagerJoinResultService pullTaskService =
            mock(PullTaskManagerJoinResultService.class);
    private final TaskResultOwnerScopeRunner ownerScopeRunner = mock(TaskResultOwnerScopeRunner.class);
    private final ProtocolGroupJoinResultRouter router;

    ProtocolGroupJoinResultRouterTest() {
        when(ownerScopeRunner.runForJoinTask(anyLong(), anyLong(), any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(2).run();
            return true;
        });
        when(ownerScopeRunner.runForPullTask(anyLong(), anyLong(), any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(2).run();
            return true;
        });
        router = new ProtocolGroupJoinResultRouter(joinTaskService, pullTaskService, ownerScopeRunner);
    }

    @Test
    void joinTaskCorrelationKeepsExistingResultContract() {
        ProtocolGroupJoinResultReportedEvent source = new ProtocolGroupJoinResultReportedEvent(
                "event-1", 1L, new ProtocolJoinTaskGroupJoinCorrelation(9L, 26L),
                382L, "acc-1", "cmd-1", 2,
                "FAILED", null, "TEMPORARY_FAILURE", "temporary", true, 123L, "worker-a");

        router.handleJoinResultReported(source);

        ArgumentCaptor<JoinTaskResultReportedEvent> captor =
                ArgumentCaptor.forClass(JoinTaskResultReportedEvent.class);
        verify(joinTaskService).apply(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new JoinTaskResultReportedEvent(
                "event-1", 1L, 9L, 26L, 382L, "acc-1", "cmd-1", 2,
                "FAILED", null, "TEMPORARY_FAILURE", "temporary", true, 123L, "worker-a"));
    }

    @Test
    void pullTaskCorrelationRoutesToManagerJoinStateMachine() {
        ProtocolGroupJoinResultReportedEvent source = new ProtocolGroupJoinResultReportedEvent(
                "event-pull-1", 7L, new ProtocolPullTaskGroupJoinCorrelation(100L, 11L, 601L),
                382L, "acc-1", "cmd-pull-1", 1,
                "JOINED", "120363group@g.us", null, null, false, 5_000L, "worker-a");

        router.handleJoinResultReported(source);

        ArgumentCaptor<PullTaskManagerJoinCallback> captor =
                ArgumentCaptor.forClass(PullTaskManagerJoinCallback.class);
        verify(pullTaskService).apply(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new PullTaskManagerJoinCallback(
                7L, 100L, 11L, 601L, "cmd-pull-1",
                PullTaskManagerJoinProtocolOutcome.JOINED,
                "120363group@g.us", null, null, false, 5_000L));
    }
}
