package com.armada.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.group.service.GroupParticipantObservationService;
import com.armada.shared.exception.BusinessException;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMemberQueryMapper;
import com.armada.task.model.dto.PullTaskMemberFact;
import com.armada.task.model.dto.PullTaskMemberQueryCallback;
import com.armada.task.model.entity.PullTaskMemberQuery;
import com.armada.task.model.enums.PullTaskMemberQueryOutcome;
import com.armada.task.model.enums.PullTaskMemberQueryPurpose;
import com.armada.task.model.enums.PullTaskMemberQueryStatus;
import com.armada.task.scheduler.PullTaskExecutionDispatchTrigger;
import com.armada.task.scheduler.PullTaskUnknownResultReconciliationScheduler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PullTaskMemberQueryResultServiceImplTest {

    @Mock private PullTaskMemberQueryMapper queryMapper;
    @Mock private PullTaskGroupExecutionMapper executionMapper;
    @Mock private PullTaskExecutionDispatchTrigger dispatchTrigger;
    @Mock private PullTaskUnknownResultReconciliationScheduler reconciliationScheduler;
    @Mock private GroupParticipantObservationService observationService;

    @AfterEach
    void clearTenant() {
        com.armada.shared.tenant.TenantContext.clear();
    }

    @Test
    void apply_successSettlesCurrentAttemptAndWakesOnlyItsExecutionStage() {
        PullTaskMemberQuery row = row(PullTaskMemberQueryPurpose.MANAGER_ADMIN_MEMBERSHIP);
        when(queryMapper.selectById(701L)).thenReturn(row);
        when(queryMapper.settlePending(any())).thenReturn(1);
        when(executionMapper.wakeForMemberQuery(any())).thenReturn(1);
        PullTaskMemberQueryResultServiceImpl service = service();

        assertThat(service.apply(callback(PullTaskMemberQueryOutcome.SUCCESS))).isTrue();

        ArgumentCaptor<com.armada.task.model.dto.PullTaskMemberQuerySettlement> settlement =
                ArgumentCaptor.forClass(com.armada.task.model.dto.PullTaskMemberQuerySettlement.class);
        verify(queryMapper).settlePending(settlement.capture());
        assertThat(settlement.getValue().queryId()).isEqualTo(701L);
        assertThat(settlement.getValue().commandId()).isEqualTo("cmd-query-2");
        assertThat(settlement.getValue().targetStatus())
                .isEqualTo(PullTaskMemberQueryStatus.SUCCEEDED.code());
        assertThat(settlement.getValue().resultJson()).contains("8613800000902@s.whatsapp.net");

        ArgumentCaptor<com.armada.task.model.dto.PullTaskMemberQueryWake> wake =
                ArgumentCaptor.forClass(com.armada.task.model.dto.PullTaskMemberQueryWake.class);
        verify(executionMapper).wakeForMemberQuery(wake.capture());
        assertThat(wake.getValue().executionId()).isEqualTo(11L);
        assertThat(wake.getValue().expectedStage()).isEqualTo(3);
        verify(dispatchTrigger).dispatchAfterCommit();
        verifyNoInteractions(reconciliationScheduler);
    }

    @Test
    void apply_reconciliationResultDoesNotTouchExecutionRowAndTriggersReconciler() {
        PullTaskMemberQuery row = row(PullTaskMemberQueryPurpose.UNKNOWN_RESULT_RECONCILIATION);
        when(queryMapper.selectById(701L)).thenReturn(row);
        when(queryMapper.settlePending(any())).thenReturn(1);
        PullTaskMemberQueryResultServiceImpl service = service();

        assertThat(service.apply(callback(
                PullTaskMemberQueryOutcome.FAILED,
                PullTaskMemberQueryPurpose.UNKNOWN_RESULT_RECONCILIATION))).isTrue();

        verify(executionMapper, never()).wakeForMemberQuery(any());
        verifyNoInteractions(dispatchTrigger);
        verify(reconciliationScheduler).trigger();
    }

    @Test
    void apply_mismatchedOrLateResultCannotSettleOrWake() {
        PullTaskMemberQuery mismatched = row(PullTaskMemberQueryPurpose.MANAGER_ADMIN_MEMBERSHIP);
        mismatched.setProtocolAccountId("another-account");
        when(queryMapper.selectById(701L)).thenReturn(mismatched);
        PullTaskMemberQueryResultServiceImpl service = service();

        assertThat(service.apply(callback(PullTaskMemberQueryOutcome.SUCCESS))).isFalse();

        verify(queryMapper, never()).settlePending(any());
        verifyNoInteractions(executionMapper, dispatchTrigger, reconciliationScheduler);
    }

    @Test
    void apply_successMustReturnExactlyTheFrozenTargets() {
        PullTaskMemberQuery row = row(PullTaskMemberQueryPurpose.MANAGER_ADMIN_MEMBERSHIP);
        when(queryMapper.selectById(701L)).thenReturn(row);
        PullTaskMemberQueryResultServiceImpl service = service();
        PullTaskMemberQueryCallback callback = new PullTaskMemberQueryCallback(
                "event-701", 7L, 100L, 11L, 701L, PullTaskMemberQueryPurpose.MANAGER_ADMIN_MEMBERSHIP,
                901L, "manager-901", "WEB", "cmd-query-2", 2,
                PullTaskMemberQueryOutcome.SUCCESS, "120363group@g.us",
                List.of(new PullTaskMemberFact(
                        "different@s.whatsapp.net", null, null, false, false)),
                "", "", false, 5_000L);

        assertThatThrownBy(() -> service.apply(callback)).isInstanceOf(BusinessException.class);
        verify(queryMapper, never()).settlePending(any());
    }

    @Test
    void discoverySuccessWritesGlobalFactsBeforeWakingManagerAdmin() {
        PullTaskMemberQuery row = row(PullTaskMemberQueryPurpose.MANAGER_ADMIN_DISCOVERY);
        when(queryMapper.selectById(701L)).thenReturn(row);
        when(queryMapper.settlePending(any())).thenReturn(1);
        when(executionMapper.wakeForMemberQuery(any())).thenReturn(1);
        PullTaskMemberQueryResultServiceImpl service = service();

        assertThat(service.apply(callback(
                PullTaskMemberQueryOutcome.SUCCESS,
                PullTaskMemberQueryPurpose.MANAGER_ADMIN_DISCOVERY))).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.armada.group.model.dto.GroupParticipantObservation>> facts =
                ArgumentCaptor.forClass(List.class);
        verify(observationService).apply(facts.capture());
        assertThat(facts.getValue()).singleElement().satisfies(fact -> {
            assertThat(fact.observerAccountId()).isEqualTo(901L);
            assertThat(fact.groupJid()).isEqualTo("120363group@g.us");
            assertThat(fact.admin()).isTrue();
            assertThat(fact.source().name()).isEqualTo("MEMBER_QUERY");
            assertThat(fact.sourceEventId())
                    .isEqualTo("event-701:8613800000902@s.whatsapp.net");
        });
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(
                observationService, executionMapper);
        order.verify(observationService).apply(any());
        order.verify(executionMapper).wakeForMemberQuery(any());
    }

    private PullTaskMemberQueryResultServiceImpl service() {
        return new PullTaskMemberQueryResultServiceImpl(
                queryMapper, executionMapper, new ObjectMapper(), dispatchTrigger,
                reconciliationScheduler, observationService);
    }

    private static PullTaskMemberQuery row(PullTaskMemberQueryPurpose purpose) {
        PullTaskMemberQuery row = new PullTaskMemberQuery();
        row.setId(701L);
        row.setTenantId(7L);
        row.setTaskId(100L);
        row.setGroupExecutionId(11L);
        row.setPurpose(purpose.name());
        row.setCommandId("cmd-query-2");
        row.setAccountId(901L);
        row.setProtocolAccountId("manager-901");
        row.setProtocolBackend("WEB");
        row.setGroupJid("120363group@g.us");
        row.setTargetJidsJson("[\"8613800000902@s.whatsapp.net\"]");
        row.setQueryStatus(PullTaskMemberQueryStatus.PENDING.code());
        row.setAttemptNo(2);
        return row;
    }

    private static PullTaskMemberQueryCallback callback(PullTaskMemberQueryOutcome outcome) {
        return callback(outcome, PullTaskMemberQueryPurpose.MANAGER_ADMIN_MEMBERSHIP);
    }

    private static PullTaskMemberQueryCallback callback(
            PullTaskMemberQueryOutcome outcome,
            PullTaskMemberQueryPurpose purpose) {
        return new PullTaskMemberQueryCallback(
                "event-701", 7L, 100L, 11L, 701L, purpose,
                901L, "manager-901", "WEB", "cmd-query-2", 2, outcome,
                "120363group@g.us",
                outcome == PullTaskMemberQueryOutcome.SUCCESS
                        ? List.of(new PullTaskMemberFact(
                        "8613800000902@s.whatsapp.net", "8613800000902@s.whatsapp.net",
                        "8613800000902", true, true))
                        : List.of(),
                outcome == PullTaskMemberQueryOutcome.SUCCESS ? "" : "MEMBER_QUERY_FAILED",
                outcome == PullTaskMemberQueryOutcome.SUCCESS ? "" : "failed",
                outcome == PullTaskMemberQueryOutcome.FAILED, 5_000L);
    }
}
