package com.armada.task.scheduler;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.dto.PullTaskExecutionLease;
import com.armada.task.model.dto.PullTaskExecutionSlotClaim;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionReasonCode;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAdminStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskGroupAccountSource;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import com.armada.task.model.enums.PullTaskWaitResourceType;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** SC-05 只恢复已通过真实可用性校验的资源等待行，不解除人工暂停。 */
@Service
public class PullTaskResourceRecoveryTransactionService {

    private static final String NORMAL_LINK_MODE = "NORMAL_LINK";
    private static final int REQUIRED_MANAGER_COUNT = 1;
    private static final List<Integer> OFFLINE_AVAILABILITY =
            List.of(PullTaskGroupAccountAvailability.OFFLINE.code());

    private final PullTaskMapper taskMapper;
    private final PullTaskStandardSettingMapper settingMapper;
    private final PullTaskGroupAccountMapper accountMapper;
    private final PullTaskResourceRecoveryResources resources;

    /**
     * @param taskMapper 父任务 Mapper
     * @param settingMapper 冻结配置 Mapper
     * @param accountMapper 角色账号 Mapper
     * @param resources 执行行、账号域与站台候选依赖
     */
    public PullTaskResourceRecoveryTransactionService(
            PullTaskMapper taskMapper,
            PullTaskStandardSettingMapper settingMapper,
            PullTaskGroupAccountMapper accountMapper,
            PullTaskResourceRecoveryResources resources) {
        this.taskMapper = taskMapper;
        this.settingMapper = settingMapper;
        this.accountMapper = accountMapper;
        this.resources = resources;
    }

    /** 复核等待类型对应资源；恢复成功只回到原检查点，下一轮才执行业务动作。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskExecutionDispatchResult recover(
            PullTaskGroupExecution candidate,
            String lockOwner,
            long now,
            long retryDelayMs) {
        if (!hasIdentity(candidate)) {
            return PullTaskExecutionDispatchResult.LOST;
        }
        Long previousTenant = TenantContext.get();
        TenantContext.set(candidate.getTenantId());
        try {
            PullTask parent = taskMapper.selectLifecycle(candidate.getTaskId());
            PullTaskStandardSetting setting =
                    settingMapper.selectByTaskId(candidate.getTaskId());
            if (!isDispatchable(parent, candidate, lockOwner) || setting == null) {
                release(candidate, lockOwner, now);
                return PullTaskExecutionDispatchResult.LOST;
            }
            ResourceCheck check = check(candidate, setting, now);
            if (!check.ready()) {
                return defer(candidate, check, now, retryDelayMs);
            }
            if (!hasConcurrentPolicy(setting)
                    || !acquireExecutionSlot(parent, candidate,
                    setting.getConcurrentGroupCount(), now)) {
                return deferForSlot(candidate, now, retryDelayMs);
            }
            return resume(candidate, now);
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private ResourceCheck check(
            PullTaskGroupExecution candidate,
            PullTaskStandardSetting setting,
            long now) {
        if (Objects.equals(candidate.getWaitResourceType(),
                PullTaskWaitResourceType.MANAGER.code())) {
            return managerCheck(candidate, setting, now);
        }
        if (Objects.equals(candidate.getWaitResourceType(),
                PullTaskWaitResourceType.PULLER.code())) {
            return pullerCheck(candidate, setting, now);
        }
        if (Objects.equals(candidate.getWaitResourceType(),
                PullTaskWaitResourceType.STATION.code())) {
            return stationCheck(candidate, setting);
        }
        return ResourceCheck.waiting(
                "RESOURCE_WAIT_TYPE_INVALID", "资源等待类型无效");
    }

    private ResourceCheck managerCheck(
            PullTaskGroupExecution candidate,
            PullTaskStandardSetting setting,
            long now) {
        List<PullTaskGroupAccount> stored = accountMapper.selectByExecutionAndRole(
                candidate.getId(), PullTaskGroupAccountRole.MANAGER.code());
        if (stored.isEmpty()) {
            boolean ready = candidate.getStage() == PullTaskExecutionStage.MANAGER_JOIN.code()
                    && setting.getManagerGroupId() != null
                    && resources.accountLookup()
                    .findRandomOnlineNormalPullerByGroupId(
                            setting.getManagerGroupId()).isPresent();
            return ready ? ResourceCheck.available() : managerWaiting(0);
        }
        List<Long> activeIds = activeIds(stored);
        restoreOffline(activeIds, PullTaskGroupAccountRole.MANAGER, now);
        List<PullTaskGroupAccount> refreshed = accountMapper.selectByExecutionAndRole(
                candidate.getId(), PullTaskGroupAccountRole.MANAGER.code());
        if (candidate.getStage() == PullTaskExecutionStage.MANAGER_ADMIN.code()) {
            return managerAdminCheck(candidate, refreshed, activeIds);
        }
        int available = (int) refreshed.stream()
                .filter(row -> activeIds.contains(row.getAccountId()))
                .filter(row -> managerSupportsStage(row, candidate.getStage()))
                .count();
        return available > 0 ? ResourceCheck.available() : managerWaiting(available);
    }

    private ResourceCheck managerAdminCheck(
            PullTaskGroupExecution candidate,
            List<PullTaskGroupAccount> managers,
            List<Long> activeIds) {
        PullTaskGroupAccount manager = managers.stream()
                .filter(row -> activeIds.contains(row.getAccountId()))
                .filter(row -> Objects.equals(row.getAvailabilityStatus(),
                        PullTaskGroupAccountAvailability.AVAILABLE.code()))
                .filter(row -> Objects.equals(row.getMembershipStatus(),
                        PullTaskGroupAccountMembershipStatus.IN_GROUP.code()))
                .findFirst().orElse(null);
        if (manager == null) {
            return ResourceCheck.waiting(
                    PullTaskExecutionReasonCode.MANAGER_ADMIN_SETUP_FAILED.name(),
                    PullTaskExecutionReasonCode.MANAGER_ADMIN_SETUP_FAILED.message());
        }
        List<GroupExecutionAccount> candidates = resources.promoterSelector()
                .findPullTaskAdminPromoterCandidates(
                        candidate.getTenantId(), candidate.getGroupJid(), manager.getAccountId());
        List<PullTaskGroupAccount> roles = accountMapper.selectByExecutionAndRole(
                candidate.getId(), PullTaskGroupAccountRole.PROMOTER.code());
        var actions = resources.actionMapper().selectByExecutionAndType(
                candidate.getId(), PullTaskAccountActionType.PROMOTE_MANAGER.code());
        boolean selectable = resources.managerAdminCandidateSelector()
                .select(candidates, roles, actions, manager.getId()).isPresent();
        if (selectable) {
            return ResourceCheck.available();
        }
        PullTaskExecutionReasonCode reason = candidates == null || candidates.isEmpty()
                ? PullTaskExecutionReasonCode.MANAGER_ADMIN_ACTOR_UNAVAILABLE
                : PullTaskExecutionReasonCode.MANAGER_ADMIN_SETUP_FAILED;
        return ResourceCheck.waiting(reason.name(), reason.message());
    }

    private ResourceCheck pullerCheck(
            PullTaskGroupExecution candidate,
            PullTaskStandardSetting setting,
            long now) {
        List<PullTaskGroupAccount> stored = accountMapper.selectByExecutionAndRole(
                candidate.getId(), PullTaskGroupAccountRole.PULLER.code());
        List<ProtocolAccountRef> validated = setting.getPullerGroupId() == null
                ? List.of() : safe(resources.accountLookup()
                .findOnlineNormalPullersByGroupId(setting.getPullerGroupId()));
        Set<Long> validatedIds = new LinkedHashSet<>(validated.stream()
                .filter(Objects::nonNull)
                .map(ProtocolAccountRef::armadaAccountId)
                .toList());
        List<Long> supplementedAccountIds = stored.stream()
                .filter(row -> Objects.equals(row.getSourceType(),
                        PullTaskGroupAccountSource.SUPPLEMENT.code()))
                .map(PullTaskGroupAccount::getAccountId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        validatedIds.addAll(safe(resources.accountLookup()
                .findEligiblePullerProtocolRefs(supplementedAccountIds)).stream()
                .filter(Objects::nonNull)
                .map(ProtocolAccountRef::armadaAccountId)
                .toList());
        List<Long> validatedIdList = List.copyOf(validatedIds);
        restoreOffline(validatedIdList, PullTaskGroupAccountRole.PULLER, now);
        List<PullTaskGroupAccount> refreshed = accountMapper.selectByExecutionAndRole(
                candidate.getId(), PullTaskGroupAccountRole.PULLER.code());
        int available = reoccupyValidatedPullers(refreshed, validatedIds, now);
        int planned = setting.getPullerCountPerGroup() == null
                ? 0 : setting.getPullerCountPerGroup();
        boolean stageCanSelect = candidate.getStage()
                == PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code();
        boolean ready = available > 0 || stageCanSelect && !validatedIds.isEmpty();
        if (ready) {
            return ResourceCheck.available();
        }
        int missing = Math.max(planned - available, 0);
        return ResourceCheck.waiting(
                "PULLER_UNAVAILABLE", "当前没有可用拉手，缺口人数=" + missing);
    }

    private ResourceCheck stationCheck(
            PullTaskGroupExecution candidate,
            PullTaskStandardSetting setting) {
        PullTaskStationCandidates candidates = resources.stationSelectionService()
                .findCandidates(candidate, setting);
        if (candidates.sufficient()) {
            return ResourceCheck.available();
        }
        return ResourceCheck.waiting(
                "STATION_UNAVAILABLE",
                "当前可用站台不足，缺口人数=" + candidates.missingCount());
    }

    private List<Long> activeIds(List<PullTaskGroupAccount> rows) {
        List<Long> accountIds = rows.stream()
                .map(PullTaskGroupAccount::getAccountId).distinct().toList();
        if (accountIds.isEmpty()) {
            return List.of();
        }
        return safe(resources.accountLookup().findActiveProtocolRefs(accountIds)).stream()
                .filter(Objects::nonNull)
                .map(ProtocolAccountRef::armadaAccountId)
                .distinct().toList();
    }

    private void restoreOffline(
            List<Long> validatedIds, PullTaskGroupAccountRole role, long now) {
        if (validatedIds.isEmpty()) {
            return;
        }
        accountMapper.restoreValidatedAvailability(
                validatedIds, role.code(), OFFLINE_AVAILABILITY,
                PullTaskGroupAccountAvailability.AVAILABLE.code(), now);
    }

    private int reoccupyValidatedPullers(
            List<PullTaskGroupAccount> stored,
            Set<Long> eligibleIds,
            long now) {
        int available = 0;
        for (PullTaskGroupAccount row : stored) {
            if (Objects.equals(row.getMembershipStatus(),
                    PullTaskGroupAccountMembershipStatus.JOIN_FAILED.code())) {
                continue;
            }
            if (!eligibleIds.contains(row.getAccountId())
                    || !Objects.equals(row.getAvailabilityStatus(),
                    PullTaskGroupAccountAvailability.AVAILABLE.code())) {
                continue;
            }
            if (row.getReleasedAt() == null) {
                available++;
                continue;
            }
            try {
                if (accountMapper.reoccupyPuller(row.getId(), now) == 1) {
                    available++;
                }
            } catch (DuplicateKeyException ignored) {
                // 账号已被其他执行行占用，本行保持等待并在后续周期重新竞争。
            }
        }
        return available;
    }

    private PullTaskExecutionDispatchResult resume(
            PullTaskGroupExecution candidate, long now) {
        PullTaskGroupExecution update = transition(candidate, now);
        update.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        update.setStage(candidate.getStage());
        update.setNextRunAt(0L);
        return transitionWaiting(update, PullTaskExecutionDispatchResult.ADVANCED);
    }

    private boolean acquireExecutionSlot(
            PullTask parent,
            PullTaskGroupExecution candidate,
            int concurrentLimit,
            long now) {
        if (parent.getVersion() == null || candidate.getStage() == null) {
            return false;
        }
        PullTaskExecutionSlotClaim claim = new PullTaskExecutionSlotClaim(
                new PullTaskExecutionSlotClaim.Candidate(
                        candidate.getTaskId(), candidate.getId(),
                        PullTaskExecutionStatus.WAIT_RESOURCE.code(), candidate.getStage(),
                        new PullTaskExecutionLease(
                                candidate.getLockOwner(), candidate.getVersion())),
                new PullTaskExecutionSlotClaim.Parent(
                        parent.getVersion(), PullTaskType.STANDARD.name(), NORMAL_LINK_MODE,
                        PullTaskStandardStatus.EXECUTING.name()),
                new PullTaskExecutionSlotClaim.Policy(
                        PullTaskExecutionStatus.EXECUTING.code(), concurrentLimit),
                now);
        return taskMapper.acquireExecutionSlot(claim) == 1;
    }

    private PullTaskExecutionDispatchResult deferForSlot(
            PullTaskGroupExecution candidate,
            long now,
            long retryDelayMs) {
        if (Objects.equals(candidate.getWaitResourceType(),
                PullTaskWaitResourceType.PULLER.code())) {
            accountMapper.releaseAllPullersOfExecution(
                    candidate.getId(), PullTaskGroupAccountRole.PULLER.code(), now);
        }
        PullTaskGroupExecution update = transition(candidate, now);
        update.setExecutionStatus(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        update.setStage(candidate.getStage());
        update.setWaitResourceType(candidate.getWaitResourceType());
        update.setReasonCode(candidate.getReasonCode());
        update.setReasonMessage(candidate.getReasonMessage());
        update.setNextRunAt(Math.addExact(now, retryDelayMs));
        return transitionWaiting(update, PullTaskExecutionDispatchResult.DEFERRED);
    }

    private PullTaskExecutionDispatchResult defer(
            PullTaskGroupExecution candidate,
            ResourceCheck check,
            long now,
            long retryDelayMs) {
        PullTaskGroupExecution update = transition(candidate, now);
        update.setExecutionStatus(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        update.setStage(candidate.getStage());
        update.setWaitResourceType(candidate.getWaitResourceType());
        update.setReasonCode(check.reasonCode());
        update.setReasonMessage(check.reasonMessage());
        update.setNextRunAt(Math.addExact(now, retryDelayMs));
        return transitionWaiting(update, PullTaskExecutionDispatchResult.DEFERRED);
    }

    private PullTaskExecutionDispatchResult transitionWaiting(
            PullTaskGroupExecution update,
            PullTaskExecutionDispatchResult success) {
        return resources.executionMapper().transitionClaimed(
                update, PullTaskExecutionStatus.WAIT_RESOURCE.code(), update.getStage()) == 1
                ? success : PullTaskExecutionDispatchResult.LOST;
    }

    private static PullTaskGroupExecution transition(
            PullTaskGroupExecution candidate, long now) {
        PullTaskGroupExecution update = new PullTaskGroupExecution();
        update.setId(candidate.getId());
        update.setVersion(candidate.getVersion());
        update.setLockOwner(candidate.getLockOwner());
        update.setGroupJid(candidate.getGroupJid());
        update.setUpdatedAt(now);
        return update;
    }

    private static boolean managerSupportsStage(
            PullTaskGroupAccount row, Integer stage) {
        if (!Objects.equals(row.getAvailabilityStatus(),
                PullTaskGroupAccountAvailability.AVAILABLE.code())) {
            return false;
        }
        if (stage == PullTaskExecutionStage.MANAGER_JOIN.code()) {
            return !Objects.equals(row.getMembershipStatus(),
                    PullTaskGroupAccountMembershipStatus.JOIN_FAILED.code());
        }
        if (!Objects.equals(row.getMembershipStatus(),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code())) {
            return false;
        }
        return stage != PullTaskExecutionStage.MATERIAL_ADMIN.code()
                || !Objects.equals(row.getAdminStatus(),
                PullTaskGroupAccountAdminStatus.FAILED.code());
    }

    private static ResourceCheck managerWaiting(int available) {
        int missing = Math.max(REQUIRED_MANAGER_COUNT - available, 0);
        return ResourceCheck.waiting(
                "MANAGER_UNAVAILABLE", "当前没有可用管理员，缺口人数=" + missing);
    }

    private static boolean hasIdentity(PullTaskGroupExecution candidate) {
        return candidate != null && candidate.getTenantId() != null
                && candidate.getId() != null && candidate.getTaskId() != null;
    }

    private static boolean hasConcurrentPolicy(PullTaskStandardSetting setting) {
        return setting.getConcurrentGroupCount() != null
                && setting.getConcurrentGroupCount() > 0;
    }

    private static boolean isDispatchable(
            PullTask parent, PullTaskGroupExecution candidate, String lockOwner) {
        return parent != null
                && parent.getTaskType() == PullTaskType.STANDARD
                && NORMAL_LINK_MODE.equals(parent.getMode())
                && PullTaskStandardStatus.EXECUTING.name().equals(parent.getStatus())
                && candidate.getExecutionStatus() == PullTaskExecutionStatus.WAIT_RESOURCE.code()
                && supportedStage(candidate.getStage())
                && candidate.getWaitResourceType() != null
                && lockOwner != null && lockOwner.equals(candidate.getLockOwner());
    }

    private static boolean supportedStage(Integer stage) {
        return stage != null && stage >= PullTaskExecutionStage.MANAGER_JOIN.code()
                && stage <= PullTaskExecutionStage.MATERIAL_ADMIN.code();
    }

    private void release(
            PullTaskGroupExecution candidate, String lockOwner, long now) {
        if (candidate != null && candidate.getId() != null && lockOwner != null) {
            resources.executionMapper().releaseLock(candidate.getId(), lockOwner, now);
        }
    }

    private static <T> List<T> safe(List<T> rows) {
        return rows == null ? List.of() : rows;
    }

    private static void restoreTenant(Long previousTenant) {
        if (previousTenant == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenant);
        }
    }

    private record ResourceCheck(
            boolean ready,
            String reasonCode,
            String reasonMessage) {

        private static ResourceCheck available() {
            return new ResourceCheck(true, null, null);
        }

        private static ResourceCheck waiting(String reasonCode, String reasonMessage) {
            return new ResourceCheck(false, reasonCode, reasonMessage);
        }
    }
}
