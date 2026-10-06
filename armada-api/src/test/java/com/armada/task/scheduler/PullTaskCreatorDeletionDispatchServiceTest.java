package com.armada.task.scheduler;

import com.armada.platform.protocol.model.command.CreatorDeletionCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.port.CreatorAccountDeletionPort;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskCreatorDeletion;
import com.armada.task.model.entity.PullTaskGroupExecution;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class PullTaskCreatorDeletionDispatchServiceTest {
    @Test void networkQueryExpiredLeaseIsCheckedAgainstLocalTimeBeforePost() {
        var tasks=mock(PullTaskMapper.class);var executions=mock(PullTaskGroupExecutionMapper.class);
        var port=mock(CreatorAccountDeletionPort.class);
        var service=new PullTaskCreatorDeletionDispatchService(new PullTaskCreatorDeletionResources(tasks,executions,null,null,null,null,null),port);
        var parent=new PullTask();parent.setStatus("EXECUTING");when(tasks.selectLifecycleForUpdate(2)).thenReturn(parent);
        var row=new PullTaskGroupExecution();row.setTenantId(1L);row.setTaskId(2L);row.setId(3L);
        row.setManualPaused(0);row.setVersion(1);row.setLockOwner("lease");
        row.setLockExpiresAt(System.currentTimeMillis()-1);
        when(executions.selectByIdForUpdate(3)).thenReturn(row);
        var creator=new ProtocolAccountRef(7L,ProtocolBackend.ANDROID,"100","100");
        var work=new PullTaskCreatorDeletionWork(row,new PullTaskCreatorDeletion(),creator,creator);
        var command=new CreatorDeletionCommand(1,2,3,creator,"hash","create","operation");
        assertThat(service.send(work,command).reason()).isEqualTo("TASK_STOPPED_OR_LEASE_LOST");
        verifyNoInteractions(port);
    }

    @Test void stopBetweenCommittedIntentAndDispatchPreventsPost() {
        var tasks=mock(PullTaskMapper.class);var executions=mock(PullTaskGroupExecutionMapper.class);
        var port=mock(CreatorAccountDeletionPort.class);
        var service=new PullTaskCreatorDeletionDispatchService(new PullTaskCreatorDeletionResources(tasks,executions,null,null,null,null,null),port);
        var parent=new PullTask();parent.setStatus("ENDED");when(tasks.selectLifecycleForUpdate(2)).thenReturn(parent);
        var row=new PullTaskGroupExecution();row.setTenantId(1L);row.setTaskId(2L);row.setId(3L);
        var creator=new ProtocolAccountRef(7L,ProtocolBackend.ANDROID,"100","100");
        var work=new PullTaskCreatorDeletionWork(row,new PullTaskCreatorDeletion(),creator,creator);
        var command=new CreatorDeletionCommand(1,2,3,creator,"hash","create","operation");
        assertThat(service.send(work,command).reason()).isEqualTo("TASK_STOPPED_OR_LEASE_LOST");
        verifyNoInteractions(port);
    }
}
