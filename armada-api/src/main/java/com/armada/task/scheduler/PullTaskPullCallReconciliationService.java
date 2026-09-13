package com.armada.task.scheduler;

import com.armada.task.model.dto.PullTaskUncertainParticipantSettlement;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.entity.PullTaskPullCallMemberAttempt;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskParticipantAttemptStatus;
import com.armada.task.model.enums.PullTaskParticipantExecutionState;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.model.enums.PullTaskRosterObservation;
import com.armada.task.service.impl.PullTaskPullCallParticipantResultService;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 新版逐号码批次在结果窗口结束后保留未知事实，不重放可能已生效的命令。 */
@Service
public class PullTaskPullCallReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(
            PullTaskPullCallReconciliationService.class);

    private final PullTaskUnknownResultResources resources;
    private final PullTaskPullCallParticipantResultService participantResultService;

    /** 创建逐号码异常批次核实服务。 */
    public PullTaskPullCallReconciliationService(
            PullTaskUnknownResultResources resources,
            PullTaskPullCallParticipantResultService participantResultService) {
        this.resources = resources;
        this.participantResultService = participantResultService;
    }

    /**
     * 释放一个已确认使用新版 attempt 台账且结果窗口已经结束的批次。
     */
    public PullTaskUnknownResultReconciliationStats reconcile(
            PullTaskGroupExecution execution,
            PullTaskPullCall call,
            List<PullTaskPullCallMemberAttempt> attempts,
            List<PullTaskGroupAccount> accounts,
            long cutoff,
            long now) {
        if (!Objects.equals(call.getCallStatus(), PullTaskPullCallStatus.SUBMITTED.code())
                || call.getSubmittedAt() == null) {
            return PullTaskUnknownResultReconciliationStats.empty();
        }
        List<PullTaskPullCallMemberAttempt> unresolved = attempts.stream()
                .filter(row -> Objects.equals(
                        row.getLifecycleStatus(), PullTaskParticipantAttemptStatus.SUBMITTED.code()))
                .filter(row -> row.getExecutionState()
                        != PullTaskParticipantExecutionState.NOT_STARTED)
                .toList();
        if (unresolved.isEmpty()) {
            return PullTaskUnknownResultReconciliationStats.empty();
        }
        if (call.getSubmittedAt() > cutoff
                && !unavailablePullerStillOwnsOpenAttempt(call, unresolved, accounts)) {
            return PullTaskUnknownResultReconciliationStats.empty();
        }
        int released = 0;
        for (PullTaskPullCallMemberAttempt attempt : unresolved) {
            PullTaskUncertainParticipantSettlement settlement =
                    new PullTaskUncertainParticipantSettlement(
                            new PullTaskUncertainParticipantSettlement.Context(
                                    execution.getTenantId(), call, execution),
                            attempt, PullTaskRosterObservation.UNCONFIRMED, now);
            try {
                if (participantResultService.settleUncertain(settlement)) {
                    released++;
                }
            } catch (RuntimeException error) {
                log.warn("event=pull_participant_reconciliation_failed tenantId={} taskId={} "
                                + "executionId={} callId={} attemptId={}",
                        execution.getTenantId(), execution.getTaskId(), execution.getId(),
                        call.getId(), attempt.getId(), error);
            }
        }
        log.info("event=pull_call_unconfirmed_settled tenantId={} taskId={} "
                        + "executionId={} waveId={} callId={} unresolvedCount={} settledCount={}",
                execution.getTenantId(), execution.getTaskId(), execution.getId(),
                call.getPullWaveId(), call.getId(), unresolved.size(), released);
        return new PullTaskUnknownResultReconciliationStats(0, released);
    }

    private static boolean unavailablePullerStillOwnsOpenAttempt(
            PullTaskPullCall call,
            List<PullTaskPullCallMemberAttempt> unresolved,
            List<PullTaskGroupAccount> accounts) {
        Long pullerId = call.getPullerGroupAccountId();
        if (pullerId == null || accounts.stream().noneMatch(account ->
                Objects.equals(account.getId(), pullerId)
                        && account.getAvailabilityStatus() != null
                        && !Objects.equals(account.getAvailabilityStatus(),
                        PullTaskGroupAccountAvailability.AVAILABLE.code()))) {
            return false;
        }
        return unresolved.stream().anyMatch(attempt ->
                Objects.equals(attempt.getPullerGroupAccountId(), pullerId));
    }

}
