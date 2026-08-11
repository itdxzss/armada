package com.armada.task.scheduler;

import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.platform.protocol.model.command.ProtocolPullTaskManagerAdminCommandRequest;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.model.dto.PullTaskManagerAdminWork;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionReasonCode;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAdminStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import com.armada.task.model.enums.PullTaskWaitResourceType;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 管理员设置阶段的候选选择、动作提交与状态收敛短事务。 */
@Service
public class PullTaskManagerAdminTransactionService {

    private static final String NORMAL_LINK_MODE = "NORMAL_LINK";
    private static final int INITIAL_SOURCE = 1;
    private static final int AUTOMATIC_SELECTION = 1;
    private static final List<Integer> OBSERVABLE_ACTION_STATUSES = List.of(
            PullTaskActionStatus.PENDING.code(),
            PullTaskActionStatus.SUBMITTED.code(),
            PullTaskActionStatus.SUCCESS.code(),
            PullTaskActionStatus.FAILED.code(),
            PullTaskActionStatus.UNKNOWN.code());

    private final PullTaskMapper taskMapper;
    private final PullTaskGroupAccountMapper accountMapper;
    private final PullTaskAccountActionMapper actionMapper;
    private final PullTaskManagerAdminCandidateSelector candidateSelector;
    private final PullTaskManagerAdminResources resources;

    /** 创建管理员设置阶段短事务服务。 */
    public PullTaskManagerAdminTransactionService(
            PullTaskMapper taskMapper,
            PullTaskGroupAccountMapper accountMapper,
            PullTaskAccountActionMapper actionMapper,
            PullTaskManagerAdminCandidateSelector candidateSelector,
            PullTaskManagerAdminResources resources) {
        this.taskMapper = taskMapper;
        this.accountMapper = accountMapper;
        this.actionMapper = actionMapper;
        this.candidateSelector = candidateSelector;
        this.resources = resources;
    }

    /** 复核租约并选择一个可执行提权的我方既有管理员。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskManagerAdminPreparation prepare(
            PullTaskGroupExecution candidate, String lockOwner, long now) {
        if (!hasIdentity(candidate)) {
            return PullTaskManagerAdminPreparation.completed(PullTaskExecutionDispatchResult.LOST);
        }
        Long previousTenant = TenantContext.get();
        TenantContext.set(candidate.getTenantId());
        try {
            PullTask parent = taskMapper.selectLifecycle(candidate.getTaskId());
            if (!isDispatchable(parent, candidate, lockOwner)) {
                resources.executionMapper().releaseLock(candidate.getId(), lockOwner, now);
                return PullTaskManagerAdminPreparation.completed(
                        PullTaskExecutionDispatchResult.LOST);
            }
            PullTaskGroupAccount manager = singleManager(candidate.getId());
            if (!usableManager(manager)) {
                return waitForCandidate(candidate,
                        PullTaskExecutionReasonCode.MANAGER_ADMIN_SETUP_FAILED, now);
            }
            if (Objects.equals(manager.getAdminStatus(),
                    PullTaskGroupAccountAdminStatus.SUCCESS.code())) {
                return PullTaskManagerAdminPreparation.completed(advance(candidate, now));
            }
            List<GroupExecutionAccount> candidates =
                    resources.promoterSelector().findPullTaskAdminPromoterCandidates(
                            candidate.getTenantId(), candidate.getGroupJid(), manager.getAccountId());
            List<PullTaskGroupAccount> roles = accountMapper.selectByExecutionAndRole(
                    candidate.getId(), PullTaskGroupAccountRole.PROMOTER.code());
            List<PullTaskAccountAction> actions = actionMapper.selectByExecutionAndType(
                    candidate.getId(), PullTaskAccountActionType.PROMOTE_MANAGER.code());
            PullTaskManagerAdminCandidateSelector.Selection selected = candidateSelector
                    .select(candidates, roles, actions, manager.getId())
                    .orElse(null);
            if (selected == null) {
                PullTaskExecutionReasonCode reason = candidates == null || candidates.isEmpty()
                        ? PullTaskExecutionReasonCode.MANAGER_ADMIN_ACTOR_UNAVAILABLE
                        : PullTaskExecutionReasonCode.MANAGER_ADMIN_SETUP_FAILED;
                return waitForCandidate(candidate, reason, now);
            }
            PullTaskGroupAccount promoterRole = selected.promoterRole() == null
                    ? insertPromoterRole(candidate, selected.candidate(), roles, now)
                    : selected.promoterRole();
            PullTaskAccountAction action = selected.action() == null
                    ? insertPromotionAction(candidate, promoterRole, manager, now)
                    : selected.action();
            return PullTaskManagerAdminPreparation.ready(new PullTaskManagerAdminWork(
                    candidate.getTenantId(), candidate.getTaskId(), candidate.getId(),
                    candidate.getVersion(), lockOwner, candidate.getGroupJid(), manager,
                    selected.candidate(), promoterRole, action));
        } finally {
            restoreTenant(previousTenant);
        }
    }

    /** 根据成功回执或兜底成员事实确认权限，并推进到管理—拉手联系人阶段。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskExecutionDispatchResult confirmManagerAdmin(
            PullTaskManagerAdminWork work, long now) {
        return withTenant(work.tenantId(), () -> {
            PullTaskGroupExecution update = transition(
                    work, PullTaskExecutionStatus.EXECUTING,
                    PullTaskExecutionStage.MANAGER_PULLER_CONTACT, 0L, now);
            if (resources.executionMapper().transitionClaimed(
                    update, PullTaskExecutionStage.MANAGER_ADMIN.code()) != 1) {
                return PullTaskExecutionDispatchResult.LOST;
            }
            if (actionMapper.transitionManagerAdminObservation(
                    work.action().getId(), OBSERVABLE_ACTION_STATUSES,
                    PullTaskActionStatus.SUCCESS.code(), false, null, null, now) != 1
                    || accountMapper.transitionAdminStatus(
                    work.manager().getId(), List.of(
                            PullTaskGroupAccountAdminStatus.PENDING.code(),
                            PullTaskGroupAccountAdminStatus.SUBMITTED.code(),
                            PullTaskGroupAccountAdminStatus.FAILED.code(),
                            PullTaskGroupAccountAdminStatus.UNKNOWN.code(),
                            PullTaskGroupAccountAdminStatus.SUCCESS.code()),
                    PullTaskGroupAccountAdminStatus.SUCCESS.code(), now) != 1) {
                throw new IllegalStateException("管理员设置成功事实写入不完整");
            }
            return PullTaskExecutionDispatchResult.ADVANCED;
        });
    }

    /** 根据兜底成员事实标记已失去管理员权限的候选，并立即轮换。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskExecutionDispatchResult rejectPromoter(
            PullTaskManagerAdminWork work, long now) {
        return withTenant(work.tenantId(), () -> {
            PullTaskExecutionReasonCode reason = PullTaskExecutionReasonCode.MANAGER_ADMIN_SETUP_FAILED;
            PullTaskExecutionDispatchResult deferred = defer(work, 0L, reason, now);
            if (deferred == PullTaskExecutionDispatchResult.LOST) {
                return deferred;
            }
            if (actionMapper.transitionManagerAdminObservation(
                    work.action().getId(), OBSERVABLE_ACTION_STATUSES,
                    PullTaskActionStatus.FAILED.code(), false,
                    reason.name(), reason.message(), now) != 1) {
                throw new IllegalStateException("提权候选失效事实写入不完整");
            }
            if (accountMapper.transitionAdminStatus(
                    work.manager().getId(), OBSERVABLE_ADMIN_STATUSES,
                    PullTaskGroupAccountAdminStatus.PENDING.code(), now) != 1) {
                throw new IllegalStateException("任务管理员权限状态写入不完整");
            }
            return deferred;
        });
    }

    /** 兜底成员列表暂不可用时，仅延迟观察，不提交新的提权动作。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskExecutionDispatchResult deferObservation(
            PullTaskManagerAdminWork work, long now) {
        return withTenant(work.tenantId(), () -> defer(
                work, now + resources.properties().getRetryDelayMs(),
                PullTaskExecutionReasonCode.MANAGER_ADMIN_UNCONFIRMED, now));
    }

    /** 提交首次或后续提权尝试；已提交动作只等待回调和成员查询兜底。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskExecutionDispatchResult submitOrDefer(
            PullTaskManagerAdminWork work, long now) {
        return withTenant(work.tenantId(), () -> {
            if (!Objects.equals(
                    work.action().getActionStatus(), PullTaskActionStatus.SUBMITTED.code())) {
                ProtocolCommandOutboxEnqueueResult enqueued =
                        resources.outboxService().enqueuePullTaskManagerAdminCommands(List.of(
                                new ProtocolPullTaskManagerAdminCommandRequest(
                                        work.tenantId(), work.taskId(), work.executionId(),
                                        work.action().getId(), work.promoter().protocolRef())));
                String commandId = singleCommandId(enqueued);
                if (actionMapper.submitAttempt(
                        work.action().getId(), List.of(
                                PullTaskActionStatus.PENDING.code(),
                                PullTaskActionStatus.FAILED.code(),
                                PullTaskActionStatus.UNKNOWN.code()),
                        commandId, now) != 1) {
                    throw new IllegalStateException("管理员设置动作状态已变化");
                }
                if (accountMapper.transitionAdminStatus(
                        work.manager().getId(), OBSERVABLE_ADMIN_STATUSES,
                        PullTaskGroupAccountAdminStatus.SUBMITTED.code(), now) != 1) {
                    throw new IllegalStateException("任务管理员权限状态已变化");
                }
            }
            PullTaskExecutionDispatchResult deferred = defer(
                    work, now + resources.properties().getResultReconciliationDelayMs(),
                    null, now);
            if (deferred == PullTaskExecutionDispatchResult.LOST) {
                throw new IllegalStateException("管理员设置执行行租约已变化");
            }
            return deferred;
        });
    }

    private static final List<Integer> OBSERVABLE_ADMIN_STATUSES = List.of(
            PullTaskGroupAccountAdminStatus.PENDING.code(),
            PullTaskGroupAccountAdminStatus.SUBMITTED.code(),
            PullTaskGroupAccountAdminStatus.FAILED.code(),
            PullTaskGroupAccountAdminStatus.UNKNOWN.code());

    private PullTaskExecutionDispatchResult advance(
            PullTaskGroupExecution candidate, long now) {
        PullTaskGroupExecution update = baseTransition(
                candidate.getId(), candidate.getVersion(), candidate.getLockOwner(), now);
        update.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        update.setStage(PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code());
        return resources.executionMapper().transitionClaimed(
                update, PullTaskExecutionStage.MANAGER_ADMIN.code()) == 1
                ? PullTaskExecutionDispatchResult.ADVANCED
                : PullTaskExecutionDispatchResult.LOST;
    }

    private PullTaskExecutionDispatchResult defer(
            PullTaskManagerAdminWork work,
            long nextRunAt,
            PullTaskExecutionReasonCode reason,
            long now) {
        PullTaskGroupExecution update = transition(
                work, PullTaskExecutionStatus.EXECUTING,
                PullTaskExecutionStage.MANAGER_ADMIN, nextRunAt, now);
        if (reason != null) {
            update.setReasonCode(reason.name());
            update.setReasonMessage(reason.message());
        }
        return resources.executionMapper().transitionClaimed(
                update, PullTaskExecutionStage.MANAGER_ADMIN.code()) == 1
                ? PullTaskExecutionDispatchResult.DEFERRED
                : PullTaskExecutionDispatchResult.LOST;
    }

    private PullTaskManagerAdminPreparation waitForCandidate(
            PullTaskGroupExecution candidate,
            PullTaskExecutionReasonCode reason,
            long now) {
        PullTaskGroupExecution update = baseTransition(
                candidate.getId(), candidate.getVersion(), candidate.getLockOwner(), now);
        update.setExecutionStatus(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        update.setStage(PullTaskExecutionStage.MANAGER_ADMIN.code());
        update.setWaitResourceType(PullTaskWaitResourceType.MANAGER.code());
        update.setReasonCode(reason.name());
        update.setReasonMessage(reason.message());
        if (resources.executionMapper().transitionClaimed(
                update, PullTaskExecutionStage.MANAGER_ADMIN.code()) != 1) {
            return PullTaskManagerAdminPreparation.completed(PullTaskExecutionDispatchResult.LOST);
        }
        return PullTaskManagerAdminPreparation.completed(PullTaskExecutionDispatchResult.DEFERRED);
    }

    private PullTaskGroupAccount singleManager(long executionId) {
        List<PullTaskGroupAccount> managers = accountMapper.selectByExecutionAndRole(
                executionId, PullTaskGroupAccountRole.MANAGER.code());
        return managers.size() == 1 ? managers.get(0) : null;
    }

    private PullTaskGroupAccount insertPromoterRole(
            PullTaskGroupExecution candidate,
            GroupExecutionAccount promoter,
            List<PullTaskGroupAccount> roles,
            long now) {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setTaskId(candidate.getTaskId());
        row.setGroupExecutionId(candidate.getId());
        row.setAccountId(promoter.accountId());
        row.setAccountPhone(promoter.wsPhone());
        row.setRoleType(PullTaskGroupAccountRole.PROMOTER.code());
        row.setRoleSeq(nextRoleSeq(roles));
        row.setSourceType(INITIAL_SOURCE);
        row.setSelectionMode(AUTOMATIC_SELECTION);
        row.setMembershipStatus(PullTaskGroupAccountMembershipStatus.IN_GROUP.code());
        row.setAdminStatus(PullTaskGroupAccountAdminStatus.SUCCESS.code());
        row.setAvailabilityStatus(PullTaskGroupAccountAvailability.AVAILABLE.code());
        row.setJoinedAt(now);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        if (accountMapper.insertInitialized(row) != 1 || row.getId() == null) {
            throw new IllegalStateException("提权管理员角色行写入失败");
        }
        return row;
    }

    private PullTaskAccountAction insertPromotionAction(
            PullTaskGroupExecution candidate,
            PullTaskGroupAccount promoter,
            PullTaskGroupAccount manager,
            long now) {
        PullTaskAccountAction row = new PullTaskAccountAction();
        row.setTaskId(candidate.getTaskId());
        row.setGroupExecutionId(candidate.getId());
        row.setActionType(PullTaskAccountActionType.PROMOTE_MANAGER.code());
        row.setActorGroupAccountId(promoter.getId());
        row.setTargetGroupAccountId(manager.getId());
        row.setAttemptNo(0);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        if (actionMapper.insertIfAbsent(row) == 1 && row.getId() != null) {
            return row;
        }
        return actionMapper.selectByExecutionAndType(
                        candidate.getId(), PullTaskAccountActionType.PROMOTE_MANAGER.code())
                .stream()
                .filter(action -> Objects.equals(
                        action.getActorGroupAccountId(), promoter.getId()))
                .filter(action -> Objects.equals(
                        action.getTargetGroupAccountId(), manager.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("管理员设置动作写入失败"));
    }

    private static int nextRoleSeq(List<PullTaskGroupAccount> roles) {
        if (roles == null || roles.isEmpty()) {
            return 1;
        }
        return roles.stream().map(PullTaskGroupAccount::getRoleSeq)
                .filter(Objects::nonNull).mapToInt(Integer::intValue).max().orElse(0) + 1;
    }

    private static boolean usableManager(PullTaskGroupAccount manager) {
        return manager != null
                && Objects.equals(manager.getMembershipStatus(),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code())
                && Objects.equals(manager.getAvailabilityStatus(),
                PullTaskGroupAccountAvailability.AVAILABLE.code());
    }

    private static PullTaskGroupExecution transition(
            PullTaskManagerAdminWork work,
            PullTaskExecutionStatus status,
            PullTaskExecutionStage stage,
            long nextRunAt,
            long now) {
        PullTaskGroupExecution update = baseTransition(
                work.executionId(), work.expectedVersion(), work.lockOwner(), now);
        update.setExecutionStatus(status.code());
        update.setStage(stage.code());
        update.setGroupJid(work.groupJid());
        update.setNextRunAt(nextRunAt);
        return update;
    }

    private static PullTaskGroupExecution baseTransition(
            long executionId, int version, String lockOwner, long now) {
        PullTaskGroupExecution update = new PullTaskGroupExecution();
        update.setId(executionId);
        update.setVersion(version);
        update.setLockOwner(lockOwner);
        update.setNextRunAt(0L);
        update.setLastBusinessExecutedAt(now);
        update.setUpdatedAt(now);
        return update;
    }

    private static String singleCommandId(ProtocolCommandOutboxEnqueueResult enqueued) {
        if (enqueued == null || enqueued.inserted() != 1
                || enqueued.commandIds() == null || enqueued.commandIds().size() != 1
                || enqueued.commandIds().get(0) == null
                || enqueued.commandIds().get(0).isBlank()) {
            throw new IllegalStateException("管理员设置 Outbox 写入结果不完整");
        }
        return enqueued.commandIds().get(0);
    }

    private static boolean hasIdentity(PullTaskGroupExecution candidate) {
        return candidate != null && candidate.getTenantId() != null
                && candidate.getId() != null && candidate.getTaskId() != null;
    }

    private static boolean isDispatchable(
            PullTask parent, PullTaskGroupExecution row, String lockOwner) {
        return parent != null
                && parent.getTaskType() == PullTaskType.STANDARD
                && NORMAL_LINK_MODE.equals(parent.getMode())
                && PullTaskStandardStatus.EXECUTING.name().equals(parent.getStatus())
                && row.getExecutionStatus() == PullTaskExecutionStatus.EXECUTING.code()
                && row.getStage() == PullTaskExecutionStage.MANAGER_ADMIN.code()
                && lockOwner != null && lockOwner.equals(row.getLockOwner())
                && row.getGroupJid() != null && !row.getGroupJid().isBlank();
    }

    private <T> T withTenant(long tenantId, TransactionOperation<T> operation) {
        Long previousTenant = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            return operation.execute();
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private static void restoreTenant(Long tenantId) {
        if (tenantId == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(tenantId);
        }
    }

    @FunctionalInterface
    private interface TransactionOperation<T> {
        T execute();
    }
}
