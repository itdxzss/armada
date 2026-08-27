package com.armada.task.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.platform.kafka.consumer.group.ProtocolPullTaskBatchParticipantResultReportedEvent;
import com.armada.task.model.dto.PullTaskBatchParticipantCallback;
import com.armada.task.model.enums.PullTaskBatchParticipantProtocolOutcome;
import com.armada.task.model.enums.PullTaskParticipantExecutionState;
import com.armada.task.service.impl.ProtocolPullTaskBatchParticipantResultAdapter;
import com.armada.task.service.impl.TaskResultOwnerScopeRunner;
import org.junit.jupiter.api.Test;

/** 协议批量拉人逐成员事件适配单测。 */
class ProtocolPullTaskBatchParticipantResultAdapterTest {

    @Test
    void mapsStrongCorrelationIntoTaskCallback() {
        PullTaskProtocolResultCallbackService service =
                mock(PullTaskProtocolResultCallbackService.class);
        TaskResultOwnerScopeRunner ownerScopeRunner = mock(TaskResultOwnerScopeRunner.class);
        when(ownerScopeRunner.runForPullTask(anyLong(), anyLong(), any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(2).run();
            return true;
        });
        ProtocolPullTaskBatchParticipantResultAdapter adapter =
                new ProtocolPullTaskBatchParticipantResultAdapter(service, ownerScopeRunner);

        adapter.handleBatchParticipantResultReported(
                new ProtocolPullTaskBatchParticipantResultReportedEvent(
                        "evt-1", 7L, 100L, 11L, 801L, 902L, "puller-902",
                        "cmd-batch-1", 1, "8613800000903@s.whatsapp.net", "UNKNOWN", "UNCERTAIN",
                        "PARTICIPANT_ADD_TIMEOUT", "timed out", true, 5_000L, "worker-a"));

        verify(service).handlePullCallParticipant(new PullTaskBatchParticipantCallback(
                7L, 100L, 11L, 801L, 902L, "puller-902", "cmd-batch-1", 1,
                "8613800000903@s.whatsapp.net",
                PullTaskBatchParticipantProtocolOutcome.UNKNOWN,
                PullTaskParticipantExecutionState.UNCERTAIN,
                "PARTICIPANT_ADD_TIMEOUT", "timed out", true, 5_000L));
    }
}
