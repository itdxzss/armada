package com.armada.task.service.impl;

import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.group.service.GroupInviteLinkService;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.model.dto.PullTaskFactResult;
import com.armada.task.model.dto.PullTaskFactTransition;
import com.armada.task.model.dto.PullTaskExecutionResultTransition;
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
import com.armada.task.scheduler.PullTaskOfflineRoleWaitProperties;
import com.armada.task.service.PullTaskManagerJoinResultService;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 以命令 ID 和执行行版本 CAS 收敛普通拉群账号踩链接结果。 */
@Service
public class PullTaskManagerJoinResultServiceImpl implements PullTaskManagerJoinResultService {

    private static final String PENDING_APPROVAL_MESSAGE =
            "管理员已提交入群申请，等待群主或管理员审批；该群拉群已暂停";
    private static final List<Integer> ACTION_OPEN = List.of(
            PullTaskActionStatus.SUBMITTED.code(), PullTaskActionStatus.UNKNOWN.code());
    private static final List<Integer> DIRECT_ACTION_OPEN = List.of(
            PullTaskActionStatus.SUBMITTED.code(), PullTaskActionStatus.UNKNOWN.code(),
            PullTaskActionStatus.PENDING_APPROVAL.code());
    private static final List<Integer> MEMBERSHIP_OPEN = List.of(
            PullTaskGroupAccountMembershipStatus.JOINING.code(),
            PullTaskGroupAccountMembershipStatus.UNKNOWN.code());
    private static final List<Integer> DIRECT_MEMBERSHIP_OPEN = List.of(
            PullTaskGroupAccountMembershipStatus.JOINING.code(),
            PullTaskGroupAccountMembershipStatus.UNKNOWN.code(),
            PullTaskGroupAccountMembershipStatus.PENDING_APPROVAL.code());
    private static final Set<String> EXECUTION_FAILURE_CODES = Set.of(
            "INVITE_INVALID", "INVITE_REVOKED", "INVALID_GROUP_LINK", "GROUP_UNAVAILABLE",
            "GROUP_BANNED", "GROUP_FULL");
    private static final Set<String> RECOVERABLE_INVITE_FAILURE_CODES = Set.of(
            "INVITE_INVALID", "INVITE_REVOKED");
    private static final Set<String> MANAGER_FAILURE_CODES = Set.of(
            "ACCOUNT_NOT_FOUND", "ACCOUNT_NOT_ONLINE", "NEED_REAUTH",
            "ACCOUNT_REACHOUT_RESTRICTED", "GROUP_JOIN_REJECTED");

    private final PullTaskAccountActionMapper actionMapper;
    private final PullTaskGroupAccountMapper accountMapper;
    private final PullTaskGroupExecutionMapper executionMapper;
    private final PullTaskParentCompletionService completionService;
    private final PullTaskExecutionDispatchProperties properties;
    private final PullTaskOperationDelayPolicy delayPolicy;
    private final GroupInviteLinkService inviteLinkService;
    /** 明确离线拒绝保留原角色及待执行动作的任务侧开关。 */
    private final PullTaskOfflineRoleWaitProperties offlineWaitProperties;

    /**
     * 创建管理员踩链接结果状态机。
     *
     * @param actionMapper 账号动作 Mapper
     * @param accountMapper 角色账号 Mapper
     * @param executionMapper 执行行 Mapper
     * @param completionService 父任务终态聚合服务
     * @param properties 调度重试配置
     * @param delayPolicy 协议动作间隔策略
     * @param inviteLinkService 当前群邀请链接事实服务
     * @param offlineWaitProperties 任务角色离线等待开关
     */
    public PullTaskManagerJoinResultServiceImpl(
            PullTaskAccountActionMapper actionMapper,
            PullTaskGroupAccountMapper accountMapper,
            PullTaskGroupExecutionMapper executionMapper,
            PullTaskParentCompletionService completionService,
            PullTaskExecutionDispatchProperties properties,
            PullTaskOperationDelayPolicy delayPolicy,
            GroupInviteLinkService inviteLinkService,
            PullTaskOfflineRoleWaitProperties offlineWaitProperties) {
        this.actionMapper = actionMapper;
        this.accountMapper = accountMapper;
        this.executionMapper = executionMapper;
        this.completionService = completionService;
        this.properties = properties;
        this.delayPolicy = delayPolicy;
        this.inviteLinkService = inviteLinkService;
        this.offlineWaitProperties = offlineWaitProperties;
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
            PullTaskGroupAccount account = accountMapper.selectById(action.getTargetGroupAccountId());
            PullTaskGroupExecution execution = executionMapper.selectById(callback.groupExecutionId());
            if (!matches(action, account, execution, callback)) {
                return false;
            }
            boolean puller = Objects.equals(
                    account.getRoleType(), PullTaskGroupAccountRole.PULLER.code());
            boolean directPuller = puller && Objects.equals(
                    execution.getStage(), PullTaskExecutionStage.DIRECT_PULLER_JOIN.code());
            // 已知审批等待交给持租约调度器收尾；旧群不因迟到批准或重复回调重新推进。
            if (directPuller && (Objects.equals(action.getActionStatus(), PullTaskActionStatus.PENDING_APPROVAL.code())
                    || Objects.equals(execution.getReasonCode(),
                    PullTaskExecutionReasonCode.GROUP_JOIN_APPROVAL_REQUIRED.name()))) {
                return false;
            }
            if (offlineWaitProperties.isEnabled()
                    && callback.outcome() == PullTaskManagerJoinProtocolOutcome.FAILED
                    && PullTaskExecutionReasonCode.ACCOUNT_NOT_ONLINE.name().equals(callback.reasonCode())) {
                return waitForOfflineRole(action, account, execution, callback);
            }
            ResultKind kind = directPuller ? classifyDirectPuller(execution, callback)
                    : puller ? classifyPuller(callback) : classify(callback);
            String reasonMessage = directPuller ? directReasonMessage(execution, callback, kind)
                    : safeReasonMessage(callback, kind);
            WriteResult actionWrite = writeAction(action, callback, kind, reasonMessage, directPuller);
            if (actionWrite == WriteResult.REJECTED) {
                return false;
            }
            WriteResult membershipWrite = writeMembership(
                    account, callback, kind, reasonMessage, directPuller);
            if (membershipWrite == WriteResult.REJECTED) {
                if (actionWrite == WriteResult.UPDATED) {
                    throw new IllegalStateException(
                            puller ? "拉手进群事实写入不完整" : "管理员进群事实写入不完整");
                }
                return false;
            }
            if (actionWrite == WriteResult.ALREADY_TARGET
                    && membershipWrite == WriteResult.ALREADY_TARGET) {
                return true;
            }
            if (puller) {
                if (directPuller) {
                    return applyDirectPuller(execution, callback, kind, reasonMessage);
                }
                int executionWrite = executionMapper.transitionProtocolResult(
                        new PullTaskExecutionResultTransition(
                                execution.getId(), execution.getTaskId(), execution.getVersion(),
                                PullTaskExecutionStatus.EXECUTING.code(),
                                PullTaskExecutionStage.PULLER_INVITE.code(),
                                PullTaskExecutionStage.PULLER_INVITE.code(),
                                null, 0L, callback.occurredAt()));
                if (executionWrite != 1
                        && (actionWrite == WriteResult.UPDATED
                        || membershipWrite == WriteResult.UPDATED)) {
                    throw new IllegalStateException("拉手踩链接执行行唤醒 CAS 失败");
                }
                return true;
            }
            long nextRunAt = nextRunAt(callback, kind, properties.getRetryDelayMs());
            int advanced = executionMapper.transitionManagerJoinResult(
                    executionTransition(execution, callback, kind, reasonMessage,
                            nextRunAt));
            // 执行行 CAS 要求 lock_owner 为空，调度器持租约时必然落空。此时事实已写而执行行没推进，
            // 必须整体回滚交给重试，否则 action=SUCCESS/membership=IN_GROUP 会永久领先于空 group_jid。
            if (advanced != 1
                    && (actionWrite == WriteResult.UPDATED
                    || membershipWrite == WriteResult.UPDATED)) {
                throw new IllegalStateException("管理员踩链接执行行结果 CAS 失败");
            }
            if (advanced == 1 && kind == ResultKind.SUCCESS
                    && execution.getGroupLinkId() != null) {
                inviteLinkService.bindGroupJid(
                        execution.getGroupLinkId(), callback.groupJid(), callback.occurredAt());
            }
            if (advanced == 1 && kind == ResultKind.EXECUTION_FAILED) {
                completionService.completeIfTerminalByExecutionId(execution.getId(), callback.occurredAt());
            }
            return true;
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private boolean waitForOfflineRole(PullTaskAccountAction action, PullTaskGroupAccount account,
            PullTaskGroupExecution execution, PullTaskManagerJoinCallback callback) {
        if (!isRoleJoinStage(account, execution)) {
            return false;
        }
        if (Objects.equals(action.getActionStatus(), PullTaskActionStatus.PENDING.code())
                && Objects.equals(account.getMembershipStatus(), PullTaskGroupAccountMembershipStatus.NOT_JOINED.code())
                && Objects.equals(account.getAvailabilityStatus(), PullTaskGroupAccountAvailability.OFFLINE.code())) {
            return true;
        }
        if (!Objects.equals(execution.getExecutionStatus(), PullTaskExecutionStatus.EXECUTING.code())) {
            return false;
        }
        boolean puller = Objects.equals(account.getRoleType(), PullTaskGroupAccountRole.PULLER.code());
        String message = puller ? "拉手暂时离线，等待原账号恢复后重试入群" : "管理员暂时离线，等待原账号恢复后重试入群";
        if (actionMapper.transitionManagerAdminResult(action.getId(), callback.commandId(),
                action.getAttemptNo() == null ? 0 : action.getAttemptNo(), ACTION_OPEN,
                PullTaskActionStatus.PENDING.code(), true, callback.reasonCode(), message, callback.occurredAt()) != 1) {
            return false;
        }
        if (accountMapper.transitionMembership(new PullTaskFactTransition(account.getId(), MEMBERSHIP_OPEN,
                PullTaskGroupAccountMembershipStatus.NOT_JOINED.code(),
                PullTaskFactResult.reason(callback.reasonCode(), message), callback.occurredAt())) != 1
                || accountMapper.markUnavailable(account.getId(), PullTaskGroupAccountAvailability.OFFLINE.code(),
                callback.reasonCode(), null, callback.occurredAt()) != 1) {
            throw new IllegalStateException("离线进群角色等待事实写入不完整");
        }
        var transition = new PullTaskManagerJoinResultTransition(execution.getId(), execution.getTaskId(),
                execution.getVersion(), new PullTaskManagerJoinResultTransition.Expected(
                PullTaskExecutionStatus.EXECUTING.code(), execution.getStage()),
                new PullTaskManagerJoinResultTransition.Target(PullTaskExecutionStatus.WAIT_RESOURCE.code(),
                        execution.getStage(), null, puller ? PullTaskWaitResourceType.PULLER.code()
                        : PullTaskWaitResourceType.MANAGER.code(), callback.reasonCode(), message,
                        Math.addExact(callback.occurredAt(), properties.getRetryDelayMs()), null), callback.occurredAt());
        if (executionMapper.transitionManagerJoinResult(transition) != 1) {
            throw new IllegalStateException("离线进群执行行等待 CAS 失败");
        }
        return true;
    }

    private static boolean isRoleJoinStage(PullTaskGroupAccount account, PullTaskGroupExecution execution) {
        if (Objects.equals(account.getRoleType(), PullTaskGroupAccountRole.MANAGER.code())) {
            return Objects.equals(execution.getStage(), PullTaskExecutionStage.MANAGER_JOIN.code());
        }
        return Objects.equals(execution.getStage(), PullTaskExecutionStage.PULLER_INVITE.code())
                || Objects.equals(execution.getStage(), PullTaskExecutionStage.DIRECT_PULLER_JOIN.code());
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

    private boolean applyDirectPuller(PullTaskGroupExecution execution,
            PullTaskManagerJoinCallback callback, ResultKind kind, String reasonMessage) {
        boolean approvalRequired = kind == ResultKind.PENDING_APPROVAL;
        boolean failed = kind == ResultKind.EXECUTION_FAILED || approvalRequired;
        String reasonCode = approvalRequired ? PullTaskExecutionReasonCode.GROUP_JOIN_APPROVAL_REQUIRED.name()
                : kind == ResultKind.SUCCESS ? null : callback.reasonCode();
        String executionMessage = approvalRequired
                ? PullTaskExecutionReasonCode.GROUP_JOIN_APPROVAL_REQUIRED.message() : reasonMessage;
        long nextRunAt = kind == ResultKind.UNKNOWN
                ? Math.addExact(callback.occurredAt(), properties.getResultReconciliationDelayMs()) : 0L;
        PullTaskManagerJoinResultTransition transition = new PullTaskManagerJoinResultTransition(
                execution.getId(), execution.getTaskId(), execution.getVersion(),
                new PullTaskManagerJoinResultTransition.Expected(
                        PullTaskExecutionStatus.EXECUTING.code(), PullTaskExecutionStage.DIRECT_PULLER_JOIN.code()),
                new PullTaskManagerJoinResultTransition.Target(
                        failed ? PullTaskExecutionStatus.FAILED.code() : PullTaskExecutionStatus.EXECUTING.code(),
                        PullTaskExecutionStage.DIRECT_PULLER_JOIN.code(),
                        execution.getGroupJid() == null || execution.getGroupJid().isBlank()
                                ? callback.groupJid() : execution.getGroupJid(), null,
                        reasonCode, executionMessage,
                        nextRunAt, failed ? callback.occurredAt() : null), callback.occurredAt());
        if (executionMapper.transitionManagerJoinResult(transition) != 1) {
            throw new IllegalStateException("新群链接拉手入群结果 CAS 失败");
        }
        if (kind == ResultKind.SUCCESS && execution.getGroupLinkId() != null) {
            inviteLinkService.bindGroupJid(execution.getGroupLinkId(), callback.groupJid(), callback.occurredAt());
        }
        if (failed) {
            accountMapper.releaseAllPullersOfExecution(execution.getId(), callback.occurredAt());
            completionService.completeIfTerminalByExecutionId(execution.getId(), callback.occurredAt());
        }
        return true;
    }

    private static ResultKind classifyDirectPuller(
            PullTaskGroupExecution execution, PullTaskManagerJoinCallback callback) {
        if (differentGroup(execution, callback)) {
            return ResultKind.UNKNOWN;
        }
        if (callback.outcome() == PullTaskManagerJoinProtocolOutcome.FAILED
                && callback.reasonCode() != null
                && EXECUTION_FAILURE_CODES.contains(callback.reasonCode())) {
            return ResultKind.EXECUTION_FAILED;
        }
        return classify(callback);
    }

    private static String directReasonMessage(PullTaskGroupExecution execution,
            PullTaskManagerJoinCallback callback, ResultKind kind) {
        if (differentGroup(execution, callback)) {
            return PullTaskExecutionReasonCode.PULLER_GROUP_ID_MISMATCH.message();
        }
        if (kind == ResultKind.PENDING_APPROVAL) {
            return PullTaskExecutionReasonCode.PULLER_JOIN_PENDING_APPROVAL.message();
        }
        if (kind == ResultKind.UNKNOWN && (callback.groupJid() == null || callback.groupJid().isBlank())) {
            return PullTaskExecutionReasonCode.PULLER_GROUP_ID_UNCONFIRMED.message();
        }
        return safeReasonMessage(callback, kind);
    }

    private static boolean differentGroup(PullTaskGroupExecution execution, PullTaskManagerJoinCallback callback) {
        return execution.getGroupJid() != null && !execution.getGroupJid().isBlank()
                && callback.groupJid() != null && !callback.groupJid().isBlank()
                && !execution.getGroupJid().equals(callback.groupJid());
    }

    private WriteResult writeAction(
            PullTaskAccountAction action,
            PullTaskManagerJoinCallback callback,
            ResultKind kind,
            String reasonMessage,
            boolean directPuller) {
        int target = switch (kind) {
            case SUCCESS -> PullTaskActionStatus.SUCCESS.code();
            case MANAGER_FAILED, EXECUTION_FAILED -> PullTaskActionStatus.FAILED.code();
            case UNKNOWN -> PullTaskActionStatus.UNKNOWN.code();
            case PENDING_APPROVAL -> PullTaskActionStatus.PENDING_APPROVAL.code();
        };
        if (Objects.equals(action.getActionStatus(), target)) {
            return WriteResult.ALREADY_TARGET;
        }
        int updated = actionMapper.transitionManagerAdminResult(
                action.getId(), callback.commandId(), action.getAttemptNo() == null ? 0 : action.getAttemptNo(),
                directPuller ? DIRECT_ACTION_OPEN : ACTION_OPEN, target,
                callback.retryable(), callback.reasonCode(), reasonMessage, callback.occurredAt());
        return updated == 1 ? WriteResult.UPDATED : WriteResult.REJECTED;
    }

    private WriteResult writeMembership(
            PullTaskGroupAccount account,
            PullTaskManagerJoinCallback callback,
            ResultKind kind,
            String reasonMessage,
            boolean directPuller) {
        int target = switch (kind) {
            case SUCCESS -> PullTaskGroupAccountMembershipStatus.IN_GROUP.code();
            case MANAGER_FAILED, EXECUTION_FAILED -> PullTaskGroupAccountMembershipStatus.JOIN_FAILED.code();
            case UNKNOWN -> PullTaskGroupAccountMembershipStatus.UNKNOWN.code();
            case PENDING_APPROVAL -> PullTaskGroupAccountMembershipStatus.PENDING_APPROVAL.code();
        };
        if (Objects.equals(account.getMembershipStatus(), target)) {
            return WriteResult.ALREADY_TARGET;
        }
        Long joinedAt = kind == ResultKind.SUCCESS ? callback.occurredAt() : null;
        int updated = accountMapper.transitionMembership(new PullTaskFactTransition(
                account.getId(), directPuller ? DIRECT_MEMBERSHIP_OPEN : MEMBERSHIP_OPEN, target,
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
                && RECOVERABLE_INVITE_FAILURE_CODES.contains(callback.reasonCode())) {
            return ResultKind.UNKNOWN;
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

    private static ResultKind classifyPuller(PullTaskManagerJoinCallback callback) {
        if (callback.outcome() == PullTaskManagerJoinProtocolOutcome.JOINED
                || callback.outcome() == PullTaskManagerJoinProtocolOutcome.ALREADY_JOINED) {
            return ResultKind.SUCCESS;
        }
        return switch (classify(callback)) {
            case SUCCESS -> ResultKind.SUCCESS;
            case MANAGER_FAILED, EXECUTION_FAILED -> ResultKind.MANAGER_FAILED;
            case PENDING_APPROVAL, UNKNOWN -> ResultKind.UNKNOWN;
        };
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
            PullTaskAccountAction action,
            PullTaskGroupAccount account,
            PullTaskGroupExecution execution,
            PullTaskManagerJoinCallback callback) {
        return account != null && execution != null
                && !(Objects.equals(account.getRoleType(), PullTaskGroupAccountRole.MANAGER.code())
                && Objects.equals(account.getAvailabilityStatus(), PullTaskGroupAccountAvailability.REMOVED.code()))
                && (Objects.equals(account.getRoleType(), PullTaskGroupAccountRole.MANAGER.code())
                || Objects.equals(account.getRoleType(), PullTaskGroupAccountRole.PULLER.code()))
                && Objects.equals(action.getActorGroupAccountId(), account.getId())
                && Objects.equals(action.getTargetGroupAccountId(), account.getId())
                && Objects.equals(account.getTaskId(), callback.pullTaskId())
                && Objects.equals(account.getGroupExecutionId(), callback.groupExecutionId())
                && Objects.equals(execution.getId(), callback.groupExecutionId())
                && Objects.equals(execution.getTaskId(), callback.pullTaskId());
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
