package com.armada.task.service.impl;

import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.model.dto.PullTaskContactSaveCallback;
import com.armada.task.model.dto.PullTaskExecutionResultTransition;
import com.armada.task.model.dto.PullTaskFactResult;
import com.armada.task.model.dto.PullTaskFactTransition;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskContactSaveOutcome;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.scheduler.PullTaskOperationDelayPolicy;
import com.armada.task.service.PullTaskContactSaveResultService;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 以 commandId 回写联系人结果，并通过无租约 CAS 唤醒执行行。 */
@Service
public class PullTaskContactSaveResultServiceImpl implements PullTaskContactSaveResultService {

    private static final List<Integer> OPEN_STATUSES = List.of(
            PullTaskActionStatus.SUBMITTED.code(), PullTaskActionStatus.UNKNOWN.code());

    private final PullTaskAccountActionMapper actionMapper;
    private final PullTaskGroupAccountMapper accountMapper;
    private final PullTaskGroupExecutionMapper executionMapper;
    private final PullTaskOperationDelayPolicy delayPolicy;

    /**
     * 创建联系人结果状态机。
     *
     * @param actionMapper 账号动作 Mapper
     * @param accountMapper 角色账号 Mapper
     * @param executionMapper 执行行 Mapper
     */
    public PullTaskContactSaveResultServiceImpl(
            PullTaskAccountActionMapper actionMapper,
            PullTaskGroupAccountMapper accountMapper,
            PullTaskGroupExecutionMapper executionMapper,
            PullTaskOperationDelayPolicy delayPolicy) {
        this.actionMapper = actionMapper;
        this.accountMapper = accountMapper;
        this.executionMapper = executionMapper;
        this.delayPolicy = delayPolicy;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean apply(PullTaskContactSaveCallback callback) {
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
            ContactLane lane = contactLane(actor, target);
            if (!matchesContext(action, actor, target, execution, callback) || lane == null) {
                return false;
            }
            int targetStatus = switch (callback.outcome()) {
                case SUCCESS -> PullTaskActionStatus.SUCCESS.code();
                case FAILED -> PullTaskActionStatus.FAILED.code();
                case UNKNOWN -> PullTaskActionStatus.UNKNOWN.code();
            };
            WriteResult actionWrite = writeAction(action, callback, targetStatus);
            if (actionWrite == WriteResult.REJECTED) {
                return false;
            }
            if (actionWrite == WriteResult.ALREADY_TARGET) {
                return true;
            }
            int targetStage = targetStage(lane, action, targetStatus, callback.groupExecutionId());
            int executionWrite = executionMapper.transitionProtocolResult(
                    new PullTaskExecutionResultTransition(
                    execution.getId(), execution.getTaskId(), execution.getVersion(),
                    PullTaskExecutionStatus.EXECUTING.code(),
                    lane.expectedStage(),
                    targetStage, null,
                    delayPolicy.nextSideEffectAt(callback.occurredAt()),
                    callback.occurredAt()));
            if (executionWrite != 1 && actionWrite == WriteResult.UPDATED) {
                throw new IllegalStateException("联系人结果执行行唤醒 CAS 失败");
            }
            return true;
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private WriteResult writeAction(
            PullTaskAccountAction action,
            PullTaskContactSaveCallback callback,
            int targetStatus) {
        if (Objects.equals(action.getActionStatus(), targetStatus)) {
            return WriteResult.ALREADY_TARGET;
        }
        int changed = actionMapper.transitionResult(new PullTaskFactTransition(
                action.getId(), OPEN_STATUSES, targetStatus,
                PullTaskFactResult.reason(callback.reasonCode(), callback.reasonMessage()),
                callback.occurredAt()));
        return changed == 1 ? WriteResult.UPDATED : WriteResult.REJECTED;
    }

    private int targetStage(
            ContactLane lane,
            PullTaskAccountAction current,
            int targetStatus,
            long executionId) {
        if (lane == ContactLane.PULLER_STATION) {
            return PullTaskExecutionStage.PULL_EXECUTION.code();
        }
        List<PullTaskAccountAction> actions = actionMapper.selectByExecutionAndType(
                executionId, PullTaskAccountActionType.SAVE_CONTACT.code());
        boolean allTerminal = !actions.isEmpty() && actions.stream().allMatch(
                candidate -> terminalStatus(candidate, current.getId(), targetStatus));
        return allTerminal
                ? PullTaskExecutionStage.PULLER_INVITE.code()
                : PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code();
    }

    private static boolean matchesAction(
            PullTaskAccountAction action,
            PullTaskContactSaveCallback callback) {
        return action != null
                && callback.attemptNo() == 1
                && Objects.equals(action.getId(), callback.actionId())
                && Objects.equals(action.getTaskId(), callback.pullTaskId())
                && Objects.equals(action.getGroupExecutionId(), callback.groupExecutionId())
                && Objects.equals(action.getCommandId(), callback.commandId())
                && Objects.equals(action.getActionType(), PullTaskAccountActionType.SAVE_CONTACT.code());
    }

    private static boolean matchesContext(
            PullTaskAccountAction action,
            PullTaskGroupAccount actor,
            PullTaskGroupAccount target,
            PullTaskGroupExecution execution,
            PullTaskContactSaveCallback callback) {
        return actor != null && target != null && execution != null
                && Objects.equals(actor.getId(), action.getActorGroupAccountId())
                && Objects.equals(actor.getAccountId(), callback.accountId())
                && Objects.equals(actor.getTaskId(), callback.pullTaskId())
                && Objects.equals(actor.getGroupExecutionId(), callback.groupExecutionId())
                && Objects.equals(target.getId(), action.getTargetGroupAccountId())
                && Objects.equals(target.getTaskId(), callback.pullTaskId())
                && Objects.equals(target.getGroupExecutionId(), callback.groupExecutionId())
                && Objects.equals(execution.getId(), callback.groupExecutionId())
                && Objects.equals(execution.getTaskId(), callback.pullTaskId());
    }

    private static ContactLane contactLane(
            PullTaskGroupAccount actor, PullTaskGroupAccount target) {
        if (pair(actor, target,
                PullTaskGroupAccountRole.MANAGER, PullTaskGroupAccountRole.PULLER)) {
            return ContactLane.MANAGER_PULLER;
        }
        if (pair(actor, target,
                PullTaskGroupAccountRole.PULLER, PullTaskGroupAccountRole.STATION)) {
            return ContactLane.PULLER_STATION;
        }
        return null;
    }

    private static boolean pair(
            PullTaskGroupAccount first,
            PullTaskGroupAccount second,
            PullTaskGroupAccountRole left,
            PullTaskGroupAccountRole right) {
        return Objects.equals(first.getRoleType(), left.code())
                && Objects.equals(second.getRoleType(), right.code())
                || Objects.equals(first.getRoleType(), right.code())
                && Objects.equals(second.getRoleType(), left.code());
    }

    private static boolean terminalStatus(PullTaskAccountAction action, long currentId, int targetStatus) {
        Integer status = Objects.equals(action.getId(), currentId) ? targetStatus : action.getActionStatus();
        return status != null
                && status != PullTaskActionStatus.PENDING.code()
                && status != PullTaskActionStatus.SUBMITTED.code();
    }

    private static void restoreTenant(Long tenantId) {
        if (tenantId == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(tenantId);
        }
    }

    private enum ContactLane {
        MANAGER_PULLER(PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code()),
        PULLER_STATION(PullTaskExecutionStage.PULL_EXECUTION.code());

        private final int expectedStage;

        ContactLane(int expectedStage) {
            this.expectedStage = expectedStage;
        }

        int expectedStage() {
            return expectedStage;
        }
    }

    private enum WriteResult {
        ALREADY_TARGET,
        UPDATED,
        REJECTED
    }
}
