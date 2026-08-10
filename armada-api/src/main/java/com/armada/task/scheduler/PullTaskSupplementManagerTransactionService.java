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

/**
 * 人工补充管理员的事务状态机。
 *
 * <p>补充管理员需要依次完成“进群”和“提权”两个步骤。调度器先调用 {@link #prepare}，在短事务内选出
 * 一名待处理账号并预写动作状态；协议调用在事务外完成；随后调用 {@link #complete}，通过 CAS 回写
 * 协议事实并决定继续处理、恢复主链路或进入资源等待。这样可以避免数据库事务覆盖耗时的网络调用。
 *
 * <p>本类只处理来源为 {@link PullTaskGroupAccountSource#SUPPLEMENT} 的管理员。原始管理员仍由正常的
 * 管理员入群链路处理。
 */
@Service
public class PullTaskSupplementManagerTransactionService {

    private static final String NORMAL_LINK_MODE = "NORMAL_LINK";
    /** 协议结果允许覆盖的动作中间态，终态不能被迟到结果反向改写。 */
    private static final List<Integer> ENTRY_MUTABLE_STATUSES = List.of(
            PullTaskActionStatus.SUBMITTED.code(), PullTaskActionStatus.UNKNOWN.code());
    /** 补充管理员入群结果允许覆盖的成员中间态。 */
    private static final List<Integer> MEMBERSHIP_MUTABLE_STATUSES = List.of(
            PullTaskGroupAccountMembershipStatus.NOT_JOINED.code(),
            PullTaskGroupAccountMembershipStatus.JOINING.code(),
            PullTaskGroupAccountMembershipStatus.UNKNOWN.code());
    /** 提权确认允许覆盖的管理员中间态。 */
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

    /**
     * 在短事务内查找一条人工补充行，并准备本轮唯一一个协议动作。
     *
     * <p>选择顺序为：先处理尚未入群的补充管理员；已经在群但尚未提权的账号则进入提权步骤。
     * 如果所有可处理账号都已完成，则把执行行推进到管理员与拉手互加阶段。
     */
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

    /**
     * 原子回写本次入群或提权事实，并决定继续补充、恢复主链路或等待管理员。
     *
     * <p>动作行、成员事实和执行行都使用期望状态/版本做 CAS；返回 {@code LOST} 表示本轮锁或状态
     * 已被其他调度实例推进，调用方不应继续使用当前 work 重试写入。
     */
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
        // 补充管理员沿用补充时冻结的进群方式：自己踩链接，或由当前管理员邀请入群。
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
        // 非 PENDING 说明动作曾经提交过。此时只能查询群成员事实，不能重复发送入群命令。
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
        // 优先沿用邀请该账号入群的管理员执行提权，保证冻结动作的 actor 一致；找不到时再回退。
        PullTaskGroupAccount actorRow = promotionActor(target, managers, candidate.getId());
        ProtocolAccountRef actorRef = actorRow == null ? targetRef : resources.accountLookup()
                .findActiveProtocolRef(actorRow.getAccountId()).orElse(targetRef);
        boolean pending = Objects.equals(target.getAdminStatus(),
                PullTaskGroupAccountAdminStatus.PENDING.code());
        // 目标账号不能给自己提权；这种场景以及已提交过的动作都只做管理员身份核验。
        boolean verificationOnly = !pending || Objects.equals(
                actorRef.armadaAccountId(), targetRef.armadaAccountId());
        AccountRefs refs = new AccountRefs(actorRef, targetRef);
        WorkSpec spec = new WorkSpec(
                PullTaskSupplementManagerOperation.PROMOTE_ADMIN, refs,
                "pull-task-manager-admin:" + target.getId(), verificationOnly);
        return PullTaskSupplementManagerPreparation.ready(
                work(candidate, target, null, spec));
    }

    /**
     * 所有异步前置事实已就绪后，紧邻协议提权调用预写 {@code SUBMITTED}。
     *
     * <p>该方法与 {@link #prepare} 分开，是为了让调用方在账号、群信息等异步准备完成后再占用本次
     * 提权提交权；CAS 失败时不得调用协议。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean markAdminSubmitted(PullTaskSupplementManagerWork work, long now) {
        Long previousTenant = TenantContext.get();
        TenantContext.set(work.tenantId());
        try {
            return work.operation() == PullTaskSupplementManagerOperation.PROMOTE_ADMIN
                    && !work.verificationOnly()
                    && accountMapper.transitionAdminStatus(
                    work.targetGroupAccountId(),
                    List.of(PullTaskGroupAccountAdminStatus.PENDING.code()),
                    PullTaskGroupAccountAdminStatus.SUBMITTED.code(), now) == 1;
        } finally {
            restoreTenant(previousTenant);
        }
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
        // 踩链接由目标账号自己执行，因此 actor 与 target 相同。
        if (Objects.equals(action.getActorGroupAccountId(), target.getId())) {
            return new AccountRefs(targetRef, targetRef);
        }
        // 管理员邀请必须使用预先冻结在动作行上的 actor，不能在执行时任意换人。
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
        // 若目标通过管理员邀请入群，优先由同一名管理员继续提权。
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
        // 链接入群没有邀请 actor，回退到任意一名当前仍可用的群管理员。
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
        // 两次 CAS 位于同一事务：成员状态竞争失败时，动作 SUBMITTED 也会随事务回滚。
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
        // 动作事实与成员事实必须同时成功，避免出现“动作成功但账号仍未入群”的分裂状态。
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
        // 入群成功后仍停留在 MANAGER_JOIN；下一轮 prepare 会为同一账号准备提权或核验。
        PullTaskGroupExecution update = transition(work, now);
        update.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        update.setStage(PullTaskExecutionStage.MANAGER_JOIN.code());
        return transition(update, PullTaskExecutionDispatchResult.DEFERRED);
    }

    private PullTaskExecutionDispatchResult advance(
            PullTaskSupplementManagerWork work, long now) {
        // 只有补充管理员的群成员身份和管理员身份均确认后，才恢复管理员-拉手互加主链路。
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
        if (outcome.kind() == PullTaskSupplementManagerOutcome.Kind.ENTRY_PENDING_APPROVAL) {
            fillPendingApprovalWait(update, outcome.reasonCode(), outcome.reasonMessage());
        } else {
            fillWait(update, outcome.reasonCode(), outcome.reasonMessage());
        }
        PullTaskExecutionDispatchResult result = transition(
                update, PullTaskExecutionDispatchResult.DEFERRED);
        if (result != PullTaskExecutionDispatchResult.LOST) {
            // 管理员缺口尚未解决，释放已冻结拉手，避免等待期间长期占用账号资源。
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
        // 协议层参数按目标账号后端生成：Android 传邀请码，Web 传带 https:// 的完整群链接。
        PullTaskSupplementManagerPayload payload = new PullTaskSupplementManagerPayload(
                spec.operation(),
                new PullTaskSupplementManagerPayload.Accounts(
                        spec.refs().actor(), spec.refs().target()),
                new PullTaskSupplementManagerPayload.Group(
                        PullTaskGroupJoinArgumentResolver.resolve(
                                spec.refs().target().backend(), candidate.getNormalizedLink(),
                                candidate.getInviteCode()),
                        candidate.getGroupJid(),
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
            case ENTRY_PENDING_APPROVAL -> new EntryResult(
                    PullTaskActionStatus.PENDING_APPROVAL.code(),
                    PullTaskGroupAccountMembershipStatus.PENDING_APPROVAL.code(), false, false);
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

    private static void fillPendingApprovalWait(
            PullTaskGroupExecution update, String reasonCode, String reasonMessage) {
        update.setExecutionStatus(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        update.setStage(PullTaskExecutionStage.MANAGER_JOIN.code());
        update.setWaitResourceType(PullTaskWaitResourceType.APPROVAL.code());
        update.setReasonCode(reasonCode);
        update.setReasonMessage(reasonMessage);
    }

    private static boolean supplement(PullTaskGroupAccount row) {
        return Objects.equals(row.getSourceType(), PullTaskGroupAccountSource.SUPPLEMENT.code());
    }

    private static boolean needsProcessing(PullTaskGroupAccount row) {
        // 不可用账号保留事实供排查，但不再进入调度候选集。
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
