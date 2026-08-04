package com.armada.task.service.impl;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.util.WhatsappJids;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.dto.PullTaskBatchParticipantCallback;
import com.armada.task.model.dto.PullTaskCallReschedule;
import com.armada.task.model.dto.PullTaskCommandCallback;
import com.armada.task.model.dto.PullTaskMaterialAdminCallback;
import com.armada.task.model.dto.PullTaskExecutionResultTransition;
import com.armada.task.model.dto.PullTaskFactStatusCriteria;
import com.armada.task.model.dto.PullTaskFactResult;
import com.armada.task.model.dto.PullTaskFactTransition;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskBatchParticipantProtocolOutcome;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountAdminStatus;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskMaterialAdminStatus;
import com.armada.task.model.enums.PullTaskMaterialAdminTiming;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskProtocolOutcome;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.scheduler.PullTaskUnknownResultResources;
import com.armada.task.service.PullTaskProtocolResultCallbackService;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 使用命令 ID 和条件更新幂等收敛协议回调，不把 UNKNOWN 退回可执行状态。 */
@Service
public class PullTaskProtocolResultCallbackServiceImpl
        implements PullTaskProtocolResultCallbackService {

    private static final String UNCONFIRMED = "PROTOCOL_RESULT_UNCONFIRMED";
    private static final int ADMIN_REQUIRED = 1;
    private static final long MILLIS_PER_MINUTE = 60_000L;
    private static final Set<String> RISK_REASON_CODES = Set.of(
            ProtocolErrorCode.RATE_LIMITED.name(),
            ProtocolErrorCode.ACCOUNT_REACHOUT_RESTRICTED.name());
    private static final List<Integer> ACTION_OPEN = List.of(
            PullTaskActionStatus.SUBMITTED.code(), PullTaskActionStatus.UNKNOWN.code());
    private static final List<Integer> CALL_OPEN = List.of(
            PullTaskPullCallStatus.SUBMITTED.code(), PullTaskPullCallStatus.UNKNOWN.code());
    private static final List<Integer> CALL_CALLBACK_STATES = List.of(
            PullTaskPullCallStatus.SUBMITTED.code(),
            PullTaskPullCallStatus.UNKNOWN.code(),
            PullTaskPullCallStatus.WRITTEN_BACK.code());
    private static final List<Integer> PULL_OPEN = List.of(
            PullTaskMaterialPullStatus.SUBMITTED.code(), PullTaskMaterialPullStatus.UNKNOWN.code());
    private static final List<Integer> MEMBERSHIP_OPEN = List.of(
            PullTaskGroupAccountMembershipStatus.JOINING.code(),
            PullTaskGroupAccountMembershipStatus.UNKNOWN.code());
    private static final List<Integer> PULL_PENDING = List.of(
            PullTaskMaterialPullStatus.SUBMITTED.code());
    private static final List<Integer> MEMBERSHIP_PENDING = List.of(
            PullTaskGroupAccountMembershipStatus.JOINING.code());
    private static final List<Integer> PULL_UNKNOWN = List.of(
            PullTaskMaterialPullStatus.UNKNOWN.code());
    private static final List<Integer> MEMBERSHIP_UNKNOWN = List.of(
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
    private final PullTaskStandardSettingMapper settingMapper;

    /** 构造协议回调收敛服务。 */
    public PullTaskProtocolResultCallbackServiceImpl(
            PullTaskUnknownResultResources resources,
            PullTaskGroupExecutionMapper executionMapper,
            PullTaskStandardSettingMapper settingMapper) {
        this.resources = resources;
        this.executionMapper = executionMapper;
        this.settingMapper = settingMapper;
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
    @Transactional
    public boolean handlePullCallParticipant(PullTaskBatchParticipantCallback callback) {
        Long previousTenant = TenantContext.get();
        TenantContext.set(callback.tenantId());
        try {
            PullTaskPullCall call = resources.callMapper()
                    .selectByCommandId(callback.commandId());
            PullTaskGroupExecution execution = executionMapper
                    .selectById(callback.groupExecutionId());
            if (!matchesCall(call, execution, callback)) {
                return false;
            }
            List<PullTaskGroupAccount> pullers = resources.accountMapper()
                    .selectByExecutionAndRole(
                            callback.groupExecutionId(), PullTaskGroupAccountRole.PULLER.code());
            PullTaskGroupAccount puller = pullers.stream()
                    .filter(row -> Objects.equals(row.getId(), call.getPullerGroupAccountId()))
                    .findFirst().orElse(null);
            if (!matchesPuller(puller, call, callback)) {
                return false;
            }
            Optional<ParticipantTarget> target = participantTarget(call, callback);
            if (target.isEmpty()) {
                return false;
            }
            if (ACCOUNT_NOT_ONLINE.equals(callback.reasonCode())) {
                return rescheduleOfflinePuller(call, execution, puller, callback);
            }
            WriteResult write = writeParticipant(target.get(), callback);
            if (write == WriteResult.REJECTED) {
                return false;
            }
            applyRiskCooldown(puller, callback);
            if (!Objects.equals(
                    call.getCallStatus(), PullTaskPullCallStatus.SUBMITTED.code())
                    || !activePullCallStage(execution)) {
                return convergeLatePullCall(call, write, callback);
            }
            if (hasPendingParticipants(call.getId())) {
                return true;
            }
            finalizeCall(call, execution, pullers, write, callback);
            return true;
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private boolean rescheduleOfflinePuller(
            PullTaskPullCall call,
            PullTaskGroupExecution execution,
            PullTaskGroupAccount puller,
            PullTaskBatchParticipantCallback callback) {
        PullTaskCallReschedule change = new PullTaskCallReschedule(
                new PullTaskCallReschedule.Scope(
                        call.getId(), callback.commandId(), callback.occurredAt()),
                new PullTaskCallReschedule.Status(
                        PullTaskPullCallStatus.SUBMITTED.code(),
                        PullTaskPullCallStatus.PLANNED.code()),
                callback.reasonCode(), callback.reasonMessage());
        if (resources.callMapper().rescheduleSubmitted(change) != 1) {
            return false;
        }
        if (resources.accountMapper().markUnavailable(
                puller.getId(), PullTaskGroupAccountAvailability.OFFLINE.code(),
                callback.reasonCode(), null, callback.occurredAt()) != 1) {
            throw new IllegalStateException("离线拉手状态写入失败");
        }
        if (executionMapper.transitionProtocolResult(new PullTaskExecutionResultTransition(
                execution.getId(), execution.getTaskId(), execution.getVersion(),
                PullTaskExecutionStatus.EXECUTING.code(),
                PullTaskExecutionStage.PULL_EXECUTION.code(),
                PullTaskExecutionStage.PULL_EXECUTION.code(),
                null, 0L, callback.occurredAt())) != 1) {
            throw new IllegalStateException("离线拉手调用重新调度 CAS 失败");
        }
        return true;
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
            transitionMaterialAdminExecution(execution, targetStage, 0L, callback.occurredAt());
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
                nextRunAt, callback.occurredAt());
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

    private Optional<ParticipantTarget> participantTarget(
            PullTaskPullCall call,
            PullTaskBatchParticipantCallback callback) {
        Optional<PullTaskMaterialMember> material = resources.materialMapper()
                .selectByExecution(call.getGroupExecutionId()).stream()
                .filter(row -> Objects.equals(row.getPullCallId(), call.getId()))
                .filter(row -> sameUserJid(row.getNormalizedPhone(), callback.targetJid()))
                .findFirst();
        if (material.isPresent()) {
            return Optional.of(ParticipantTarget.material(material.get()));
        }
        return resources.accountMapper().selectByExecutionAndRole(
                        call.getGroupExecutionId(), PullTaskGroupAccountRole.STATION.code()).stream()
                .filter(row -> Objects.equals(row.getPullCallId(), call.getId()))
                .filter(row -> sameUserJid(row.getAccountPhone(), callback.targetJid()))
                .findFirst()
                .map(ParticipantTarget::station);
    }

    private WriteResult writeParticipant(
            ParticipantTarget target,
            PullTaskBatchParticipantCallback callback) {
        if (target.material() != null) {
            return writeMaterial(target.material(), callback);
        }
        return writeStation(target.station(), callback);
    }

    private WriteResult writeMaterial(
            PullTaskMaterialMember material,
            PullTaskBatchParticipantCallback callback) {
        int target = switch (callback.outcome()) {
            case SUCCESS -> PullTaskMaterialPullStatus.SUCCESS.code();
            case FAILED -> PullTaskMaterialPullStatus.FAILED.code();
            case UNKNOWN -> PullTaskMaterialPullStatus.UNKNOWN.code();
        };
        if (Objects.equals(material.getPullStatus(), target)) {
            return WriteResult.ALREADY_TARGET;
        }
        String jid = callback.outcome() == PullTaskBatchParticipantProtocolOutcome.SUCCESS
                ? callback.targetJid() : null;
        int changed = resources.materialMapper().transitionPullResult(transition(
                material.getId(), PULL_OPEN, target,
                new PullTaskFactResult(callback.reasonCode(), callback.reasonMessage(),
                        jid, callback.occurredAt()), callback.occurredAt()));
        return changed == 1 ? WriteResult.UPDATED : WriteResult.REJECTED;
    }

    private WriteResult writeStation(
            PullTaskGroupAccount station,
            PullTaskBatchParticipantCallback callback) {
        int target = switch (callback.outcome()) {
            case SUCCESS -> PullTaskGroupAccountMembershipStatus.IN_GROUP.code();
            case FAILED -> PullTaskGroupAccountMembershipStatus.JOIN_FAILED.code();
            case UNKNOWN -> PullTaskGroupAccountMembershipStatus.UNKNOWN.code();
        };
        if (Objects.equals(station.getMembershipStatus(), target)) {
            return WriteResult.ALREADY_TARGET;
        }
        Long joinedAt = callback.outcome() == PullTaskBatchParticipantProtocolOutcome.SUCCESS
                ? callback.occurredAt() : null;
        int changed = resources.accountMapper().transitionMembership(transition(
                station.getId(), MEMBERSHIP_OPEN, target,
                new PullTaskFactResult(callback.reasonCode(), callback.reasonMessage(),
                        null, joinedAt), callback.occurredAt()));
        return changed == 1 ? WriteResult.UPDATED : WriteResult.REJECTED;
    }

    private boolean hasPendingParticipants(long callId) {
        return countMaterials(callId, PULL_PENDING) > 0
                || countStations(callId, MEMBERSHIP_PENDING) > 0;
    }

    private boolean hasUnknownParticipants(long callId) {
        return countMaterials(callId, PULL_UNKNOWN) > 0
                || countStations(callId, MEMBERSHIP_UNKNOWN) > 0;
    }

    private boolean convergeLatePullCall(
            PullTaskPullCall call,
            WriteResult participantWrite,
            PullTaskBatchParticipantCallback callback) {
        if (Objects.equals(
                call.getCallStatus(), PullTaskPullCallStatus.WRITTEN_BACK.code())) {
            return participantWrite != WriteResult.REJECTED;
        }
        if (hasPendingParticipants(call.getId())) {
            return true;
        }
        boolean unknown = hasUnknownParticipants(call.getId());
        int targetStatus = unknown
                ? PullTaskPullCallStatus.UNKNOWN.code()
                : PullTaskPullCallStatus.WRITTEN_BACK.code();
        if (Objects.equals(call.getCallStatus(), targetStatus)) {
            return true;
        }
        PullTaskFactResult result = unknown
                ? PullTaskFactResult.reason(UNCONFIRMED, "批量拉人存在未知参与者结果")
                : PullTaskFactResult.empty();
        resources.callMapper().transitionResult(new PullTaskFactTransition(
                call.getId(), List.of(call.getCallStatus()), targetStatus,
                result, callback.occurredAt()));
        return true;
    }

    private int countMaterials(long callId, List<Integer> statuses) {
        return resources.materialMapper().countByPullCallAndStatuses(
                new PullTaskFactStatusCriteria(callId, statuses));
    }

    private int countStations(long callId, List<Integer> statuses) {
        return resources.accountMapper().countByPullCallAndMembershipStatuses(
                new PullTaskFactStatusCriteria(callId, statuses));
    }

    private void finalizeCall(
            PullTaskPullCall call,
            PullTaskGroupExecution execution,
            List<PullTaskGroupAccount> pullers,
            WriteResult participantWrite,
            PullTaskBatchParticipantCallback callback) {
        boolean unknown = hasUnknownParticipants(call.getId());
        int callStatus = unknown
                ? PullTaskPullCallStatus.UNKNOWN.code()
                : PullTaskPullCallStatus.WRITTEN_BACK.code();
        int callWrite = resources.callMapper().transitionResult(transition(
                call.getId(), CALL_OPEN, callStatus,
                unknown ? PullTaskFactResult.reason(
                        UNCONFIRMED, "批量拉人存在未知参与者结果")
                        : PullTaskFactResult.empty(), callback.occurredAt()));
        if (callWrite != 1) {
            if (participantWrite == WriteResult.UPDATED) {
                throw new IllegalStateException("批量拉人调用结果 CAS 失败");
            }
            return;
        }
        int nextPullerIndex = nextPullerIndex(pullers, call.getPullerGroupAccountId());
        int targetStage = targetStage(execution.getId(), execution.getTaskId());
        int executionWrite = executionMapper.transitionProtocolResult(
                new PullTaskExecutionResultTransition(
                        execution.getId(), execution.getTaskId(), execution.getVersion(),
                        PullTaskExecutionStatus.EXECUTING.code(),
                        PullTaskExecutionStage.PULL_EXECUTION.code(), targetStage,
                        nextPullerIndex, 0L, callback.occurredAt()));
        if (executionWrite != 1) {
            throw new IllegalStateException("批量拉人执行行推进 CAS 失败");
        }
    }

    private int targetStage(long executionId, long taskId) {
        boolean hasPendingAdmin = !resources.materialMapper().selectPendingAdmin(
                executionId, ADMIN_REQUIRED, PullTaskMaterialPullStatus.SUCCESS.code(),
                PullTaskMaterialAdminStatus.PENDING.code()).isEmpty();
        boolean hasUnconsumed = !resources.materialMapper()
                .selectUnconsumed(executionId, 1).isEmpty();
        if (!hasPendingAdmin) {
            return hasUnconsumed
                    ? PullTaskExecutionStage.PULL_EXECUTION.code()
                    : PullTaskExecutionStage.CLOSING.code();
        }
        PullTaskStandardSetting setting = settingMapper.selectByTaskId(taskId);
        if (setting == null || setting.getMaterialAdminTiming() == null) {
            throw new IllegalStateException("料子提权设置时机缺失");
        }
        if (Objects.equals(setting.getMaterialAdminTiming(),
                PullTaskMaterialAdminTiming.IMMEDIATE.code()) || !hasUnconsumed) {
            return PullTaskExecutionStage.MATERIAL_ADMIN.code();
        }
        return PullTaskExecutionStage.PULL_EXECUTION.code();
    }

    private void applyRiskCooldown(
            PullTaskGroupAccount puller,
            PullTaskBatchParticipantCallback callback) {
        if (callback.reasonCode() == null
                || !RISK_REASON_CODES.contains(callback.reasonCode())) {
            return;
        }
        if (Objects.equals(puller.getAvailabilityStatus(),
                PullTaskGroupAccountAvailability.RISK_COOLDOWN.code())
                && Objects.equals(puller.getUnavailableReasonCode(), callback.reasonCode())) {
            return;
        }
        PullTaskStandardSetting setting = settingMapper.selectByTaskId(callback.pullTaskId());
        if (setting == null) {
            throw new IllegalStateException("拉手风控事实缺少冻结配置");
        }
        Long cooldownUntil = setting.getPullerRiskMinutes() == null
                || setting.getPullerRiskMinutes() <= 0
                ? null : Math.addExact(callback.occurredAt(), Math.multiplyExact(
                setting.getPullerRiskMinutes().longValue(), MILLIS_PER_MINUTE));
        if (resources.accountMapper().markUnavailable(
                puller.getId(), PullTaskGroupAccountAvailability.RISK_COOLDOWN.code(),
                callback.reasonCode(), cooldownUntil, callback.occurredAt()) != 1) {
            throw new IllegalStateException("拉手风控冷却状态写入失败");
        }
    }

    private static int nextPullerIndex(
            List<PullTaskGroupAccount> pullers,
            long currentPullerId) {
        for (PullTaskGroupAccount puller : pullers) {
            if (Objects.equals(puller.getId(), currentPullerId)) {
                Integer roleSeq = puller.getRoleSeq();
                return roleSeq == null ? 0 : Math.addExact(roleSeq, 1);
            }
        }
        return 0;
    }

    private static boolean matchesCall(
            PullTaskPullCall call,
            PullTaskGroupExecution execution,
            PullTaskBatchParticipantCallback callback) {
        return call != null && execution != null && callback.attemptNo() == 1
                && CALL_CALLBACK_STATES.contains(call.getCallStatus())
                && Objects.equals(call.getId(), callback.pullCallId())
                && Objects.equals(call.getTaskId(), callback.pullTaskId())
                && Objects.equals(call.getGroupExecutionId(), callback.groupExecutionId())
                && Objects.equals(call.getCommandId(), callback.commandId())
                && Objects.equals(execution.getId(), callback.groupExecutionId())
                && Objects.equals(execution.getTaskId(), callback.pullTaskId());
    }

    private static boolean activePullCallStage(PullTaskGroupExecution execution) {
        return Objects.equals(execution.getExecutionStatus(),
                PullTaskExecutionStatus.EXECUTING.code())
                && Objects.equals(execution.getStage(),
                PullTaskExecutionStage.PULL_EXECUTION.code());
    }

    private static boolean matchesPuller(
            PullTaskGroupAccount puller,
            PullTaskPullCall call,
            PullTaskBatchParticipantCallback callback) {
        return puller != null
                && Objects.equals(puller.getId(), call.getPullerGroupAccountId())
                && Objects.equals(puller.getAccountId(), callback.accountId())
                && Objects.equals(puller.getTaskId(), callback.pullTaskId())
                && Objects.equals(puller.getGroupExecutionId(), callback.groupExecutionId());
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

    private enum WriteResult {
        ALREADY_TARGET,
        UPDATED,
        REJECTED
    }

    private record ParticipantTarget(
            PullTaskMaterialMember material,
            PullTaskGroupAccount station) {

        private static ParticipantTarget material(PullTaskMaterialMember material) {
            return new ParticipantTarget(material, null);
        }

        private static ParticipantTarget station(PullTaskGroupAccount station) {
            return new ParticipantTarget(null, station);
        }
    }
}
