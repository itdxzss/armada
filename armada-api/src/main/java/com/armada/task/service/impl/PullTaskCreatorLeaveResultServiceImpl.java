package com.armada.task.service.impl;

import com.armada.platform.protocol.util.WhatsappJids;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.model.dto.PullTaskCreatorLeaveCallback;
import com.armada.task.model.dto.PullTaskExecutionResultTransition;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskCreatorLeaveOperation;
import com.armada.task.model.enums.PullTaskCreatorLeaveProtocolOutcome;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.service.PullTaskCreatorLeaveResultService;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 以 commandId 和 attemptNo 收敛群主退群动作，并唤醒 CLOSING 执行行。 */
@Service
public class PullTaskCreatorLeaveResultServiceImpl implements PullTaskCreatorLeaveResultService {

    private static final List<Integer> ACTION_OPEN = List.of(
            PullTaskActionStatus.SUBMITTED.code(),
            PullTaskActionStatus.UNKNOWN.code());

    private final PullTaskAccountActionMapper actionMapper;
    private final PullTaskGroupAccountMapper accountMapper;
    private final PullTaskGroupExecutionMapper executionMapper;

    /** 创建群主退群结果状态机。 */
    public PullTaskCreatorLeaveResultServiceImpl(
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
    public boolean apply(PullTaskCreatorLeaveCallback callback) {
        Long previousTenant = TenantContext.get();
        TenantContext.set(callback.tenantId());
        try {
            PullTaskAccountAction action = actionMapper.selectByCommandId(callback.commandId());
            PullTaskGroupAccount owner = action == null ? null
                    : accountMapper.selectById(action.getActorGroupAccountId());
            PullTaskGroupAccount target = action == null ? null
                    : accountMapper.selectById(action.getTargetGroupAccountId());
            PullTaskGroupExecution execution = executionMapper.selectById(callback.groupExecutionId());
            if (!matches(action, owner, target, execution, callback)) {
                return false;
            }
            long occurredAt = callback.occurredAt() > 0
                    ? callback.occurredAt() : System.currentTimeMillis();
            ResultTarget result = result(callback);
            if (!Objects.equals(action.getActionStatus(), result.status())
                    && actionMapper.transitionManagerAdminResult(
                    action.getId(), callback.commandId(), callback.attemptNo(), ACTION_OPEN,
                    result.status(), false, callback.reasonCode(), result.message(), occurredAt) != 1) {
                return false;
            }
            executionMapper.transitionProtocolResult(new PullTaskExecutionResultTransition(
                    execution.getId(), execution.getTaskId(), execution.getVersion(),
                    PullTaskExecutionStatus.EXECUTING.code(), PullTaskExecutionStage.CLOSING.code(),
                    PullTaskExecutionStage.CLOSING.code(), null, 0L, occurredAt));
            return true;
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private static boolean matches(
            PullTaskAccountAction action,
            PullTaskGroupAccount owner,
            PullTaskGroupAccount target,
            PullTaskGroupExecution execution,
            PullTaskCreatorLeaveCallback callback) {
        PullTaskAccountActionType expectedType = callback.operation()
                == PullTaskCreatorLeaveOperation.PROMOTE
                ? PullTaskAccountActionType.PROMOTE_CREATOR_SUCCESSOR
                : PullTaskAccountActionType.CREATOR_LEAVE;
        return action != null && owner != null && target != null && execution != null
                && Objects.equals(action.getId(), callback.actionId())
                && Objects.equals(action.getTaskId(), callback.pullTaskId())
                && Objects.equals(action.getGroupExecutionId(), callback.groupExecutionId())
                && Objects.equals(action.getActionType(), expectedType.code())
                && Objects.equals(action.getCommandId(), callback.commandId())
                && Objects.equals(action.getAttemptNo(), callback.attemptNo())
                && Objects.equals(owner.getId(), action.getActorGroupAccountId())
                && Objects.equals(owner.getAccountId(), callback.accountId())
                && Objects.equals(owner.getRoleType(), PullTaskGroupAccountRole.PROMOTER.code())
                && Objects.equals(execution.getId(), callback.groupExecutionId())
                && Objects.equals(execution.getTaskId(), callback.pullTaskId())
                && Objects.equals(execution.getExecutionStatus(), PullTaskExecutionStatus.EXECUTING.code())
                && Objects.equals(execution.getStage(), PullTaskExecutionStage.CLOSING.code())
                && validTarget(action, target, callback);
    }

    private static boolean validTarget(
            PullTaskAccountAction action,
            PullTaskGroupAccount target,
            PullTaskCreatorLeaveCallback callback) {
        if (callback.operation() == PullTaskCreatorLeaveOperation.LEAVE) {
            return Objects.equals(action.getActorGroupAccountId(), action.getTargetGroupAccountId())
                    && Objects.equals(target.getRoleType(), PullTaskGroupAccountRole.PROMOTER.code());
        }
        if (!Objects.equals(target.getRoleType(), PullTaskGroupAccountRole.CONTROLLER.code())) {
            return false;
        }
        try {
            return callback.targetJid() != null
                    && WhatsappJids.userJid(target.getAccountPhone())
                    .equalsIgnoreCase(callback.targetJid().trim());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static ResultTarget result(PullTaskCreatorLeaveCallback callback) {
        return switch (callback.outcome()) {
            case SUCCESS -> new ResultTarget(PullTaskActionStatus.SUCCESS.code(), null);
            case FAILED -> new ResultTarget(
                    PullTaskActionStatus.FAILED.code(), safeMessage(callback.operation()));
            case UNKNOWN -> new ResultTarget(
                    PullTaskActionStatus.UNKNOWN.code(), safeMessage(callback.operation()));
        };
    }

    private static String safeMessage(PullTaskCreatorLeaveOperation operation) {
        return operation == PullTaskCreatorLeaveOperation.PROMOTE
                ? "控端成员管理员设置失败" : "建群者退群失败";
    }

    private static void restoreTenant(Long tenantId) {
        if (tenantId == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(tenantId);
        }
    }

    private record ResultTarget(int status, String message) {
    }
}
