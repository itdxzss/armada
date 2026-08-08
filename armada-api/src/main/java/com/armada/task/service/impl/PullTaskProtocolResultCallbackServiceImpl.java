package com.armada.task.service.impl;

import com.armada.platform.protocol.util.WhatsappJids;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.model.dto.PullTaskBatchParticipantCallback;
import com.armada.task.model.dto.PullTaskCommandCallback;
import com.armada.task.model.dto.PullTaskMaterialAdminCallback;
import com.armada.task.model.dto.PullTaskExecutionResultTransition;
import com.armada.task.model.dto.PullTaskFactResult;
import com.armada.task.model.dto.PullTaskFactTransition;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountAdminStatus;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskMaterialAdminStatus;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskProtocolOutcome;
import com.armada.task.scheduler.PullTaskUnknownResultResources;
import com.armada.task.scheduler.PullTaskOperationDelayPolicy;
import com.armada.task.service.PullTaskProtocolResultCallbackService;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 使用命令 ID 和条件更新幂等收敛协议回调，不把 UNKNOWN 退回可执行状态。 */
@Service
public class PullTaskProtocolResultCallbackServiceImpl
        implements PullTaskProtocolResultCallbackService {

    private static final int ADMIN_REQUIRED = 1;
    private static final List<Integer> ACTION_OPEN = List.of(
            PullTaskActionStatus.SUBMITTED.code(), PullTaskActionStatus.UNKNOWN.code());
    private static final List<Integer> MEMBERSHIP_OPEN = List.of(
            PullTaskGroupAccountMembershipStatus.JOINING.code(),
            PullTaskGroupAccountMembershipStatus.UNKNOWN.code());
    private static final List<Integer> ADMIN_OPEN = List.of(
            PullTaskMaterialAdminStatus.SUBMITTED.code(),
            PullTaskMaterialAdminStatus.UNKNOWN.code());
    private static final List<Integer> ADMIN_CALLBACK_STATES = List.of(
            PullTaskMaterialAdminStatus.SUBMITTED.code(),
            PullTaskMaterialAdminStatus.UNKNOWN.code(),
            PullTaskMaterialAdminStatus.SUCCESS.code(),
            PullTaskMaterialAdminStatus.FAILED.code());
    private static final List<Integer> MANAGER_ADMIN_OPEN = List.of(
            PullTaskGroupAccountAdminStatus.PENDING.code(),
            PullTaskGroupAccountAdminStatus.SUCCESS.code(),
            PullTaskGroupAccountAdminStatus.UNKNOWN.code());
    private static final String ADMIN_ACTOR_DENIED =
            "MATERIAL_ADMIN_ACTOR_PERMISSION_DENIED";
    private static final String ADMIN_ACTOR_UNCONFIRMED =
            "MATERIAL_ADMIN_ACTOR_PERMISSION_UNCONFIRMED";
    private static final String ACCOUNT_NOT_ONLINE = "ACCOUNT_NOT_ONLINE";
    private static final long ADMIN_PERMISSION_RETRY_DELAY_MS = 1_000L;

    private final PullTaskUnknownResultResources resources;
    private final PullTaskGroupExecutionMapper executionMapper;
    private final PullTaskPullCallParticipantResultService participantResultService;
    private final PullTaskOperationDelayPolicy delayPolicy;

    /** 构造协议回调收敛服务。 */
    public PullTaskProtocolResultCallbackServiceImpl(
            PullTaskUnknownResultResources resources,
            PullTaskGroupExecutionMapper executionMapper,
            PullTaskPullCallParticipantResultService participantResultService,
            PullTaskOperationDelayPolicy delayPolicy) {
        this.resources = resources;
        this.executionMapper = executionMapper;
        this.participantResultService = participantResultService;
        this.delayPolicy = delayPolicy;
    }

    @Override
    @Transactional
    public boolean handleAccountAction(PullTaskCommandCallback callback) {
        PullTaskAccountAction action = resources.actionMapper()
                .selectByCommandId(callback.commandId());
        if (action == null) {
            return false;
        }
        int target = callback.outcome() == PullTaskProtocolOutcome.SUCCESS
                ? PullTaskActionStatus.SUCCESS.code() : PullTaskActionStatus.FAILED.code();
        if (Objects.equals(action.getActionStatus(), target)) {
            return true;
        }
        int changed = resources.actionMapper().transitionResult(transition(
                action.getId(), ACTION_OPEN, target,
                callbackResult(callback, null), callback.occurredAt()));
        if (changed != 1) {
            return false;
        }
        convergeActionMembership(action, callback);
        return true;
    }

    @Override
    public boolean handlePullCallParticipant(PullTaskBatchParticipantCallback callback) {
        return participantResultService.handle(callback);
    }

    @Override
    @Transactional
    public boolean handleMaterialAdmin(PullTaskMaterialAdminCallback callback) {
        Long previousTenant = TenantContext.get();
        TenantContext.set(callback.tenantId());
        try {
            PullTaskMaterialMember material = resources.materialMapper()
                    .selectByAdminCommandId(callback.commandId());
            PullTaskGroupExecution execution = executionMapper.selectById(
                    callback.groupExecutionId());
            PullTaskGroupAccount manager = materialAdminManager(callback);
            if (!matchesMaterialAdmin(material, execution, manager, callback)) {
                return false;
            }
            int target = switch (callback.outcome()) {
                case SUCCESS -> PullTaskMaterialAdminStatus.SUCCESS.code();
                case FAILED -> PullTaskMaterialAdminStatus.FAILED.code();
                case UNKNOWN -> PullTaskMaterialAdminStatus.UNKNOWN.code();
            };
            if (!ADMIN_OPEN.contains(material.getAdminStatus())) {
                return Objects.equals(material.getAdminStatus(), target);
            }
            boolean activeStage = activeMaterialAdminStage(execution);
            if (!activeStage) {
                return convergeLateMaterialAdmin(
                        material, manager, callback, target);
            }
            if (Objects.equals(material.getAdminStatus(), target)) {
                return true;
            }
            if (ADMIN_ACTOR_DENIED.equals(callback.reasonCode())) {
                if (!markManagerAdmin(
                        manager, PullTaskGroupAccountAdminStatus.FAILED,
                        callback.occurredAt())) {
                    return false;
                }
                return resetAfterActorCheck(material, execution, callback, 0L);
            }
            if (ADMIN_ACTOR_UNCONFIRMED.equals(callback.reasonCode())) {
                return resetAfterActorCheck(material, execution, callback,
                        Math.addExact(callback.occurredAt(), ADMIN_PERMISSION_RETRY_DELAY_MS));
            }
            if (ACCOUNT_NOT_ONLINE.equals(callback.reasonCode())) {
                if (resources.accountMapper().markUnavailable(
                        manager.getId(), PullTaskGroupAccountAvailability.OFFLINE.code(),
                        callback.reasonCode(), null, callback.occurredAt()) != 1) {
                    return false;
                }
                return resetAfterActorCheck(material, execution, callback, 0L);
            }
            if (!markManagerAdmin(manager, PullTaskGroupAccountAdminStatus.SUCCESS,
                    callback.occurredAt())) {
                return false;
            }
            if (!writeMaterialAdminResult(material, callback, target)) {
                return false;
            }
            int targetStage = nextMaterialAdminStage(callback.groupExecutionId());
            long nextRunAt = targetStage == PullTaskExecutionStage.CLOSING.code()
                    ? 0L : delayPolicy.nextSideEffectAt(callback.occurredAt());
            transitionMaterialAdminExecution(
                    execution, targetStage, nextRunAt, callback.occurredAt());
            return true;
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private PullTaskGroupAccount materialAdminManager(PullTaskMaterialAdminCallback callback) {
        return resources.accountMapper().selectByExecutionAndRole(
                        callback.groupExecutionId(), PullTaskGroupAccountRole.MANAGER.code())
                .stream()
                .filter(row -> Objects.equals(row.getAccountId(), callback.accountId()))
                .findFirst().orElse(null);
    }

    private boolean resetAfterActorCheck(
            PullTaskMaterialMember material,
            PullTaskGroupExecution execution,
            PullTaskMaterialAdminCallback callback,
            long nextRunAt) {
        if (resources.materialMapper().returnAdminToPending(
                material.getId(), material.getAdminStatus(),
                PullTaskMaterialAdminStatus.PENDING.code(), callback.reasonCode(),
                callback.occurredAt()) != 1) {
            return false;
        }
        transitionMaterialAdminExecution(
                execution, PullTaskExecutionStage.MATERIAL_ADMIN.code(),
                delayPolicy.maxDeadline(nextRunAt, callback.occurredAt()),
                callback.occurredAt());
        return true;
    }

    private boolean convergeLateMaterialAdmin(
            PullTaskMaterialMember material,
            PullTaskGroupAccount manager,
            PullTaskMaterialAdminCallback callback,
            int target) {
        if (ADMIN_ACTOR_DENIED.equals(callback.reasonCode())) {
            if (!markManagerAdmin(
                    manager, PullTaskGroupAccountAdminStatus.FAILED,
                    callback.occurredAt())) {
                return false;
            }
        } else if (ACCOUNT_NOT_ONLINE.equals(callback.reasonCode())) {
            if (resources.accountMapper().markUnavailable(
                    manager.getId(), PullTaskGroupAccountAvailability.OFFLINE.code(),
                    callback.reasonCode(), null, callback.occurredAt()) != 1) {
                return false;
            }
        } else if (!ADMIN_ACTOR_UNCONFIRMED.equals(callback.reasonCode())
                && !markManagerAdmin(manager, PullTaskGroupAccountAdminStatus.SUCCESS,
                callback.occurredAt())) {
            return false;
        }
        return Objects.equals(material.getAdminStatus(), target)
                || writeMaterialAdminResult(material, callback, target);
    }

    private boolean writeMaterialAdminResult(
            PullTaskMaterialMember material,
            PullTaskMaterialAdminCallback callback,
            int target) {
        PullTaskFactResult result = new PullTaskFactResult(
                callback.reasonCode(), callback.reasonMessage(), null, callback.occurredAt());
        return resources.materialMapper().transitionAdminResult(transition(
                material.getId(), ADMIN_OPEN, target, result, callback.occurredAt())) == 1;
    }

    private boolean markManagerAdmin(
            PullTaskGroupAccount manager,
            PullTaskGroupAccountAdminStatus target,
            long now) {
        return resources.accountMapper().transitionAdminStatus(
                manager.getId(), MANAGER_ADMIN_OPEN, target.code(), now) == 1;
    }

    private int nextMaterialAdminStage(long executionId) {
        boolean pending = resources.materialMapper().selectByExecution(executionId).stream()
                .anyMatch(row -> Objects.equals(
                        row.getAdminStatus(), PullTaskMaterialAdminStatus.PENDING.code())
                        || Objects.equals(
                        row.getAdminStatus(), PullTaskMaterialAdminStatus.SUBMITTED.code()));
        if (pending) {
            return PullTaskExecutionStage.MATERIAL_ADMIN.code();
        }
        return resources.materialMapper().selectUnconsumed(executionId, 1).isEmpty()
                ? PullTaskExecutionStage.CLOSING.code()
                : PullTaskExecutionStage.PULL_EXECUTION.code();
    }

    private void transitionMaterialAdminExecution(
            PullTaskGroupExecution execution,
            int targetStage,
            long nextRunAt,
            long now) {
        if (executionMapper.transitionProtocolResult(new PullTaskExecutionResultTransition(
                execution.getId(), execution.getTaskId(), execution.getVersion(),
                PullTaskExecutionStatus.EXECUTING.code(),
                PullTaskExecutionStage.MATERIAL_ADMIN.code(), targetStage,
                null, nextRunAt, now)) != 1) {
            throw new IllegalStateException("料子提权执行行推进 CAS 失败");
        }
    }

    private static boolean matchesMaterialAdmin(
            PullTaskMaterialMember material,
            PullTaskGroupExecution execution,
            PullTaskGroupAccount manager,
            PullTaskMaterialAdminCallback callback) {
        return material != null && execution != null && manager != null
                && callback.attemptNo() == 1
                && ADMIN_CALLBACK_STATES.contains(material.getAdminStatus())
                && Objects.equals(material.getId(), callback.materialId())
                && Objects.equals(material.getGroupExecutionId(), callback.groupExecutionId())
                && Objects.equals(material.getAdminRequired(), ADMIN_REQUIRED)
                && Objects.equals(material.getPullStatus(), PullTaskMaterialPullStatus.SUCCESS.code())
                && Objects.equals(material.getAdminCommandId(), callback.commandId())
                && sameUserJid(material.getWaJid(), callback.targetJid())
                && Objects.equals(execution.getId(), callback.groupExecutionId())
                && Objects.equals(execution.getTaskId(), callback.pullTaskId())
                && Objects.equals(manager.getTaskId(), callback.pullTaskId())
                && Objects.equals(manager.getGroupExecutionId(), callback.groupExecutionId())
                && Objects.equals(manager.getAccountId(), callback.accountId());
    }

    private static boolean activeMaterialAdminStage(PullTaskGroupExecution execution) {
        return Objects.equals(execution.getExecutionStatus(),
                PullTaskExecutionStatus.EXECUTING.code())
                && Objects.equals(execution.getStage(),
                PullTaskExecutionStage.MATERIAL_ADMIN.code());
    }

    private void convergeActionMembership(
            PullTaskAccountAction action,
            PullTaskCommandCallback callback) {
        if (Objects.equals(action.getActionType(), PullTaskAccountActionType.SAVE_CONTACT.code())) {
            return;
        }
        PullTaskGroupAccount target = resources.accountMapper()
                .selectById(action.getTargetGroupAccountId());
        if (target == null) {
            return;
        }
        int membership = callback.outcome() == PullTaskProtocolOutcome.SUCCESS
                ? PullTaskGroupAccountMembershipStatus.IN_GROUP.code()
                : PullTaskGroupAccountMembershipStatus.JOIN_FAILED.code();
        Long joinedAt = callback.outcome() == PullTaskProtocolOutcome.SUCCESS
                ? callback.occurredAt() : null;
        resources.accountMapper().transitionMembership(transition(
                target.getId(), MEMBERSHIP_OPEN, membership,
                new PullTaskFactResult(callback.reasonCode(), callback.reasonMessage(),
                        null, joinedAt), callback.occurredAt()));
    }

    private static boolean sameUserJid(String phone, String targetJid) {
        try {
            return WhatsappJids.userJid(phone).equalsIgnoreCase(targetJid);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static PullTaskFactTransition transition(
            long id,
            List<Integer> expected,
            int target,
            PullTaskFactResult result,
            long now) {
        return new PullTaskFactTransition(id, expected, target, result, now);
    }

    private static PullTaskFactResult callbackResult(
            PullTaskCommandCallback callback, String jid) {
        return new PullTaskFactResult(
                callback.reasonCode(), callback.reasonMessage(), jid, callback.occurredAt());
    }

    private static void restoreTenant(Long tenantId) {
        if (tenantId == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(tenantId);
        }
    }

}
