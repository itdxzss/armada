package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.vo.PullTaskExecutionObservationFact;
import com.armada.task.model.vo.PullTaskStandardExecutionAggregate;
import com.armada.task.service.impl.PullTaskExecutionObservation;
import org.junit.jupiter.api.Test;

class PullTaskExecutionObservationTest {
    private final PullTaskGroupExecution execution = execution();
    private final PullTaskExecutionObservationFact facts = facts();
    private final PullTaskStandardExecutionAggregate aggregate = new PullTaskStandardExecutionAggregate();

    @Test
    void intervalUsesDispatchEvidenceAndDoesNotRenameBusinessState() {
        facts.setWaveId(1L);
        facts.setWaveNo(3);
        facts.setWaveStatus(1);
        facts.setCallStatus(1);
        facts.setPlannedMaterialCount(12);
        facts.setBoundMaterialCount(12L);
        facts.setNextDispatchAt(9000L);
        var view = PullTaskExecutionObservation.describe(execution, aggregate, facts, 5000L);
        assertThat(view.state()).isEqualTo("INTERVAL");
        assertThat(view.nextDispatchAt()).isEqualTo(9000L);
        assertThat(view.waitStartedAt()).isNull();
        assertThat(execution.getExecutionStatus()).isEqualTo(2);
        assertThat(execution.getStage()).isEqualTo(6);
    }

    @Test
    void noCallbackStaysAwaitingResultWithoutBlamingWhatsappOrClaimingFailure() {
        facts.setCallStatus(2);
        facts.setSubmittedAt(1000L);
        var view = PullTaskExecutionObservation.describe(execution, aggregate, facts, 900_000L);
        assertThat(view.state()).isEqualTo("WAIT_RESULT");
        assertThat(view.waitStartedAt()).isEqualTo(1000L);
        assertThat(view.detail()).doesNotContain("WhatsApp 响应慢", "后台异常", "失败");
    }

    @Test
    void plannedCountMismatchIsEvidenceAndIsNeverAppliedToSubmittedCalls() {
        facts.setCallStatus(1);
        facts.setPlannedMaterialCount(13);
        facts.setBoundMaterialCount(12L);
        var view = PullTaskExecutionObservation.describe(execution, aggregate, facts, 5000L);
        assertThat(view.state()).isEqualTo("BATCH_INCONSISTENT");
        assertThat(view.detail()).contains("13", "12");
        facts.setCallStatus(2);
        facts.setSubmittedAt(1000L);
        assertThat(PullTaskExecutionObservation.describe(execution, aggregate, facts, 5000L).state())
                .isEqualTo("WAIT_RESULT");
    }

    @Test
    void resourceGapDoesNotInventWaitingStartFromUpdatedAt() {
        execution.setExecutionStatus(3);
        execution.setWaitResourceType(2);
        aggregate.setPlannedPullerCount(2);
        aggregate.setCurrentPullerCount(0);
        var view = PullTaskExecutionObservation.describe(execution, aggregate, facts, 5000L);
        assertThat(view.state()).isEqualTo("WAIT_RESOURCE");
        assertThat(view.nextStep()).contains("2", "拉手");
        assertThat(view.waitStartedAt()).isNull();
    }

    @Test
    void pauseAndTerminalOverrideOldWaveAndOutstandingResults() {
        facts.setCallStatus(2);
        facts.setSubmittedAt(1000L);
        facts.setNextDispatchAt(9000L);
        facts.setTaskStatus("PAUSED");
        var paused = PullTaskExecutionObservation.describe(execution, aggregate, facts, 5000L);
        assertThat(paused.state()).isEqualTo("PAUSED");
        assertThat(paused.nextCheckAt()).isNull();
        assertThat(paused.nextDispatchAt()).isNull();
        assertThat(paused.waitStartedAt()).isNull();
        execution.setExecutionStatus(4);
        aggregate.setUnknownMemberCount(12);
        var ended = PullTaskExecutionObservation.describe(execution, aggregate, facts, 5000L);
        assertThat(ended.state()).isEqualTo("FINISHED");
        assertThat(ended.nextStep()).contains("12", "核实");
        assertThat(ended.waveNo()).isNull();
    }

    @Test
    void missingFactsDoNotPretendExecutionIsHealthy() {
        execution.setNextRunAt(0L);
        assertThat(PullTaskExecutionObservation.describe(execution, aggregate, null, 5000L).state())
                .isEqualTo("UNOBSERVED");
        assertThat(PullTaskExecutionObservation.describe(execution, aggregate, facts, 5000L).state())
                .isEqualTo("READY");
    }

    private static PullTaskGroupExecution execution() {
        var row = new PullTaskGroupExecution();
        row.setExecutionStatus(2);
        row.setStage(6);
        row.setManualPaused(0);
        row.setNextRunAt(6000L);
        row.setUpdatedAt(4900L);
        row.setLastBusinessExecutedAt(4800L);
        return row;
    }

    @Test
    void permissionBlockIsNotMisreportedAsMissingAccounts() {
        execution.setExecutionStatus(3);
        execution.setWaitResourceType(1);
        execution.setReasonCode("MANAGER_ADMIN_SETUP_FAILED");
        aggregate.setRequiredManagerCount(1);
        aggregate.setCurrentManagerCount(0);
        assertThat(PullTaskExecutionObservation.describe(execution, aggregate, facts, 5000L).nextStep())
                .contains("权限").doesNotContain("补充");
    }

    @Test
    void futureSchedulerCheckIsNotCalledNormalPullIntervalWithoutWaveEvidence() {
        var view = PullTaskExecutionObservation.describe(execution, aggregate, facts, 5000L);
        assertThat(view.state()).isEqualTo("SCHEDULED");
        assertThat(view.nextCheckAt()).isEqualTo(6000L);
        assertThat(view.nextDispatchAt()).isNull();
    }

    @Test
    void commandEvidenceAndMissingWaveAreNeverShownAsOrdinaryUnsubmittedWork() {
        facts.setCallStatus(1);
        facts.setCommandId("already-enqueued");
        facts.setPlannedMaterialCount(13);
        facts.setBoundMaterialCount(12L);
        assertThat(PullTaskExecutionObservation.describe(execution, aggregate, facts, 5000L).state())
                .isEqualTo("WAIT_RESULT");
        execution.setActivePullWaveId(123L);
        assertThat(PullTaskExecutionObservation.describe(execution, aggregate, facts, 5000L).state())
                .isEqualTo("UNOBSERVED");
    }

    private static PullTaskExecutionObservationFact facts() {
        var row = new PullTaskExecutionObservationFact();
        row.setTaskStatus("EXECUTING");
        return row;
    }

    @Test
    void validLeaseShowsClaimEvidenceWithoutPretendingProtocolIsRunning() {
        execution.setLockOwner("worker-local");
        execution.setLockExpiresAt(9000L);
        var claimed = PullTaskExecutionObservation.describe(execution, aggregate, facts, 5000L);
        assertThat(claimed.state()).isEqualTo("CLAIMED");
        assertThat(claimed.detail()).contains("租约").doesNotContain("WhatsApp");
        assertThat(PullTaskExecutionObservation.describe(execution, aggregate, facts, 10000L).state())
                .isEqualTo("READY");
    }
}
