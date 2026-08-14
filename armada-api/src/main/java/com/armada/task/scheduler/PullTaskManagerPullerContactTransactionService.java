package com.armada.task.scheduler;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.command.ProtocolPullTaskContactSaveCommandRequest;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.dto.PullTaskMemberAddPermissionWork;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskAccountEntryMode;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** EX-03 拉手占用、管理—拉手全组合动作预写和结果检查点事务。 */
@Service
public class PullTaskManagerPullerContactTransactionService {

    private static final String NORMAL_LINK_MODE = "NORMAL_LINK";
    private static final String ACCOUNT_UNAVAILABLE = "ACCOUNT_UNAVAILABLE";
    private static final int INITIAL_SOURCE = 1;
    private static final int AUTOMATIC_SELECTION = 1;

    private final PullTaskMapper taskMapper;
    private final PullTaskStandardSettingMapper settingMapper;
    private final PullTaskGroupAccountMapper groupAccountMapper;
    private final PullTaskAccountActionMapper actionMapper;
    private final PullTaskManagerPullerContactResources resources;

    /**
     * @param taskMapper         父任务 Mapper
     * @param settingMapper      普通任务配置 Mapper
     * @param groupAccountMapper 执行行角色账号 Mapper
     * @param actionMapper       账号动作 Mapper
     * @param resources          执行行与账号域依赖
     */
    public PullTaskManagerPullerContactTransactionService(
            PullTaskMapper taskMapper,
            PullTaskStandardSettingMapper settingMapper,
            PullTaskGroupAccountMapper groupAccountMapper,
            PullTaskAccountActionMapper actionMapper,
            PullTaskManagerPullerContactResources resources) {
        this.taskMapper = taskMapper;
        this.settingMapper = settingMapper;
        this.groupAccountMapper = groupAccountMapper;
        this.actionMapper = actionMapper;
        this.resources = resources;
    }

    /** 在短事务内占用拉手、补齐双向动作，并提交一条尚未执行的动作。 */
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
            PullTaskStandardSetting setting = settingMapper.selectByTaskId(candidate.getTaskId());
            if (setting == null) {
                return waitForPuller(candidate, PullTaskExecutionReasonCode.PULLER_UNAVAILABLE, now);
            }
            List<PullTaskGroupAccount> managers = availableManagers(candidate.getId());
            if (managers.isEmpty()) {
                return waitForManager(candidate, now);
            }
            List<PullTaskGroupAccount> pullers = ensurePullers(candidate, setting, now);
            if (pullers.isEmpty()) {
                return waitForPuller(candidate, PullTaskExecutionReasonCode.PULLER_UNAVAILABLE, now);
            }
            createContactActions(candidate, managers, pullers, now);
            return prepareNextAction(candidate, managers, pullers, now);
        } finally {
            restoreTenant(previousTenant);
        }
    }

    /**
     * 在短事务内复核租约并解析用于设置普通成员添加权限的任务管理员。
     *
     * <p>该方法不占用拉手，也不创建联系人动作；协议读写由事务外 Processor 执行。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskMemberAddPermissionPreparation prepareMemberAddPermission(
            PullTaskGroupExecution candidate, String lockOwner, long now) {
        if (!hasIdentity(candidate)) {
            return PullTaskMemberAddPermissionPreparation.completed(
                    PullTaskExecutionDispatchResult.LOST);
        }
        Long previousTenant = TenantContext.get();
        TenantContext.set(candidate.getTenantId());
        try {
            PullTask parent = taskMapper.selectLifecycle(candidate.getTaskId());
            if (!isDispatchable(parent, candidate, lockOwner)) {
                resources.executionMapper().releaseLock(candidate.getId(), lockOwner, now);
                return PullTaskMemberAddPermissionPreparation.completed(
                        PullTaskExecutionDispatchResult.LOST);
            }
            List<PullTaskGroupAccount> managers = availableManagers(candidate.getId());
            if (managers.isEmpty()) {
                return PullTaskMemberAddPermissionPreparation.completed(
                        waitForManager(candidate, now));
            }
            Map<Long, ProtocolAccountRef> accounts = accountMap(managers);
            ProtocolAccountRef manager = managers.stream()
                    .map(PullTaskGroupAccount::getAccountId)
                    .map(accounts::get)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
            if (manager == null) {
                return PullTaskMemberAddPermissionPreparation.completed(
                        waitForManager(candidate, now));
            }
            if (candidate.getGroupJid() == null || candidate.getGroupJid().isBlank()) {
                return PullTaskMemberAddPermissionPreparation.completed(
                        deferMemberAddPermission(
                                candidate,
                                PullTaskExecutionReasonCode
                                        .GROUP_MEMBER_ADD_PERMISSION_UNCONFIRMED,
                                now));
            }
            return PullTaskMemberAddPermissionPreparation.ready(
                    new PullTaskMemberAddPermissionWork(
                            candidate.getTenantId(),
                            candidate.getId(),
                            candidate.getVersion(),
                            lockOwner,
                            candidate.getGroupJid(),
                            manager));
        } finally {
            restoreTenant(previousTenant);
        }
    }

    /** 延迟尚未确认的普通成员添加权限并释放当前执行租约。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskExecutionDispatchResult deferMemberAddPermission(
            PullTaskMemberAddPermissionWork work,
            PullTaskExecutionReasonCode reason,
            long now) {
        Long previousTenant = TenantContext.get();
        TenantContext.set(work.tenantId());
        try {
            PullTaskGroupExecution candidate = new PullTaskGroupExecution();
            candidate.setId(work.executionId());
            candidate.setVersion(work.expectedVersion());
            candidate.setLockOwner(work.lockOwner());
            return deferMemberAddPermission(candidate, reason, now);
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private PullTaskExecutionDispatchResult deferMemberAddPermission(
            PullTaskGroupExecution candidate,
            PullTaskExecutionReasonCode reason,
            long now) {
        PullTaskGroupExecution update = transition(candidate, now);
        update.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        update.setStage(PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code());
        update.setReasonCode(reason.name());
        update.setReasonMessage(reason.message());
        update.setNextRunAt(now + resources.properties().getRetryDelayMs());
        return resources.executionMapper().transitionClaimed(
                update, PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code()) == 1
                ? PullTaskExecutionDispatchResult.DEFERRED
                : PullTaskExecutionDispatchResult.LOST;
    }

    private List<PullTaskGroupAccount> availableManagers(long executionId) {
        return groupAccountMapper.selectByExecutionAndRole(
                        executionId, PullTaskGroupAccountRole.MANAGER.code())
                .stream()
                .filter(PullTaskManagerPullerContactTransactionService::available)
                .filter(row -> row.getMembershipStatus()
                        == PullTaskGroupAccountMembershipStatus.IN_GROUP.code())
                .filter(row -> Objects.equals(row.getAdminStatus(),
                        PullTaskGroupAccountAdminStatus.SUCCESS.code()))
                .toList();
    }

    private List<PullTaskGroupAccount> ensurePullers(
            PullTaskGroupExecution candidate,
            PullTaskStandardSetting setting,
            long now) {
        int planned = setting.getPullerCountPerGroup() == null
                ? 0 : setting.getPullerCountPerGroup();
        if (planned <= 0 || setting.getPullerGroupId() == null) {
            return List.of();
        }
        List<ProtocolAccountRef> eligible = eligiblePullers(
                setting.getPullerGroupId(), now);
        List<PullTaskGroupAccount> existing = groupAccountMapper.selectByExecutionAndRole(
                candidate.getId(), PullTaskGroupAccountRole.PULLER.code());
        restoreReleasedPullers(existing, eligible, planned, now);
        existing = groupAccountMapper.selectByExecutionAndRole(
                candidate.getId(), PullTaskGroupAccountRole.PULLER.code());
        int activeCount = activePullers(existing).size();
        Set<Long> existingIds = accountIds(existing);
        int nextSeq = existing.stream().map(PullTaskGroupAccount::getRoleSeq)
                .filter(value -> value != null).max(Integer::compareTo).orElse(0) + 1;
        for (ProtocolAccountRef account : eligible) {
            if (activeCount >= planned) {
                break;
            }
            if (account == null || existingIds.contains(account.armadaAccountId())) {
                continue;
            }
            try {
                insertPuller(candidate, account, nextSeq++, pullerEntryMode(setting), now);
                existingIds.add(account.armadaAccountId());
                activeCount++;
            } catch (DuplicateKeyException ignored) {
                // 账号刚被别的执行行占用，继续尝试分组内下一个候选。
            }
        }
        return activePullers(groupAccountMapper.selectByExecutionAndRole(
                        candidate.getId(), PullTaskGroupAccountRole.PULLER.code()))
                .stream().limit(planned).toList();
    }

    private List<ProtocolAccountRef> eligiblePullers(Long groupId, long now) {
        List<ProtocolAccountRef> validated = resources.accountLookup()
                .findOnlineNormalByGroupId(groupId);
        List<Long> accountIds = validated.stream()
                .filter(Objects::nonNull)
                .map(ProtocolAccountRef::armadaAccountId)
                .distinct().toList();
        if (accountIds.isEmpty()) {
            return List.of();
        }
        groupAccountMapper.restoreExpiredPullerCooldowns(
                accountIds, PullTaskGroupAccountRole.PULLER.code(),
                PullTaskGroupAccountAvailability.RISK_COOLDOWN.code(),
                PullTaskGroupAccountAvailability.AVAILABLE.code(), now);
        List<Long> blockedRows = groupAccountMapper.selectAccountIdsByAvailability(
                accountIds, PullTaskGroupAccountRole.PULLER.code(),
                PullTaskGroupAccountAvailability.RISK_COOLDOWN.code());
        Set<Long> blocked = blockedRows == null ? Set.of() : new HashSet<>(blockedRows);
        return validated.stream()
                .filter(Objects::nonNull)
                .filter(account -> !blocked.contains(account.armadaAccountId()))
                .toList();
    }

    private void restoreReleasedPullers(
            List<PullTaskGroupAccount> existing,
            List<ProtocolAccountRef> eligible,
            int planned,
            long now) {
        Set<Long> eligibleIds = new HashSet<>();
        for (ProtocolAccountRef account : eligible) {
            if (account != null) {
                eligibleIds.add(account.armadaAccountId());
            }
        }
        int activeCount = activePullers(existing).size();
        for (PullTaskGroupAccount row : existing) {
            if (activeCount >= planned) {
                break;
            }
            if (Objects.equals(row.getMembershipStatus(),
                    PullTaskGroupAccountMembershipStatus.JOIN_FAILED.code())) {
                continue;
            }
            if (row.getReleasedAt() == null || !available(row)
                    || !eligibleIds.contains(row.getAccountId())) {
                continue;
            }
            try {
                if (groupAccountMapper.reoccupyPuller(row.getId(), now) == 1) {
                    activeCount++;
                }
            } catch (DuplicateKeyException ignored) {
                // 原账号已被别的执行行占用，本行继续竞争其他候选。
            }
        }
    }

    private PullTaskGroupAccount insertPuller(
            PullTaskGroupExecution candidate,
            ProtocolAccountRef account,
            int roleSeq,
            int entryMode,
            long now) {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setTaskId(candidate.getTaskId());
        row.setGroupExecutionId(candidate.getId());
        row.setAccountId(account.armadaAccountId());
        row.setAccountPhone(account.wsPhone());
        row.setRoleType(PullTaskGroupAccountRole.PULLER.code());
        row.setRoleSeq(roleSeq);
        row.setSourceType(INITIAL_SOURCE);
        row.setSelectionMode(AUTOMATIC_SELECTION);
        row.setEntryMode(entryMode);
        row.setOccupiedAt(now);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        if (groupAccountMapper.insert(row) != 1 || row.getId() == null) {
            throw new IllegalStateException("拉手角色行写入失败");
        }
        return row;
    }

    private static int pullerEntryMode(PullTaskStandardSetting setting) {
        return Integer.valueOf(1).equals(setting.getPullerJoinByLink())
                ? PullTaskAccountEntryMode.JOIN_BY_LINK.code()
                : PullTaskAccountEntryMode.MANAGER_INVITE.code();
    }

    private void createContactActions(
            PullTaskGroupExecution candidate,
            List<PullTaskGroupAccount> managers,
            List<PullTaskGroupAccount> pullers,
            long now) {
        for (PullTaskGroupAccount manager : managers) {
            for (PullTaskGroupAccount puller : pullers) {
                insertContactAction(candidate, manager.getId(), puller.getId(), now);
                insertContactAction(candidate, puller.getId(), manager.getId(), now);
            }
        }
    }

    private void insertContactAction(
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

    private PullTaskExecutionDispatchResult prepareNextAction(
            PullTaskGroupExecution candidate,
            List<PullTaskGroupAccount> managers,
            List<PullTaskGroupAccount> pullers,
            long now) {
        List<PullTaskAccountAction> actions = contactActions(candidate.getId());
        Map<Long, PullTaskGroupAccount> roles = roleMap(managers, pullers);
        Map<Long, ProtocolAccountRef> accounts = accountMap(roles.values());
        for (PullTaskAccountAction action : actions) {
            if (action.getActionStatus() != PullTaskActionStatus.PENDING.code()) {
                continue;
            }
            PullTaskGroupAccount actor = roles.get(action.getActorGroupAccountId());
            PullTaskGroupAccount target = roles.get(action.getTargetGroupAccountId());
            ProtocolAccountRef account = actor == null ? null : accounts.get(actor.getAccountId());
            if (actor == null || target == null || account == null) {
                closeUnavailableAction(action, actor, now);
                continue;
            }
            return submit(candidate, action, account, now);
        }
        return actions.stream().allMatch(PullTaskManagerPullerContactTransactionService::terminal)
                ? advance(candidate, now) : deferSubmitted(candidate, now);
    }

    private void closeUnavailableAction(
            PullTaskAccountAction action,
            PullTaskGroupAccount actor,
            long now) {
        if (actionMapper.markSubmitted(action.getId(), operationId(action.getId()), now) != 1) {
            return;
        }
        actionMapper.writeBackResult(action.getId(), PullTaskActionStatus.FAILED.code(),
                ACCOUNT_UNAVAILABLE, "联系人发起账号不可用", now);
        action.setActionStatus(PullTaskActionStatus.FAILED.code());
        if (actor != null) {
            groupAccountMapper.markUnavailable(actor.getId(),
                    PullTaskGroupAccountAvailability.OFFLINE.code(),
                    ACCOUNT_UNAVAILABLE, null, now);
        }
    }

    private PullTaskExecutionDispatchResult submit(
            PullTaskGroupExecution candidate,
            PullTaskAccountAction action,
            ProtocolAccountRef actor,
            long now) {
        ProtocolCommandOutboxEnqueueResult enqueued = resources.outboxService()
                .enqueuePullTaskContactSaveCommands(List.of(
                        new ProtocolPullTaskContactSaveCommandRequest(
                                candidate.getTenantId(), candidate.getTaskId(), candidate.getId(),
                                action.getId(), actor)));
        if (enqueued.inserted() != 1 || enqueued.commandIds().size() != 1) {
            throw new IllegalStateException("联系人 Outbox 命令写入数量不一致");
        }
        if (actionMapper.markSubmitted(action.getId(), enqueued.commandIds().get(0), now) != 1) {
            throw new IllegalStateException("联系人动作提交状态写入失败");
        }
        return deferSubmitted(candidate, now);
    }

    private PullTaskExecutionDispatchResult deferSubmitted(
            PullTaskGroupExecution candidate, long now) {
        PullTaskGroupExecution update = transition(candidate, now);
        update.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        update.setStage(PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code());
        update.setNextRunAt(now + resources.properties().getResultReconciliationDelayMs());
        if (resources.executionMapper().transitionClaimed(
                update, PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code()) != 1) {
            throw new IllegalStateException("联系人动作提交后执行行释放失败");
        }
        return PullTaskExecutionDispatchResult.DEFERRED;
    }

    private PullTaskExecutionDispatchResult waitForManager(
            PullTaskGroupExecution candidate, long now) {
        return waitForResource(candidate, PullTaskWaitResourceType.MANAGER,
                PullTaskExecutionReasonCode.MANAGER_UNAVAILABLE, now);
    }

    private PullTaskExecutionDispatchResult waitForPuller(
            PullTaskGroupExecution candidate,
            PullTaskExecutionReasonCode reason,
            long now) {
        return waitForResource(candidate, PullTaskWaitResourceType.PULLER, reason, now);
    }

    private PullTaskExecutionDispatchResult waitForResource(
            PullTaskGroupExecution candidate,
            PullTaskWaitResourceType resourceType,
            PullTaskExecutionReasonCode reason,
            long now) {
        PullTaskGroupExecution update = transition(candidate, now);
        update.setExecutionStatus(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        update.setStage(PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code());
        update.setWaitResourceType(resourceType.code());
        update.setReasonCode(reason.name());
        update.setReasonMessage(waitMessage(candidate, resourceType, reason));
        update.setLastBusinessExecutedAt(null);
        if (resources.executionMapper().transitionClaimed(
                update, PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code()) != 1) {
            return PullTaskExecutionDispatchResult.LOST;
        }
        groupAccountMapper.releaseAllPullersOfExecution(candidate.getId(), now);
        return PullTaskExecutionDispatchResult.DEFERRED;
    }

    private String waitMessage(
            PullTaskGroupExecution candidate,
            PullTaskWaitResourceType resourceType,
            PullTaskExecutionReasonCode reason) {
        if (resourceType == PullTaskWaitResourceType.MANAGER) {
            return reason.message() + "，缺口人数=1";
        }
        PullTaskStandardSetting setting = settingMapper.selectByTaskId(candidate.getTaskId());
        int planned = setting == null || setting.getPullerCountPerGroup() == null
                ? 1 : setting.getPullerCountPerGroup();
        long current = activePullers(groupAccountMapper.selectByExecutionAndRole(
                candidate.getId(), PullTaskGroupAccountRole.PULLER.code())).size();
        return reason.message() + "，缺口人数=" + Math.max(planned - current, 1L);
    }

    private PullTaskExecutionDispatchResult advance(
            PullTaskGroupExecution candidate, long now) {
        PullTaskGroupExecution update = transition(candidate, now);
        update.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        update.setStage(PullTaskExecutionStage.PULLER_INVITE.code());
        update.setGroupJid(candidate.getGroupJid());
        if (resources.executionMapper().transitionClaimed(
                update, PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code()) != 1) {
            return PullTaskExecutionDispatchResult.LOST;
        }
        return PullTaskExecutionDispatchResult.ADVANCED;
    }

    private List<PullTaskAccountAction> contactActions(long executionId) {
        return actionMapper.selectByExecutionAndType(
                executionId, PullTaskAccountActionType.SAVE_CONTACT.code());
    }

    private Map<Long, ProtocolAccountRef> accountMap(
            java.util.Collection<PullTaskGroupAccount> roles) {
        List<Long> ids = roles.stream().map(PullTaskGroupAccount::getAccountId).distinct().toList();
        Map<Long, ProtocolAccountRef> result = new HashMap<>();
        for (ProtocolAccountRef account : resources.accountLookup().findActiveProtocolRefs(ids)) {
            if (account != null) {
                result.putIfAbsent(account.armadaAccountId(), account);
            }
        }
        return result;
    }

    private static Map<Long, PullTaskGroupAccount> roleMap(
            List<PullTaskGroupAccount> managers,
            List<PullTaskGroupAccount> pullers) {
        Map<Long, PullTaskGroupAccount> result = new HashMap<>();
        managers.forEach(row -> result.put(row.getId(), row));
        pullers.forEach(row -> result.put(row.getId(), row));
        return result;
    }

    private static List<PullTaskGroupAccount> activePullers(
            List<PullTaskGroupAccount> pullers) {
        return pullers.stream()
                .filter(PullTaskManagerPullerContactTransactionService::available)
                .filter(row -> row.getReleasedAt() == null)
                .toList();
    }

    private static Set<Long> accountIds(List<PullTaskGroupAccount> rows) {
        Set<Long> result = new HashSet<>();
        rows.stream().map(PullTaskGroupAccount::getAccountId).forEach(result::add);
        return result;
    }

    private static boolean available(PullTaskGroupAccount row) {
        return row.getAvailabilityStatus()
                == PullTaskGroupAccountAvailability.AVAILABLE.code();
    }

    private static boolean terminal(PullTaskAccountAction action) {
        return action.getActionStatus() != PullTaskActionStatus.PENDING.code()
                && action.getActionStatus() != PullTaskActionStatus.SUBMITTED.code();
    }

    private static PullTaskGroupExecution transition(
            PullTaskGroupExecution candidate, long now) {
        PullTaskGroupExecution update = new PullTaskGroupExecution();
        update.setId(candidate.getId());
        update.setVersion(candidate.getVersion());
        update.setLockOwner(candidate.getLockOwner());
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
                && row.getStage() == PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code()
                && lockOwner != null && lockOwner.equals(row.getLockOwner());
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
}
