package com.armada.group.service.impl;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.group.mapper.GroupMetadataSyncTaskMapper;
import com.armada.group.mapper.GroupBatchTaskItemMapper;
import com.armada.group.mapper.GroupBatchTaskMapper;
import com.armada.group.model.entity.GroupBatchTask;
import com.armada.group.model.entity.GroupBatchTaskItem;
import com.armada.group.model.entity.GroupMetadataSyncTask;
import com.armada.group.model.dto.AccountGroupMembershipChangedEvent;
import com.armada.group.model.enums.GroupBatchTaskItemStatus;
import com.armada.group.model.enums.GroupBatchTaskStatus;
import com.armada.group.model.enums.GroupBatchTaskType;
import com.armada.group.model.enums.GroupMetadataSyncStatus;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.observability.GroupSnapshotMetrics;
import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.group.service.AccountGroupMembershipStatusService;
import com.armada.group.service.GroupMetadataSyncLimits;
import com.armada.group.service.GroupSnapshotDispatchService;
import com.armada.group.service.GroupBatchSnapshotDispatchService;
import com.armada.group.service.GroupSnapshotProperties;
import com.armada.platform.kafka.consumer.group.ProtocolGroupSnapshotResultReportedEvent;
import com.armada.platform.kafka.consumer.group.ProtocolGroupSnapshotResultReportedSink;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.tenant.TenantContext;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 以 currentCommandId CAS 收口群快照任务，并在可恢复失败时同事务换候选写下一条 Outbox。 */
@Service
public class GroupSnapshotResultReportedSinkAdapter
        implements ProtocolGroupSnapshotResultReportedSink {

    private static final Logger log =
            LoggerFactory.getLogger(GroupSnapshotResultReportedSinkAdapter.class);
    private static final String GROUP_INVITE_LINK_UNAVAILABLE =
            "GROUP_INVITE_LINK_UNAVAILABLE";
    private static final String INVITE_LINK_UNAVAILABLE_DESCRIPTION =
            "当前群没有可用邀请链接";
    private static final Set<String> NON_RETRYABLE_ERRORS =
            Set.of("GROUP_UNAVAILABLE", "INVALID_PAYLOAD", "PAYLOAD_TOO_LARGE");

    private final GroupMetadataSyncTaskMapper taskMapper;
    private final AccountMapper accountMapper;
    private final AccountGroupMembershipStatusService membershipStatusService;
    private final GroupBatchTaskItemMapper batchItemMapper;
    private final GroupBatchTaskMapper batchTaskMapper;
    private final GroupExecutionAccountSelector selector;
    private final GroupSnapshotDispatchService dispatchService;
    private final GroupBatchSnapshotDispatchService batchDispatchService;
    private final GroupSnapshotProperties properties;
    private final GroupSnapshotMetrics metrics;

    public GroupSnapshotResultReportedSinkAdapter(
            GroupMetadataSyncTaskMapper taskMapper,
            AccountMapper accountMapper,
            AccountGroupMembershipStatusService membershipStatusService,
            GroupBatchTaskItemMapper batchItemMapper,
            GroupBatchTaskMapper batchTaskMapper,
            GroupExecutionAccountSelector selector,
            GroupSnapshotDispatchService dispatchService,
            GroupBatchSnapshotDispatchService batchDispatchService,
            GroupSnapshotProperties properties,
            GroupSnapshotMetrics metrics) {
        this.taskMapper = taskMapper;
        this.accountMapper = accountMapper;
        this.membershipStatusService = membershipStatusService;
        this.batchItemMapper = batchItemMapper;
        this.batchTaskMapper = batchTaskMapper;
        this.selector = selector;
        this.dispatchService = dispatchService;
        this.batchDispatchService = batchDispatchService;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleSnapshotResult(ProtocolGroupSnapshotResultReportedEvent event) {
        Long previous = TenantContext.get();
        try {
            TenantContext.set(event.tenantId());
            settle(event);
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previous);
            }
        }
    }

    private void settle(ProtocolGroupSnapshotResultReportedEvent event) {
        boolean invalidPayloadSettlement = isInvalidPayloadSettlement(event.scopes());
        if (!invalidPayloadSettlement) {
            validateAccountBinding(event);
        }
        if ("GROUP_BATCH_TASK_ITEM".equals(event.taskType())) {
            settleBatch(event, invalidPayloadSettlement);
            return;
        }
        if (!"GROUP_METADATA_SYNC".equals(event.taskType())) {
            throw new IllegalArgumentException("群快照结算 taskType 不受支持");
        }
        GroupMetadataSyncTask task = invalidPayloadSettlement
                ? taskMapper.selectByCurrentCommandIdUnscoped(event.commandId())
                : taskMapper.selectByCurrentCommandId(event.tenantId(), event.commandId());
        if (task == null) {
            metrics.recordStaleResult("COMMAND_NOT_CURRENT");
            log.info("群快照旧或重复结算已忽略 commandId={} taskId={}", event.commandId(), event.taskId());
            return;
        }
        if (invalidPayloadSettlement) {
            TenantContext.set(task.getTenantId());
            settleTerminal(task, GroupMetadataSyncStatus.FAILED, "INVALID_PAYLOAD",
                    "群快照命令字段非法", System.currentTimeMillis());
            return;
        }
        validateCorrelation(task, event);
        int eventMask = scopeMask(event.scopes());
        if (eventMask != valueOrZero(task.getRequestedScopeMask())) {
            throw new IllegalArgumentException("群快照结算 scopes 与请求不一致");
        }
        int successMask = successMask(event.scopes());
        if ((valueOrZero(task.getCompletedScopeMask()) & successMask) != successMask) {
            throw new IllegalStateException("群快照成功 scope 的事实尚未完成落库");
        }
        metrics.recordResult(event.protocolBackend(), event.taskType());
        metrics.recordEndToEnd(System.currentTimeMillis()
                - (task.getLastStartedAt() == null
                        ? System.currentTimeMillis() : task.getLastStartedAt()));
        if (event.scopes().values().stream().allMatch(result -> "SUCCESS".equals(result.outcome()))) {
            settleTerminal(task, GroupMetadataSyncStatus.SUCCEEDED, null, null, System.currentTimeMillis());
            return;
        }
        String errorCode = firstFailureCode(event.scopes());
        calibrateNotJoined(event);
        if (GROUP_INVITE_LINK_UNAVAILABLE.equals(errorCode)) {
            settleTerminal(task, GroupMetadataSyncStatus.FAILED, errorCode,
                    INVITE_LINK_UNAVAILABLE_DESCRIPTION, System.currentTimeMillis());
            return;
        }
        if (NON_RETRYABLE_ERRORS.contains(errorCode)) {
            settleTerminal(task, GroupMetadataSyncStatus.FAILED, errorCode,
                    "群快照协议返回不可重试失败", System.currentTimeMillis());
            return;
        }
        rotateCandidate(task, errorCode, System.currentTimeMillis());
    }

    private void validateAccountBinding(ProtocolGroupSnapshotResultReportedEvent event) {
        Account account = accountMapper.selectActiveByProtocolAccountId(event.protocolAccountId());
        ProtocolBackend boundBackend;
        try {
            boundBackend = account == null
                    ? null
                    : ProtocolBackend.fromExplicitProtocolId(account.getProtocolId());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("群快照结算账号协议绑定无效", exception);
        }
        if (account == null
                || !event.accountId().equals(account.getId())
                || !event.tenantId().equals(account.getTenantId())
                || boundBackend == null
                || !event.protocolBackend().equals(boundBackend.name())) {
            throw new IllegalArgumentException("群快照结算账号协议绑定不一致");
        }
    }

    private void settleBatch(
            ProtocolGroupSnapshotResultReportedEvent event,
            boolean invalidPayloadSettlement) {
        GroupBatchTaskItem item = invalidPayloadSettlement
                ? batchItemMapper.selectByCurrentCommandIdUnscoped(event.commandId())
                : batchItemMapper.selectByCurrentCommandId(event.tenantId(), event.commandId());
        if (item == null) {
            metrics.recordStaleResult("COMMAND_NOT_CURRENT");
            log.info("批量群快照旧或重复结算已忽略 commandId={} itemId={}",
                    event.commandId(), event.taskId());
            return;
        }
        if (invalidPayloadSettlement) {
            TenantContext.set(item.getTenantId());
        }
        GroupBatchTask task = batchTaskMapper.selectById(item.getTaskId());
        if (task == null) {
            throw new IllegalArgumentException("批量群快照所属任务不存在");
        }
        if (invalidPayloadSettlement) {
            settleBatchTerminal(item, task, false, "INVALID_PAYLOAD",
                    "群快照命令字段非法", System.currentTimeMillis());
            return;
        }
        GroupBatchTaskType type = GroupBatchTaskType.fromCode(task.getTaskType());
        validateBatchCorrelation(item, event);
        int expectedMask = type == GroupBatchTaskType.REFRESH_LINK
                ? GroupSnapshotDispatchService.SCOPE_INVITE_CODE
                : GroupSnapshotDispatchService.SCOPE_METADATA;
        if (scopeMask(event.scopes()) != expectedMask) {
            throw new IllegalArgumentException("批量群快照结算 scopes 与任务类型不一致");
        }
        int successful = successMask(event.scopes());
        if ((valueOrZero(item.getCompletedScopeMask()) & successful) != successful) {
            throw new IllegalStateException("批量群快照成功 scope 的事实尚未完成落库");
        }
        metrics.recordResult(event.protocolBackend(), event.taskType());
        metrics.recordEndToEnd(System.currentTimeMillis()
                - (item.getUpdatedAt() == null ? System.currentTimeMillis() : item.getUpdatedAt()));
        long now = System.currentTimeMillis();
        if (event.scopes().values().stream().allMatch(result -> "SUCCESS".equals(result.outcome()))) {
            settleBatchTerminal(item, task, true, null,
                    type == GroupBatchTaskType.REFRESH_LINK ? "邀请链接已更新" : "群信息已刷新", now);
            return;
        }
        String errorCode = firstFailureCode(event.scopes());
        calibrateNotJoined(event);
        if (GROUP_INVITE_LINK_UNAVAILABLE.equals(errorCode)) {
            settleBatchTerminal(item, task, false, errorCode,
                    INVITE_LINK_UNAVAILABLE_DESCRIPTION, now);
            return;
        }
        if (NON_RETRYABLE_ERRORS.contains(errorCode)) {
            settleBatchTerminal(item, task, false, errorCode, "群快照协议返回不可重试失败", now);
            return;
        }
        rotateBatchCandidate(item, task, type, errorCode, now);
    }

    private void rotateBatchCandidate(
            GroupBatchTaskItem item,
            GroupBatchTask task,
            GroupBatchTaskType type,
            String errorCode,
            long now) {
        int nextCursor = valueOrZero(item.getCandidateCursor()) + 1;
        int maxCandidates = Math.max(1, properties.maxCandidates());
        Optional<GroupExecutionAccount> next = nextCursor < maxCandidates
                ? selector.find(item.getGroupLinkId(), nextCursor)
                : Optional.empty();
        if (next.isEmpty()) {
            settleBatchTerminal(item, task, false, errorCode, "群快照候选账号已耗尽", now);
            return;
        }
        metrics.recordCandidateSwitch(errorCode);
        GroupBatchTaskItem retry = batchIdentity(item);
        retry.setCurrentCommandId(item.getCurrentCommandId());
        retry.setCandidateCursor(nextCursor);
        retry.setErrorCode(errorCode);
        retry.setDescription("群快照失败，切换执行账号");
        retry.setUpdatedAt(now);
        if (batchItemMapper.resetCurrentCommandForRetry(
                retry, GroupBatchTaskItemStatus.WAITING_RESULT.code(),
                GroupBatchTaskItemStatus.PENDING.code()) != 1) {
            return;
        }
        item.setStatus(GroupBatchTaskItemStatus.PENDING.code());
        item.setCurrentCommandId(null);
        item.setCandidateCursor(nextCursor);
        if (!batchDispatchService.dispatch(item, type, now)) {
            return;
        }
    }

    private void settleBatchTerminal(
            GroupBatchTaskItem item,
            GroupBatchTask task,
            boolean success,
            String errorCode,
            String description,
            long now) {
        GroupBatchTaskItem outcome = batchIdentity(item);
        outcome.setCurrentCommandId(item.getCurrentCommandId());
        outcome.setStatus(success
                ? GroupBatchTaskItemStatus.SUCCESS.code()
                : GroupBatchTaskItemStatus.FAILED.code());
        outcome.setErrorCode(errorCode);
        outcome.setDescription(description);
        outcome.setOperatedAt(now);
        outcome.setUpdatedAt(now);
        int settled = batchItemMapper.settleCurrentCommand(
                outcome, GroupBatchTaskItemStatus.WAITING_RESULT.code());
        if (settled == 1 && task.getStatus() != GroupBatchTaskStatus.CANCELED.code()) {
            batchTaskMapper.applyItemOutcome(
                    item.getTaskId(), success, GroupBatchTaskStatus.COMPLETED.code(),
                    GroupBatchTaskStatus.RUNNING.code(), now);
        }
    }

    private static void validateBatchCorrelation(
            GroupBatchTaskItem item,
            ProtocolGroupSnapshotResultReportedEvent event) {
        if (!event.taskId().equals(item.getId())
                || !event.groupLinkId().equals(item.getGroupLinkId())
                || !event.accountId().equals(item.getAccountId())
                || item.getGroupJid() == null
                || !event.groupJid().equalsIgnoreCase(item.getGroupJid())
                || event.attemptNo() != valueOrZero(item.getAttemptCount())) {
            throw new IllegalArgumentException("批量群快照结算任务关联不一致");
        }
        long dispatchedAt = item.getUpdatedAt() == null ? 0L : item.getUpdatedAt();
        if (event.scopes().values().stream().anyMatch(result -> result.completedAt() < dispatchedAt)) {
            throw new IllegalArgumentException("批量群快照结算 completedAt 早于当前尝试");
        }
    }

    private static GroupBatchTaskItem batchIdentity(GroupBatchTaskItem item) {
        GroupBatchTaskItem row = new GroupBatchTaskItem();
        row.setId(item.getId());
        row.setTenantId(item.getTenantId());
        row.setTaskId(item.getTaskId());
        row.setGroupLinkId(item.getGroupLinkId());
        return row;
    }

    private void rotateCandidate(GroupMetadataSyncTask task, String errorCode, long now) {
        int nextCursor = valueOrZero(task.getCandidateCursor()) + 1;
        int maxCandidates = Math.max(1, properties.maxCandidates());
        Optional<GroupExecutionAccount> next = nextCursor < maxCandidates
                ? selector.find(task.getGroupLinkId(), nextCursor)
                : Optional.empty();
        if (next.isEmpty()) {
            settleTerminal(task, GroupMetadataSyncStatus.FAILED, errorCode,
                    "群快照候选账号已耗尽", now);
            return;
        }
        metrics.recordCandidateSwitch(errorCode);
        GroupMetadataSyncTask reset = identity(task);
        reset.setCurrentCommandId(task.getCurrentCommandId());
        reset.setCandidateCursor(nextCursor);
        reset.setNextRunAt(now);
        reset.setLastErrorCode(errorCode);
        reset.setLastErrorMessage("群快照失败，切换执行账号");
        reset.setUpdatedAt(now);
        if (taskMapper.resetCurrentCommandForRetry(
                reset, GroupMetadataSyncStatus.RUNNING.code(), GroupMetadataSyncStatus.PENDING.code()) != 1) {
            return;
        }
        task.setCandidateCursor(nextCursor);
        task.setCurrentCommandId(null);
        long deadline = now + Math.max(1L, properties.resultTimeoutMs());
        if (!dispatchService.dispatchMetadataTask(
                task, next.get(), now, deadline, new GroupMetadataSyncLimits(3, 1),
                "INVITE_CANDIDATE_ROTATION")) {
            throw new IllegalStateException("群快照候选轮换领取失败");
        }
    }

    private void settleTerminal(
            GroupMetadataSyncTask task,
            GroupMetadataSyncStatus status,
            String errorCode,
            String errorMessage,
            long now) {
        GroupMetadataSyncTask row = identity(task);
        row.setCurrentCommandId(task.getCurrentCommandId());
        boolean rerun = status == GroupMetadataSyncStatus.SUCCEEDED
                && Boolean.TRUE.equals(task.getRerunRequested());
        row.setStatus(rerun
                ? GroupMetadataSyncStatus.PENDING.code() : status.code());
        row.setAttemptCount(status == GroupMetadataSyncStatus.SUCCEEDED ? 0 : task.getAttemptCount());
        row.setNextRunAt(rerun ? now : null);
        row.setCompletedScopeMask(rerun ? 0 : valueOrZero(task.getCompletedScopeMask()));
        row.setCandidateCursor(rerun ? 0 : valueOrZero(task.getCandidateCursor()));
        row.setLastSuccessAt(status == GroupMetadataSyncStatus.SUCCEEDED ? now : null);
        row.setLastErrorCode(errorCode);
        row.setLastErrorMessage(errorMessage);
        row.setUpdatedAt(now);
        taskMapper.settleCurrentCommand(row, GroupMetadataSyncStatus.RUNNING.code());
    }

    private static void validateCorrelation(
            GroupMetadataSyncTask task,
            ProtocolGroupSnapshotResultReportedEvent event) {
        if (!event.taskId().equals(task.getId())
                || !event.groupLinkId().equals(task.getGroupLinkId())
                || !event.accountId().equals(task.getExecutionAccountId())
                || !event.groupJid().equalsIgnoreCase(task.getGroupJid())
                || event.attemptNo() != valueOrZero(task.getCandidateCursor()) + 1) {
            throw new IllegalArgumentException("群快照结算任务关联不一致");
        }
        long startedAt = task.getLastStartedAt() == null ? 0L : task.getLastStartedAt();
        if (event.scopes().values().stream().anyMatch(result -> result.completedAt() < startedAt)) {
            throw new IllegalArgumentException("群快照结算 completedAt 早于当前尝试");
        }
    }

    private static int scopeMask(Map<String, ProtocolGroupSnapshotResultReportedEvent.ScopeResult> scopes) {
        int mask = 0;
        if (scopes.containsKey("METADATA")) {
            mask |= GroupSnapshotDispatchService.SCOPE_METADATA;
        }
        if (scopes.containsKey("INVITE_CODE")) {
            mask |= GroupSnapshotDispatchService.SCOPE_INVITE_CODE;
        }
        return mask;
    }

    private static boolean isInvalidPayloadSettlement(
            Map<String, ProtocolGroupSnapshotResultReportedEvent.ScopeResult> scopes) {
        return scopes != null && !scopes.isEmpty() && scopes.values().stream().allMatch(result ->
                "FAILED".equals(result.outcome()) && "INVALID_PAYLOAD".equals(result.errorCode()));
    }

    private static int successMask(Map<String, ProtocolGroupSnapshotResultReportedEvent.ScopeResult> scopes) {
        int mask = 0;
        for (Map.Entry<String, ProtocolGroupSnapshotResultReportedEvent.ScopeResult> entry : scopes.entrySet()) {
            if ("SUCCESS".equals(entry.getValue().outcome())) {
                mask |= "METADATA".equals(entry.getKey())
                        ? GroupSnapshotDispatchService.SCOPE_METADATA
                        : GroupSnapshotDispatchService.SCOPE_INVITE_CODE;
            }
        }
        return mask;
    }

    private static String firstFailureCode(
            Map<String, ProtocolGroupSnapshotResultReportedEvent.ScopeResult> scopes) {
        return scopes.values().stream()
                .filter(result -> "FAILED".equals(result.outcome()))
                .map(ProtocolGroupSnapshotResultReportedEvent.ScopeResult::errorCode)
                .findFirst()
                .orElse("UNKNOWN");
    }

    /** 只校准本次命令的执行账号，不推断也不清理同群其他账号关系。 */
    private void calibrateNotJoined(ProtocolGroupSnapshotResultReportedEvent event) {
        long observedAt = event.scopes().values().stream()
                .filter(result -> "FAILED".equals(result.outcome()))
                .filter(result -> "GROUP_NOT_JOINED".equals(result.errorCode()))
                .mapToLong(ProtocolGroupSnapshotResultReportedEvent.ScopeResult::completedAt)
                .max()
                .orElse(-1L);
        if (observedAt < 0L) {
            return;
        }
        membershipStatusService.applyMembershipChanged(new AccountGroupMembershipChangedEvent(
                event.tenantId(), event.accountId(), event.protocolAccountId(), event.groupJid(),
                "remove", observedAt, event.eventId() + ":not-joined",
                AccountGroupMembershipStatusServiceImpl.GROUP_SNAPSHOT_NOT_JOINED_SOURCE));
    }

    private static GroupMetadataSyncTask identity(GroupMetadataSyncTask task) {
        GroupMetadataSyncTask row = new GroupMetadataSyncTask();
        row.setId(task.getId());
        row.setTenantId(task.getTenantId());
        row.setGroupLinkId(task.getGroupLinkId());
        return row;
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
