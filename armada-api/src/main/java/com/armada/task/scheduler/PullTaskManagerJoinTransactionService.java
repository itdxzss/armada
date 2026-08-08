package com.armada.task.scheduler;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.command.ProtocolPullTaskGroupJoinCommandRequest;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.dto.PullTaskManagerJoinPayload;
import com.armada.task.model.dto.PullTaskManagerJoinWork;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionReasonCode;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import com.armada.task.model.enums.PullTaskWaitResourceType;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** EX-02 管理账号选择、动作预写与结果原子回写。 */
@Service
public class PullTaskManagerJoinTransactionService {

    private static final String NORMAL_LINK_MODE = "NORMAL_LINK";
    private static final String HTTPS_SCHEME_PREFIX = "https://";
    private static final int INITIAL_SOURCE = 1;
    private static final int AUTOMATIC_SELECTION = 1;
    private static final int JOIN_BY_LINK_ENTRY = 1;

    private final PullTaskMapper taskMapper;
    private final PullTaskStandardSettingMapper settingMapper;
    private final PullTaskGroupAccountMapper groupAccountMapper;
    private final PullTaskAccountActionMapper actionMapper;
    private final PullTaskManagerJoinResources resources;

    /**
     * @param taskMapper         父任务 Mapper
     * @param settingMapper      普通任务配置 Mapper
     * @param groupAccountMapper 执行行角色账号 Mapper
     * @param actionMapper       账号动作 Mapper
     * @param resources          执行行与账号域依赖
     */
    public PullTaskManagerJoinTransactionService(
            PullTaskMapper taskMapper,
            PullTaskStandardSettingMapper settingMapper,
            PullTaskGroupAccountMapper groupAccountMapper,
            PullTaskAccountActionMapper actionMapper,
            PullTaskManagerJoinResources resources) {
        this.taskMapper = taskMapper;
        this.settingMapper = settingMapper;
        this.groupAccountMapper = groupAccountMapper;
        this.actionMapper = actionMapper;
        this.resources = resources;
    }

    /** 在短事务内复核租约、选择一个管理员并预写待执行动作。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskManagerJoinPreparation prepare(
            PullTaskGroupExecution candidate, String lockOwner, long now) {
        if (!hasIdentity(candidate)) {
            return PullTaskManagerJoinPreparation.completed(
                    PullTaskExecutionDispatchResult.LOST);
        }
        Long previousTenant = TenantContext.get();
        TenantContext.set(candidate.getTenantId());
        try {
            PullTask parent = taskMapper.selectLifecycle(candidate.getTaskId());
            if (!isDispatchable(parent, candidate, lockOwner)) {
                resources.executionMapper().releaseLock(candidate.getId(), lockOwner, now);
                return PullTaskManagerJoinPreparation.completed(
                        PullTaskExecutionDispatchResult.LOST);
            }
            List<PullTaskGroupAccount> existing = groupAccountMapper.selectByExecutionAndRole(
                    candidate.getId(), PullTaskGroupAccountRole.MANAGER.code());
            if (!existing.isEmpty()) {
                return prepareExisting(candidate, existing.get(0), now);
            }
            return selectAndPrepare(candidate, now);
        } finally {
            restoreTenant(previousTenant);
        }
    }

    /** 把事务外协议结果、角色状态、动作状态和执行检查点一次性落库。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskExecutionDispatchResult complete(
            PullTaskManagerJoinWork work,
            PullTaskManagerJoinOutcome outcome,
            long now) {
        Long previousTenant = TenantContext.get();
        TenantContext.set(work.tenantId());
        try {
            PullTaskGroupExecution update = completionTransition(work, outcome, now);
            if (resources.executionMapper().transitionClaimed(
                    update, PullTaskExecutionStage.MANAGER_JOIN.code()) != 1) {
                return PullTaskExecutionDispatchResult.LOST;
            }
            writeActionResult(work, outcome, now);
            writeMembershipResult(work, outcome, now);
            if (outcome.kind() == PullTaskManagerJoinOutcome.Kind.EXECUTION_FAILED) {
                resources.parentCompletionService().completeIfTerminalByExecutionId(
                        work.executionId(), now);
            }
            return switch (outcome.kind()) {
                case CONFIRMED -> PullTaskExecutionDispatchResult.ADVANCED;
                case EXECUTION_FAILED -> PullTaskExecutionDispatchResult.FAILED;
                case PENDING_APPROVAL, MANAGER_FAILED, UNCONFIRMED ->
                        PullTaskExecutionDispatchResult.DEFERRED;
            };
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private PullTaskManagerJoinPreparation selectAndPrepare(
            PullTaskGroupExecution candidate, long now) {
        PullTaskStandardSetting setting = settingMapper.selectByTaskId(candidate.getTaskId());
        if (setting == null) {
            return waitForManager(candidate,
                    PullTaskExecutionReasonCode.MANAGER_UNAVAILABLE, now);
        }
        AccountProtocolLookupService accountLookup = resources.accountLookup();
        ProtocolAccountRef selected = accountLookup
                .findRandomOnlineNormalByGroupId(setting.getManagerGroupId())
                .orElse(null);
        if (selected == null) {
            return waitForManager(candidate,
                    PullTaskExecutionReasonCode.MANAGER_UNAVAILABLE, now);
        }
        PullTaskGroupAccount manager = insertManager(candidate, selected, now);
        PullTaskAccountAction action = insertJoinAction(candidate, manager.getId(), now);
        return submit(candidate, manager, action, selected, now);
    }

    private PullTaskManagerJoinPreparation prepareExisting(
            PullTaskGroupExecution candidate,
            PullTaskGroupAccount manager,
            long now) {
        List<PullTaskAccountAction> actions = actionMapper.selectByExecutionAndType(
                candidate.getId(), PullTaskAccountActionType.JOIN_BY_LINK.code());
        PullTaskAccountAction action = actions.stream()
                .filter(row -> manager.getId().equals(row.getTargetGroupAccountId()))
                .findFirst()
                .orElseGet(() -> insertJoinAction(candidate, manager.getId(), now));
        if (manager.getMembershipStatus()
                == PullTaskGroupAccountMembershipStatus.IN_GROUP.code()) {
            PullTaskManagerJoinOutcome outcome = candidate.getGroupJid() == null
                    ? PullTaskManagerJoinOutcome.unconfirmed(
                            null, PullTaskExecutionReasonCode.MANAGER_MEMBERSHIP_UNCONFIRMED.name())
                    : PullTaskManagerJoinOutcome.confirmed(candidate.getGroupJid());
            PullTaskManagerJoinWork work = work(candidate, manager, action);
            return PullTaskManagerJoinPreparation.completed(complete(work, outcome, now));
        }
        if (action.getActionStatus() != null
                && (action.getActionStatus() == PullTaskActionStatus.SUBMITTED.code()
                || action.getActionStatus() == PullTaskActionStatus.UNKNOWN.code())) {
            return recover(candidate, manager, action, now);
        }
        if (action.getActionStatus() != null
                && action.getActionStatus() != PullTaskActionStatus.PENDING.code()) {
            return waitForManager(candidate,
                    PullTaskExecutionReasonCode.MANAGER_MEMBERSHIP_UNCONFIRMED, now);
        }
        ProtocolAccountRef account = resources.accountLookup()
                .findActiveProtocolRef(manager.getAccountId())
                .orElse(null);
        if (account == null) {
            return waitForManager(candidate,
                    PullTaskExecutionReasonCode.MANAGER_UNAVAILABLE, now);
        }
        return submit(candidate, manager, action, account, now);
    }

    private PullTaskManagerJoinPreparation recover(
            PullTaskGroupExecution candidate,
            PullTaskGroupAccount manager,
            PullTaskAccountAction action,
            long now) {
        if (action.getActionStatus() == PullTaskActionStatus.UNKNOWN.code()
                && actionMapper.reopenForVerification(
                action.getId(), PullTaskActionStatus.UNKNOWN.code(),
                PullTaskActionStatus.SUBMITTED.code(), now) != 1) {
            resources.executionMapper().releaseLock(
                    candidate.getId(), candidate.getLockOwner(), now);
            return PullTaskManagerJoinPreparation.completed(
                    PullTaskExecutionDispatchResult.LOST);
        }
        ProtocolAccountRef account = resources.accountLookup()
                .findActiveProtocolRef(manager.getAccountId())
                .orElse(null);
        if (account == null) {
            return waitForManager(candidate,
                    PullTaskExecutionReasonCode.MANAGER_UNAVAILABLE, now);
        }
        String inviteLinkOrCode = switch (account.backend()) {
            case WEB -> HTTPS_SCHEME_PREFIX + candidate.getNormalizedLink();
            case ANDROID -> candidate.getInviteCode();
        };
        PullTaskManagerJoinPayload payload = new PullTaskManagerJoinPayload(
                account, inviteLinkOrCode, operationId(action.getId()),
                candidate.getLockOwner(), candidate.getVersion(), candidate.getGroupJid());
        return PullTaskManagerJoinPreparation.ready(new PullTaskManagerJoinWork(
                candidate.getTenantId(), candidate.getId(), manager.getId(), action.getId(), payload));
    }

    private PullTaskManagerJoinPreparation submit(
            PullTaskGroupExecution candidate,
            PullTaskGroupAccount manager,
            PullTaskAccountAction action,
            ProtocolAccountRef account,
            long now) {
        ProtocolCommandOutboxEnqueueResult enqueued = resources.outboxService()
                .enqueuePullTaskGroupJoinCommands(List.of(
                        new ProtocolPullTaskGroupJoinCommandRequest(
                                candidate.getTenantId(), candidate.getTaskId(), candidate.getId(),
                                action.getId(), account)));
        String commandId = singleCommandId(enqueued);
        if (actionMapper.markSubmitted(action.getId(), commandId, now) != 1
                || groupAccountMapper.updateMembership(
                        manager.getId(), PullTaskGroupAccountMembershipStatus.JOINING.code(),
                        null, now) != 1) {
            throw new IllegalStateException("管理员入群动作状态已变化");
        }
        PullTaskGroupExecution update = baseTransition(
                candidate.getId(), candidate.getVersion(), candidate.getLockOwner(), now);
        update.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        update.setStage(PullTaskExecutionStage.MANAGER_JOIN.code());
        update.setGroupJid(candidate.getGroupJid());
        update.setNextRunAt(now + resources.properties().getResultReconciliationDelayMs());
        if (resources.executionMapper().transitionClaimed(
                update, PullTaskExecutionStage.MANAGER_JOIN.code()) != 1) {
            throw new IllegalStateException("管理员入群执行行租约已变化");
        }
        return PullTaskManagerJoinPreparation.completed(PullTaskExecutionDispatchResult.DEFERRED);
    }

    private static String singleCommandId(ProtocolCommandOutboxEnqueueResult enqueued) {
        if (enqueued == null || enqueued.inserted() != 1
                || enqueued.commandIds() == null || enqueued.commandIds().size() != 1
                || enqueued.commandIds().get(0) == null
                || enqueued.commandIds().get(0).isBlank()) {
            throw new IllegalStateException("管理员入群 Outbox 写入结果不完整");
        }
        return enqueued.commandIds().get(0);
    }

    private PullTaskManagerJoinPreparation waitForManager(
            PullTaskGroupExecution candidate,
            PullTaskExecutionReasonCode reason,
            long now) {
        PullTaskGroupExecution update = baseTransition(candidate.getId(), candidate.getVersion(),
                candidate.getLockOwner(), now);
        update.setGroupJid(candidate.getGroupJid());
        update.setLastBusinessExecutedAt(null);
        update.setExecutionStatus(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        update.setStage(PullTaskExecutionStage.MANAGER_JOIN.code());
        update.setWaitResourceType(PullTaskWaitResourceType.MANAGER.code());
        update.setReasonCode(reason.name());
        update.setReasonMessage(reason.message() + "，缺口人数=1");
        if (resources.executionMapper().transitionClaimed(
                update, PullTaskExecutionStage.MANAGER_JOIN.code()) != 1) {
            return PullTaskManagerJoinPreparation.completed(PullTaskExecutionDispatchResult.LOST);
        }
        return PullTaskManagerJoinPreparation.completed(PullTaskExecutionDispatchResult.DEFERRED);
    }

    private PullTaskGroupAccount insertManager(
            PullTaskGroupExecution candidate,
            ProtocolAccountRef account,
            long now) {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setTaskId(candidate.getTaskId());
        row.setGroupExecutionId(candidate.getId());
        row.setAccountId(account.armadaAccountId());
        row.setAccountPhone(account.wsPhone());
        row.setRoleType(PullTaskGroupAccountRole.MANAGER.code());
        row.setRoleSeq(1);
        row.setSourceType(INITIAL_SOURCE);
        row.setSelectionMode(AUTOMATIC_SELECTION);
        row.setEntryMode(JOIN_BY_LINK_ENTRY);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        if (groupAccountMapper.insert(row) != 1 || row.getId() == null) {
            throw new IllegalStateException("管理员角色行写入失败");
        }
        return row;
    }

    private PullTaskAccountAction insertJoinAction(
            PullTaskGroupExecution candidate,
            long groupAccountId,
            long now) {
        PullTaskAccountAction row = new PullTaskAccountAction();
        row.setTaskId(candidate.getTaskId());
        row.setGroupExecutionId(candidate.getId());
        row.setActionType(PullTaskAccountActionType.JOIN_BY_LINK.code());
        row.setActorGroupAccountId(groupAccountId);
        row.setTargetGroupAccountId(groupAccountId);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        if (actionMapper.insertIfAbsent(row) != 1 || row.getId() == null) {
            throw new IllegalStateException("管理员踩链接动作写入失败");
        }
        row.setActionStatus(PullTaskActionStatus.PENDING.code());
        return row;
    }

    private PullTaskManagerJoinWork work(
            PullTaskGroupExecution candidate,
            PullTaskGroupAccount manager,
            PullTaskAccountAction action) {
        ProtocolAccountRef account = resources.accountLookup()
                .findActiveProtocolRef(manager.getAccountId())
                .orElseThrow(() -> new IllegalStateException("管理员协议身份不存在"));
        PullTaskManagerJoinPayload payload = new PullTaskManagerJoinPayload(
                account, candidate.getNormalizedLink(), operationId(action.getId()),
                candidate.getLockOwner(), candidate.getVersion());
        return new PullTaskManagerJoinWork(candidate.getTenantId(), candidate.getId(),
                manager.getId(), action.getId(), payload);
    }

    private static PullTaskGroupExecution completionTransition(
            PullTaskManagerJoinWork work,
            PullTaskManagerJoinOutcome outcome,
            long now) {
        PullTaskGroupExecution update = baseTransition(
                work.executionId(), work.expectedVersion(), work.lockOwner(), now);
        if (outcome.kind() == PullTaskManagerJoinOutcome.Kind.CONFIRMED) {
            update.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
            update.setStage(PullTaskExecutionStage.MANAGER_ADMIN.code());
            update.setGroupJid(outcome.groupJid());
            return update;
        }
        if (outcome.kind() == PullTaskManagerJoinOutcome.Kind.EXECUTION_FAILED) {
            update.setExecutionStatus(PullTaskExecutionStatus.FAILED.code());
            update.setStage(PullTaskExecutionStage.MANAGER_JOIN.code());
            update.setReasonCode(outcome.reasonCode());
            update.setReasonMessage(outcome.reasonMessage());
            update.setFinishedAt(now);
            return update;
        }
        if (outcome.kind() == PullTaskManagerJoinOutcome.Kind.PENDING_APPROVAL) {
            update.setExecutionStatus(PullTaskExecutionStatus.WAIT_RESOURCE.code());
            update.setStage(PullTaskExecutionStage.MANAGER_JOIN.code());
            update.setGroupJid(outcome.groupJid());
            update.setWaitResourceType(PullTaskWaitResourceType.APPROVAL.code());
            update.setReasonCode(outcome.reasonCode());
            update.setReasonMessage(outcome.reasonMessage());
            return update;
        }
        update.setExecutionStatus(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        update.setStage(PullTaskExecutionStage.MANAGER_JOIN.code());
        update.setGroupJid(outcome.groupJid());
        update.setWaitResourceType(PullTaskWaitResourceType.MANAGER.code());
        update.setReasonCode(outcome.reasonCode());
        update.setReasonMessage(outcome.reasonMessage() + "，缺口人数=1");
        return update;
    }

    private void writeActionResult(
            PullTaskManagerJoinWork work,
            PullTaskManagerJoinOutcome outcome,
            long now) {
        int status = switch (outcome.kind()) {
            case CONFIRMED -> PullTaskActionStatus.SUCCESS.code();
            case PENDING_APPROVAL -> PullTaskActionStatus.PENDING_APPROVAL.code();
            case MANAGER_FAILED, EXECUTION_FAILED -> PullTaskActionStatus.FAILED.code();
            case UNCONFIRMED -> PullTaskActionStatus.UNKNOWN.code();
        };
        if (actionMapper.writeBackResult(work.actionId(), status,
                outcome.reasonCode(), outcome.reasonMessage(), now) != 1) {
            throw new IllegalStateException("管理员踩链接结果回写失败");
        }
    }

    private void writeMembershipResult(
            PullTaskManagerJoinWork work,
            PullTaskManagerJoinOutcome outcome,
            long now) {
        int membership = switch (outcome.kind()) {
            case CONFIRMED -> PullTaskGroupAccountMembershipStatus.IN_GROUP.code();
            case PENDING_APPROVAL -> PullTaskGroupAccountMembershipStatus.PENDING_APPROVAL.code();
            case MANAGER_FAILED, EXECUTION_FAILED ->
                    PullTaskGroupAccountMembershipStatus.JOIN_FAILED.code();
            case UNCONFIRMED -> PullTaskGroupAccountMembershipStatus.UNKNOWN.code();
        };
        Long joinedAt = outcome.kind() == PullTaskManagerJoinOutcome.Kind.CONFIRMED ? now : null;
        if (groupAccountMapper.updateMembership(
                work.groupAccountId(), membership, joinedAt, now) != 1) {
            throw new IllegalStateException("管理员在群状态回写失败");
        }
        if (outcome.kind() == PullTaskManagerJoinOutcome.Kind.MANAGER_FAILED) {
            if (groupAccountMapper.markUnavailable(
                    work.groupAccountId(), PullTaskGroupAccountAvailability.OFFLINE.code(),
                    outcome.reasonCode(), null, now) != 1) {
                throw new IllegalStateException("管理员可用状态回写失败");
            }
        }
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
                && row.getStage() == PullTaskExecutionStage.MANAGER_JOIN.code()
                && lockOwner != null && lockOwner.equals(row.getLockOwner());
    }

    private static String operationId(long actionId) {
        return "pull-task-manager-join:" + actionId;
    }

    private static void restoreTenant(Long previousTenant) {
        if (previousTenant == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenant);
        }
    }
}
