package com.armada.task.scheduler;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.model.dto.PullTaskActionSubmission;
import com.armada.task.model.dto.PullTaskExecutionLease;
import com.armada.task.model.dto.PullTaskFactResult;
import com.armada.task.model.dto.PullTaskFactTransition;
import com.armada.task.model.dto.PullTaskSupplementManagerPayload;
import com.armada.task.model.dto.PullTaskSupplementManagerWork;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskAccountEntryMode;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionReasonCode;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAdminStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskGroupAccountSource;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskSupplementManagerOperation;
import com.armada.task.model.enums.PullTaskType;
import com.armada.task.model.enums.PullTaskWaitResourceType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 人工补充管理员的动作预写、结果 CAS 和管理员检查点推进事务。 */
@Service
public class PullTaskSupplementManagerTransactionService {

    private static final String NORMAL_LINK_MODE = "NORMAL_LINK";
    private static final List<Integer> ENTRY_MUTABLE_STATUSES = List.of(
            PullTaskActionStatus.SUBMITTED.code(), PullTaskActionStatus.UNKNOWN.code());
    private static final List<Integer> MEMBERSHIP_MUTABLE_STATUSES = List.of(
            PullTaskGroupAccountMembershipStatus.NOT_JOINED.code(),
            PullTaskGroupAccountMembershipStatus.JOINING.code(),
            PullTaskGroupAccountMembershipStatus.UNKNOWN.code());
    private static final List<Integer> ADMIN_MUTABLE_STATUSES = List.of(
            PullTaskGroupAccountAdminStatus.PENDING.code(),
            PullTaskGroupAccountAdminStatus.SUBMITTED.code(),
            PullTaskGroupAccountAdminStatus.UNKNOWN.code());

    private final PullTaskMapper taskMapper;
    private final PullTaskGroupAccountMapper accountMapper;
    private final PullTaskAccountActionMapper actionMapper;
    private final PullTaskSupplementManagerResources resources;

    /**
     * @param taskMapper 父任务 Mapper
     * @param accountMapper 角色账号 Mapper
     * @param actionMapper 账号动作 Mapper
     * @param resources 执行行与账号域依赖
     */
    public PullTaskSupplementManagerTransactionService(
            PullTaskMapper taskMapper,
            PullTaskGroupAccountMapper accountMapper,
            PullTaskAccountActionMapper actionMapper,
            PullTaskSupplementManagerResources resources) {
        this.taskMapper = taskMapper;
        this.accountMapper = accountMapper;
        this.actionMapper = actionMapper;
        this.resources = resources;
    }

    /** 在短事务内查找一条人工补充行并预写入群或提权单步。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskSupplementManagerPreparation prepare(
            PullTaskGroupExecution candidate, String lockOwner, long now) {
        if (!hasIdentity(candidate)) {
            return PullTaskSupplementManagerPreparation.notHandled();
        }
        Long previousTenant = TenantContext.get();
        TenantContext.set(candidate.getTenantId());
        try {
            PullTask parent = taskMapper.selectLifecycle(candidate.getTaskId());
            if (!isDispatchable(parent, candidate, lockOwner)) {
                resources.executionMapper().releaseLock(candidate.getId(), lockOwner, now);
                return PullTaskSupplementManagerPreparation.completed(
                        PullTaskExecutionDispatchResult.LOST);
            }
            List<PullTaskGroupAccount> managers = managers(candidate.getId());
            List<PullTaskGroupAccount> supplements = managers.stream()
                    .filter(PullTaskSupplementManagerTransactionService::supplement).toList();
            if (supplements.isEmpty()) {
                return PullTaskSupplementManagerPreparation.notHandled();
            }
            PullTaskGroupAccount target = supplements.stream()
                    .filter(PullTaskSupplementManagerTransactionService::needsProcessing)
                    .findFirst().orElse(null);
            if (target == null) {
                return hasReadySupplement(supplements)
                        ? advance(candidate, now)
                        : waitForManager(candidate, "补充管理员尚不可用", now);
            }
            return Objects.equals(target.getMembershipStatus(),
                    PullTaskGroupAccountMembershipStatus.IN_GROUP.code())
                    ? prepareAdmin(candidate, target, managers, now)
                    : prepareEntry(candidate, target, managers, now);
        } finally {
            restoreTenant(previousTenant);
        }
    }

    /** 原子回写本次入群或提权事实，并决定继续补充、恢复主链路或等待管理员。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskExecutionDispatchResult complete(
            PullTaskSupplementManagerWork work,
            PullTaskSupplementManagerOutcome outcome,
            long now) {
        Long previousTenant = TenantContext.get();
        TenantContext.set(work.tenantId());
        try {
            if (outcome == null) {
                throw new IllegalArgumentException("outcome 不能为空");
            }
            return work.operation() == PullTaskSupplementManagerOperation.PROMOTE_ADMIN
                    ? completeAdmin(work, outcome, now)
                    : completeEntry(work, outcome, now);
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private PullTaskSupplementManagerPreparation prepareEntry(
            PullTaskGroupExecution candidate,
            PullTaskGroupAccount target,
            List<PullTaskGroupAccount> managers,
            long now) {
        PullTaskAccountEntryMode entryMode =
                PullTaskAccountEntryMode.fromCode(target.getEntryMode());
        if (entryMode != PullTaskAccountEntryMode.JOIN_BY_LINK
                && entryMode != PullTaskAccountEntryMode.MANAGER_INVITE) {
            return waitForManager(candidate, "补充管理员进群方式无效", now);
        }
        PullTaskAccountAction action = entryAction(candidate.getId(), target.getId(), entryMode);
        if (action == null) {
            return waitForManager(candidate, "补充管理员进群动作不存在", now);
        }
        if (Objects.equals(action.getActionStatus(), PullTaskActionStatus.FAILED.code())
                || Objects.equals(action.getActionStatus(), PullTaskActionStatus.CANCELED.code())) {
            return waitForManager(candidate, "补充管理员进群已失败", now);
        }
        AccountRefs refs = entryRefs(action, target, managers);
        if (refs == null) {
            accountMapper.markUnavailable(target.getId(),
                    PullTaskGroupAccountAvailability.OFFLINE.code(),
                    PullTaskExecutionReasonCode.MANAGER_UNAVAILABLE.name(), null, now);
            return waitForManager(candidate, "补充管理员账号当前不可用", now);
        }
        boolean verificationOnly = !Objects.equals(
                action.getActionStatus(), PullTaskActionStatus.PENDING.code());
        if (!verificationOnly && !submitEntry(action, target, now)) {
            return PullTaskSupplementManagerPreparation.completed(
                    PullTaskExecutionDispatchResult.LOST);
        }
        PullTaskSupplementManagerOperation operation =
                entryMode == PullTaskAccountEntryMode.JOIN_BY_LINK
                        ? PullTaskSupplementManagerOperation.JOIN_BY_LINK
                        : PullTaskSupplementManagerOperation.MANAGER_INVITE;
        WorkSpec spec = new WorkSpec(
                operation, refs, operationId(action), verificationOnly);
        return PullTaskSupplementManagerPreparation.ready(
                work(candidate, target, action.getId(), spec));
    }

    private PullTaskSupplementManagerPreparation prepareAdmin(
            PullTaskGroupExecution candidate,
            PullTaskGroupAccount target,
            List<PullTaskGroupAccount> managers,
            long now) {
        ProtocolAccountRef targetRef = resources.accountLookup()
                .findActiveProtocolRef(target.getAccountId()).orElse(null);
        if (targetRef == null) {
            accountMapper.markUnavailable(target.getId(),
                    PullTaskGroupAccountAvailability.OFFLINE.code(),
                    PullTaskExecutionReasonCode.MANAGER_UNAVAILABLE.name(), null, now);
            return waitForManager(candidate, "补充管理员账号当前不可用", now);
        }
        PullTaskGroupAccount actorRow = promotionActor(target, managers, candidate.getId());
        ProtocolAccountRef actorRef = actorRow == null ? targetRef : resources.accountLookup()
                .findActiveProtocolRef(actorRow.getAccountId()).orElse(targetRef);
        boolean pending = Objects.equals(target.getAdminStatus(),
                PullTaskGroupAccountAdminStatus.PENDING.code());
        boolean verificationOnly = !pending || Objects.equals(
                actorRef.armadaAccountId(), targetRef.armadaAccountId());
        if (!verificationOnly && accountMapper.transitionAdminStatus(
                target.getId(), List.of(PullTaskGroupAccountAdminStatus.PENDING.code()),
                PullTaskGroupAccountAdminStatus.SUBMITTED.code(), now) != 1) {
            return PullTaskSupplementManagerPreparation.completed(
                    PullTaskExecutionDispatchResult.LOST);
        }
        AccountRefs refs = new AccountRefs(actorRef, targetRef);
        WorkSpec spec = new WorkSpec(
                PullTaskSupplementManagerOperation.PROMOTE_ADMIN, refs,
                "pull-task-manager-admin:" + target.getId(), verificationOnly);
        return PullTaskSupplementManagerPreparation.ready(
                work(candidate, target, null, spec));
    }

    private AccountRefs entryRefs(
            PullTaskAccountAction action,
            PullTaskGroupAccount target,
            List<PullTaskGroupAccount> managers) {
        ProtocolAccountRef targetRef = resources.accountLookup()
                .findActiveProtocolRef(target.getAccountId()).orElse(null);
        if (targetRef == null) {
            return null;
        }
        if (Objects.equals(action.getActorGroupAccountId(), target.getId())) {
            return new AccountRefs(targetRef, targetRef);
        }
        PullTaskGroupAccount actor = managers.stream()
                .filter(row -> Objects.equals(row.getId(), action.getActorGroupAccountId()))
                .filter(PullTaskSupplementManagerTransactionService::currentManager)
                .findFirst().orElse(null);
        if (actor == null) {
            return null;
        }
        ProtocolAccountRef actorRef = resources.accountLookup()
                .findActiveProtocolRef(actor.getAccountId()).orElse(null);
        return actorRef == null ? null : new AccountRefs(actorRef, targetRef);
    }

    private PullTaskGroupAccount promotionActor(
            PullTaskGroupAccount target,
            List<PullTaskGroupAccount> managers,
            long executionId) {
        PullTaskAccountAction invite = entryAction(
                executionId, target.getId(), PullTaskAccountEntryMode.MANAGER_INVITE);
        if (invite != null) {
            PullTaskGroupAccount frozen = managers.stream()
                    .filter(row -> Objects.equals(row.getId(), invite.getActorGroupAccountId()))
                    .filter(PullTaskSupplementManagerTransactionService::currentManager)
                    .findFirst().orElse(null);
            if (frozen != null) {
                return frozen;
            }
        }
        return managers.stream()
                .filter(row -> !Objects.equals(row.getId(), target.getId()))
                .filter(PullTaskSupplementManagerTransactionService::currentManager)
                .findFirst().orElse(null);
    }

    private PullTaskAccountAction entryAction(
            long executionId,
            long targetRoleRowId,
            PullTaskAccountEntryMode entryMode) {
        int actionType = entryMode == PullTaskAccountEntryMode.MANAGER_INVITE
                ? PullTaskAccountActionType.INVITE_TO_GROUP.code()
                : PullTaskAccountActionType.JOIN_BY_LINK.code();
        return actionMapper.selectByExecutionAndType(executionId, actionType).stream()
                .filter(action -> Objects.equals(
                        action.getTargetGroupAccountId(), targetRoleRowId))
                .findFirst().orElse(null);
    }

    private boolean submitEntry(
            PullTaskAccountAction action,
            PullTaskGroupAccount target,
            long now) {
        PullTaskActionSubmission submission = new PullTaskActionSubmission(
                action.getId(), PullTaskActionStatus.PENDING.code(),
                PullTaskActionStatus.SUBMITTED.code(), operationId(action), now);
        if (actionMapper.transitionSubmitted(submission) != 1) {
            return false;
        }
        PullTaskFactTransition membership = new PullTaskFactTransition(
                target.getId(), List.of(
                PullTaskGroupAccountMembershipStatus.NOT_JOINED.code(),
                PullTaskGroupAccountMembershipStatus.UNKNOWN.code()),
                PullTaskGroupAccountMembershipStatus.JOINING.code(),
                PullTaskFactResult.empty(), now);
        return accountMapper.transitionMembership(membership) == 1;
    }

    private PullTaskExecutionDispatchResult completeEntry(
            PullTaskSupplementManagerWork work,
            PullTaskSupplementManagerOutcome outcome,
            long now) {
        EntryResult result = entryResult(outcome);
        PullTaskFactResult fact = result.success()
                ? PullTaskFactResult.success(null, now)
                : PullTaskFactResult.reason(outcome.reasonCode(), outcome.reasonMessage());
        if (actionMapper.transitionResult(new PullTaskFactTransition(
                work.actionId(), ENTRY_MUTABLE_STATUSES, result.actionStatus(), fact, now)) != 1
                || accountMapper.transitionMembership(new PullTaskFactTransition(
                work.targetGroupAccountId(), MEMBERSHIP_MUTABLE_STATUSES,
                result.membershipStatus(), fact, now)) != 1) {
            return PullTaskExecutionDispatchResult.LOST;
        }
        if (result.failed()) {
            markTargetUnavailable(work.targetGroupAccountId(), outcome.reasonCode(), now);
        }
        return result.success()
                ? continueOnboarding(work, now)
                : waitAfterResult(work, outcome, now);
    }

    private PullTaskExecutionDispatchResult completeAdmin(
            PullTaskSupplementManagerWork work,
            PullTaskSupplementManagerOutcome outcome,
            long now) {
        int targetStatus = switch (outcome.kind()) {
            case ADMIN_CONFIRMED -> PullTaskGroupAccountAdminStatus.SUCCESS.code();
            case ADMIN_FAILED -> PullTaskGroupAccountAdminStatus.FAILED.code();
            case ADMIN_UNKNOWN -> PullTaskGroupAccountAdminStatus.UNKNOWN.code();
            default -> throw new IllegalArgumentException("提权工作收到入群结果");
        };
        if (accountMapper.transitionAdminStatus(
                work.targetGroupAccountId(), ADMIN_MUTABLE_STATUSES, targetStatus, now) != 1) {
            return PullTaskExecutionDispatchResult.LOST;
        }
        if (outcome.kind() == PullTaskSupplementManagerOutcome.Kind.ADMIN_FAILED) {
            markTargetUnavailable(work.targetGroupAccountId(), outcome.reasonCode(), now);
        }
        return outcome.kind() == PullTaskSupplementManagerOutcome.Kind.ADMIN_CONFIRMED
                ? advance(work, now)
                : waitAfterResult(work, outcome, now);
    }

    private PullTaskExecutionDispatchResult continueOnboarding(
            PullTaskSupplementManagerWork work, long now) {
        PullTaskGroupExecution update = transition(work, now);
        update.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        update.setStage(PullTaskExecutionStage.MANAGER_JOIN.code());
        return transition(update, PullTaskExecutionDispatchResult.DEFERRED);
    }

    private PullTaskExecutionDispatchResult advance(
            PullTaskSupplementManagerWork work, long now) {
        PullTaskGroupExecution update = transition(work, now);
        update.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        update.setStage(PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code());
        return transition(update, PullTaskExecutionDispatchResult.ADVANCED);
    }

    private PullTaskExecutionDispatchResult waitAfterResult(
            PullTaskSupplementManagerWork work,
            PullTaskSupplementManagerOutcome outcome,
            long now) {
        PullTaskGroupExecution update = transition(work, now);
        fillWait(update, outcome.reasonCode(), outcome.reasonMessage());
        PullTaskExecutionDispatchResult result = transition(
                update, PullTaskExecutionDispatchResult.DEFERRED);
        if (result != PullTaskExecutionDispatchResult.LOST) {
            accountMapper.releaseAllPullersOfExecution(work.executionId(), now);
        }
        return result;
    }

    private PullTaskSupplementManagerPreparation waitForManager(
            PullTaskGroupExecution candidate, String reasonMessage, long now) {
        PullTaskGroupExecution update = transition(candidate, now);
        fillWait(update, PullTaskExecutionReasonCode.MANAGER_UNAVAILABLE.name(), reasonMessage);
        PullTaskExecutionDispatchResult result = resources.executionMapper().transitionClaimed(
                update, PullTaskExecutionStage.MANAGER_JOIN.code()) == 1
                ? PullTaskExecutionDispatchResult.DEFERRED
                : PullTaskExecutionDispatchResult.LOST;
        if (result != PullTaskExecutionDispatchResult.LOST) {
            accountMapper.releaseAllPullersOfExecution(candidate.getId(), now);
        }
        return PullTaskSupplementManagerPreparation.completed(result);
    }

    private PullTaskSupplementManagerPreparation advance(
            PullTaskGroupExecution candidate, long now) {
        PullTaskGroupExecution update = transition(candidate, now);
        update.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        update.setStage(PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code());
        PullTaskExecutionDispatchResult result = resources.executionMapper().transitionClaimed(
                update, PullTaskExecutionStage.MANAGER_JOIN.code()) == 1
                ? PullTaskExecutionDispatchResult.ADVANCED
                : PullTaskExecutionDispatchResult.LOST;
        return PullTaskSupplementManagerPreparation.completed(result);
    }

    private PullTaskExecutionDispatchResult transition(
            PullTaskGroupExecution update,
            PullTaskExecutionDispatchResult success) {
        return resources.executionMapper().transitionClaimed(
                update, PullTaskExecutionStage.MANAGER_JOIN.code()) == 1
                ? success : PullTaskExecutionDispatchResult.LOST;
    }

    private PullTaskSupplementManagerWork work(
            PullTaskGroupExecution candidate,
            PullTaskGroupAccount target,
            Long actionId,
            WorkSpec spec) {
        PullTaskSupplementManagerPayload payload = new PullTaskSupplementManagerPayload(
                spec.operation(),
                new PullTaskSupplementManagerPayload.Accounts(
                        spec.refs().actor(), spec.refs().target()),
                new PullTaskSupplementManagerPayload.Group(
                        candidate.getNormalizedLink(), candidate.getGroupJid(),
                        spec.operationId()),
                new PullTaskExecutionLease(candidate.getLockOwner(), candidate.getVersion()),
                spec.verificationOnly());
        return new PullTaskSupplementManagerWork(
                candidate.getTenantId(), candidate.getId(), target.getId(), actionId, payload);
    }

    private void markTargetUnavailable(long roleRowId, String reasonCode, long now) {
        if (accountMapper.markUnavailable(
                roleRowId, PullTaskGroupAccountAvailability.OFFLINE.code(),
                reasonCode, null, now) != 1) {
            throw new IllegalStateException("补充管理员不可用状态回写失败");
        }
    }

    private List<PullTaskGroupAccount> managers(long executionId) {
        List<PullTaskGroupAccount> rows = accountMapper.selectByExecutionAndRole(
                executionId, PullTaskGroupAccountRole.MANAGER.code());
        return rows == null ? List.of() : rows;
    }

    private static EntryResult entryResult(PullTaskSupplementManagerOutcome outcome) {
        return switch (outcome.kind()) {
            case ENTRY_CONFIRMED -> new EntryResult(
                    PullTaskActionStatus.SUCCESS.code(),
                    PullTaskGroupAccountMembershipStatus.IN_GROUP.code(), true, false);
            case ENTRY_FAILED -> new EntryResult(
                    PullTaskActionStatus.FAILED.code(),
                    PullTaskGroupAccountMembershipStatus.JOIN_FAILED.code(), false, true);
            case ENTRY_UNKNOWN -> new EntryResult(
                    PullTaskActionStatus.UNKNOWN.code(),
                    PullTaskGroupAccountMembershipStatus.UNKNOWN.code(), false, false);
            default -> throw new IllegalArgumentException("入群工作收到提权结果");
        };
    }

    private static PullTaskGroupExecution transition(
            PullTaskSupplementManagerWork work, long now) {
        PullTaskGroupExecution update = new PullTaskGroupExecution();
        update.setId(work.executionId());
        update.setVersion(work.expectedVersion());
        update.setLockOwner(work.lockOwner());
        update.setGroupJid(work.groupJid());
        update.setNextRunAt(0L);
        update.setLastBusinessExecutedAt(now);
        update.setUpdatedAt(now);
        return update;
    }

    private static PullTaskGroupExecution transition(
            PullTaskGroupExecution candidate, long now) {
        PullTaskGroupExecution update = new PullTaskGroupExecution();
        update.setId(candidate.getId());
        update.setVersion(candidate.getVersion());
        update.setLockOwner(candidate.getLockOwner());
        update.setGroupJid(candidate.getGroupJid());
        update.setNextRunAt(0L);
        update.setLastBusinessExecutedAt(now);
        update.setUpdatedAt(now);
        return update;
    }

    private static void fillWait(
            PullTaskGroupExecution update, String reasonCode, String reasonMessage) {
        update.setExecutionStatus(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        update.setStage(PullTaskExecutionStage.MANAGER_JOIN.code());
        update.setWaitResourceType(PullTaskWaitResourceType.MANAGER.code());
        update.setReasonCode(reasonCode);
        update.setReasonMessage(reasonMessage + "，缺口人数=1");
    }

    private static boolean supplement(PullTaskGroupAccount row) {
        return Objects.equals(row.getSourceType(), PullTaskGroupAccountSource.SUPPLEMENT.code());
    }

    private static boolean needsProcessing(PullTaskGroupAccount row) {
        if (!Objects.equals(row.getAvailabilityStatus(),
                PullTaskGroupAccountAvailability.AVAILABLE.code())) {
            return false;
        }
        if (MEMBERSHIP_MUTABLE_STATUSES.contains(row.getMembershipStatus())) {
            return true;
        }
        return Objects.equals(row.getMembershipStatus(),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code())
                && ADMIN_MUTABLE_STATUSES.contains(row.getAdminStatus());
    }

    private static boolean hasReadySupplement(List<PullTaskGroupAccount> rows) {
        return rows.stream().anyMatch(row -> currentManager(row)
                && Objects.equals(row.getAdminStatus(),
                PullTaskGroupAccountAdminStatus.SUCCESS.code()));
    }

    private static boolean currentManager(PullTaskGroupAccount row) {
        return Objects.equals(row.getAvailabilityStatus(),
                PullTaskGroupAccountAvailability.AVAILABLE.code())
                && Objects.equals(row.getMembershipStatus(),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code())
                && !Objects.equals(row.getAdminStatus(),
                PullTaskGroupAccountAdminStatus.FAILED.code());
    }

    private static String operationId(PullTaskAccountAction action) {
        return action.getCommandId() == null || action.getCommandId().isBlank()
                ? "pull-task-manager-supplement-entry:" + action.getId()
                : action.getCommandId();
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
                && Objects.equals(row.getExecutionStatus(),
                PullTaskExecutionStatus.EXECUTING.code())
                && Objects.equals(row.getStage(), PullTaskExecutionStage.MANAGER_JOIN.code())
                && lockOwner != null && lockOwner.equals(row.getLockOwner());
    }

    private static void restoreTenant(Long previousTenant) {
        if (previousTenant == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenant);
        }
    }

    private record AccountRefs(ProtocolAccountRef actor, ProtocolAccountRef target) {
    }

    private record WorkSpec(
            PullTaskSupplementManagerOperation operation,
            AccountRefs refs,
            String operationId,
            boolean verificationOnly) {
    }

    private record EntryResult(
            int actionStatus,
            int membershipStatus,
            boolean success,
            boolean failed) {
    }
}
