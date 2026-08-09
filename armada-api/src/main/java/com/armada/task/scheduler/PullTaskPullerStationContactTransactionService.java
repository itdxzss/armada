package com.armada.task.scheduler;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.command.ProtocolPullTaskContactSaveCommandRequest;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** EX-05 为单次调用建立并提交拉手—站台双向联系人 Outbox 动作。 */
@Service
public class PullTaskPullerStationContactTransactionService {

    private static final String NORMAL_LINK_MODE = "NORMAL_LINK";
    private static final String ACCOUNT_UNAVAILABLE = "ACCOUNT_UNAVAILABLE";

    private final PullTaskMapper taskMapper;
    private final PullTaskGroupAccountMapper groupAccountMapper;
    private final PullTaskAccountActionMapper actionMapper;
    private final PullTaskPullerStationContactResources resources;

    /**
     * @param taskMapper         父任务 Mapper
     * @param groupAccountMapper 角色账号 Mapper
     * @param actionMapper       账号动作 Mapper
     * @param resources          执行行、账号域和调用依赖
     */
    public PullTaskPullerStationContactTransactionService(
            PullTaskMapper taskMapper,
            PullTaskGroupAccountMapper groupAccountMapper,
            PullTaskAccountActionMapper actionMapper,
            PullTaskPullerStationContactResources resources) {
        this.taskMapper = taskMapper;
        this.groupAccountMapper = groupAccountMapper;
        this.actionMapper = actionMapper;
        this.resources = resources;
    }

    /** 补齐本次调用的双向联系人动作，并提交一条待执行方向。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskStationContactStepResult prepare(
            PullTaskGroupExecution candidate,
            PullTaskPullCall call,
            String lockOwner,
            long now) {
        if (!hasIdentity(candidate)) {
            return PullTaskStationContactStepResult.LOST;
        }
        Long previousTenant = TenantContext.get();
        TenantContext.set(candidate.getTenantId());
        try {
            PullTaskGroupExecution execution = resources.executionMapper()
                    .selectById(candidate.getId());
            PullTask parent = taskMapper.selectLifecycle(candidate.getTaskId());
            PullTaskPullCall storedCall = currentPlannedCall(
                    candidate.getId(), call == null ? null : call.getId());
            if (!isDispatchable(parent, execution, storedCall,
                    call == null ? null : call.getId(), lockOwner)) {
                resources.executionMapper().releaseLock(candidate.getId(), lockOwner, now);
                return PullTaskStationContactStepResult.LOST;
            }
            ContactScope scope = contactScope(execution.getId(), storedCall);
            if (scope == null) {
                resources.executionMapper().releaseLock(candidate.getId(), lockOwner, now);
                return PullTaskStationContactStepResult.LOST;
            }
            createActions(execution, scope, now);
            List<PullTaskAccountAction> actions = relevantActions(execution.getId(), scope);
            if (hasSubmitted(actions)) {
                return deferSubmitted(execution, now, false);
            }
            return preparePending(execution, scope, actions, now);
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private ContactScope contactScope(long executionId, PullTaskPullCall call) {
        PullTaskGroupAccount puller = groupAccountMapper.selectByExecutionAndRole(
                        executionId, PullTaskGroupAccountRole.PULLER.code())
                .stream()
                .filter(row -> Objects.equals(row.getId(), call.getPullerGroupAccountId()))
                .findFirst()
                .orElse(null);
        List<PullTaskGroupAccount> stations = groupAccountMapper.selectByExecutionAndRole(
                        executionId, PullTaskGroupAccountRole.STATION.code())
                .stream()
                .filter(row -> Objects.equals(row.getPullCallId(), call.getId()))
                .toList();
        if (puller == null || stations.size() != call.getPlannedStationCount()) {
            return null;
        }
        return new ContactScope(puller, stations);
    }

    private void createActions(
            PullTaskGroupExecution candidate,
            ContactScope scope,
            long now) {
        for (PullTaskGroupAccount station : scope.stations()) {
            insertAction(candidate, scope.puller().getId(), station.getId(), now);
            insertAction(candidate, station.getId(), scope.puller().getId(), now);
        }
    }

    private void insertAction(
            PullTaskGroupExecution candidate,
            long actorId,
            long targetId,
            long now) {
        PullTaskAccountAction row = new PullTaskAccountAction();
        row.setTaskId(candidate.getTaskId());
        row.setGroupExecutionId(candidate.getId());
        row.setActionType(PullTaskAccountActionType.SAVE_CONTACT.code());
        row.setActorGroupAccountId(actorId);
        row.setTargetGroupAccountId(targetId);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        actionMapper.insertIfAbsent(row);
    }

    private PullTaskStationContactStepResult preparePending(
            PullTaskGroupExecution candidate,
            ContactScope scope,
            List<PullTaskAccountAction> actions,
            long now) {
        Map<Long, PullTaskGroupAccount> roles = roleMap(scope);
        Map<Long, ProtocolAccountRef> accounts = accountMap(roles.values().stream().toList());
        for (PullTaskAccountAction action : actions) {
            if (!Objects.equals(action.getActionStatus(), PullTaskActionStatus.PENDING.code())) {
                continue;
            }
            PullTaskGroupAccount actor = roles.get(action.getActorGroupAccountId());
            PullTaskGroupAccount target = roles.get(action.getTargetGroupAccountId());
            ProtocolAccountRef account = actor == null ? null : accounts.get(actor.getAccountId());
            if (actor == null || target == null || account == null) {
                closeUnavailable(action, now);
                continue;
            }
            return submit(candidate, action, account, now);
        }
        return PullTaskStationContactStepResult.CALL_READY;
    }

    private PullTaskStationContactStepResult submit(
            PullTaskGroupExecution candidate,
            PullTaskAccountAction action,
            ProtocolAccountRef actor,
            long now) {
        ProtocolCommandOutboxEnqueueResult enqueued = resources.outboxService()
                .enqueuePullTaskContactSaveCommands(List.of(
                        new ProtocolPullTaskContactSaveCommandRequest(
                                candidate.getTenantId(), candidate.getTaskId(), candidate.getId(),
                                action.getId(), actor)));
        String commandId = singleCommandId(enqueued);
        if (actionMapper.markSubmitted(action.getId(), commandId, now) != 1) {
            throw new IllegalStateException("拉手站台联系人动作提交状态已变化");
        }
        return deferSubmitted(candidate, now, true);
    }

    private PullTaskStationContactStepResult deferSubmitted(
            PullTaskGroupExecution candidate,
            long now,
            boolean requireTransition) {
        PullTaskGroupExecution update = transition(candidate, now);
        update.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        update.setStage(PullTaskExecutionStage.PULL_EXECUTION.code());
        update.setNextRunAt(Math.addExact(
                now, resources.properties().getResultReconciliationDelayMs()));
        if (resources.executionMapper().transitionClaimed(
                update, PullTaskExecutionStage.PULL_EXECUTION.code()) != 1) {
            if (requireTransition) {
                throw new IllegalStateException("拉手站台联系人提交后执行行租约已变化");
            }
            return PullTaskStationContactStepResult.LOST;
        }
        return PullTaskStationContactStepResult.MORE_CONTACTS;
    }

    private static String singleCommandId(ProtocolCommandOutboxEnqueueResult enqueued) {
        if (enqueued == null || enqueued.inserted() != 1
                || enqueued.commandIds() == null || enqueued.commandIds().size() != 1
                || enqueued.commandIds().get(0) == null
                || enqueued.commandIds().get(0).isBlank()) {
            throw new IllegalStateException("拉手站台联系人 Outbox 写入结果不完整");
        }
        return enqueued.commandIds().get(0);
    }

    private void closeUnavailable(PullTaskAccountAction action, long now) {
        if (actionMapper.markSubmitted(action.getId(), operationId(action.getId()), now) == 1) {
            actionMapper.writeBackResult(action.getId(), PullTaskActionStatus.FAILED.code(),
                    ACCOUNT_UNAVAILABLE, "联系人发起账号不可用", now);
            action.setActionStatus(PullTaskActionStatus.FAILED.code());
        }
    }

    private List<PullTaskAccountAction> relevantActions(
            long executionId, ContactScope scope) {
        Set<Long> stationIds = new HashSet<>();
        scope.stations().stream().map(PullTaskGroupAccount::getId).forEach(stationIds::add);
        long pullerId = scope.puller().getId();
        return actionMapper.selectByExecutionAndType(
                        executionId, PullTaskAccountActionType.SAVE_CONTACT.code())
                .stream()
                .filter(action -> stationPair(action, pullerId, stationIds))
                .toList();
    }

    private Map<Long, ProtocolAccountRef> accountMap(List<PullTaskGroupAccount> roles) {
        List<Long> accountIds = roles.stream()
                .map(PullTaskGroupAccount::getAccountId).distinct().toList();
        Map<Long, ProtocolAccountRef> result = new HashMap<>();
        for (ProtocolAccountRef account : resources.accountLookup()
                .findActiveProtocolRefs(accountIds)) {
            if (account != null) {
                result.putIfAbsent(account.armadaAccountId(), account);
            }
        }
        return result;
    }

    private static Map<Long, PullTaskGroupAccount> roleMap(ContactScope scope) {
        Map<Long, PullTaskGroupAccount> result = new HashMap<>();
        result.put(scope.puller().getId(), scope.puller());
        scope.stations().forEach(row -> result.put(row.getId(), row));
        return result;
    }

    private PullTaskPullCall currentPlannedCall(long executionId, Long requestedCallId) {
        if (requestedCallId == null) {
            return null;
        }
        return resources.pullCallMapper().selectPlannedByExecution(executionId)
                .stream()
                .filter(row -> Objects.equals(row.getId(), requestedCallId))
                .findFirst().orElse(null);
    }

    private static boolean stationPair(
            PullTaskAccountAction action,
            long pullerId,
            Set<Long> stationIds) {
        return (Objects.equals(action.getActorGroupAccountId(), pullerId)
                && stationIds.contains(action.getTargetGroupAccountId()))
                || (Objects.equals(action.getTargetGroupAccountId(), pullerId)
                && stationIds.contains(action.getActorGroupAccountId()));
    }

    private static boolean hasSubmitted(List<PullTaskAccountAction> actions) {
        return actions.stream().anyMatch(action -> Objects.equals(
                action.getActionStatus(), PullTaskActionStatus.SUBMITTED.code()));
    }

    private static boolean hasIdentity(PullTaskGroupExecution candidate) {
        return candidate != null && candidate.getTenantId() != null
                && candidate.getId() != null && candidate.getTaskId() != null;
    }

    private static boolean isDispatchable(
            PullTask parent,
            PullTaskGroupExecution execution,
            PullTaskPullCall call,
            Long requestedCallId,
            String lockOwner) {
        return parent != null && call != null
                && parent.getTaskType() == PullTaskType.STANDARD
                && NORMAL_LINK_MODE.equals(parent.getMode())
                && PullTaskStandardStatus.EXECUTING.name().equals(parent.getStatus())
                && execution.getExecutionStatus() == PullTaskExecutionStatus.EXECUTING.code()
                && execution.getStage() == PullTaskExecutionStage.PULL_EXECUTION.code()
                && Objects.equals(call.getGroupExecutionId(), execution.getId())
                && Objects.equals(call.getId(), requestedCallId)
                && Objects.equals(call.getCallStatus(), PullTaskPullCallStatus.PLANNED.code())
                && lockOwner != null && lockOwner.equals(execution.getLockOwner());
    }

    private static PullTaskGroupExecution transition(
            PullTaskGroupExecution candidate, long now) {
        PullTaskGroupExecution update = new PullTaskGroupExecution();
        update.setId(candidate.getId());
        update.setVersion(candidate.getVersion());
        update.setLockOwner(candidate.getLockOwner());
        update.setGroupJid(candidate.getGroupJid());
        update.setLastBusinessExecutedAt(now);
        update.setUpdatedAt(now);
        return update;
    }

    private static String operationId(long actionId) {
        return "pull-task-contact:" + actionId;
    }

    private static void restoreTenant(Long previousTenant) {
        if (previousTenant == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenant);
        }
    }

    private record ContactScope(
            PullTaskGroupAccount puller,
            List<PullTaskGroupAccount> stations) {
    }
}
