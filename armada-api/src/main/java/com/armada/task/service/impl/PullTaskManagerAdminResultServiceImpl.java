package com.armada.task.service.impl;

import com.armada.platform.protocol.util.WhatsappJids;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.model.dto.PullTaskManagerAdminCallback;
import com.armada.task.model.dto.PullTaskManagerJoinResultTransition;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionReasonCode;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAdminStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskManagerAdminProtocolOutcome;
import com.armada.task.scheduler.PullTaskExecutionDispatchProperties;
import com.armada.task.service.PullTaskManagerAdminResultService;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 以 commandId 和 attemptNo 收敛提权结果，并保持实时权限为最终事实。 */
@Service
public class PullTaskManagerAdminResultServiceImpl implements PullTaskManagerAdminResultService {

    private static final List<Integer> ACTION_OPEN = List.of(
            PullTaskActionStatus.SUBMITTED.code(), PullTaskActionStatus.UNKNOWN.code());
    private static final List<Integer> ADMIN_OPEN = List.of(
            PullTaskGroupAccountAdminStatus.PENDING.code(),
            PullTaskGroupAccountAdminStatus.SUBMITTED.code(),
            PullTaskGroupAccountAdminStatus.FAILED.code(),
            PullTaskGroupAccountAdminStatus.UNKNOWN.code());

    private final PullTaskAccountActionMapper actionMapper;
    private final PullTaskGroupAccountMapper accountMapper;
    private final PullTaskGroupExecutionMapper executionMapper;
    private final PullTaskExecutionDispatchProperties properties;

    /** 创建任务管理员提权结果状态机。 */
    public PullTaskManagerAdminResultServiceImpl(
            PullTaskAccountActionMapper actionMapper,
            PullTaskGroupAccountMapper accountMapper,
            PullTaskGroupExecutionMapper executionMapper,
            PullTaskExecutionDispatchProperties properties) {
        this.actionMapper = actionMapper;
        this.accountMapper = accountMapper;
        this.executionMapper = executionMapper;
        this.properties = properties;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean apply(PullTaskManagerAdminCallback callback) {
        Long previousTenant = TenantContext.get();
        TenantContext.set(callback.tenantId());
        try {
            PullTaskAccountAction action = actionMapper.selectByCommandId(callback.commandId());
            if (!matchesAction(action, callback)) {
                return false;
            }
            PullTaskGroupAccount actor = accountMapper.selectById(action.getActorGroupAccountId());
            PullTaskGroupAccount manager = accountMapper.selectById(action.getTargetGroupAccountId());
            PullTaskGroupExecution execution = executionMapper.selectById(callback.groupExecutionId());
            if (!matchesContext(action, actor, manager, execution, callback)) {
                return false;
            }
            ResultTarget target = resultTarget(callback);
            if (Objects.equals(action.getActionStatus(), target.actionStatus())) {
                return true;
            }
            int actionChanged = actionMapper.transitionManagerAdminResult(
                    action.getId(), callback.commandId(), callback.attemptNo(), ACTION_OPEN,
                    target.actionStatus(), target.retryable(), callback.reasonCode(),
                    target.actionReasonMessage(), callback.occurredAt());
            if (actionChanged != 1) {
                return false;
            }
            if (target.managerAdminStatus() != null
                    && accountMapper.transitionAdminStatus(
                    manager.getId(), ADMIN_OPEN, target.managerAdminStatus(),
                    callback.occurredAt()) != 1) {
                throw new IllegalStateException("任务管理员权限事实写入不完整");
            }
            PullTaskManagerJoinResultTransition transition = new PullTaskManagerJoinResultTransition(
                    execution.getId(), execution.getTaskId(), execution.getVersion(),
                    new PullTaskManagerJoinResultTransition.Expected(
                            PullTaskExecutionStatus.EXECUTING.code(),
                            PullTaskExecutionStage.MANAGER_ADMIN.code()),
                    new PullTaskManagerJoinResultTransition.Target(
                            PullTaskExecutionStatus.EXECUTING.code(),
                            PullTaskExecutionStage.MANAGER_ADMIN.code(),
                            null, null, target.executionReason().name(),
                            target.executionMessage(), target.nextRunAt(), null),
                    callback.occurredAt());
            if (executionMapper.transitionManagerJoinResult(transition) != 1) {
                throw new IllegalStateException("管理员设置执行行唤醒 CAS 失败");
            }
            return true;
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private ResultTarget resultTarget(PullTaskManagerAdminCallback callback) {
        if (callback.outcome() == PullTaskManagerAdminProtocolOutcome.SUCCESS) {
            return new ResultTarget(
                    PullTaskActionStatus.SUCCESS.code(), false, null, null,
                    PullTaskExecutionReasonCode.MANAGER_ADMIN_UNCONFIRMED,
                    PullTaskExecutionReasonCode.MANAGER_ADMIN_UNCONFIRMED.message(), 0L);
        }
        String safeMessage = safeReasonMessage(callback.reasonCode());
        if (callback.outcome() == PullTaskManagerAdminProtocolOutcome.UNKNOWN) {
            return new ResultTarget(
                    PullTaskActionStatus.UNKNOWN.code(), true, safeMessage,
                    PullTaskGroupAccountAdminStatus.UNKNOWN.code(),
                    PullTaskExecutionReasonCode.MANAGER_ADMIN_UNCONFIRMED,
                    safeMessage, callback.occurredAt() + properties.getRetryDelayMs());
        }
        boolean retryable = callback.retryable();
        return new ResultTarget(
                PullTaskActionStatus.FAILED.code(), retryable, safeMessage,
                retryable ? PullTaskGroupAccountAdminStatus.UNKNOWN.code()
                        : PullTaskGroupAccountAdminStatus.PENDING.code(),
                PullTaskExecutionReasonCode.MANAGER_ADMIN_SETUP_FAILED,
                safeMessage,
                retryable ? callback.occurredAt() + properties.getRetryDelayMs() : 0L);
    }

    private static boolean matchesAction(
            PullTaskAccountAction action,
            PullTaskManagerAdminCallback callback) {
        return action != null
                && Objects.equals(action.getId(), callback.actionId())
                && Objects.equals(action.getTaskId(), callback.pullTaskId())
                && Objects.equals(action.getGroupExecutionId(), callback.groupExecutionId())
                && Objects.equals(action.getActionType(),
                PullTaskAccountActionType.PROMOTE_MANAGER.code())
                && Objects.equals(action.getCommandId(), callback.commandId())
                && Objects.equals(action.getAttemptNo(), callback.attemptNo());
    }

    private static boolean matchesContext(
            PullTaskAccountAction action,
            PullTaskGroupAccount actor,
            PullTaskGroupAccount manager,
            PullTaskGroupExecution execution,
            PullTaskManagerAdminCallback callback) {
        return actor != null && manager != null && execution != null
                && Objects.equals(actor.getId(), action.getActorGroupAccountId())
                && Objects.equals(actor.getAccountId(), callback.accountId())
                && Objects.equals(actor.getRoleType(), PullTaskGroupAccountRole.PROMOTER.code())
                && Objects.equals(manager.getId(), action.getTargetGroupAccountId())
                && Objects.equals(manager.getRoleType(), PullTaskGroupAccountRole.MANAGER.code())
                && sameTask(actor, callback) && sameTask(manager, callback)
                && targetJid(manager, callback.targetJid())
                && Objects.equals(execution.getId(), callback.groupExecutionId())
                && Objects.equals(execution.getTaskId(), callback.pullTaskId())
                && Objects.equals(execution.getExecutionStatus(),
                PullTaskExecutionStatus.EXECUTING.code())
                && Objects.equals(execution.getStage(), PullTaskExecutionStage.MANAGER_ADMIN.code());
    }

    private static boolean sameTask(
            PullTaskGroupAccount account,
            PullTaskManagerAdminCallback callback) {
        return Objects.equals(account.getTaskId(), callback.pullTaskId())
                && Objects.equals(account.getGroupExecutionId(), callback.groupExecutionId());
    }

    private static boolean targetJid(PullTaskGroupAccount manager, String targetJid) {
        try {
            return targetJid != null
                    && WhatsappJids.userJid(manager.getAccountPhone())
                    .equalsIgnoreCase(targetJid.trim());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static String safeReasonMessage(String reasonCode) {
        return switch (reasonCode == null ? "" : reasonCode) {
            case "GROUP_PERMISSION_DENIED" -> "提权账号已无群管理员权限";
            case "RATE_LIMITED" -> "群操作触发限流，稍后重试";
            case "ACCOUNT_NOT_ONLINE" -> "提权账号当前离线";
            case "ACCOUNT_BUSY", "WORKER_BUSY" -> "提权账号当前繁忙，稍后重试";
            default -> "管理员设置暂时失败";
        };
    }

    private static void restoreTenant(Long tenantId) {
        if (tenantId == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(tenantId);
        }
    }

    private record ResultTarget(
            int actionStatus,
            boolean retryable,
            String actionReasonMessage,
            Integer managerAdminStatus,
            PullTaskExecutionReasonCode executionReason,
            String executionMessage,
            long nextRunAt) {
    }
}
