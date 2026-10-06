package com.armada.task.scheduler;

import com.armada.task.model.entity.PullTaskGroupExecution;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class PullTaskCreatorDeletionDownstreamGateTest {
    @Test void everyPostTakeoverStageReturnsToUnfinishedDeletionWithoutDispatchingDownstream() {
        var contact=mock(PullTaskManagerPullerContactProcessor.class);
        var invite=mock(PullTaskPullerInviteProcessor.class);
        var pull=mock(PullTaskPullExecutionProcessor.class);
        var material=mock(PullTaskMaterialAdminProcessor.class);
        var deletion=mock(PullTaskCreatorDeletionProcessor.class);
        var gate=mock(PullTaskCreatorDeletionGate.class);
        var router=new PullTaskExecutionStageRouter(mock(PullTaskLinkValidationProcessor.class),
                mock(PullTaskManagerJoinProcessor.class),mock(PullTaskManagerAdminProcessor.class),
                contact,invite,pull,material,mock(PullTaskGroupCreateProcessor.class),deletion,gate);
        when(gate.requiredAndClosed(1L,2L,3L)).thenReturn(true);
        for (int stage : new int[]{4,5,6,7,8,10}) {
            var row=new PullTaskGroupExecution();row.setTenantId(1L);row.setTaskId(2L);
            row.setId(3L);row.setStage(stage);
            router.process(row,"lease",1000);
            verify(deletion).process(row,"lease",1000);
        }
        verifyNoInteractions(contact,invite,pull,material);
    }
}
