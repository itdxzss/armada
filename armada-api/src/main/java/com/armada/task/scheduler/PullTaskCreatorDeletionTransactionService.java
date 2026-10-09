package com.armada.task.scheduler;

import com.armada.account.model.dto.CreatorDeletionBinding;
import com.armada.account.model.dto.CreatorReservationRequest;
import com.armada.account.model.dto.CreatorReleaseRequest;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.CreatorDeletionObservation;
import com.armada.platform.protocol.model.result.CreatorDeletionResult;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskCreatorDeletionMapper;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskCreatorDeletion;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskCreationMode;
import com.armada.task.model.enums.PullTaskCreatorDeletionStatus;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 永久注销账本和执行行的短事务；只有首次发送意图允许 POST，恢复仅查询。 */
@Service
public class PullTaskCreatorDeletionTransactionService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PullTaskCreatorDeletionTransactionService.class);
    private static final long VERIFICATION_WINDOW_MS = 30 * 60_000L;
    private static final long INITIAL_BACKOFF_MS = 15_000L;
    private static final long MAX_BACKOFF_MS = 120_000L;
    private static final long ACCEPTED_FAST_POLL_WINDOW_MS = 6 * 60_000L;
    private static final long ACCEPTED_FAST_POLL_INTERVAL_MS = 10_000L;
    private final PullTaskCreatorDeletionMapper deletions;
    private final PullTaskCreatorDeletionGate gate;
    private final PullTaskCreatorDeletionResources resources;

    /** 创建永久注销事务边界。 */
    public PullTaskCreatorDeletionTransactionService(PullTaskCreatorDeletionMapper deletions,
            PullTaskCreatorDeletionGate gate, PullTaskCreatorDeletionResources resources) {
        this.deletions = deletions;
        this.gate = gate;
        this.resources = resources;
    }

    /**
     * 在执行行终态事务中释放从未提交注销的预留；失败或不满足判据时保留保护。
     * 锁序是提交路径的子序列：执行行、账本、身份别名、账号，不反向获取父任务锁。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean releaseIfTerminalUnsubmitted(long tenantId, long executionId, String reason, long now) {
        return tenant(tenantId, () -> {
            PullTaskGroupExecution execution = resources.executions().selectByIdForUpdate(executionId);
            if (execution == null || !Objects.equals(execution.getTenantId(), tenantId)
                    || !List.of(PullTaskExecutionStatus.COMPLETED.code(), PullTaskExecutionStatus.FAILED.code(),
                            PullTaskExecutionStatus.ABANDONED.code()).contains(execution.getExecutionStatus())) return false;
            PullTaskCreatorDeletion row = deletions.selectByExecutionIdForUpdate(executionId);
            if (row == null) {
                if (resources.lifecycle().hasReservation(executionId)) {
                    log.warn("终态执行缺少建群注销账本，保留预留 tenantId={} taskId={} executionId={}",
                            tenantId, execution.getTaskId(), executionId);
                }
                return false;
            }
            if (!Objects.equals(row.getTenantId(), tenantId) || !Objects.equals(row.getTaskId(), execution.getTaskId())
                    || !Objects.equals(row.getStatus(), PullTaskCreatorDeletionStatus.RESERVED.code())
                    || row.getSubmittedAt() != null) return false;
            // 账本锁持续到提交；释放和 claimSubmission 不能交错，CAS 失败必须回滚账号归档。
            if (!resources.lifecycle().releaseUnsubmitted(new CreatorReleaseRequest(binding(row),
                    execution.getExecutionStatus(), reason, now))) return false;
            if (deletions.releaseUnsubmitted(row, now) != 1) {
                throw new IllegalStateException("建群账号预留释放账本竞争失败");
            }
            resources.roles().releaseCreatorReservation(row, now);
            log.info("建群账号预留已释放 tenantId={} taskId={} executionId={} accountId={} reason={}",
                    tenantId, row.getTaskId(), executionId, row.getCreatorAccountId(), reason);
            return true;
        });
    }

    /** 父任务结束后的同步收口，只遍历本任务实际存在的注销账本。 */
    @Transactional(rollbackFor = Exception.class)
    public void releaseTerminalByTask(long tenantId, long taskId, long now) {
        tenant(tenantId, () -> {
            for (Long executionId : deletions.selectExecutionIdsByTask(taskId)) {
                releaseIfTerminalUnsubmitted(tenantId, executionId, "TASK_ENDED", now);
            }
            return true;
        });
    }

    /** 在选号事务内跨任务排他占用，并冻结创建者，不能从后续 PROMOTER 角色推断。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean reserve(PullTaskGroupExecution execution, ProtocolAccountRef creator,
            String createOperationId, long now) {
        if (!resources.lifecycle().reserve(new CreatorReservationRequest(execution.getTenantId(),
                execution.getTaskId(), execution.getId(), creator, createOperationId, now))) {
            return false;
        }
        PullTaskCreatorDeletion old = deletions.selectByExecutionId(execution.getId());
        if (old != null) {
            return Objects.equals(old.getCreatorAccountId(), creator.armadaAccountId())
                    && Objects.equals(old.getCreateOperationId(), createOperationId);
        }
        PullTaskCreatorDeletion row = new PullTaskCreatorDeletion();
        row.setTenantId(execution.getTenantId());
        row.setTaskId(execution.getTaskId());
        row.setGroupExecutionId(execution.getId());
        row.setCreatorAccountId(creator.armadaAccountId());
        row.setCreatorIdentityHash(resources.lifecycle().identityHash(creator));
        row.setCreatorProtocolAccountId(creator.protocolAccountId());
        row.setCreatorPhone(canonicalPhone(creator.wsPhone()));
        row.setCreateOperationId(createOperationId);
        row.setOperationId("ptcd_tenant_" + execution.getTenantId() + "_execution_" + execution.getId());
        row.setStatus(PullTaskCreatorDeletionStatus.RESERVED.code());
        row.setAttempts(0);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        if (deletions.insert(row) != 1) {
            throw new IllegalStateException("建群者冻结账本写入失败");
        }
        return true;
    }

    /** 建群调用前核对冻结身份，禁止同一后台 accountId 已换号后创建另一个目标。 */
    public boolean frozenCreatorMatches(PullTaskGroupExecution execution, ProtocolAccountRef creator) {
        PullTaskCreatorDeletion row = deletions.selectByExecutionId(execution.getId());
        return row != null && Objects.equals(row.getCreatorAccountId(), creator.armadaAccountId())
                && Objects.equals(row.getCreateOperationId(), execution.getCreateOperationId())
                && Objects.equals(row.getCreatorProtocolAccountId(), creator.protocolAccountId())
                && Objects.equals(canonicalPhone(row.getCreatorPhone()), canonicalPhone(creator.wsPhone()))
                && Objects.equals(row.getCreatorIdentityHash(), resources.lifecycle().identityHash(creator));
    }

    /** 验证当前租约、父任务和冻结目标，为一次新鲜查询准备固定管理号。 */
    @Transactional(rollbackFor = Exception.class)
    public Optional<PullTaskCreatorDeletionWork> prepare(PullTaskGroupExecution candidate, long now) {
        return tenant(candidate.getTenantId(), () -> {
            PullTaskGroupExecution current = current(candidate, now);
            if (current == null) {
                return Optional.empty();
            }
            PullTaskCreatorDeletion row = deletions.selectByExecutionIdForUpdate(candidate.getId());
            if (!gate.enabled(candidate.getTaskId()) || !validBinding(current, row)) {
                pause(current, "CREATOR_DELETE_IDENTITY_UNCONFIRMED", "建群账号冻结身份或注销配置不完整，禁止注销", now);
                return Optional.empty();
            }
            if (Objects.equals(row.getStatus(), PullTaskCreatorDeletionStatus.COMPLETE.code())) {
                transition(current, new Transition(PullTaskExecutionStage.MANAGER_PULLER_CONTACT, null, null, false, 0), now);
                return Optional.empty();
            }
            if (Objects.equals(row.getStatus(), PullTaskCreatorDeletionStatus.FAILED.code())) {
                pause(current, "CREATOR_DELETE_FAILED", "永久注销已明确失败，只允许人工核查，禁止重复发送", now);
                return Optional.empty();
            }
            ProtocolAccountRef manager = manager(current, row);
            if (manager == null) {
                pause(current, "CREATOR_DELETE_MANAGER_UNAVAILABLE", "接管管理号不在群、不可用或与建群者相同，禁止注销", now);
                return Optional.empty();
            }
            if (Objects.equals(row.getStatus(), PullTaskCreatorDeletionStatus.RESERVED.code())
                    && (!creatorReady(current, row) || !profileReady(current))) {
                pause(current, "CREATOR_DELETE_PRECONDITION_FAILED", "建群者不在线、身份变化或依赖建群者的群设置未完成", now);
                return Optional.empty();
            }
            return Optional.of(new PullTaskCreatorDeletionWork(current, row, creator(row), manager));
        });
    }

    /** 在协议发送前提交唯一意图；并发/重启看到 SUBMITTED 后只能查询。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean claimSubmission(PullTaskCreatorDeletionWork work,
            CreatorDeletionObservation observation, long now) {
        return tenant(work.execution().getTenantId(), () -> {
            PullTaskGroupExecution current = current(work.execution(), now);
            if (current == null || !PullTaskCreatorDeletionProof.takeover(work, observation, now)) {
                return false;
            }
            PullTaskCreatorDeletion row = deletions.selectByExecutionIdForUpdate(current.getId());
            if (row == null || !Objects.equals(row.getStatus(), PullTaskCreatorDeletionStatus.RESERVED.code())
                    || !creatorReady(current, row) || !profileReady(current)
                    || !Objects.equals(manager(current, row), work.manager())) {
                return false;
            }
            if (!resources.lifecycle().beginDeletion(binding(row), now)) {
                pause(current, "CREATOR_DELETE_ACCOUNT_IN_USE", "建群账号存在其他活动任务、角色或命令依赖，禁止注销", now);
                return false;
            }
            row.setManagerAccountId(work.manager().armadaAccountId());
            row.setGroupJid(current.getGroupJid());
            row.setCreationBefore(observation.creation());
            row.setEvidenceJson(json(observation));
            row.setObservedAt(observation.queriedAt());
            row.setSubmittedAt(now);
            row.setDeadlineAt(now + VERIFICATION_WINDOW_MS);
            row.setUpdatedAt(now);
            if (deletions.claimSubmission(row) != 1) {
                throw new IllegalStateException("永久注销唯一发送意图竞争失败");
            }
            row.setStatus(PullTaskCreatorDeletionStatus.SUBMITTED.code());
            work.deletion().setStatus(row.getStatus());
            work.deletion().setCreationBefore(row.getCreationBefore());
            work.deletion().setSubmittedAt(now);
            work.deletion().setDeadlineAt(row.getDeadlineAt());
            return true;
        });
    }

    /**
     * 将协议结果和独立证明保存；只有完整证明才原子标记已注销并推进联系人阶段。
     * now 保留本轮查询开始时的证明新鲜度基准，recordedAt 用于接受计时及查询完成后的排期。
     */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskExecutionDispatchResult record(PullTaskCreatorDeletionWork work,
            CreatorDeletionResult result, CreatorDeletionObservation observation, long now, long recordedAt) {
        return tenant(work.execution().getTenantId(), () -> {
            PullTaskGroupExecution current = current(work.execution(), now);
            if (current == null) {
                return PullTaskExecutionDispatchResult.LOST;
            }
            PullTaskCreatorDeletion row = deletions.selectByExecutionIdForUpdate(current.getId());
            if (row == null || Objects.equals(row.getStatus(), PullTaskCreatorDeletionStatus.RESERVED.code())) {
                return PullTaskExecutionDispatchResult.LOST;
            }
            boolean match = PullTaskCreatorDeletionProof.matches(row, result);
            boolean accepted = match && result.accepted();
            long firstAcceptedAt = firstAcceptedAt(row, accepted, recordedAt);
            if (match) {
                row.setDeletionResultStatus(result.state());
                row.setResultOperationId(result.operationId());
                row.setResultIdentityHash(result.accountHash());
            }
            boolean failed = match && result.state() != null
                    && java.util.Set.of("FAILED", "REJECTED", "NOT_SENT").contains(result.state());
            boolean managerChanged = accepted && !Objects.equals(manager(current, row), work.manager());
            boolean complete = accepted && !managerChanged
                    && PullTaskCreatorDeletionProof.cleaned(work, observation, now);
            row.setStatus(complete ? PullTaskCreatorDeletionStatus.COMPLETE.code()
                    : failed ? PullTaskCreatorDeletionStatus.FAILED.code()
                    : accepted ? PullTaskCreatorDeletionStatus.ACCEPTED.code()
                    : PullTaskCreatorDeletionStatus.UNKNOWN.code());
            row.setAttempts(row.getAttempts() + 1);
            if (result != null || observation != null) {
                row.setEvidenceJson(json(new Evidence(result, observation, firstAcceptedAt)));
            }
            if (observation != null) { row.setObservedAt(observation.queriedAt()); }
            row.setCompletedAt(complete ? now : null);
            boolean expired = row.getDeadlineAt() == null || now >= row.getDeadlineAt();
            row.setReasonCode(complete ? "CREATOR_DELETE_COMPLETE" : failed ? "CREATOR_DELETE_FAILED"
                    : managerChanged ? "CREATOR_DELETE_MANAGER_CHANGED"
                    : expired ? "CREATOR_DELETE_VERIFICATION_TIMEOUT"
                    : accepted ? "CREATOR_DELETE_WAIT_CLEANUP" : "CREATOR_DELETE_RESULT_UNKNOWN");
            row.setReasonMessage(complete ? "建群账号注销及创建者清理已确认"
                    : failed ? "永久注销失败：" + failureReason(result.reason()) + "；禁止重复发送"
                    : managerChanged ? "接管管理号在实时查询期间已变化或不可用，已暂停；恢复后仅查询原注销操作"
                    : expired ? "注销或创建者清理核验超时，已暂停；恢复后仅查询原操作"
                    : accepted ? "注销已接受，等待核验：" + PullTaskCreatorDeletionProof.pendingReason(work, observation, now)
                    : "注销结果未知，已暂停；恢复后仅查询原操作，禁止重新发送删除");
            row.setUpdatedAt(now);
            if (deletions.updateObservation(row) != 1) {
                return PullTaskExecutionDispatchResult.LOST;
            }
            if (complete) {
                resources.lifecycle().completeDeletion(binding(row), now);
            }
            return transition(current, new Transition(complete ? PullTaskExecutionStage.MANAGER_PULLER_CONTACT
                    : PullTaskExecutionStage.CREATOR_DELETE_VERIFY, row.getReasonCode(),
                    row.getReasonMessage(), !complete && (failed || expired || !accepted || managerChanged),
                    complete ? 0 : recordedAt + backoff(row.getAttempts(), accepted, firstAcceptedAt, recordedAt)), now);
        });
    }

    /** 实时核验未通过时暂停，保留原目标与操作，绝不删除或拉人。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskExecutionDispatchResult reject(PullTaskGroupExecution candidate, String reason, long now) {
        return tenant(candidate.getTenantId(), () -> {
            PullTaskGroupExecution current = current(candidate, now);
            return current == null ? PullTaskExecutionDispatchResult.LOST
                    : pause(current, "CREATOR_DELETE_TAKEOVER_UNCONFIRMED", reason, now);
        });
    }

    private boolean creatorReady(PullTaskGroupExecution execution, PullTaskCreatorDeletion row) {
        return resources.lifecycle().findReservedCreator(execution.getId())
                .filter(ref -> Objects.equals(ref.armadaAccountId(), row.getCreatorAccountId()))
                .filter(ref -> Objects.equals(resources.lifecycle().identityHash(ref), row.getCreatorIdentityHash()))
                .isPresent();
    }

    private boolean profileReady(PullTaskGroupExecution execution) {
        if (execution.getProfileVerifiedAt() == null || execution.getProfileVerifiedAt() <= 0
                || !hasText(execution.getProfileVerifiedCommandId()) || !hasText(execution.getNormalizedLink())) {
            return false;
        }
        List<PullTaskAccountAction> actions = resources.actions().selectByExecutionAndType(
                execution.getId(), PullTaskAccountActionType.APPLY_GROUP_SETTINGS.code());
        return actions != null && actions.stream().anyMatch(action -> Objects.equals(
                action.getCommandId(), execution.getProfileVerifiedCommandId())
                && Objects.equals(action.getActionStatus(), PullTaskActionStatus.SUCCESS.code()));
    }

    private ProtocolAccountRef manager(PullTaskGroupExecution execution, PullTaskCreatorDeletion row) {
        List<PullTaskGroupAccount> managers = resources.roles().selectByExecutionAndRole(
                execution.getId(), PullTaskGroupAccountRole.MANAGER.code());
        List<Long> ids = managers.stream().filter(role -> role.getReleasedAt() == null)
                .filter(role -> Objects.equals(role.getAvailabilityStatus(), PullTaskGroupAccountAvailability.AVAILABLE.code()))
                .filter(role -> Objects.equals(role.getMembershipStatus(), PullTaskGroupAccountMembershipStatus.IN_GROUP.code()))
                .map(PullTaskGroupAccount::getAccountId).distinct().toList();
        if (ids.size() != 1 || Objects.equals(ids.get(0), row.getCreatorAccountId())
                || row.getManagerAccountId() != null && !Objects.equals(ids.get(0), row.getManagerAccountId())) {
            return null;
        }
        return resources.accounts().findEligibleManagerProtocolRefs(ids).stream().findFirst().orElse(null);
    }

    private PullTaskGroupExecution current(PullTaskGroupExecution candidate, long now) {
        PullTask parent = resources.tasks().selectLifecycleForUpdate(candidate.getTaskId());
        PullTaskGroupExecution current = resources.executions().selectByIdForUpdate(candidate.getId());
        if (parent == null || !PullTaskCreationMode.fromNullable(parent.getCreationMode()).isNewGroup()
                || !PullTaskStandardStatus.EXECUTING.name().equals(parent.getStatus()) || current == null
                || !Objects.equals(current.getManualPaused(), 0)
                || !Objects.equals(current.getExecutionStatus(), PullTaskExecutionStatus.EXECUTING.code())
                || !Objects.equals(current.getVersion(), candidate.getVersion())
                || !Objects.equals(current.getLockOwner(), candidate.getLockOwner())
                || current.getLockExpiresAt() == null || current.getLockExpiresAt() <= now) {
            return null;
        }
        return current;
    }

    private static boolean validBinding(PullTaskGroupExecution execution, PullTaskCreatorDeletion row) {
        return row != null && Objects.equals(row.getTaskId(), execution.getTaskId())
                && Objects.equals(row.getTenantId(), execution.getTenantId())
                && Objects.equals(row.getCreateOperationId(), execution.getCreateOperationId())
                && hasText(row.getCreatorIdentityHash()) && hasText(row.getOperationId())
                && hasText(execution.getGroupJid());
    }

    private PullTaskExecutionDispatchResult pause(PullTaskGroupExecution current, String code, String reason, long now) {
        return transition(current, new Transition(current.getStage() == PullTaskExecutionStage.CREATOR_DELETE_VERIFY.code()
                ? PullTaskExecutionStage.CREATOR_DELETE_VERIFY : PullTaskExecutionStage.CREATOR_DELETE,
                code, reason, true, 0), now);
    }

    private record Transition(PullTaskExecutionStage stage, String code, String reason,
            boolean paused, long nextRunAt) { }

    private PullTaskExecutionDispatchResult transition(PullTaskGroupExecution current,
            Transition target, long now) {
        PullTaskGroupExecution update = new PullTaskGroupExecution();
        update.setId(current.getId());
        update.setVersion(current.getVersion());
        update.setLockOwner(current.getLockOwner());
        update.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        update.setStage(target.stage().code());
        update.setManualPaused(target.paused() ? 1 : 0);
        update.setReasonCode(target.code());
        update.setReasonMessage(target.reason());
        update.setNextRunAt(target.nextRunAt());
        update.setUpdatedAt(now);
        return resources.executions().transitionCreatorDeletion(update, current.getStage()) == 1
                ? target.stage() == PullTaskExecutionStage.MANAGER_PULLER_CONTACT
                    ? PullTaskExecutionDispatchResult.ADVANCED : PullTaskExecutionDispatchResult.DEFERRED
                : PullTaskExecutionDispatchResult.LOST;
    }

    private String json(Object value) {
        try {
            return resources.json().writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("注销核验证据序列化失败", exception);
        }
    }

    private static String failureReason(String rawReason) {
        String code = rawReason == null ? "" : rawReason.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (code) {
            case "ACCOUNT_NOT_ONLINE" -> "建群账号不在线（ACCOUNT_NOT_ONLINE）";
            case "PRIMARY_ONLINE_ACCOUNT_REQUIRED" -> "需要在线的 Android 主设备（PRIMARY_ONLINE_ACCOUNT_REQUIRED）";
            case "SERVER_REJECTED" -> "WhatsApp 拒绝注销请求（SERVER_REJECTED）";
            case "REQUEST_NOT_SENT" -> "协议确认请求未发送（REQUEST_NOT_SENT）";
            case "TIMEOUT" -> "协议等待超时（TIMEOUT）";
            case "ENCODING_FAILED" -> "注销 IQ 编码失败（ENCODING_FAILED）";
            case "TRANSPORT_FAILURE" -> "协议连接传输失败（TRANSPORT_FAILURE）";
            case "PROCESSOR_CLOSED" -> "协议处理器已关闭（PROCESSOR_CLOSED）";
            case "CONTEXT_CANCELLED" -> "协议请求已取消（CONTEXT_CANCELLED）";
            default -> "协议失败原因未识别，原始内容已隐藏（DETAIL_REDACTED）";
        };
    }

    /** 查询失败不抹去上次新鲜证据；首次接受时间持久化后不随轮询或重启重置。 */
    private record Evidence(CreatorDeletionResult deletionResult, CreatorDeletionObservation observation,
            long firstAcceptedAt) { }

    private long firstAcceptedAt(PullTaskCreatorDeletion row, boolean accepted, long recordedAt) {
        if (hasText(row.getEvidenceJson())) {
            try {
                long persisted = resources.json().readTree(row.getEvidenceJson()).path("firstAcceptedAt").asLong();
                if (persisted > 0) { return persisted; }
            } catch (JsonProcessingException exception) {
                log.warn("注销核验计时证据无法解析 executionId={}", row.getGroupExecutionId());
            }
        }
        // 旧记录没有首次接受时间，沿用提交时间，避免上线后为已等待多时的任务重开快查窗口。
        if ("ACCEPTED".equals(row.getDeletionResultStatus()) && row.getSubmittedAt() != null) {
            return row.getSubmittedAt();
        }
        return accepted ? recordedAt : 0;
    }

    private static long backoff(int attempt, boolean accepted, long firstAcceptedAt, long recordedAt) {
        if (accepted && firstAcceptedAt > 0 && recordedAt >= firstAcceptedAt
                && recordedAt - firstAcceptedAt < ACCEPTED_FAST_POLL_WINDOW_MS) {
            return ACCEPTED_FAST_POLL_INTERVAL_MS;
        }
        return Math.min(MAX_BACKOFF_MS, INITIAL_BACKOFF_MS * (1L << Math.min(attempt - 1, 3)));
    }

    private static ProtocolAccountRef creator(PullTaskCreatorDeletion row) {
        return new ProtocolAccountRef(row.getCreatorAccountId(), ProtocolBackend.ANDROID,
                row.getCreatorProtocolAccountId(), canonicalPhone(row.getCreatorPhone()));
    }

    /** 与账号生命周期完全相同的显示格式归一化，不补国家码、不改变号码身份。 */
    private static String canonicalPhone(String phone) {
        return phone == null ? "" : phone.trim().replace("+", "").replace(" ", "");
    }

    private static CreatorDeletionBinding binding(PullTaskCreatorDeletion row) {
        return new CreatorDeletionBinding(row.getTenantId(), row.getTaskId(), row.getGroupExecutionId(),
                row.getCreatorAccountId(), row.getCreatorIdentityHash(), row.getCreateOperationId(), row.getOperationId());
    }

    private static boolean hasText(String text) { return text != null && !text.isBlank(); }
    private static <T> T tenant(Long tenantId, Supplier<T> action) {
        Long previous = TenantContext.get();
        try { TenantContext.set(tenantId); return action.get(); }
        finally { if (previous == null) { TenantContext.clear(); } else { TenantContext.set(previous); } }
    }
}
