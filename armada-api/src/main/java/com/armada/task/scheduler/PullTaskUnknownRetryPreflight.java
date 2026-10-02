package com.armada.task.scheduler;

import com.armada.task.model.dto.PullTaskUncertainParticipantSettlement;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.entity.PullTaskPullCallMemberAttempt;
import com.armada.task.model.enums.PullTaskParticipantAttemptStatus;
import com.armada.task.model.enums.PullTaskRosterObservation;
import com.armada.task.service.impl.PullTaskPullCallParticipantResultService;
import com.armada.task.service.impl.PullTaskUnknownParticipantRecovery;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 补拉提交前再次核对本地入群事实；不发协议查询，不处理普通首轮计划。 */
@Service
public class PullTaskUnknownRetryPreflight {
    private final PullTaskUnknownResultResources resources;
    private final PullTaskPullCallParticipantResultService results;

    public PullTaskUnknownRetryPreflight(PullTaskUnknownResultResources resources,
            PullTaskPullCallParticipantResultService results) {
        this.resources = resources;
        this.results = results;
    }

    /** 新补拉尚未提交时再核对原未知的入群事实，迟到成功会移除新计划中的同一号码。 */
    @Transactional(rollbackFor = Exception.class)
    public void confirmBeforeRetry(PullTaskGroupExecution execution, PullTaskPullCall planned, long now) {
        List<PullTaskPullCallMemberAttempt> targets = resources.attemptMapper()
                .selectByCallAndStatus(planned.getId(), PullTaskParticipantAttemptStatus.PLANNED.code());
        targets = targets.stream().filter(target -> target.getAttemptNo() > 1).toList();
        if (targets.isEmpty()) {
            return;
        }
        List<PullTaskPullCall> calls = resources.callMapper().selectByExecution(execution.getId());
        for (PullTaskPullCallMemberAttempt target : targets) {
            for (PullTaskPullCallMemberAttempt previous : resources.attemptMapper().selectParticipantHistory(
                    execution.getId(), target.getParticipantType(), target.getParticipantRefId())) {
                if (!PullTaskUnknownParticipantRecovery.RETRY_REASON.equals(previous.getReasonCode())) {
                    continue;
                }
                calls.stream().filter(call -> Objects.equals(call.getId(), previous.getPullCallId()))
                        .findFirst().ifPresent(call -> results.confirmLocalJoin(new PullTaskUncertainParticipantSettlement(
                                new PullTaskUncertainParticipantSettlement.Context(
                                        execution.getTenantId(), call, execution),
                                previous, PullTaskRosterObservation.UNCONFIRMED, now)));
            }
        }
    }

}
