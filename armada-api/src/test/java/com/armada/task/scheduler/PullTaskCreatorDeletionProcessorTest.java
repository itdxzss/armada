package com.armada.task.scheduler;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.CreatorDeletionObservation;
import com.armada.platform.protocol.model.result.CreatorDeletionResult;
import com.armada.platform.protocol.port.CreatorAccountDeletionPort;
import com.armada.task.model.entity.PullTaskCreatorDeletion;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskCreatorDeletionStatus;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PullTaskCreatorDeletionProcessorTest {
    private final PullTaskCreatorDeletionTransactionService transactions = mock(PullTaskCreatorDeletionTransactionService.class);
    private final CreatorAccountDeletionPort protocol = mock(CreatorAccountDeletionPort.class);
    private final PullTaskCreatorDeletionDispatchService dispatch = mock(PullTaskCreatorDeletionDispatchService.class);
    private final PullTaskCreatorDeletionProcessor processor = new PullTaskCreatorDeletionProcessor(transactions, protocol, dispatch);

    @Test void stoppedOrUnavailableExecutionNeverQueriesOrDeletes() {
        var work = work(PullTaskCreatorDeletionStatus.RESERVED);
        when(transactions.prepare(work.execution(),1000)).thenReturn(Optional.empty());
        processor.process(work.execution(),"lease",1000);
        verifyNoInteractions(protocol, dispatch);
    }
    @Test void adminSuccessDatabaseFlagCannotBypassFreshPermissionProof() {
        var work = prepared(PullTaskCreatorDeletionStatus.RESERVED);
        when(protocol.observe(any())).thenReturn(proof(false));
        processor.process(work.execution(),"lease",1000);
        verify(dispatch,never()).send(any(),any());
        verify(transactions).reject(eq(work.execution()),anyString(),eq(1000L));
    }
    @Test void crashAfterIntentAndUnknownResultOnlyQueryOriginalOperation() {
        for (var status : new PullTaskCreatorDeletionStatus[]{PullTaskCreatorDeletionStatus.SUBMITTED,PullTaskCreatorDeletionStatus.UNKNOWN}) {
            var work = prepared(status);
            processor.process(work.execution(),"lease",1000);
        }
        verify(protocol,times(2)).query(argThat(command -> command.operationId().equals("original")));
        verify(dispatch,never()).send(any(),any());
    }
    @Test void timeoutAfterOneSendNeverRetriesPost() {
        var work = prepared(PullTaskCreatorDeletionStatus.RESERVED);
        when(protocol.observe(any())).thenReturn(proof(true));
        when(transactions.claimSubmission(eq(work),any(),eq(1000L))).thenReturn(true);
        when(dispatch.send(any(),any())).thenThrow(new IllegalStateException("timeout"));
        processor.process(work.execution(),"lease",1000);
        verify(dispatch,times(1)).send(eq(work),argThat(command -> command.creator().armadaAccountId().equals(7L)));
        verify(transactions).record(eq(work),isNull(),isNull(),eq(1000L),anyLong());
        work.deletion().setStatus(PullTaskCreatorDeletionStatus.UNKNOWN.code());
        processor.process(work.execution(),"lease",1000);
        verify(dispatch,times(1)).send(any(),any());
        verify(protocol).query(any());
    }
    @Test void acceptedQueriesFreshCleanupButDoesNotAdvanceItself() {
        var work = prepared(PullTaskCreatorDeletionStatus.ACCEPTED);
        var accepted = new CreatorDeletionResult("original","hash","ACCEPTED","iq","result",null);
        var observedAt = new AtomicLong();
        when(protocol.query(any())).thenReturn(accepted);
        when(protocol.observe(any())).thenAnswer(invocation -> {
            observedAt.set(System.currentTimeMillis());
            return proof(true);
        });
        processor.process(work.execution(),"lease",1000);
        var recordedAt = ArgumentCaptor.forClass(Long.class);
        verify(transactions).record(eq(work),eq(accepted),eq(proof(true)),eq(1000L),recordedAt.capture());
        assertThat(recordedAt.getValue()).isBetween(observedAt.get(), System.currentTimeMillis());
        verify(dispatch,never()).send(any(),any());
    }
    @Test void firstAcceptanceRecordsTimeAfterDeletionResponseInsteadOfRoundStart() {
        var work = prepared(PullTaskCreatorDeletionStatus.RESERVED);
        var accepted = new CreatorDeletionResult("original","hash","ACCEPTED","iq","result",null);
        var returnedAt = new AtomicLong();
        when(protocol.observe(any())).thenReturn(proof(true));
        when(transactions.claimSubmission(eq(work),any(),eq(1000L))).thenReturn(true);
        when(dispatch.send(any(),any())).thenAnswer(invocation -> {
            returnedAt.set(System.currentTimeMillis());
            return accepted;
        });
        processor.process(work.execution(),"lease",1000);
        var recordedAt = ArgumentCaptor.forClass(Long.class);
        verify(transactions).record(eq(work),eq(accepted),isNull(),eq(1000L),recordedAt.capture());
        assertThat(recordedAt.getValue()).isBetween(returnedAt.get(), System.currentTimeMillis());
    }
    private PullTaskCreatorDeletionWork prepared(PullTaskCreatorDeletionStatus status) {
        var work = work(status); when(transactions.prepare(work.execution(),1000)).thenReturn(Optional.of(work)); return work;
    }
    private static CreatorDeletionObservation proof(boolean admin) {
        return new CreatorDeletionObservation("g@g.us",12,"100@s.whatsapp.net","100",true,admin,true,true,true,true,1000);
    }
    private static PullTaskCreatorDeletionWork work(PullTaskCreatorDeletionStatus status) {
        var execution = new PullTaskGroupExecution(); execution.setId(3L);execution.setTaskId(2L);execution.setTenantId(1L);execution.setGroupJid("g@g.us");execution.setLockOwner("lease");
        var row = new PullTaskCreatorDeletion();row.setTenantId(1L);row.setTaskId(2L);row.setGroupExecutionId(3L);row.setOperationId("original");row.setCreatorIdentityHash("hash");row.setCreateOperationId("created");row.setStatus(status.code());row.setCreationBefore(12L);
        return new PullTaskCreatorDeletionWork(execution,row,new ProtocolAccountRef(7L,ProtocolBackend.ANDROID,"100","100"),new ProtocolAccountRef(8L,ProtocolBackend.ANDROID,"200","200"));
    }
}
