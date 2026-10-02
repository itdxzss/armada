package com.armada.task.scheduler;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.result.GroupCreateResult;
import com.armada.platform.protocol.model.result.GroupInviteResult;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupCreateStep;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 新群模式建群阶段处理器；一次调度只推进一个持久化步骤。 */
@Component
public class PullTaskGroupCreateProcessor {
    private static final Logger log = LoggerFactory.getLogger(PullTaskGroupCreateProcessor.class);

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
            case APPLY_PROFILE -> applyProfile(candidate, now);
            case CAPTURE_INVITE_LINK -> captureInvite(candidate, now);
            case APPLY_BEFORE_PULL_SETTINGS -> applyProfile(candidate, now);
            case REGISTER_GROUP -> groupTransactions.registerGroup(candidate, now);
        };
    }

    private PullTaskExecutionDispatchResult applyProfile(PullTaskGroupExecution candidate, long now) {
        var prepared = groupTransactions.prepareProfile(candidate, properties.getRetryDelayMs(), now);
        if (!prepared.ready()) {
            return prepared.completedResult();
        }
        GroupMetadataResult metadata = null;
        long startedNanos = System.nanoTime();
        try {
            // 真实群资料读取不占数据库事务；完成方法再次校验动作身份与执行行版本。
            metadata = resources.metadataPort().getMetadata(prepared.account(), candidate.getGroupJid());
        } catch (RuntimeException unavailable) {
            // 查询失败不等于资料失败，更不能当成功；完成方法按截止时间等待或暂停。
            log.warn("event=new_group_profile_verification_unavailable executionId={} commandId={} errorType={}",
                    candidate.getId(), prepared.commandId(), unavailable.getClass().getSimpleName());
        }
        long completedAt = now + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        return groupTransactions.completeProfile(candidate, prepared, metadata,
                delayPolicy.nextSideEffectAt(completedAt), completedAt);
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
