package com.armada.task.service.impl;

import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.model.dto.PullTaskFactResult;
import com.armada.task.model.dto.PullTaskFactTransition;
import com.armada.task.model.dto.PullTaskManagerJoinCallback;
import com.armada.task.model.dto.PullTaskManagerJoinResultTransition;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.JoinTaskFailureReason;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionReasonCode;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskManagerJoinProtocolOutcome;
import com.armada.task.model.enums.PullTaskWaitResourceType;
import com.armada.task.scheduler.PullTaskExecutionDispatchProperties;
import com.armada.task.scheduler.PullTaskParentCompletionService;
import com.armada.task.scheduler.PullTaskOperationDelayPolicy;
import com.armada.task.service.PullTaskManagerJoinResultService;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 以命令 ID 和执行行版本 CAS 收敛管理员踩链接结果。 */
@Service
public class PullTaskManagerJoinResultServiceImpl implements PullTaskManagerJoinResultService {

    private static final String PENDING_APPROVAL_MESSAGE =
            "管理员已提交入群申请，等待群主或管理员审批；该群拉群已暂停";
    private static final List<Integer> ACTION_OPEN = List.of(
            PullTaskActionStatus.SUBMITTED.code(), PullTaskActionStatus.UNKNOWN.code());
    private static final List<Integer> MEMBERSHIP_OPEN = List.of(
            PullTaskGroupAccountMembershipStatus.JOINING.code(),
            PullTaskGroupAccountMembershipStatus.UNKNOWN.code());
    private static final Set<String> EXECUTION_FAILURE_CODES = Set.of(
            "INVITE_INVALID", "INVITE_REVOKED", "INVALID_GROUP_LINK", "GROUP_UNAVAILABLE");
    private static final Set<String> MANAGER_FAILURE_CODES = Set.of(
            "ACCOUNT_NOT_FOUND", "ACCOUNT_NOT_ONLINE", "NEED_REAUTH",
            "ACCOUNT_REACHOUT_RESTRICTED", "GROUP_JOIN_REJECTED");

    private final PullTaskAccountActionMapper actionMapper;
    private final PullTaskGroupAccountMapper accountMapper;
    private final PullTaskGroupExecutionMapper executionMapper;
    private final PullTaskParentCompletionService completionService;
    private final PullTaskExecutionDispatchProperties properties;
    private final PullTaskOperationDelayPolicy delayPolicy;

    /**
     * 创建管理员踩链接结果状态机。
     *
     * @param actionMapper 账号动作 Mapper
     * @param accountMapper 角色账号 Mapper
     * @param executionMapper 执行行 Mapper
     * @param completionService 父任务终态聚合服务
     * @param properties 调度重试配置
     */
    public PullTaskManagerJoinResultServiceImpl(
            PullTaskAccountActionMapper actionMapper,
            PullTaskGroupAccountMapper accountMapper,
            PullTaskGroupExecutionMapper executionMapper,
            PullTaskParentCompletionService completionService,
            PullTaskExecutionDispatchProperties properties,
            PullTaskOperationDelayPolicy delayPolicy) {
        this.actionMapper = actionMapper;
        this.accountMapper = accountMapper;
        this.executionMapper = executionMapper;
        this.completionService = completionService;
        this.properties = properties;
        this.delayPolicy = delayPolicy;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Kafka 消费线程没有租户上下文，必须先恢复回调中的 tenantId。动作、角色和执行检查点在
     * 同一事务写入；重复或迟到结果只允许从相同 commandId 的开放状态收敛。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean apply(PullTaskManagerJoinCallback callback) {
        Long previousTenant = TenantContext.get();
        TenantContext.set(callback.tenantId());
        try {
            PullTaskAccountAction action = actionMapper.selectByCommandId(callback.commandId());
            if (!matches(action, callback)) {
                return false;
            }
            PullTaskGroupAccount manager = accountMapper.selectById(action.getTargetGroupAccountId());
            PullTaskGroupExecution execution = executionMapper.selectById(callback.groupExecutionId());
            if (!matches(manager, execution, callback)) {
                return false;
            }
            ResultKind kind = classify(callback);
            String reasonMessage = safeReasonMessage(callback, kind);
            WriteResult actionWrite = writeAction(action, callback, kind, reasonMessage);
            if (actionWrite == WriteResult.REJECTED) {
                return false;
            }
            WriteResult membershipWrite = writeMembership(
                    manager, callback, kind, reasonMessage);
            if (membershipWrite == WriteResult.REJECTED) {
                if (actionWrite == WriteResult.UPDATED) {
                    throw new IllegalStateException("管理员进群事实写入不完整");
                }
                return false;
            }
            if (actionWrite == WriteResult.ALREADY_TARGET
                    && membershipWrite == WriteResult.ALREADY_TARGET) {
                return true;
            }
            long nextRunAt = nextRunAt(callback, kind, properties.getRetryDelayMs());
            int advanced = executionMapper.transitionManagerJoinResult(
                    executionTransition(execution, callback, kind, reasonMessage,
                            nextRunAt));
            if (advanced == 1 && kind == ResultKind.EXECUTION_FAILED) {
                completionService.completeIfTerminalByExecutionId(execution.getId(), callback.occurredAt());
            }
            return true;
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private long nextRunAt(
            PullTaskManagerJoinCallback callback,
            ResultKind kind,
            long retryDelayMs) {
        if (kind == ResultKind.SUCCESS) {
            return delayPolicy.nextSideEffectAt(callback.occurredAt());
        }
        if (kind == ResultKind.UNKNOWN) {
            return delayPolicy.maxDeadline(
                    Math.addExact(callback.occurredAt(), retryDelayMs),
                    callback.occurredAt());
        }
        return 0L;
    }

    private WriteResult writeAction(
            PullTaskAccountAction action,
            PullTaskManagerJoinCallback callback,
            ResultKind kind,
            String reasonMessage) {
        int target = switch (kind) {
            case SUCCESS -> PullTaskActionStatus.SUCCESS.code();
            case MANAGER_FAILED, EXECUTION_FAILED -> PullTaskActionStatus.FAILED.code();
            case UNKNOWN -> PullTaskActionStatus.UNKNOWN.code();
            case PENDING_APPROVAL -> PullTaskActionStatus.PENDING_APPROVAL.code();
        };
        if (Objects.equals(action.getActionStatus(), target)) {
            return WriteResult.ALREADY_TARGET;
        }
        int updated = actionMapper.transitionResult(new PullTaskFactTransition(
                action.getId(), ACTION_OPEN, target,
                result(callback, reasonMessage), callback.occurredAt()));
        return updated == 1 ? WriteResult.UPDATED : WriteResult.REJECTED;
    }

    private WriteResult writeMembership(
            PullTaskGroupAccount manager,
            PullTaskManagerJoinCallback callback,
            ResultKind kind,
            String reasonMessage) {
        int target = switch (kind) {
            case SUCCESS -> PullTaskGroupAccountMembershipStatus.IN_GROUP.code();
            case MANAGER_FAILED, EXECUTION_FAILED -> PullTaskGroupAccountMembershipStatus.JOIN_FAILED.code();
            case UNKNOWN -> PullTaskGroupAccountMembershipStatus.UNKNOWN.code();
            case PENDING_APPROVAL -> PullTaskGroupAccountMembershipStatus.PENDING_APPROVAL.code();
        };
        if (Objects.equals(manager.getMembershipStatus(), target)) {
            return WriteResult.ALREADY_TARGET;
        }
        Long joinedAt = kind == ResultKind.SUCCESS ? callback.occurredAt() : null;
        int updated = accountMapper.transitionMembership(new PullTaskFactTransition(
                manager.getId(), MEMBERSHIP_OPEN, target,
                new PullTaskFactResult(callback.reasonCode(), reasonMessage,
                        null, joinedAt), callback.occurredAt()));
        return updated == 1 ? WriteResult.UPDATED : WriteResult.REJECTED;
    }

    private static PullTaskManagerJoinResultTransition executionTransition(
            PullTaskGroupExecution execution,
            PullTaskManagerJoinCallback callback,
            ResultKind kind,
            String reasonMessage,
            long nextRunAt) {
        PullTaskManagerJoinResultTransition.Target target = switch (kind) {
            case SUCCESS -> new PullTaskManagerJoinResultTransition.Target(
                    PullTaskExecutionStatus.EXECUTING.code(),
                    PullTaskExecutionStage.MANAGER_ADMIN.code(),
                    callback.groupJid(), null, null, null, nextRunAt, null);
            case EXECUTION_FAILED -> new PullTaskManagerJoinResultTransition.Target(
                    PullTaskExecutionStatus.FAILED.code(),
                    PullTaskExecutionStage.MANAGER_JOIN.code(),
                    null, null, callback.reasonCode(), reasonMessage,
                    0L, callback.occurredAt());
            case MANAGER_FAILED -> new PullTaskManagerJoinResultTransition.Target(
                    PullTaskExecutionStatus.WAIT_RESOURCE.code(),
                    PullTaskExecutionStage.MANAGER_JOIN.code(),
                    null, PullTaskWaitResourceType.MANAGER.code(),
                    callback.reasonCode(), reasonMessage, 0L, null);
            case PENDING_APPROVAL -> new PullTaskManagerJoinResultTransition.Target(
                    PullTaskExecutionStatus.WAIT_RESOURCE.code(),
                    PullTaskExecutionStage.MANAGER_JOIN.code(),
                    callback.groupJid(), PullTaskWaitResourceType.APPROVAL.code(),
                    PullTaskExecutionReasonCode.MANAGER_JOIN_PENDING_APPROVAL.name(),
                    reasonMessage, 0L, null);
            case UNKNOWN -> new PullTaskManagerJoinResultTransition.Target(
                    PullTaskExecutionStatus.EXECUTING.code(),
                    PullTaskExecutionStage.MANAGER_JOIN.code(),
                    callback.groupJid(), null, callback.reasonCode(), reasonMessage,
                    nextRunAt, null);
        };
        return new PullTaskManagerJoinResultTransition(
                execution.getId(), execution.getTaskId(), execution.getVersion(),
                new PullTaskManagerJoinResultTransition.Expected(
                        PullTaskExecutionStatus.EXECUTING.code(),
                        PullTaskExecutionStage.MANAGER_JOIN.code()),
                target, callback.occurredAt());
    }

    private static ResultKind classify(PullTaskManagerJoinCallback callback) {
        if (callback.outcome() == PullTaskManagerJoinProtocolOutcome.PENDING_APPROVAL) {
            return ResultKind.PENDING_APPROVAL;
        }
        if ((callback.outcome() == PullTaskManagerJoinProtocolOutcome.JOINED
                || callback.outcome() == PullTaskManagerJoinProtocolOutcome.ALREADY_JOINED)
                && callback.groupJid() != null && !callback.groupJid().isBlank()) {
            return ResultKind.SUCCESS;
        }
        if (callback.outcome() == PullTaskManagerJoinProtocolOutcome.FAILED
                && callback.reasonCode() != null
                && EXECUTION_FAILURE_CODES.contains(callback.reasonCode())) {
            return ResultKind.EXECUTION_FAILED;
        }
        if (callback.outcome() == PullTaskManagerJoinProtocolOutcome.FAILED
                && callback.reasonCode() != null
                && MANAGER_FAILURE_CODES.contains(callback.reasonCode())) {
            return ResultKind.MANAGER_FAILED;
        }
        return ResultKind.UNKNOWN;
    }

    private static boolean matches(
            PullTaskAccountAction action,
            PullTaskManagerJoinCallback callback) {
        return action != null
                && Objects.equals(action.getId(), callback.actionId())
                && Objects.equals(action.getTaskId(), callback.pullTaskId())
                && Objects.equals(action.getGroupExecutionId(), callback.groupExecutionId())
                && Objects.equals(action.getCommandId(), callback.commandId())
                && Objects.equals(action.getActionType(), PullTaskAccountActionType.JOIN_BY_LINK.code());
    }

    private static boolean matches(
            PullTaskGroupAccount manager,
            PullTaskGroupExecution execution,
            PullTaskManagerJoinCallback callback) {
        return manager != null && execution != null
                && Objects.equals(manager.getRoleType(), PullTaskGroupAccountRole.MANAGER.code())
                && Objects.equals(manager.getTaskId(), callback.pullTaskId())
                && Objects.equals(manager.getGroupExecutionId(), callback.groupExecutionId())
                && Objects.equals(execution.getId(), callback.groupExecutionId())
                && Objects.equals(execution.getTaskId(), callback.pullTaskId());
    }

    private static PullTaskFactResult result(
            PullTaskManagerJoinCallback callback, String reasonMessage) {
        return new PullTaskFactResult(
                callback.reasonCode(), reasonMessage,
                callback.groupJid(), callback.occurredAt());
    }

    private static String safeReasonMessage(
            PullTaskManagerJoinCallback callback, ResultKind kind) {
        if (kind == ResultKind.SUCCESS) {
            return null;
        }
        if (kind == ResultKind.PENDING_APPROVAL) {
            return PENDING_APPROVAL_MESSAGE;
        }
        if (callback.reasonCode() == null || callback.reasonCode().isBlank()) {
            return "进群结果暂未确认";
        }
        if ("GROUP_JOIN_UNKNOWN".equals(callback.reasonCode())) {
            return "进群结果暂未确认";
        }
        String label = JoinTaskFailureReason.labelOf(callback.reasonCode());
        return label.isBlank() ? "进群结果暂未确认" : label;
    }

    private static void restoreTenant(Long tenantId) {
        if (tenantId == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(tenantId);
        }
    }

    private enum ResultKind {
        SUCCESS,
        MANAGER_FAILED,
        EXECUTION_FAILED,
        PENDING_APPROVAL,
        UNKNOWN
    }

    private enum WriteResult {
        ALREADY_TARGET,
        UPDATED,
        REJECTED
    }
}
