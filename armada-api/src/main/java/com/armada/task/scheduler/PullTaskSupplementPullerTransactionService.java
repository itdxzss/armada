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
import com.armada.task.model.dto.PullTaskSupplementPullerPayload;
import com.armada.task.model.dto.PullTaskSupplementPullerWork;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskAccountEntryMode;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskGroupAccountSource;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import com.armada.task.model.enums.PullTaskWaitResourceType;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 补充拉手踩链接的预写、只查恢复与检查点结果事务。 */
@Service
public class PullTaskSupplementPullerTransactionService {

    private static final String NORMAL_LINK_MODE = "NORMAL_LINK";
    private static final String ACCOUNT_UNAVAILABLE = "PULLER_ACCOUNT_UNAVAILABLE";
    private static final String WAIT_MESSAGE = "补充拉手在群结果无法确认，缺口人数=1";
    private static final List<Integer> ACTION_RESULT_STATES = List.of(
            PullTaskActionStatus.SUBMITTED.code(), PullTaskActionStatus.UNKNOWN.code());
    private static final List<Integer> MEMBERSHIP_RESULT_STATES = List.of(
            PullTaskGroupAccountMembershipStatus.NOT_JOINED.code(),
            PullTaskGroupAccountMembershipStatus.JOINING.code(),
            PullTaskGroupAccountMembershipStatus.UNKNOWN.code());

    private final PullTaskMapper taskMapper;
    private final PullTaskGroupAccountMapper accountMapper;
    private final PullTaskAccountActionMapper actionMapper;
    private final PullTaskSupplementPullerResources resources;

    public PullTaskSupplementPullerTransactionService(
            PullTaskMapper taskMapper,
            PullTaskGroupAccountMapper accountMapper,
            PullTaskAccountActionMapper actionMapper,
            PullTaskSupplementPullerResources resources) {
        this.taskMapper = taskMapper;
        this.accountMapper = accountMapper;
        this.actionMapper = actionMapper;
        this.resources = resources;
    }

    /** 选择下一条人工补充踩链接指令，并在事务内预写提交或复核状态。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskSupplementPullerPreparation prepare(
            PullTaskGroupExecution candidate, String lockOwner, long now) {
        if (!hasIdentity(candidate)) {
            return PullTaskSupplementPullerPreparation.completed(
                    PullTaskExecutionDispatchResult.LOST);
        }
        Long previousTenant = TenantContext.get();
        TenantContext.set(candidate.getTenantId());
        try {
            PullTask parent = taskMapper.selectLifecycle(candidate.getTaskId());
            if (!isDispatchable(parent, candidate, lockOwner)) {
                resources.executionMapper().releaseLock(candidate.getId(), lockOwner, now);
                return PullTaskSupplementPullerPreparation.completed(
                        PullTaskExecutionDispatchResult.LOST);
            }
            return prepareNext(candidate, now);
        } finally {
            restoreTenant(previousTenant);
        }
    }

    /** 回写踩链接与实时在群事实，再继续联系人链或进入等待复核。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskExecutionDispatchResult complete(
            PullTaskSupplementPullerWork work,
            PullTaskSupplementPullerOutcome outcome,
            long now) {
        Long previousTenant = TenantContext.get();
        TenantContext.set(work.tenantId());
        try {
            if (!writeFacts(work, outcome, now)) {
                resources.executionMapper().releaseLock(
                        work.executionId(), work.lockOwner(), now);
                return PullTaskExecutionDispatchResult.LOST;
            }
            if (outcome.kind() == PullTaskSupplementPullerOutcome.Kind.FAILED) {
                markUnavailable(work.targetGroupAccountId(), outcome.reasonCode(), now);
            }
            return transition(work, outcome, now);
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private PullTaskSupplementPullerPreparation prepareNext(
            PullTaskGroupExecution candidate, long now) {
        List<PullTaskGroupAccount> pullers = accountMapper.selectByExecutionAndRole(
                candidate.getId(), PullTaskGroupAccountRole.PULLER.code());
        Map<Long, PullTaskAccountAction> actions = actionMapper.selectByExecutionAndType(
                        candidate.getId(), PullTaskAccountActionType.JOIN_BY_LINK.code())
                .stream().collect(Collectors.toMap(
                        PullTaskAccountAction::getTargetGroupAccountId,
                        Function.identity(), (first, ignored) -> first));
        for (PullTaskGroupAccount puller : pullers) {
            if (!processable(puller)) {
                continue;
            }
            PullTaskAccountAction action = actions.get(puller.getId());
            if (action == null) {
                throw new IllegalStateException("补充拉手踩链接动作缺失");
            }
            PullTaskSupplementPullerPreparation preparation =
                    prepareAction(candidate, puller, action, now);
            if (preparation != null) {
                return preparation;
            }
        }
        return PullTaskSupplementPullerPreparation.notHandled();
    }

    private PullTaskSupplementPullerPreparation prepareAction(
            PullTaskGroupExecution candidate,
            PullTaskGroupAccount target,
            PullTaskAccountAction action,
            long now) {
        if (Objects.equals(action.getActionStatus(), PullTaskActionStatus.PENDING.code())) {
            ProtocolAccountRef account = resources.accountLookup()
                    .findActiveProtocolRef(target.getAccountId()).orElse(null);
            if (account == null) {
                markUnavailable(target.getId(), ACCOUNT_UNAVAILABLE, now);
                return continueContactStage(candidate, now);
            }
            PullTaskActionSubmission submission = new PullTaskActionSubmission(
                    action.getId(), PullTaskActionStatus.PENDING.code(),
                    PullTaskActionStatus.SUBMITTED.code(), operationId(action.getId()), now);
            if (actionMapper.transitionSubmitted(submission) != 1
                    || accountMapper.transitionMembership(new PullTaskFactTransition(
                    target.getId(), MEMBERSHIP_RESULT_STATES,
                    PullTaskGroupAccountMembershipStatus.JOINING.code(),
                    PullTaskFactResult.reason(null, null), now)) != 1) {
                return lost(candidate, now);
            }
            return PullTaskSupplementPullerPreparation.ready(
                    work(candidate, target, action.getId(), account, false));
        }
        if (Objects.equals(action.getActionStatus(), PullTaskActionStatus.UNKNOWN.code())) {
            if (actionMapper.reopenForVerification(
                    action.getId(), PullTaskActionStatus.UNKNOWN.code(),
                    PullTaskActionStatus.SUBMITTED.code(), now) != 1) {
                return lost(candidate, now);
            }
            return verificationWork(candidate, target, action.getId(), now);
        }
        if (Objects.equals(action.getActionStatus(), PullTaskActionStatus.SUBMITTED.code())) {
            return verificationWork(candidate, target, action.getId(), now);
        }
        return null;
    }

    private PullTaskSupplementPullerPreparation verificationWork(
            PullTaskGroupExecution candidate,
            PullTaskGroupAccount target,
            long actionId,
            long now) {
        ProtocolAccountRef account = resources.accountLookup()
                .findActiveProtocolRef(target.getAccountId()).orElse(null);
        if (account == null) {
            return waitForVerification(candidate, now);
        }
        return PullTaskSupplementPullerPreparation.ready(
                work(candidate, target, actionId, account, true));
    }

    private PullTaskSupplementPullerPreparation waitForVerification(
            PullTaskGroupExecution candidate, long now) {
        PullTaskGroupExecution update = transitionRow(candidate, now);
        update.setExecutionStatus(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        update.setWaitResourceType(PullTaskWaitResourceType.PULLER.code());
        update.setReasonCode(ACCOUNT_UNAVAILABLE);
        update.setReasonMessage(WAIT_MESSAGE);
        PullTaskExecutionDispatchResult result = resources.executionMapper().transitionClaimed(
                update, PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code()) == 1
                ? PullTaskExecutionDispatchResult.DEFERRED
                : PullTaskExecutionDispatchResult.LOST;
        if (result != PullTaskExecutionDispatchResult.LOST) {
            accountMapper.releaseAllPullersOfExecution(candidate.getId(), now);
        }
        return PullTaskSupplementPullerPreparation.completed(result);
    }

    private PullTaskSupplementPullerWork work(
            PullTaskGroupExecution candidate,
            PullTaskGroupAccount target,
            long actionId,
            ProtocolAccountRef account,
            boolean verificationOnly) {
        PullTaskSupplementPullerPayload payload = new PullTaskSupplementPullerPayload(
                account,
                new PullTaskSupplementPullerPayload.Group(
                        PullTaskGroupJoinArgumentResolver.resolve(
                                account.backend(), candidate.getNormalizedLink(),
                                candidate.getInviteCode()),
                        candidate.getGroupJid(),
                        operationId(actionId)),
                new PullTaskExecutionLease(candidate.getLockOwner(), candidate.getVersion()),
                verificationOnly);
        return new PullTaskSupplementPullerWork(
                candidate.getTenantId(), candidate.getId(), target.getId(), actionId, payload);
    }

    private boolean writeFacts(
            PullTaskSupplementPullerWork work,
            PullTaskSupplementPullerOutcome outcome,
            long now) {
        int actionStatus = switch (outcome.kind()) {
            case CONFIRMED -> PullTaskActionStatus.SUCCESS.code();
            case FAILED -> PullTaskActionStatus.FAILED.code();
            case UNKNOWN -> PullTaskActionStatus.UNKNOWN.code();
        };
        int membershipStatus = switch (outcome.kind()) {
            case CONFIRMED -> PullTaskGroupAccountMembershipStatus.IN_GROUP.code();
            case FAILED -> PullTaskGroupAccountMembershipStatus.JOIN_FAILED.code();
            case UNKNOWN -> PullTaskGroupAccountMembershipStatus.UNKNOWN.code();
        };
        PullTaskFactResult result = outcome.kind()
                == PullTaskSupplementPullerOutcome.Kind.CONFIRMED
                ? PullTaskFactResult.success(null, now)
                : PullTaskFactResult.reason(outcome.reasonCode(), outcome.reasonMessage());
        return actionMapper.transitionResult(new PullTaskFactTransition(
                work.actionId(), ACTION_RESULT_STATES, actionStatus, result, now)) == 1
                && accountMapper.transitionMembership(new PullTaskFactTransition(
                work.targetGroupAccountId(), MEMBERSHIP_RESULT_STATES,
                membershipStatus, result, now)) == 1;
    }

    private PullTaskExecutionDispatchResult transition(
            PullTaskSupplementPullerWork work,
            PullTaskSupplementPullerOutcome outcome,
            long now) {
        PullTaskGroupExecution update = transitionRow(work, now);
        if (outcome.kind() == PullTaskSupplementPullerOutcome.Kind.UNKNOWN) {
            update.setExecutionStatus(PullTaskExecutionStatus.WAIT_RESOURCE.code());
            update.setWaitResourceType(PullTaskWaitResourceType.PULLER.code());
            update.setReasonCode(outcome.reasonCode());
            update.setReasonMessage(WAIT_MESSAGE);
        } else {
            update.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        }
        PullTaskExecutionDispatchResult result = resources.executionMapper().transitionClaimed(
                update, PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code()) == 1
                ? PullTaskExecutionDispatchResult.DEFERRED
                : PullTaskExecutionDispatchResult.LOST;
        if (result != PullTaskExecutionDispatchResult.LOST
                && outcome.kind() == PullTaskSupplementPullerOutcome.Kind.UNKNOWN) {
            accountMapper.releaseAllPullersOfExecution(work.executionId(), now);
        }
        return result;
    }

    private PullTaskSupplementPullerPreparation continueContactStage(
            PullTaskGroupExecution candidate, long now) {
        PullTaskGroupExecution update = transitionRow(candidate, now);
        update.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        PullTaskExecutionDispatchResult result = resources.executionMapper().transitionClaimed(
                update, PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code()) == 1
                ? PullTaskExecutionDispatchResult.DEFERRED
                : PullTaskExecutionDispatchResult.LOST;
        return PullTaskSupplementPullerPreparation.completed(result);
    }

    private PullTaskSupplementPullerPreparation lost(
            PullTaskGroupExecution candidate, long now) {
        resources.executionMapper().releaseLock(
                candidate.getId(), candidate.getLockOwner(), now);
        return PullTaskSupplementPullerPreparation.completed(
                PullTaskExecutionDispatchResult.LOST);
    }

    private void markUnavailable(long roleRowId, String reasonCode, long now) {
        if (accountMapper.markUnavailable(
                roleRowId, PullTaskGroupAccountAvailability.OFFLINE.code(),
                reasonCode, null, now) != 1) {
            throw new IllegalStateException("补充拉手不可用状态回写失败");
        }
        accountMapper.releasePuller(roleRowId, now);
    }

    private static PullTaskGroupExecution transitionRow(
            PullTaskSupplementPullerWork work, long now) {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setId(work.executionId());
        row.setVersion(work.expectedVersion());
        row.setLockOwner(work.lockOwner());
        row.setGroupJid(work.groupJid());
        row.setStage(PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code());
        row.setNextRunAt(0L);
        row.setLastBusinessExecutedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    private static PullTaskGroupExecution transitionRow(
            PullTaskGroupExecution candidate, long now) {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setId(candidate.getId());
        row.setVersion(candidate.getVersion());
        row.setLockOwner(candidate.getLockOwner());
        row.setGroupJid(candidate.getGroupJid());
        row.setStage(PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code());
        row.setNextRunAt(0L);
        row.setLastBusinessExecutedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    private static boolean processable(PullTaskGroupAccount row) {
        return Objects.equals(row.getSourceType(), PullTaskGroupAccountSource.SUPPLEMENT.code())
                && Objects.equals(row.getEntryMode(), PullTaskAccountEntryMode.JOIN_BY_LINK.code())
                && Objects.equals(row.getAvailabilityStatus(),
                PullTaskGroupAccountAvailability.AVAILABLE.code())
                && row.getReleasedAt() == null
                && !Objects.equals(row.getMembershipStatus(),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code())
                && !Objects.equals(row.getMembershipStatus(),
                PullTaskGroupAccountMembershipStatus.JOIN_FAILED.code());
    }

    private static boolean isDispatchable(
            PullTask parent, PullTaskGroupExecution row, String lockOwner) {
        return parent != null
                && parent.getTaskType() == PullTaskType.STANDARD
                && NORMAL_LINK_MODE.equals(parent.getMode())
                && PullTaskStandardStatus.EXECUTING.name().equals(parent.getStatus())
                && Objects.equals(row.getExecutionStatus(),
                PullTaskExecutionStatus.EXECUTING.code())
                && Objects.equals(row.getStage(),
                PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code())
                && lockOwner != null && lockOwner.equals(row.getLockOwner());
    }

    private static boolean hasIdentity(PullTaskGroupExecution row) {
        return row != null && row.getTenantId() != null
                && row.getId() != null && row.getTaskId() != null;
    }

    private static String operationId(long actionId) {
        return "pull-task-supplement-puller:" + actionId;
    }

    private static void restoreTenant(Long previousTenant) {
        if (previousTenant == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenant);
        }
    }
}
