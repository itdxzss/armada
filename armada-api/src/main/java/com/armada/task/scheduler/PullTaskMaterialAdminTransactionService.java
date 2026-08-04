package com.armada.task.scheduler;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.command.ProtocolPullTaskMaterialAdminCommandRequest;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.enums.PullTaskExecutionReasonCode;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAdminStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskMaterialAdminStatus;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import com.armada.task.model.enums.PullTaskWaitResourceType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** SC-04 单个 A/a 料子提权的 Outbox 提交与 stage 6 检查点事务。 */
@Service
public class PullTaskMaterialAdminTransactionService {

    private static final String NORMAL_LINK_MODE = "NORMAL_LINK";
    private static final int ADMIN_REQUIRED = 1;
    private static final List<Integer> MANAGER_ADMIN_READY_STATUSES = List.of(
            PullTaskGroupAccountAdminStatus.PENDING.code(),
            PullTaskGroupAccountAdminStatus.SUCCESS.code(),
            PullTaskGroupAccountAdminStatus.UNKNOWN.code());

    private final PullTaskMapper taskMapper;
    private final PullTaskGroupAccountMapper groupAccountMapper;
    private final PullTaskMaterialMemberMapper materialMapper;
    private final PullTaskMaterialAdminResources resources;

    /** 创建料子提权 Outbox 短事务。 */
    public PullTaskMaterialAdminTransactionService(
            PullTaskMapper taskMapper,
            PullTaskGroupAccountMapper groupAccountMapper,
            PullTaskMaterialMemberMapper materialMapper,
            PullTaskMaterialAdminResources resources) {
        this.taskMapper = taskMapper;
        this.groupAccountMapper = groupAccountMapper;
        this.materialMapper = materialMapper;
        this.resources = resources;
    }

    /** 短事务内选管理账号、写 Outbox、预写命令 ID 并释放调度租约。 */
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
                release(candidate.getId(), lockOwner, now);
                return PullTaskExecutionDispatchResult.LOST;
            }
            if (hasSubmittedAdmin(candidate.getId())) {
                return deferSubmitted(candidate, now);
            }
            Optional<PullTaskMaterialMember> material = pendingAdmin(candidate.getId())
                    .stream().findFirst();
            if (material.isEmpty()) {
                return finishOrDefer(candidate, now);
            }
            if (material.get().getWaJid() == null || material.get().getWaJid().isBlank()) {
                writeInvalidTarget(material.get().getId(), now);
                return finishOrDefer(candidate, now);
            }
            ManagerPool pool = managerPool(candidate.getId());
            if (pool.managers().isEmpty()) {
                return waitForManager(candidate, now);
            }
            PullTaskGroupAccount manager = pool.managers().get(0);
            return submit(candidate, material.get(), manager,
                    pool.refs().get(manager.getAccountId()), now);
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private PullTaskExecutionDispatchResult submit(
            PullTaskGroupExecution candidate,
            PullTaskMaterialMember material,
            PullTaskGroupAccount manager,
            ProtocolAccountRef actor,
            long now) {
        ProtocolCommandOutboxEnqueueResult enqueued = resources.outboxService()
                .enqueuePullTaskMaterialAdminCommands(List.of(
                        new ProtocolPullTaskMaterialAdminCommandRequest(
                                candidate.getTenantId(), candidate.getTaskId(), candidate.getId(),
                                material.getId(), manager.getId(), actor)));
        if (enqueued.inserted() != 1 || enqueued.commandIds().size() != 1) {
            throw new IllegalStateException("料子提权 Outbox 命令写入数量不一致");
        }
        if (materialMapper.markAdminSubmitted(
                material.getId(), PullTaskMaterialAdminStatus.PENDING.code(),
                PullTaskMaterialAdminStatus.SUBMITTED.code(),
                enqueued.commandIds().get(0), now) != 1) {
            throw new IllegalStateException("料子提权提交状态写入失败");
        }
        return deferSubmitted(candidate, now);
    }

    private PullTaskExecutionDispatchResult deferSubmitted(
            PullTaskGroupExecution candidate, long now) {
        PullTaskGroupExecution update = transition(candidate, now);
        update.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        update.setStage(PullTaskExecutionStage.MATERIAL_ADMIN.code());
        update.setNextRunAt(Math.addExact(
                now, resources.properties().getResultReconciliationDelayMs()));
        if (resources.executionMapper().transitionClaimed(
                update, PullTaskExecutionStage.MATERIAL_ADMIN.code()) != 1) {
            throw new IllegalStateException("料子提权提交后执行行释放失败");
        }
        return PullTaskExecutionDispatchResult.DEFERRED;
    }

    private PullTaskExecutionDispatchResult finishOrDefer(
            PullTaskGroupExecution candidate, long now) {
        if (!pendingAdmin(candidate.getId()).isEmpty()) {
            if (managerPool(candidate.getId()).managers().isEmpty()) {
                return waitForManager(candidate, now);
            }
            return transitionStage(
                    candidate, PullTaskExecutionStage.MATERIAL_ADMIN,
                    PullTaskExecutionDispatchResult.DEFERRED, now);
        }
        PullTaskExecutionStage target = hasUnconsumed(candidate.getId())
                ? PullTaskExecutionStage.PULL_EXECUTION
                : PullTaskExecutionStage.CLOSING;
        return transitionStage(
                candidate, target, PullTaskExecutionDispatchResult.ADVANCED, now);
    }

    private PullTaskExecutionDispatchResult transitionStage(
            PullTaskGroupExecution candidate,
            PullTaskExecutionStage target,
            PullTaskExecutionDispatchResult success,
            long now) {
        PullTaskGroupExecution update = transition(candidate, now);
        update.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        update.setStage(target.code());
        update.setNextRunAt(0L);
        return resources.executionMapper().transitionClaimed(
                update, PullTaskExecutionStage.MATERIAL_ADMIN.code()) == 1
                ? success : PullTaskExecutionDispatchResult.LOST;
    }

    private PullTaskExecutionDispatchResult waitForManager(
            PullTaskGroupExecution candidate, long now) {
        PullTaskGroupExecution update = transition(candidate, now);
        PullTaskExecutionReasonCode reason = PullTaskExecutionReasonCode.MANAGER_UNAVAILABLE;
        update.setExecutionStatus(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        update.setStage(PullTaskExecutionStage.MATERIAL_ADMIN.code());
        update.setWaitResourceType(PullTaskWaitResourceType.MANAGER.code());
        update.setReasonCode(reason.name());
        update.setReasonMessage(reason.message() + "，缺口人数=1");
        update.setNextRunAt(0L);
        PullTaskExecutionDispatchResult result = resources.executionMapper().transitionClaimed(
                update, PullTaskExecutionStage.MATERIAL_ADMIN.code()) == 1
                ? PullTaskExecutionDispatchResult.DEFERRED
                : PullTaskExecutionDispatchResult.LOST;
        if (result != PullTaskExecutionDispatchResult.LOST) {
            groupAccountMapper.releaseAllPullersOfExecution(candidate.getId(), now);
        }
        return result;
    }

    private ManagerPool managerPool(long executionId) {
        List<PullTaskGroupAccount> stored = groupAccountMapper.selectByExecutionAndRole(
                        executionId, PullTaskGroupAccountRole.MANAGER.code())
                .stream()
                .filter(PullTaskMaterialAdminTransactionService::availableManager)
                .toList();
        if (stored.isEmpty()) {
            return new ManagerPool(List.of(), Map.of());
        }
        List<Long> accountIds = stored.stream()
                .map(PullTaskGroupAccount::getAccountId).distinct().toList();
        Map<Long, ProtocolAccountRef> refs = new HashMap<>();
        for (ProtocolAccountRef ref : resources.accountLookup()
                .findActiveProtocolRefs(accountIds)) {
            if (ref != null) {
                refs.putIfAbsent(ref.armadaAccountId(), ref);
            }
        }
        return new ManagerPool(stored.stream()
                .filter(row -> refs.containsKey(row.getAccountId())).toList(), refs);
    }

    private List<PullTaskMaterialMember> pendingAdmin(long executionId) {
        return materialMapper.selectPendingAdmin(
                executionId, ADMIN_REQUIRED, PullTaskMaterialPullStatus.SUCCESS.code(),
                PullTaskMaterialAdminStatus.PENDING.code());
    }

    private boolean hasSubmittedAdmin(long executionId) {
        return materialMapper.selectByExecution(executionId).stream()
                .anyMatch(row -> Objects.equals(
                        row.getAdminStatus(), PullTaskMaterialAdminStatus.SUBMITTED.code()));
    }

    private boolean hasUnconsumed(long executionId) {
        return !materialMapper.selectUnconsumed(executionId, 1).isEmpty();
    }

    private void writeInvalidTarget(long materialId, long now) {
        if (materialMapper.writeBackAdminResult(
                materialId, PullTaskMaterialAdminStatus.PENDING.code(),
                PullTaskMaterialAdminStatus.FAILED.code(),
                "MATERIAL_ADMIN_TARGET_MISSING", now) != 1) {
            throw new IllegalStateException("无目标 JID 的料子提权状态回写失败");
        }
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

    private static boolean availableManager(PullTaskGroupAccount row) {
        return Objects.equals(row.getAvailabilityStatus(),
                PullTaskGroupAccountAvailability.AVAILABLE.code())
                && Objects.equals(row.getMembershipStatus(),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code())
                && MANAGER_ADMIN_READY_STATUSES.contains(row.getAdminStatus());
    }

    private static boolean hasIdentity(PullTaskGroupExecution candidate) {
        return candidate != null && candidate.getTenantId() != null
                && candidate.getId() != null && candidate.getTaskId() != null;
    }

    private static boolean isDispatchable(
            PullTask parent, PullTaskGroupExecution candidate, String lockOwner) {
        return parent != null
                && parent.getTaskType() == PullTaskType.STANDARD
                && NORMAL_LINK_MODE.equals(parent.getMode())
                && PullTaskStandardStatus.EXECUTING.name().equals(parent.getStatus())
                && candidate.getExecutionStatus() == PullTaskExecutionStatus.EXECUTING.code()
                && candidate.getStage() == PullTaskExecutionStage.MATERIAL_ADMIN.code()
                && lockOwner != null && lockOwner.equals(candidate.getLockOwner());
    }

    private void release(long executionId, String lockOwner, long now) {
        if (lockOwner != null) {
            resources.executionMapper().releaseLock(executionId, lockOwner, now);
        }
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
}
