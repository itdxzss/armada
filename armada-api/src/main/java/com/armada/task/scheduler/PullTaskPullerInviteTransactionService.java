package com.armada.task.scheduler;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.command.ProtocolPullTaskPullerInviteCommandRequest;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionReasonCode;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountAdminStatus;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import com.armada.task.model.enums.PullTaskWaitResourceType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** EX-04 管理员轮询与单人邀请 Outbox 提交事务。 */
@Service
public class PullTaskPullerInviteTransactionService {

    private static final String NORMAL_LINK_MODE = "NORMAL_LINK";

    private final PullTaskMapper taskMapper;
    private final PullTaskGroupAccountMapper groupAccountMapper;
    private final PullTaskAccountActionMapper actionMapper;
    private final PullTaskPullerInviteResources resources;

    /**
     * @param taskMapper         父任务 Mapper
     * @param groupAccountMapper 执行行角色账号 Mapper
     * @param actionMapper       账号动作 Mapper
     * @param resources          执行行与账号域依赖
     */
    public PullTaskPullerInviteTransactionService(
            PullTaskMapper taskMapper,
            PullTaskGroupAccountMapper groupAccountMapper,
            PullTaskAccountActionMapper actionMapper,
            PullTaskPullerInviteResources resources) {
        this.taskMapper = taskMapper;
        this.groupAccountMapper = groupAccountMapper;
        this.actionMapper = actionMapper;
        this.resources = resources;
    }

    /** 在短事务内选下一位管理员和未产生邀请动作的拉手，并预写单人邀请。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskExecutionDispatchResult prepare(
            PullTaskGroupExecution candidate, String lockOwner, long now) {
        if (!hasIdentity(candidate)) {
            return PullTaskExecutionDispatchResult.LOST;
        }
        Long previousTenant = TenantContext.get();
        TenantContext.set(candidate.getTenantId());
        try {
            PullTask parent = taskMapper.selectLifecycle(candidate.getTaskId());
            if (!isDispatchable(parent, candidate, lockOwner)) {
                resources.executionMapper().releaseLock(candidate.getId(), lockOwner, now);
                return PullTaskExecutionDispatchResult.LOST;
            }
            ManagerPool managerPool = managerPool(candidate.getId());
            if (managerPool.managers().isEmpty()) {
                return waitForResource(candidate, PullTaskWaitResourceType.MANAGER,
                        PullTaskExecutionReasonCode.MANAGER_UNAVAILABLE, now);
            }
            List<PullTaskGroupAccount> pullers = availablePullers(candidate.getId());
            if (pullers.isEmpty()) {
                return waitForResource(candidate, PullTaskWaitResourceType.PULLER,
                        PullTaskExecutionReasonCode.PULLER_UNAVAILABLE, now);
            }
            List<PullTaskAccountAction> actions = inviteActions(candidate.getId());
            if (hasSubmitted(actions)) {
                return deferSubmitted(candidate, actions, managerPool.managers(), now);
            }
            PullerSelection selection = nextUninvitedPuller(
                    pullers, actions, candidate.getNextPullerIndex());
            if (selection == null) {
                return finishInvites(candidate, pullers, now);
            }
            return submit(candidate, managerPool, selection, actions, now);
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private ManagerPool managerPool(long executionId) {
        List<PullTaskGroupAccount> stored = groupAccountMapper.selectByExecutionAndRole(
                        executionId, PullTaskGroupAccountRole.MANAGER.code())
                .stream()
                .filter(PullTaskPullerInviteTransactionService::available)
                .filter(row -> Objects.equals(row.getMembershipStatus(),
                        PullTaskGroupAccountMembershipStatus.IN_GROUP.code()))
                .filter(row -> Objects.equals(row.getAdminStatus(),
                        PullTaskGroupAccountAdminStatus.SUCCESS.code()))
                .toList();
        List<Long> accountIds = stored.stream()
                .map(PullTaskGroupAccount::getAccountId).distinct().toList();
        Map<Long, ProtocolAccountRef> refs = new HashMap<>();
        for (ProtocolAccountRef ref : resources.accountLookup()
                .findActiveProtocolRefs(accountIds)) {
            if (ref != null) {
                refs.putIfAbsent(ref.armadaAccountId(), ref);
            }
        }
        List<PullTaskGroupAccount> dispatchable = stored.stream()
                .filter(row -> refs.containsKey(row.getAccountId()))
                .toList();
        return new ManagerPool(dispatchable, refs);
    }

    private List<PullTaskGroupAccount> availablePullers(long executionId) {
        return groupAccountMapper.selectByExecutionAndRole(
                        executionId, PullTaskGroupAccountRole.PULLER.code())
                .stream()
                .filter(PullTaskPullerInviteTransactionService::available)
                .filter(row -> row.getReleasedAt() == null)
                .toList();
    }

    private PullTaskExecutionDispatchResult submit(
            PullTaskGroupExecution candidate,
            ManagerPool pool,
            PullerSelection selection,
            List<PullTaskAccountAction> actions,
            long now) {
        int managerIndex = nextManagerAfterLastAction(
                actions, pool.managers(), candidate.getNextManagerIndex());
        PullTaskGroupAccount manager = pool.managers().get(managerIndex);
        PullTaskGroupAccount target = selection.puller();
        PullTaskAccountAction action = insertAction(candidate, manager.getId(), target.getId(), now);
        ProtocolCommandOutboxEnqueueResult enqueued = resources.outboxService()
                .enqueuePullTaskPullerInviteCommands(List.of(
                        new ProtocolPullTaskPullerInviteCommandRequest(
                                candidate.getTenantId(), candidate.getTaskId(), candidate.getId(),
                                action.getId(), pool.refs().get(manager.getAccountId()))));
        String commandId = singleCommandId(enqueued);
        if (actionMapper.markSubmitted(action.getId(), commandId, now) != 1
                || groupAccountMapper.updateMembership(target.getId(),
                PullTaskGroupAccountMembershipStatus.JOINING.code(), null, now) != 1) {
            throw new IllegalStateException("拉手邀请提交状态已变化");
        }
        PullTaskGroupExecution update = transition(candidate, now);
        update.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        update.setStage(PullTaskExecutionStage.PULLER_INVITE.code());
        update.setNextManagerIndex((managerIndex + 1) % pool.managers().size());
        update.setNextPullerIndex((selection.index() + 1) % selection.poolSize());
        update.setNextRunAt(Math.addExact(
                now, resources.properties().getResultReconciliationDelayMs()));
        if (resources.executionMapper().transitionClaimed(
                update, PullTaskExecutionStage.PULLER_INVITE.code()) != 1) {
            throw new IllegalStateException("拉手邀请提交后执行行租约已变化");
        }
        return PullTaskExecutionDispatchResult.DEFERRED;
    }

    private static String singleCommandId(ProtocolCommandOutboxEnqueueResult enqueued) {
        if (enqueued == null || enqueued.inserted() != 1
                || enqueued.commandIds() == null || enqueued.commandIds().size() != 1
                || enqueued.commandIds().get(0) == null
                || enqueued.commandIds().get(0).isBlank()) {
            throw new IllegalStateException("拉手邀请 Outbox 写入结果不完整");
        }
        return enqueued.commandIds().get(0);
    }

    private PullTaskAccountAction insertAction(
            PullTaskGroupExecution candidate,
            long managerId,
            long pullerId,
            long now) {
        PullTaskAccountAction row = new PullTaskAccountAction();
        row.setTaskId(candidate.getTaskId());
        row.setGroupExecutionId(candidate.getId());
        row.setActionType(PullTaskAccountActionType.INVITE_TO_GROUP.code());
        row.setActorGroupAccountId(managerId);
        row.setTargetGroupAccountId(pullerId);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        if (actionMapper.insertIfAbsent(row) != 1 || row.getId() == null) {
            throw new IllegalStateException("拉手邀请动作已存在或写入失败");
        }
        return row;
    }

    private PullTaskExecutionDispatchResult deferSubmitted(
            PullTaskGroupExecution candidate,
            List<PullTaskAccountAction> actions,
            List<PullTaskGroupAccount> managers,
            long now) {
        PullTaskGroupExecution update = transition(candidate, now);
        update.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        update.setStage(PullTaskExecutionStage.PULLER_INVITE.code());
        update.setNextManagerIndex(nextManagerAfterLastAction(
                actions, managers, candidate.getNextManagerIndex()));
        update.setNextPullerIndex(nextPullerAfterLastAction(
                actions, availablePullers(candidate.getId()), candidate.getNextPullerIndex()));
        update.setNextRunAt(Math.addExact(
                now, resources.properties().getResultReconciliationDelayMs()));
        if (resources.executionMapper().transitionClaimed(
                update, PullTaskExecutionStage.PULLER_INVITE.code()) != 1) {
            return PullTaskExecutionDispatchResult.LOST;
        }
        return PullTaskExecutionDispatchResult.DEFERRED;
    }

    private PullTaskExecutionDispatchResult finishInvites(
            PullTaskGroupExecution candidate,
            List<PullTaskGroupAccount> pullers,
            long now) {
        boolean hasJoinedPuller = pullers.stream().anyMatch(row -> Objects.equals(
                row.getMembershipStatus(), PullTaskGroupAccountMembershipStatus.IN_GROUP.code()));
        if (!hasJoinedPuller) {
            List<PullTaskGroupAccount> failedPullers = pullers.stream()
                    .filter(row -> Objects.equals(row.getMembershipStatus(),
                            PullTaskGroupAccountMembershipStatus.JOIN_FAILED.code()))
                    .filter(row -> row.getReleasedAt() == null)
                    .toList();
            for (PullTaskGroupAccount row : failedPullers) {
                if (groupAccountMapper.releasePuller(row.getId(), now) != 1) {
                    throw new IllegalStateException("失败拉手释放事实写入不完整");
                }
            }
            return transitionStage(
                    candidate, PullTaskExecutionStage.MANAGER_PULLER_CONTACT,
                    PullTaskExecutionDispatchResult.ADVANCED, now);
        }
        PullTaskGroupExecution update = transition(candidate, now);
        update.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        update.setStage(PullTaskExecutionStage.PULL_EXECUTION.code());
        update.setNextPullerIndex(0);
        update.setNextRunAt(0L);
        if (resources.executionMapper().transitionClaimed(
                update, PullTaskExecutionStage.PULLER_INVITE.code()) != 1) {
            return PullTaskExecutionDispatchResult.LOST;
        }
        return PullTaskExecutionDispatchResult.ADVANCED;
    }

    private PullTaskExecutionDispatchResult transitionStage(
            PullTaskGroupExecution candidate,
            PullTaskExecutionStage targetStage,
            PullTaskExecutionDispatchResult success,
            long now) {
        PullTaskGroupExecution update = transition(candidate, now);
        update.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        update.setStage(targetStage.code());
        update.setNextRunAt(0L);
        if (resources.executionMapper().transitionClaimed(
                update, PullTaskExecutionStage.PULLER_INVITE.code()) != 1) {
            throw new IllegalStateException("拉手邀请完成后执行行租约已变化");
        }
        return success;
    }

    private PullTaskExecutionDispatchResult waitForResource(
            PullTaskGroupExecution candidate,
            PullTaskWaitResourceType resourceType,
            PullTaskExecutionReasonCode reason,
            long now) {
        PullTaskGroupExecution update = transition(candidate, now);
        update.setExecutionStatus(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        update.setStage(PullTaskExecutionStage.PULLER_INVITE.code());
        update.setWaitResourceType(resourceType.code());
        update.setReasonCode(reason.name());
        update.setReasonMessage(waitMessage(candidate.getId(), resourceType, reason));
        update.setLastBusinessExecutedAt(null);
        if (resources.executionMapper().transitionClaimed(
                update, PullTaskExecutionStage.PULLER_INVITE.code()) != 1) {
            return PullTaskExecutionDispatchResult.LOST;
        }
        groupAccountMapper.releaseAllPullersOfExecution(candidate.getId(), now);
        return PullTaskExecutionDispatchResult.DEFERRED;
    }

    private String waitMessage(
            long executionId,
            PullTaskWaitResourceType resourceType,
            PullTaskExecutionReasonCode reason) {
        if (resourceType == PullTaskWaitResourceType.MANAGER) {
            return reason.message() + "，缺口人数=1";
        }
        List<PullTaskGroupAccount> pullers = groupAccountMapper.selectByExecutionAndRole(
                executionId, PullTaskGroupAccountRole.PULLER.code());
        long current = pullers.stream()
                .filter(PullTaskPullerInviteTransactionService::available)
                .filter(row -> Objects.equals(row.getMembershipStatus(),
                        PullTaskGroupAccountMembershipStatus.IN_GROUP.code()))
                .count();
        long missing = Math.max(pullers.size() - current, 1L);
        return reason.message() + "，缺口人数=" + missing;
    }

    private List<PullTaskAccountAction> inviteActions(long executionId) {
        return actionMapper.selectByExecutionAndType(
                executionId, PullTaskAccountActionType.INVITE_TO_GROUP.code());
    }

    private static PullerSelection nextUninvitedPuller(
            List<PullTaskGroupAccount> pullers,
            List<PullTaskAccountAction> actions,
            Integer storedIndex) {
        Set<Long> invitedIds = new HashSet<>();
        actions.stream().map(PullTaskAccountAction::getTargetGroupAccountId)
                .forEach(invitedIds::add);
        int start = Math.floorMod(storedIndex == null ? 0 : storedIndex, pullers.size());
        for (int offset = 0; offset < pullers.size(); offset++) {
            int index = (start + offset) % pullers.size();
            PullTaskGroupAccount puller = pullers.get(index);
            if (requiresManagerInvite(puller) && !invitedIds.contains(puller.getId())) {
                return new PullerSelection(puller, index, pullers.size());
            }
        }
        return null;
    }

    private static int nextPullerAfterLastAction(
            List<PullTaskAccountAction> actions,
            List<PullTaskGroupAccount> pullers,
            Integer storedIndex) {
        int fallback = Math.floorMod(storedIndex == null ? 0 : storedIndex, pullers.size());
        if (actions.isEmpty()) {
            return fallback;
        }
        Long lastTargetId = actions.get(actions.size() - 1).getTargetGroupAccountId();
        for (int index = 0; index < pullers.size(); index++) {
            if (Objects.equals(pullers.get(index).getId(), lastTargetId)) {
                return (index + 1) % pullers.size();
            }
        }
        return fallback;
    }

    private static boolean requiresManagerInvite(PullTaskGroupAccount row) {
        return !Objects.equals(row.getMembershipStatus(),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code())
                && !Objects.equals(row.getEntryMode(),
                com.armada.task.model.enums.PullTaskAccountEntryMode.JOIN_BY_LINK.code());
    }

    private static boolean hasSubmitted(List<PullTaskAccountAction> actions) {
        return actions.stream().anyMatch(action -> Objects.equals(
                action.getActionStatus(), PullTaskActionStatus.SUBMITTED.code()));
    }

    private static int nextManagerAfterLastAction(
            List<PullTaskAccountAction> actions,
            List<PullTaskGroupAccount> managers,
            Integer storedIndex) {
        int fallback = Math.floorMod(storedIndex == null ? 0 : storedIndex, managers.size());
        if (actions.isEmpty()) {
            return fallback;
        }
        Long lastActorId = actions.get(actions.size() - 1).getActorGroupAccountId();
        for (int index = 0; index < managers.size(); index++) {
            if (Objects.equals(managers.get(index).getId(), lastActorId)) {
                return (index + 1) % managers.size();
            }
        }
        return fallback;
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

    private static boolean available(PullTaskGroupAccount row) {
        return Objects.equals(row.getAvailabilityStatus(),
                PullTaskGroupAccountAvailability.AVAILABLE.code());
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
                && row.getStage() == PullTaskExecutionStage.PULLER_INVITE.code()
                && lockOwner != null && lockOwner.equals(row.getLockOwner());
    }

    private static void restoreTenant(Long previousTenant) {
        if (previousTenant == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenant);
        }
    }

    private record ManagerPool(
            List<PullTaskGroupAccount> managers,
            Map<Long, ProtocolAccountRef> refs) {
    }

    private record PullerSelection(
            PullTaskGroupAccount puller,
            int index,
            int poolSize) {
    }
}
