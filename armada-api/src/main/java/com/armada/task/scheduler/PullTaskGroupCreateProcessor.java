package com.armada.task.scheduler;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.result.GroupCreateResult;
import com.armada.platform.protocol.model.result.GroupInviteResult;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupCreateStep;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 新群模式建群阶段处理器；一次调度只推进一个持久化步骤。 */
@Component
public class PullTaskGroupCreateProcessor {

    private final PullTaskExecutionTransactionService executionTransactions;
    private final PullTaskGroupCreateTransactionService groupTransactions;
    private final PullTaskGroupCreateResources resources;
    private final PullTaskOperationDelayPolicy delayPolicy;
    private final PullTaskExecutionDispatchProperties properties;

    public PullTaskGroupCreateProcessor(
            PullTaskExecutionTransactionService executionTransactions,
            PullTaskGroupCreateTransactionService groupTransactions,
            PullTaskGroupCreateResources resources,
            PullTaskOperationDelayPolicy delayPolicy,
            PullTaskExecutionDispatchProperties properties) {
        this.executionTransactions = executionTransactions;
        this.groupTransactions = groupTransactions;
        this.resources = resources;
        this.delayPolicy = delayPolicy;
        this.properties = properties;
    }

    /** 在共享租约与并发槽位下推进一个建群步骤。 */
    public PullTaskExecutionDispatchResult process(
            PullTaskGroupExecution candidate,
            String lockOwner,
            long now) {
        Optional<com.armada.task.model.dto.PullTaskExecutionWork> prepared =
                executionTransactions.prepare(candidate, lockOwner, now);
        if (prepared.isEmpty()) {
            return PullTaskExecutionDispatchResult.LOST;
        }
        candidate.setVersion(prepared.get().expectedVersion());
        candidate.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        PullTaskGroupCreateStep step =
                PullTaskGroupCreateStep.fromNullable(candidate.getCreateStep());
        return switch (step) {
            case SELECT_ROLES -> groupTransactions.prepareRoles(
                    candidate, now, properties.getRetryDelayMs());
            case CREATE_GROUP -> createGroup(candidate, now);
            case PERSIST_CREATE_RESULT -> groupTransactions.haltUnconfirmed(
                    candidate, "建群结果保存步骤未完成", now);
            case APPLY_PROFILE -> groupTransactions.applyProfile(
                    candidate, PullTaskGroupCreateStep.CAPTURE_INVITE_LINK,
                    delayPolicy.nextSideEffectAt(now), now);
            case CAPTURE_INVITE_LINK -> captureInvite(candidate, now);
            case APPLY_BEFORE_PULL_SETTINGS -> groupTransactions.applyProfile(
                    candidate, PullTaskGroupCreateStep.REGISTER_GROUP,
                    delayPolicy.nextSideEffectAt(now), now);
            case REGISTER_GROUP -> groupTransactions.registerGroup(candidate, now);
        };
    }

    private PullTaskExecutionDispatchResult createGroup(
            PullTaskGroupExecution candidate,
            long now) {
        PullTaskGroupCreateTransactionService.GroupCreatePreparation preparation =
                groupTransactions.prepareCreate(
                        candidate, now, properties.getRetryDelayMs());
        if (!preparation.ready()) {
            return preparation.completedResult();
        }
        try {
            GroupCreateResult result =
                    resources.groupCreatePort().create(preparation.command());
            return groupTransactions.completeCreate(
                    candidate, result, delayPolicy.nextSideEffectAt(now), now);
        } catch (ProtocolException failure) {
            return groupTransactions.failCreate(
                    candidate, failure, properties.getRetryDelayMs(), now);
        } catch (RuntimeException failure) {
            ProtocolException unknown = new ProtocolException(
                    ProtocolErrorCode.GROUP_CREATE_RESULT_UNCONFIRMED,
                    "建群结果无法确认", failure);
            return groupTransactions.failCreate(
                    candidate, unknown, properties.getRetryDelayMs(), now);
        }
    }

    private PullTaskExecutionDispatchResult captureInvite(
            PullTaskGroupExecution candidate,
            long now) {
        PullTaskGroupCreateTransactionService.InvitePreparation preparation =
                groupTransactions.prepareInvite(
                        candidate, properties.getRetryDelayMs(), now);
        if (!preparation.ready()) {
            return preparation.completedResult();
        }
        try {
            GroupInviteResult result = resources.invitePort()
                    .getInvite(preparation.creator(), candidate.getGroupJid());
            return groupTransactions.completeInvite(
                    candidate, result, delayPolicy.nextSideEffectAt(now), now);
        } catch (RuntimeException failure) {
            return groupTransactions.deferInvite(
                    candidate, compact(failure), properties.getRetryDelayMs(), now);
        }
    }

    private static String compact(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable == null ? "未知错误" : throwable.getClass().getSimpleName();
        }
        return message.length() <= 160 ? message : message.substring(0, 160);
    }
}
