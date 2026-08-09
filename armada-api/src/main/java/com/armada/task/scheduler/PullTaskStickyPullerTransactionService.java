package com.armada.task.scheduler;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.mapper.PullTaskPullCallMemberAttemptMapper;
import com.armada.task.model.dto.PullTaskPlannedCallPullerBinding;
import com.armada.task.model.dto.PullTaskStickyPullerInvalidation;
import com.armada.task.model.dto.PullTaskStickyPullerTransition;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.entity.PullTaskPullCallMemberAttempt;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.model.enums.PullTaskWaitResourceType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 为计划调用复用或切换执行行的粘性拉手，并维护分配代际。 */
@Service
public class PullTaskStickyPullerTransactionService {

    private static final Logger log = LoggerFactory.getLogger(
            PullTaskStickyPullerTransactionService.class);
    private static final Set<String> INVALIDATING_REASON_CODES = Set.of(
            "ACCOUNT_NOT_FOUND",
            "ACCOUNT_NOT_ONLINE",
            "NEED_REAUTH",
            "ACCOUNT_REACHOUT_RESTRICTED",
            "RATE_LIMITED",
            "GROUP_PERMISSION_DENIED");

    private final PullTaskGroupExecutionMapper executionMapper;
    private final PullTaskGroupAccountMapper groupAccountMapper;
    private final PullTaskPullCallMapper callMapper;
    private final PullTaskPullCallMemberAttemptMapper attemptMapper;
    private final AccountProtocolLookupService accountLookup;

    /**
     * @param executionMapper 执行行 Mapper
     * @param groupAccountMapper 角色账号 Mapper
     * @param callMapper 拉人调用 Mapper
     * @param attemptMapper 逐号码台账 Mapper
     * @param accountLookup 账号协议身份查询
     */
    public PullTaskStickyPullerTransactionService(
            PullTaskGroupExecutionMapper executionMapper,
            PullTaskGroupAccountMapper groupAccountMapper,
            PullTaskPullCallMapper callMapper,
            PullTaskPullCallMemberAttemptMapper attemptMapper,
            AccountProtocolLookupService accountLookup) {
        this.executionMapper = executionMapper;
        this.groupAccountMapper = groupAccountMapper;
        this.callMapper = callMapper;
        this.attemptMapper = attemptMapper;
        this.accountLookup = accountLookup;
    }

    /**
     * 复用当前拉手，或从稳定游标选择替代拉手并绑定计划调用和全部 attempt。
     *
     * @param execution 已抢占执行行
     * @param call 待绑定计划调用
     * @param lockOwner 当前调度实例
     * @param now 当前时间(epoch 毫秒)
     * @return 已绑定拉手，或等待/丢失结果
     */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskStickyPullerSelection bindForDispatch(
            PullTaskGroupExecution execution,
            PullTaskPullCall call,
            String lockOwner,
            long now) {
        if (!hasIdentity(execution, call)) {
            return completed(PullTaskExecutionDispatchResult.LOST);
        }
        Long previousTenant = TenantContext.get();
        TenantContext.set(execution.getTenantId());
        try {
            PullTaskGroupExecution currentExecution = executionMapper.selectById(execution.getId());
            PullTaskPullCall currentCall = currentCall(execution.getId(), call.getId());
            if (!isDispatchable(currentExecution, currentCall, lockOwner, now)) {
                release(execution.getId(), lockOwner, now);
                return completed(PullTaskExecutionDispatchResult.LOST);
            }
            List<PullTaskGroupAccount> roles = groupAccountMapper.selectByExecutionAndRole(
                    execution.getId(), PullTaskGroupAccountRole.PULLER.code());
            Map<Long, ProtocolAccountRef> protocols = protocolRefs(roles);
            PullTaskGroupAccount sticky = roleById(
                    roles, currentExecution.getActivePullerGroupAccountId());
            PullerChoice choice = choose(
                    currentExecution, sticky, roles, protocols, now);
            if (choice == null) {
                clearUnavailable(currentExecution, sticky, "ACCOUNT_NOT_ONLINE", now);
                return waitForPuller(execution.getId(), lockOwner, now);
            }
            bindCall(currentCall, choice, now);
            return PullTaskStickyPullerSelection.ready(
                    choice.role(), choice.protocol(), choice.assignmentSeq());
        } finally {
            restoreTenant(previousTenant);
        }
    }

    /**
     * 仅对确认的账号级原因清空仍匹配调用身份和代际的当前拉手。
     *
     * @param execution 调用所属执行行
     * @param call 产生账号级事实的调用
     * @param reasonCode 归一化原因码
     * @param now 当前时间(epoch 毫秒)
     * @return 是否成功清空当前代际
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean invalidateIfCurrent(
            PullTaskGroupExecution execution,
            PullTaskPullCall call,
            String reasonCode,
            long now) {
        if (!INVALIDATING_REASON_CODES.contains(reasonCode)
                || execution == null || execution.getTenantId() == null
                || call == null || call.getPullerGroupAccountId() == null
                || call.getPullerAssignmentSeq() == null) {
            return false;
        }
        Long previousTenant = TenantContext.get();
        TenantContext.set(execution.getTenantId());
        try {
            PullTaskStickyPullerInvalidation invalidation =
                    new PullTaskStickyPullerInvalidation(
                            execution.getId(),
                            call.getPullerGroupAccountId(),
                            call.getPullerAssignmentSeq(),
                            reasonCode,
                            now);
            boolean invalidated = executionMapper.clearStickyPuller(invalidation) == 1;
            if (invalidated) {
                logStickyInvalidated(execution, call.getPullerGroupAccountId(),
                        call.getPullerAssignmentSeq(), reasonCode);
            }
            return invalidated;
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private PullerChoice choose(
            PullTaskGroupExecution execution,
            PullTaskGroupAccount sticky,
            List<PullTaskGroupAccount> roles,
            Map<Long, ProtocolAccountRef> protocols,
            long now) {
        if (eligible(sticky, protocols)) {
            return new PullerChoice(
                    sticky,
                    protocols.get(sticky.getAccountId()),
                    execution.getPullerAssignmentSeq());
        }
        markOfflineIfMissing(sticky, protocols, now);
        PullTaskGroupAccount replacement = nextRole(
                roles, protocols, execution.getNextPullerIndex());
        if (replacement == null) {
            return null;
        }
        long currentGeneration = generation(execution);
        int nextCursor = nextCursor(roles, replacement.getId());
        PullTaskStickyPullerTransition transition = new PullTaskStickyPullerTransition(
                new PullTaskStickyPullerTransition.Scope(
                        execution.getId(), execution.getActivePullerGroupAccountId(),
                        currentGeneration),
                new PullTaskStickyPullerTransition.Target(
                        replacement.getId(), currentGeneration + 1, nextCursor),
                now);
        if (executionMapper.transitionStickyPuller(transition) != 1) {
            throw new IllegalStateException("粘性拉手分配发生并发变化");
        }
        log.info("event=pull_sticky_puller_assigned tenantId={} taskId={} executionId={} "
                        + "pullerGroupAccountId={} pullerAccountId={} assignmentSeq={}",
                execution.getTenantId(), execution.getTaskId(), execution.getId(),
                replacement.getId(), replacement.getAccountId(), currentGeneration + 1);
        return new PullerChoice(
                replacement,
                protocols.get(replacement.getAccountId()),
                currentGeneration + 1);
    }

    private void bindCall(PullTaskPullCall call, PullerChoice choice, long now) {
        List<PullTaskPullCallMemberAttempt> attempts = attemptMapper.selectByCallAndStatus(
                call.getId(), com.armada.task.model.enums.PullTaskParticipantAttemptStatus.PLANNED.code());
        PullTaskPlannedCallPullerBinding binding = new PullTaskPlannedCallPullerBinding(
                new PullTaskPlannedCallPullerBinding.Scope(
                        call.getId(), call.getPullerGroupAccountId(),
                        PullTaskPullCallStatus.PLANNED.code()),
                new PullTaskPlannedCallPullerBinding.Target(
                        choice.role().getId(), choice.role().getAccountId(),
                        choice.assignmentSeq()),
                now);
        if (callMapper.bindPlannedPuller(binding) != 1) {
            throw new IllegalStateException("计划调用拉手绑定发生并发变化");
        }
        if (attemptMapper.bindPlannedPullerByCall(binding) != attempts.size()) {
            throw new IllegalStateException("计划 attempt 拉手绑定数量不一致");
        }
    }

    private PullTaskStickyPullerSelection waitForPuller(
            long executionId, String lockOwner, long now) {
        PullTaskGroupExecution current = executionMapper.selectById(executionId);
        PullTaskGroupExecution update = new PullTaskGroupExecution();
        update.setId(current.getId());
        update.setVersion(current.getVersion());
        update.setLockOwner(lockOwner);
        update.setGroupJid(current.getGroupJid());
        update.setExecutionStatus(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        update.setStage(PullTaskExecutionStage.PULL_EXECUTION.code());
        update.setWaitResourceType(PullTaskWaitResourceType.PULLER.code());
        update.setReasonCode("PULLER_UNAVAILABLE");
        update.setReasonMessage("当前没有可用拉手");
        update.setNextRunAt(0L);
        update.setUpdatedAt(now);
        if (executionMapper.transitionClaimed(
                update, PullTaskExecutionStage.PULL_EXECUTION.code()) != 1) {
            return completed(PullTaskExecutionDispatchResult.LOST);
        }
        return completed(PullTaskExecutionDispatchResult.DEFERRED);
    }

    private void clearUnavailable(
            PullTaskGroupExecution execution,
            PullTaskGroupAccount sticky,
            String reasonCode,
            long now) {
        if (sticky == null || execution.getActivePullerGroupAccountId() == null) {
            return;
        }
        PullTaskStickyPullerInvalidation invalidation = new PullTaskStickyPullerInvalidation(
                execution.getId(), sticky.getId(), generation(execution), reasonCode, now);
        if (executionMapper.clearStickyPuller(invalidation) != 1) {
            throw new IllegalStateException("清空不可用粘性拉手发生并发变化");
        }
        logStickyInvalidated(
                execution, sticky.getId(), generation(execution), reasonCode);
    }

    private static void logStickyInvalidated(
            PullTaskGroupExecution execution,
            long pullerGroupAccountId,
            long assignmentSeq,
            String reasonCode) {
        log.info("event=pull_sticky_puller_invalidated tenantId={} taskId={} executionId={} "
                        + "pullerGroupAccountId={} assignmentSeq={} reasonCode={}",
                execution.getTenantId(), execution.getTaskId(), execution.getId(),
                pullerGroupAccountId, assignmentSeq, reasonCode);
    }

    private Map<Long, ProtocolAccountRef> protocolRefs(List<PullTaskGroupAccount> roles) {
        List<Long> accountIds = roles.stream()
                .map(PullTaskGroupAccount::getAccountId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<ProtocolAccountRef> resolved = accountLookup.findActiveProtocolRefs(accountIds);
        Map<Long, ProtocolAccountRef> protocols = new HashMap<>();
        if (resolved != null) {
            resolved.stream().filter(Objects::nonNull)
                    .forEach(ref -> protocols.putIfAbsent(ref.armadaAccountId(), ref));
        }
        return protocols;
    }

    private void markOfflineIfMissing(
            PullTaskGroupAccount sticky,
            Map<Long, ProtocolAccountRef> protocols,
            long now) {
        if (sticky != null
                && Objects.equals(sticky.getAvailabilityStatus(),
                        PullTaskGroupAccountAvailability.AVAILABLE.code())
                && protocols.get(sticky.getAccountId()) == null) {
            groupAccountMapper.markUnavailable(
                    sticky.getId(),
                    PullTaskGroupAccountAvailability.OFFLINE.code(),
                    "ACCOUNT_NOT_ONLINE",
                    null,
                    now);
        }
    }

    private static PullTaskGroupAccount nextRole(
            List<PullTaskGroupAccount> roles,
            Map<Long, ProtocolAccountRef> protocols,
            Integer storedCursor) {
        if (roles.isEmpty()) {
            return null;
        }
        int start = startIndex(roles, storedCursor);
        for (int offset = 0; offset < roles.size(); offset++) {
            PullTaskGroupAccount role = roles.get((start + offset) % roles.size());
            if (eligible(role, protocols)) {
                return role;
            }
        }
        return null;
    }

    private static int startIndex(List<PullTaskGroupAccount> roles, Integer storedCursor) {
        int cursor = storedCursor == null ? 0 : storedCursor;
        for (int index = 0; index < roles.size(); index++) {
            Integer roleSeq = roles.get(index).getRoleSeq();
            if (roleSeq != null && roleSeq >= cursor) {
                return index;
            }
        }
        return 0;
    }

    private static int nextCursor(List<PullTaskGroupAccount> roles, long selectedRoleId) {
        for (int index = 0; index < roles.size(); index++) {
            if (!Objects.equals(roles.get(index).getId(), selectedRoleId)) {
                continue;
            }
            if (index + 1 >= roles.size()) {
                return 0;
            }
            Integer roleSeq = roles.get(index + 1).getRoleSeq();
            return roleSeq == null ? 0 : roleSeq;
        }
        return 0;
    }

    private static PullTaskGroupAccount roleById(
            List<PullTaskGroupAccount> roles, Long roleId) {
        if (roleId == null) {
            return null;
        }
        return roles.stream().filter(row -> Objects.equals(row.getId(), roleId))
                .findFirst().orElse(null);
    }

    private static boolean eligible(
            PullTaskGroupAccount role, Map<Long, ProtocolAccountRef> protocols) {
        return role != null
                && Objects.equals(role.getAvailabilityStatus(),
                        PullTaskGroupAccountAvailability.AVAILABLE.code())
                && Objects.equals(role.getMembershipStatus(),
                        PullTaskGroupAccountMembershipStatus.IN_GROUP.code())
                && role.getReleasedAt() == null
                && protocols.get(role.getAccountId()) != null;
    }

    private static boolean hasIdentity(
            PullTaskGroupExecution execution, PullTaskPullCall call) {
        return execution != null && execution.getTenantId() != null
                && execution.getId() != null && call != null && call.getId() != null;
    }

    private static boolean isDispatchable(
            PullTaskGroupExecution execution,
            PullTaskPullCall call,
            String lockOwner,
            long now) {
        return execution != null && call != null
                && Objects.equals(execution.getExecutionStatus(),
                        PullTaskExecutionStatus.EXECUTING.code())
                && Objects.equals(execution.getStage(), PullTaskExecutionStage.PULL_EXECUTION.code())
                && Objects.equals(execution.getManualPaused(), 0)
                && Objects.equals(execution.getLockOwner(), lockOwner)
                && execution.getLockExpiresAt() != null
                && execution.getLockExpiresAt() > now
                && Objects.equals(call.getGroupExecutionId(), execution.getId())
                && Objects.equals(call.getCallStatus(), PullTaskPullCallStatus.PLANNED.code());
    }

    private PullTaskPullCall currentCall(long executionId, long callId) {
        return callMapper.selectByExecution(executionId).stream()
                .filter(row -> Objects.equals(row.getId(), callId))
                .findFirst().orElse(null);
    }

    private void release(long executionId, String lockOwner, long now) {
        if (lockOwner != null) {
            executionMapper.releaseLock(executionId, lockOwner, now);
        }
    }

    private static long generation(PullTaskGroupExecution execution) {
        return execution.getPullerAssignmentSeq() == null
                ? 0L : execution.getPullerAssignmentSeq();
    }

    private static PullTaskStickyPullerSelection completed(
            PullTaskExecutionDispatchResult result) {
        return PullTaskStickyPullerSelection.completed(result);
    }

    private static void restoreTenant(Long previousTenant) {
        if (previousTenant == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenant);
        }
    }

    private record PullerChoice(
            PullTaskGroupAccount role,
            ProtocolAccountRef protocol,
            long assignmentSeq) {
    }
}
