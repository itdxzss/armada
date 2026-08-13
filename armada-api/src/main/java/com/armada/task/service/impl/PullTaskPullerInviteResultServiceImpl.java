package com.armada.task.service.impl;

import com.armada.platform.protocol.util.WhatsappJids;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.model.dto.PullTaskExecutionResultTransition;
import com.armada.task.model.dto.PullTaskFactResult;
import com.armada.task.model.dto.PullTaskFactTransition;
import com.armada.task.model.dto.PullTaskPullerInviteCallback;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskPullerInviteProtocolOutcome;
import com.armada.task.service.PullTaskPullerInviteResultService;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 以 commandId 原子回写邀请动作和拉手在群事实，并唤醒执行行。 */
@Service
public class PullTaskPullerInviteResultServiceImpl implements PullTaskPullerInviteResultService {

    private static final List<Integer> ACTION_OPEN = List.of(
            PullTaskActionStatus.SUBMITTED.code(), PullTaskActionStatus.UNKNOWN.code());
    private static final List<Integer> MEMBERSHIP_OPEN = List.of(
            PullTaskGroupAccountMembershipStatus.JOINING.code(),
            PullTaskGroupAccountMembershipStatus.UNKNOWN.code());

    private final PullTaskAccountActionMapper actionMapper;
    private final PullTaskGroupAccountMapper accountMapper;
    private final PullTaskGroupExecutionMapper executionMapper;
    /** 创建邀请结果状态机。 */
    public PullTaskPullerInviteResultServiceImpl(
            PullTaskAccountActionMapper actionMapper,
            PullTaskGroupAccountMapper accountMapper,
            PullTaskGroupExecutionMapper executionMapper) {
        this.actionMapper = actionMapper;
        this.accountMapper = accountMapper;
        this.executionMapper = executionMapper;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean apply(PullTaskPullerInviteCallback callback) {
        Long previousTenant = TenantContext.get();
        TenantContext.set(callback.tenantId());
        try {
            PullTaskAccountAction action = actionMapper.selectByCommandId(callback.commandId());
            if (!matchesAction(action, callback)) {
                return false;
            }
            PullTaskGroupAccount actor = accountMapper.selectById(action.getActorGroupAccountId());
            PullTaskGroupAccount target = accountMapper.selectById(action.getTargetGroupAccountId());
            PullTaskGroupExecution execution = executionMapper.selectById(callback.groupExecutionId());
            if (!matchesContext(action, actor, target, execution, callback)) {
                return false;
            }
            WriteResult actionWrite = writeAction(action, callback);
            if (actionWrite == WriteResult.REJECTED) {
                return false;
            }
            WriteResult membershipWrite = writeMembership(target, callback);
            if (membershipWrite == WriteResult.REJECTED) {
                if (actionWrite == WriteResult.UPDATED) {
                    throw new IllegalStateException("拉手邀请事实写入不完整");
                }
                return false;
            }
            if (actionWrite == WriteResult.ALREADY_TARGET
                    && membershipWrite == WriteResult.ALREADY_TARGET) {
                return true;
            }
            int executionWrite = executionMapper.transitionProtocolResult(
                    new PullTaskExecutionResultTransition(
                    execution.getId(), execution.getTaskId(), execution.getVersion(),
                    PullTaskExecutionStatus.EXECUTING.code(),
                    PullTaskExecutionStage.PULLER_INVITE.code(),
                    PullTaskExecutionStage.PULLER_INVITE.code(),
                    null, 0L,
                    callback.occurredAt()));
            if (executionWrite != 1
                    && (actionWrite == WriteResult.UPDATED
                    || membershipWrite == WriteResult.UPDATED)) {
                throw new IllegalStateException("拉手邀请执行行唤醒 CAS 失败");
            }
            return true;
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private WriteResult writeAction(
            PullTaskAccountAction action,
            PullTaskPullerInviteCallback callback) {
        int target = switch (callback.outcome()) {
            case SUCCESS -> PullTaskActionStatus.SUCCESS.code();
            case FAILED -> PullTaskActionStatus.FAILED.code();
            case UNKNOWN -> PullTaskActionStatus.UNKNOWN.code();
        };
        if (Objects.equals(action.getActionStatus(), target)) {
            return WriteResult.ALREADY_TARGET;
        }
        int changed = actionMapper.transitionResult(new PullTaskFactTransition(
                action.getId(), ACTION_OPEN, target,
                PullTaskFactResult.reason(callback.reasonCode(), callback.reasonMessage()),
                callback.occurredAt()));
        return changed == 1 ? WriteResult.UPDATED : WriteResult.REJECTED;
    }

    private WriteResult writeMembership(
            PullTaskGroupAccount targetAccount,
            PullTaskPullerInviteCallback callback) {
        int target = switch (callback.outcome()) {
            case SUCCESS -> PullTaskGroupAccountMembershipStatus.IN_GROUP.code();
            case FAILED -> PullTaskGroupAccountMembershipStatus.JOIN_FAILED.code();
            case UNKNOWN -> PullTaskGroupAccountMembershipStatus.UNKNOWN.code();
        };
        if (Objects.equals(targetAccount.getMembershipStatus(), target)) {
            return WriteResult.ALREADY_TARGET;
        }
        Long joinedAt = callback.outcome() == PullTaskPullerInviteProtocolOutcome.SUCCESS
                ? callback.occurredAt() : null;
        int changed = accountMapper.transitionMembership(new PullTaskFactTransition(
                targetAccount.getId(), MEMBERSHIP_OPEN, target,
                new PullTaskFactResult(callback.reasonCode(), callback.reasonMessage(), null, joinedAt),
                callback.occurredAt()));
        return changed == 1 ? WriteResult.UPDATED : WriteResult.REJECTED;
    }

    private static boolean matchesAction(
            PullTaskAccountAction action,
            PullTaskPullerInviteCallback callback) {
        return action != null
                && callback.attemptNo() == 1
                && Objects.equals(action.getId(), callback.actionId())
                && Objects.equals(action.getTaskId(), callback.pullTaskId())
                && Objects.equals(action.getGroupExecutionId(), callback.groupExecutionId())
                && Objects.equals(action.getCommandId(), callback.commandId())
                && Objects.equals(action.getActionType(), PullTaskAccountActionType.INVITE_TO_GROUP.code());
    }

    private static boolean matchesContext(
            PullTaskAccountAction action,
            PullTaskGroupAccount actor,
            PullTaskGroupAccount target,
            PullTaskGroupExecution execution,
            PullTaskPullerInviteCallback callback) {
        return actor != null && target != null && execution != null
                && Objects.equals(actor.getId(), action.getActorGroupAccountId())
                && Objects.equals(actor.getAccountId(), callback.accountId())
                && sameTask(actor, callback) && sameTask(target, callback)
                && targetJid(target, callback.targetJid())
                && Objects.equals(execution.getId(), callback.groupExecutionId())
                && Objects.equals(execution.getTaskId(), callback.pullTaskId());
    }

    private static boolean sameTask(
            PullTaskGroupAccount account,
            PullTaskPullerInviteCallback callback) {
        return Objects.equals(account.getTaskId(), callback.pullTaskId())
                && Objects.equals(account.getGroupExecutionId(), callback.groupExecutionId());
    }

    private static boolean targetJid(PullTaskGroupAccount target, String targetJid) {
        try {
            return targetJid != null
                    && WhatsappJids.userJid(target.getAccountPhone()).equalsIgnoreCase(targetJid.trim());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static void restoreTenant(Long tenantId) {
        if (tenantId == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(tenantId);
        }
    }

    private enum WriteResult {
        ALREADY_TARGET,
        UPDATED,
        REJECTED
    }
}
