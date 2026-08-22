package com.armada.task.scheduler;

import com.armada.group.model.enums.GroupCreatorLeaveStatus;
import com.armada.group.model.vo.GroupCreatorLeaveAccount;
import com.armada.group.model.vo.GroupCreatorLeavePlan;
import com.armada.group.service.GroupCreatorLeaveService;
import com.armada.platform.protocol.model.command.ProtocolPullTaskCreatorLeaveCommandRequest;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskCreationMode;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAdminStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 标准任务单群拉人结束后的可选异步群主退群状态机。 */
@Service
public class PullTaskCreatorLeaveProcessor {

    private static final String NORMAL_LINK_MODE = "NORMAL_LINK";
    private static final int INITIAL_SOURCE = 1;
    private static final int AUTOMATIC_SELECTION = 1;
    private static final List<Integer> ACTION_OPEN = List.of(
            PullTaskActionStatus.PENDING.code(),
            PullTaskActionStatus.SUBMITTED.code(),
            PullTaskActionStatus.UNKNOWN.code());

    private final PullTaskStandardSettingMapper settingMapper;
    private final PullTaskMapper taskMapper;
    private final PullTaskGroupAccountMapper accountMapper;
    private final PullTaskAccountActionMapper actionMapper;
    private final PullTaskGroupExecutionMapper executionMapper;
    private final GroupCreatorLeaveService creatorLeaveService;
    private final ProtocolCommandOutboxService outboxService;
    private final PullTaskExecutionDispatchProperties properties;

    /** 创建拉人结束后的群主退群状态机。 */
    public PullTaskCreatorLeaveProcessor(
            PullTaskStandardSettingMapper settingMapper,
            PullTaskMapper taskMapper,
            PullTaskGroupAccountMapper accountMapper,
            PullTaskAccountActionMapper actionMapper,
            PullTaskGroupExecutionMapper executionMapper,
            GroupCreatorLeaveService creatorLeaveService,
            ProtocolCommandOutboxService outboxService,
            PullTaskExecutionDispatchProperties properties) {
        this.settingMapper = settingMapper;
        this.taskMapper = taskMapper;
        this.accountMapper = accountMapper;
        this.actionMapper = actionMapper;
        this.executionMapper = executionMapper;
        this.creatorLeaveService = creatorLeaveService;
        this.outboxService = outboxService;
        this.properties = properties;
    }

    /** 推进一次群主退群动作；ADVANCED 表示调用方可继续收口执行行。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskExecutionDispatchResult process(
            PullTaskGroupExecution candidate,
            String lockOwner,
            long now) {
        if (!hasIdentity(candidate)) {
            return PullTaskExecutionDispatchResult.LOST;
        }
        Long previousTenant = TenantContext.get();
        TenantContext.set(candidate.getTenantId());
        try {
            PullTaskStandardSetting setting = settingMapper.selectByTaskId(candidate.getTaskId());
            if (setting == null || !Integer.valueOf(1).equals(setting.getCreatorLeaveAfterPull())
                    || terminal(candidate.getCreatorLeaveResult())) {
                return PullTaskExecutionDispatchResult.ADVANCED;
            }
            PullTask parent = taskMapper.selectLifecycle(candidate.getTaskId());
            if (!isDispatchable(parent, candidate, lockOwner)) {
                executionMapper.releaseLock(candidate.getId(), lockOwner, now);
                return PullTaskExecutionDispatchResult.LOST;
            }
            Long preferredCreatorId = preferredCreatorId(parent, candidate.getId());
            if (PullTaskCreationMode.fromNullable(parent.getCreationMode()).isNewGroup()
                    && preferredCreatorId == null) {
                setTerminal(candidate, GroupCreatorLeaveStatus.NOT_CREATOR);
                return PullTaskExecutionDispatchResult.ADVANCED;
            }
            GroupCreatorLeavePlan plan = creatorLeaveService.plan(
                    candidate.getGroupLinkId(), preferredCreatorId);
            if (!plan.executable()) {
                setTerminal(candidate, plan.failure());
                return PullTaskExecutionDispatchResult.ADVANCED;
            }
            PullTaskGroupAccount owner = ensureRole(
                    candidate, plan.owner(), PullTaskGroupAccountRole.PROMOTER, now);
            if (plan.promotionRequired()) {
                PullTaskExecutionDispatchResult promotion = processPromotion(
                        candidate, plan, owner, lockOwner, now);
                if (promotion == PullTaskExecutionDispatchResult.FAILED) {
                    setTerminal(candidate, GroupCreatorLeaveStatus.PROMOTION_FAILED);
                    return PullTaskExecutionDispatchResult.ADVANCED;
                }
                if (promotion != PullTaskExecutionDispatchResult.ADVANCED) {
                    return promotion;
                }
            }
            PullTaskExecutionDispatchResult leave = processLeave(
                    candidate, plan.owner(), owner, lockOwner, now);
            if (leave == PullTaskExecutionDispatchResult.FAILED) {
                setTerminal(candidate, GroupCreatorLeaveStatus.LEAVE_FAILED);
                return PullTaskExecutionDispatchResult.ADVANCED;
            }
            if (leave == PullTaskExecutionDispatchResult.ADVANCED) {
                setTerminal(candidate, GroupCreatorLeaveStatus.SUCCESS);
            }
            return leave;
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private PullTaskExecutionDispatchResult processPromotion(
            PullTaskGroupExecution candidate,
            GroupCreatorLeavePlan plan,
            PullTaskGroupAccount owner,
            String lockOwner,
            long now) {
        PullTaskGroupAccount controller = ensureRole(
                candidate, plan.memberToPromote(), PullTaskGroupAccountRole.CONTROLLER, now);
        PullTaskAccountAction action = ensureAction(
                candidate, PullTaskAccountActionType.PROMOTE_CREATOR_SUCCESSOR,
                owner.getId(), controller.getId(), now);
        return processAction(candidate, plan.owner(), action,
                ProtocolPullTaskCreatorLeaveCommandRequest.Action.PROMOTE, lockOwner, now);
    }

    private PullTaskExecutionDispatchResult processLeave(
            PullTaskGroupExecution candidate,
            GroupCreatorLeaveAccount ownerAccount,
            PullTaskGroupAccount ownerRole,
            String lockOwner,
            long now) {
        PullTaskAccountAction action = ensureAction(
                candidate, PullTaskAccountActionType.CREATOR_LEAVE,
                ownerRole.getId(), ownerRole.getId(), now);
        return processAction(candidate, ownerAccount, action,
                ProtocolPullTaskCreatorLeaveCommandRequest.Action.LEAVE, lockOwner, now);
    }

    private PullTaskExecutionDispatchResult processAction(
            PullTaskGroupExecution candidate,
            GroupCreatorLeaveAccount owner,
            PullTaskAccountAction action,
            ProtocolPullTaskCreatorLeaveCommandRequest.Action protocolAction,
            String lockOwner,
            long now) {
        if (Objects.equals(action.getActionStatus(), PullTaskActionStatus.SUCCESS.code())) {
            return PullTaskExecutionDispatchResult.ADVANCED;
        }
        if (Objects.equals(action.getActionStatus(), PullTaskActionStatus.PENDING.code())) {
            ProtocolCommandOutboxEnqueueResult enqueued =
                    outboxService.enqueuePullTaskCreatorLeaveCommands(List.of(
                            new ProtocolPullTaskCreatorLeaveCommandRequest(
                                    candidate.getTenantId(), candidate.getTaskId(), candidate.getId(),
                                    action.getId(), protocolAction, owner.protocolRef())));
            if (actionMapper.submitAttempt(
                    action.getId(), List.of(PullTaskActionStatus.PENDING.code()),
                    singleCommandId(enqueued), now) != 1) {
                throw new IllegalStateException("群主退群动作状态已变化");
            }
            return defer(candidate, lockOwner,
                    now + properties.getResultReconciliationDelayMs(), now);
        }
        if (Objects.equals(action.getActionStatus(), PullTaskActionStatus.SUBMITTED.code())) {
            long submittedAt = action.getSubmittedAt() == null ? now : action.getSubmittedAt();
            if (submittedAt + properties.getResultReconciliationDelayMs() > now) {
                return defer(candidate, lockOwner,
                        submittedAt + properties.getResultReconciliationDelayMs(), now);
            }
            actionMapper.transitionManagerAdminResult(
                    action.getId(), action.getCommandId(), action.getAttemptNo(), ACTION_OPEN,
                    PullTaskActionStatus.UNKNOWN.code(), false,
                    "RESULT_TIMEOUT", "群主退群协议结果超时", now);
        }
        return PullTaskExecutionDispatchResult.FAILED;
    }

    private PullTaskGroupAccount ensureRole(
            PullTaskGroupExecution candidate,
            GroupCreatorLeaveAccount account,
            PullTaskGroupAccountRole role,
            long now) {
        List<PullTaskGroupAccount> roles = accountMapper.selectByExecutionAndRole(
                candidate.getId(), role.code());
        PullTaskGroupAccount existing = roles.stream()
                .filter(row -> Objects.equals(row.getAccountId(), account.accountId()))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setTaskId(candidate.getTaskId());
        row.setGroupExecutionId(candidate.getId());
        row.setAccountId(account.accountId());
        row.setAccountPhone(account.wsPhone());
        row.setRoleType(role.code());
        row.setRoleSeq(nextRoleSeq(roles));
        row.setSourceType(INITIAL_SOURCE);
        row.setSelectionMode(AUTOMATIC_SELECTION);
        row.setMembershipStatus(PullTaskGroupAccountMembershipStatus.IN_GROUP.code());
        row.setAdminStatus(role == PullTaskGroupAccountRole.PROMOTER
                ? PullTaskGroupAccountAdminStatus.SUCCESS.code()
                : PullTaskGroupAccountAdminStatus.NOT_APPLICABLE.code());
        row.setAvailabilityStatus(PullTaskGroupAccountAvailability.AVAILABLE.code());
        row.setJoinedAt(account.membershipActiveSinceAt() == null
                ? now : account.membershipActiveSinceAt());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        if (accountMapper.insertInitialized(row) != 1 || row.getId() == null) {
            throw new IllegalStateException("群主退群角色事实写入失败");
        }
        return row;
    }

    private PullTaskAccountAction ensureAction(
            PullTaskGroupExecution candidate,
            PullTaskAccountActionType type,
            long actorId,
            long targetId,
            long now) {
        PullTaskAccountAction existing = actionMapper.selectByExecutionAndType(
                        candidate.getId(), type.code()).stream()
                .filter(action -> Objects.equals(action.getActorGroupAccountId(), actorId))
                .filter(action -> Objects.equals(action.getTargetGroupAccountId(), targetId))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        PullTaskAccountAction row = new PullTaskAccountAction();
        row.setTaskId(candidate.getTaskId());
        row.setGroupExecutionId(candidate.getId());
        row.setActionType(type.code());
        row.setActorGroupAccountId(actorId);
        row.setTargetGroupAccountId(targetId);
        row.setAttemptNo(0);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        if (actionMapper.insertIfAbsent(row) != 1 || row.getId() == null) {
            throw new IllegalStateException("群主退群动作写入失败");
        }
        return row;
    }

    private PullTaskExecutionDispatchResult defer(
            PullTaskGroupExecution candidate,
            String lockOwner,
            long nextRunAt,
            long now) {
        PullTaskGroupExecution update = new PullTaskGroupExecution();
        update.setId(candidate.getId());
        update.setVersion(candidate.getVersion());
        update.setLockOwner(lockOwner);
        update.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        update.setStage(PullTaskExecutionStage.CLOSING.code());
        update.setGroupJid(candidate.getGroupJid());
        update.setNextRunAt(nextRunAt);
        update.setUpdatedAt(now);
        if (executionMapper.transitionClaimed(
                update, PullTaskExecutionStage.CLOSING.code()) != 1) {
            throw new IllegalStateException("群主退群执行行租约已变化");
        }
        return PullTaskExecutionDispatchResult.DEFERRED;
    }

    private Long preferredCreatorId(PullTask parent, long executionId) {
        if (!PullTaskCreationMode.fromNullable(parent.getCreationMode()).isNewGroup()) {
            return null;
        }
        return accountMapper.selectByExecutionAndRole(
                        executionId, PullTaskGroupAccountRole.PROMOTER.code()).stream()
                .map(PullTaskGroupAccount::getAccountId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static boolean isDispatchable(
            PullTask parent,
            PullTaskGroupExecution candidate,
            String lockOwner) {
        return parent != null
                && parent.getTaskType() == PullTaskType.STANDARD
                && NORMAL_LINK_MODE.equals(parent.getMode())
                && PullTaskStandardStatus.EXECUTING.name().equals(parent.getStatus())
                && Objects.equals(candidate.getExecutionStatus(), PullTaskExecutionStatus.EXECUTING.code())
                && Objects.equals(candidate.getStage(), PullTaskExecutionStage.CLOSING.code())
                && lockOwner != null && lockOwner.equals(candidate.getLockOwner());
    }

    private static boolean hasIdentity(PullTaskGroupExecution candidate) {
        return candidate != null && candidate.getTenantId() != null
                && candidate.getId() != null && candidate.getTaskId() != null
                && candidate.getGroupLinkId() != null;
    }

    private static boolean terminal(Integer result) {
        return result != null && result > 0;
    }

    private static void setTerminal(
            PullTaskGroupExecution candidate,
            GroupCreatorLeaveStatus status) {
        candidate.setCreatorLeaveResult(status.code());
        candidate.setCreatorLeaveReason(status == GroupCreatorLeaveStatus.SUCCESS
                ? null : message(status));
    }

    private static String message(GroupCreatorLeaveStatus status) {
        return switch (status) {
            case SUCCESS -> "群主退群成功";
            case NOT_CREATOR -> "当前账号不是建群者，无法执行群主退群";
            case CREATOR_UNAVAILABLE -> "建群者账号当前不可执行退群";
            case NO_AVAILABLE_CONTROLLER -> "当前群内无控端管理员或可提升的普通控端成员";
            case PROMOTION_FAILED -> "控端成员管理员设置失败，未执行群主退群";
            case LEAVE_FAILED -> "建群者退群失败";
        };
    }

    private static int nextRoleSeq(List<PullTaskGroupAccount> roles) {
        return roles == null ? 1 : roles.stream()
                .map(PullTaskGroupAccount::getRoleSeq)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0) + 1;
    }

    private static String singleCommandId(ProtocolCommandOutboxEnqueueResult result) {
        if (result == null || result.commandIds() == null || result.commandIds().size() != 1) {
            throw new IllegalStateException("群主退群协议命令写入结果不完整");
        }
        return result.commandIds().get(0);
    }

    private static void restoreTenant(Long previousTenant) {
        if (previousTenant == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenant);
        }
    }
}
