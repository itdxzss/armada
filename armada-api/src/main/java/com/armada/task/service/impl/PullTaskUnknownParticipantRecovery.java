package com.armada.task.service.impl;

import com.armada.group.service.WhatsappGroupMemberJoinFactService;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskPullCallMemberAttemptMapper;
import com.armada.task.model.dto.PullTaskFactResult;
import com.armada.task.model.dto.PullTaskBatchParticipantCallback;
import com.armada.task.model.enums.PullTaskBatchParticipantProtocolOutcome;
import com.armada.task.model.enums.PullTaskParticipantExecutionState;
import com.armada.task.model.enums.PullTaskRosterObservation;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskPullCallMemberAttempt;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskParticipantAttemptStatus;
import com.armada.task.service.PullTaskRetryPolicy;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 只读取本地入群事实与持久化尝试历史，未知最多立即换号补拉一次。 */
@Service
public class PullTaskUnknownParticipantRecovery {

    /** 原未知尝试的持久化补拉授权标记；迟到非成功结果不得覆盖。 */
    public static final String RETRY_REASON = "UNKNOWN_RESULT_RETRY_ONCE";
    public static final String RETRY_MESSAGE = "结果未确认，立即换拉手补拉一次";
    public static final String EXHAUSTED_MESSAGE = "已补拉一次，仍未收到确认结果，不再自动补拉";
    public static final String FACT_REASON = "GROUP_JOIN_FACT_CONFIRMED";

    private static final String EXECUTING = "EXECUTING";

    private final WhatsappGroupMemberJoinFactService joins;
    private final PullTaskPullCallMemberAttemptMapper attempts;
    private final PullTaskMapper tasks;

    public PullTaskUnknownParticipantRecovery(
            WhatsappGroupMemberJoinFactService joins,
            PullTaskPullCallMemberAttemptMapper attempts,
            PullTaskMapper tasks) {
        this.joins = joins;
        this.attempts = attempts;
        this.tasks = tasks;
    }

    /** 仅认可同租户、同群、可信号码及本执行时间窗内的 ADD；后来退群不撤销入群成功。 */
    public Optional<PullTaskFactResult> confirmedJoin(
            PullTaskGroupExecution execution, PullTaskPullCallMemberAttempt attempt, long now) {
        if (!Objects.equals(TenantContext.get(), execution.getTenantId())
                || !Objects.equals(execution.getId(), attempt.getGroupExecutionId())
                || execution.getGroupJid() == null || attempt.getSubmittedAt() == null) {
            return Optional.empty();
        }
        long since = history(attempt).stream()
                .map(PullTaskPullCallMemberAttempt::getSubmittedAt)
                .filter(Objects::nonNull).min(Long::compareTo).orElse(attempt.getSubmittedAt());
        long until = execution.getFinishedAt() == null ? now : Math.min(now, execution.getFinishedAt());
        if (until < since) {
            return Optional.empty();
        }
        return joins.findRecentJoin(execution.getTenantId(), execution.getGroupJid(),
                        attempt.getTargetPhone(), since, until)
                .map(fact -> new PullTaskFactResult(FACT_REASON,
                        "已根据群成员入群事件确认成功", attempt.getTargetJid(), fact.joinedAt()));
    }

    /** 保留总尝试预算；停止、暂停和历史已关闭的未知只允许纠正事实，不自动恢复执行。 */
    public boolean canRetryUnknown(
            PullTaskGroupExecution execution, PullTaskPullCallMemberAttempt attempt) {
        if (!Objects.equals(attempt.getLifecycleStatus(), PullTaskParticipantAttemptStatus.SUBMITTED.code())
                || attempt.getAttemptNo() == null || !PullTaskRetryPolicy.canRetry(attempt.getAttemptNo())
                || !Objects.equals(execution.getStage(), PullTaskExecutionStage.PULL_EXECUTION.code())
                || !List.of(PullTaskExecutionStatus.EXECUTING.code(),
                        PullTaskExecutionStatus.WAIT_RESOURCE.code()).contains(execution.getExecutionStatus())
                || Objects.equals(execution.getManualPaused(), 1)
                || hasUsedRetry(attempt)) {
            return false;
        }
        PullTask parent = tasks.selectLifecycle(execution.getTaskId());
        return parent != null && EXECUTING.equals(parent.getStatus())
                && Objects.equals(parent.getTenantId(), execution.getTenantId());
    }

    /** 前驱授权过一次未知补拉后，后继不再因超时、限频或未知重复申请补拉。 */
    public boolean hasUsedRetry(PullTaskPullCallMemberAttempt attempt) {
        return history(attempt).stream().anyMatch(row -> row.getAttemptNo() < attempt.getAttemptNo()
                && RETRY_REASON.equals(row.getReasonCode()));
    }

    /** 入群事实优先，首次不确定回执立即释放一次补拉；原回执仍用于账号风控处理。 */
    public PullTaskBatchParticipantCallback resolve(
            PullTaskGroupExecution execution, PullTaskPullCallMemberAttempt attempt,
            PullTaskBatchParticipantCallback callback) {
        if (callback.outcome() != PullTaskBatchParticipantProtocolOutcome.UNKNOWN) {
            return callback;
        }
        java.util.Optional<PullTaskFactResult> join = this
                .confirmedJoin(execution, attempt, callback.occurredAt());
        if (join.isPresent()) {
            return resolvedCallback(callback, PullTaskBatchParticipantProtocolOutcome.SUCCESS,
                    join.get());
        }
        if (callback.executionState() == PullTaskParticipantExecutionState.UNCERTAIN
                && canRetryUnknown(execution, attempt)) {
            return resolvedCallback(callback, callback.outcome(), new PullTaskFactResult(
                    PullTaskUnknownParticipantRecovery.RETRY_REASON,
                    PullTaskUnknownParticipantRecovery.RETRY_MESSAGE + "；原原因："
                            + String.valueOf(callback.reasonCode()), null, callback.occurredAt()));
        }
        if (hasUsedRetry(attempt)) {
            return resolvedCallback(callback, callback.outcome(), new PullTaskFactResult(
                    callback.reasonCode(), PullTaskUnknownParticipantRecovery.EXHAUSTED_MESSAGE,
                    null, callback.occurredAt()));
        }
        return callback;
    }

    private static PullTaskBatchParticipantCallback resolvedCallback(
            PullTaskBatchParticipantCallback original, PullTaskBatchParticipantProtocolOutcome outcome,
            PullTaskFactResult fact) {
        return new PullTaskBatchParticipantCallback(original.tenantId(), original.pullTaskId(),
                original.groupExecutionId(), original.pullCallId(), original.accountId(),
                original.protocolAccountId(), original.commandId(), original.attemptNo(),
                original.targetJid(), outcome,
                outcome == PullTaskBatchParticipantProtocolOutcome.SUCCESS
                        ? PullTaskParticipantExecutionState.STARTED : original.executionState(),
                fact.reasonCode(), fact.reasonMessage(), false, original.occurredAt());
    }

    /** 将本地核实/有界补拉结论转成持久化原因。 */
    public static PullTaskFactResult rosterFact(
            PullTaskRosterObservation observation,
            long now) {
        return switch (observation) {
            case ABSENT -> new PullTaskFactResult(
                    "ROSTER_NOT_PRESENT", "群成员名单未确认该号码在群", null, now);
            case UNCONFIRMED_RETRY -> new PullTaskFactResult(
                    PullTaskUnknownParticipantRecovery.RETRY_REASON,
                    PullTaskUnknownParticipantRecovery.RETRY_MESSAGE, null, now);
            case UNCONFIRMED -> new PullTaskFactResult(
                    "PROTOCOL_RESULT_UNCONFIRMED", "未收到确认结果，补拉预算已用尽或任务已停止", null, now);
            case UNAVAILABLE -> new PullTaskFactResult(
                    "ROSTER_QUERY_UNAVAILABLE", "群成员名单查询不可用，结果保持未知", null, now);
            case PRESENT -> throw new IllegalArgumentException("已确认成员不能生成失败事实");
        };
    }

    private List<PullTaskPullCallMemberAttempt> history(PullTaskPullCallMemberAttempt attempt) {
        return attempts.selectParticipantHistory(attempt.getGroupExecutionId(),
                attempt.getParticipantType(), attempt.getParticipantRefId());
    }
}
