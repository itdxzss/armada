package com.armada.group.service;

import com.armada.group.mapper.GroupBatchTaskItemMapper;
import com.armada.group.mapper.GroupBatchTaskMapper;
import com.armada.group.model.entity.GroupBatchTask;
import com.armada.group.model.entity.GroupBatchTaskItem;
import com.armada.group.model.enums.GroupBatchTaskItemStatus;
import com.armada.group.model.enums.GroupBatchTaskType;
import com.armada.group.model.enums.GroupBatchTaskStatus;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.observability.GroupSnapshotMetrics;
import com.armada.group.service.impl.GroupBatchRefreshSupport;
import com.armada.group.service.impl.GroupBatchTaskSettlement;
import com.armada.platform.protocol.model.command.ProtocolGroupSnapshotCommandRequest;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 把人工批量刷新明细从同步 HTTP 调用切换为 Outbox 群快照命令。 */
@Service
public class GroupBatchSnapshotDispatchService {

    private static final String NO_ACCOUNT_CODE = "NO_AVAILABLE_ACCOUNT";
    private static final String NO_JID_CODE = "GROUP_JID_UNKNOWN";

    private final GroupBatchTaskItemMapper itemMapper;
    private final GroupBatchTaskMapper taskMapper;
    private final GroupBatchRefreshSupport support;
    private final GroupBatchTaskSettlement settlement;
    private final ProtocolCommandOutboxService outboxService;
    private final GroupSnapshotProperties properties;
    private final GroupSnapshotMetrics metrics;

    /** 创建批量群快照派发器。 */
    public GroupBatchSnapshotDispatchService(
            GroupBatchTaskItemMapper itemMapper,
            GroupBatchTaskMapper taskMapper,
            GroupBatchRefreshSupport support,
            GroupBatchTaskSettlement settlement,
            ProtocolCommandOutboxService outboxService,
            GroupSnapshotProperties properties,
            GroupSnapshotMetrics metrics) {
        this.itemMapper = itemMapper;
        this.taskMapper = taskMapper;
        this.support = support;
        this.settlement = settlement;
        this.outboxService = outboxService;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * 选择当前游标账号并在同一事务内写 Outbox 与 WAITING_RESULT 关联。
     *
     * <p>批量链路不受主链灰度开关影响；V128 后它已没有同步 HTTP 回退路径。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean dispatch(GroupBatchTaskItem item, GroupBatchTaskType type, long now) {
        int cursor = valueOrZero(item.getCandidateCursor());
        Optional<GroupExecutionAccount> selected = support.selector().find(item.getGroupLinkId(), cursor);
        if (selected.isEmpty()) {
            settleFailure(item, NO_ACCOUNT_CODE, "系统内没有在线且仍在该群内的账号", now);
            return false;
        }
        String groupJid = support.groupJid(item.getGroupLinkId());
        if (groupJid == null) {
            settleFailure(item, NO_JID_CODE, "群组标识未知，无法读取群快照", now);
            return false;
        }
        GroupExecutionAccount account = selected.get();
        List<String> scopes = type == GroupBatchTaskType.REFRESH_LINK
                ? List.of("INVITE_CODE")
                : List.of("METADATA");
        String source = type == GroupBatchTaskType.REFRESH_LINK
                ? "MANUAL_INVITE_REFRESH"
                : "MANUAL_INFO_REFRESH";
        int attempt = valueOrZero(item.getAttemptCount()) + 1;
        ProtocolCommandOutboxEnqueueResult result = outboxService.enqueueGroupSnapshotCommands(List.of(
                new ProtocolGroupSnapshotCommandRequest(
                        tenantId(item), account.accountId(), item.getGroupLinkId(), groupJid,
                        scopes, source, "GROUP_BATCH_TASK_ITEM", item.getId(), attempt,
                        account.protocolAccountId(), account.wsPhone(), account.protocolRef().backend())));
        if (result.inserted() != 1 || result.commandIds().size() != 1) {
            throw new IllegalStateException("批量群快照 Outbox 写入结果不完整");
        }
        GroupBatchTaskItem awaiting = identity(item);
        awaiting.setGroupJid(groupJid);
        awaiting.setAccountId(account.accountId());
        awaiting.setCurrentCommandId(result.commandIds().get(0));
        awaiting.setAttemptCount(attempt);
        awaiting.setCandidateCursor(cursor);
        awaiting.setResultDeadlineAt(now + Math.max(1L, properties.resultTimeoutMs()));
        awaiting.setUpdatedAt(now);
        if (itemMapper.markWaitingResult(
                awaiting, GroupBatchTaskItemStatus.PENDING.code(),
                GroupBatchTaskItemStatus.WAITING_RESULT.code()) != 1) {
            throw new IllegalStateException("批量群快照明细 commandId 关联 CAS 失败");
        }
        taskMapper.markRunning(
                item.getTaskId(), GroupBatchTaskStatus.PENDING.code(),
                GroupBatchTaskStatus.RUNNING.code());
        copyCorrelation(item, awaiting);
        metrics.recordCommand(account.protocolRef().backend().name(),
                type == GroupBatchTaskType.REFRESH_LINK
                        ? GroupSnapshotDispatchService.SCOPE_INVITE_CODE
                        : GroupSnapshotDispatchService.SCOPE_METADATA);
        return true;
    }

    /** 超时明细推进候选；候选耗尽时 CAS 失败结算，防止任务永久悬挂。 */
    @Transactional(rollbackFor = Exception.class)
    public void recoverExpired(
            GroupBatchTask task, GroupBatchTaskItem item, GroupBatchTaskType type, long now) {
        int nextCursor = valueOrZero(item.getCandidateCursor()) + 1;
        Optional<GroupExecutionAccount> next = nextCursor < Math.max(1, properties.maxCandidates())
                ? support.selector().find(item.getGroupLinkId(), nextCursor)
                : Optional.empty();
        if (next.isPresent()) {
            GroupBatchTaskItem retry = identity(item);
            retry.setCurrentCommandId(item.getCurrentCommandId());
            retry.setCandidateCursor(nextCursor);
            retry.setErrorCode("TIMEOUT");
            retry.setDescription("群快照结果超时，切换执行账号");
            retry.setUpdatedAt(now);
            if (itemMapper.resetCurrentCommandForRetry(
                    retry, GroupBatchTaskItemStatus.WAITING_RESULT.code(),
                    GroupBatchTaskItemStatus.PENDING.code()) != 1) {
                return;
            }
            item.setStatus(GroupBatchTaskItemStatus.PENDING.code());
            item.setCurrentCommandId(null);
            item.setCandidateCursor(nextCursor);
            dispatch(item, type, now);
            return;
        }
        GroupBatchTaskItem outcome = identity(item);
        outcome.setCurrentCommandId(item.getCurrentCommandId());
        outcome.setStatus(GroupBatchTaskItemStatus.FAILED.code());
        outcome.setErrorCode("TIMEOUT");
        String operation = type == GroupBatchTaskType.REFRESH_LINK
                ? "获取群邀请链接"
                : "获取群信息";
        outcome.setDescription(operation + "超时，且没有其他可用账号可重试");
        outcome.setOperatedAt(now);
        outcome.setUpdatedAt(now);
        int settled = itemMapper.settleCurrentCommand(
                outcome, GroupBatchTaskItemStatus.WAITING_RESULT.code());
        if (settled == 1 && task.getStatus() != GroupBatchTaskStatus.CANCELED.code()) {
            taskMapper.applyItemOutcome(
                    item.getTaskId(), false, GroupBatchTaskStatus.COMPLETED.code(),
                    GroupBatchTaskStatus.RUNNING.code(), now);
        }
    }

    private void settleFailure(
            GroupBatchTaskItem item, String errorCode, String description, long now) {
        GroupBatchTaskItem outcome = identity(item);
        outcome.setStatus(GroupBatchTaskItemStatus.FAILED.code());
        outcome.setErrorCode(errorCode);
        outcome.setDescription(description);
        outcome.setOperatedAt(now);
        outcome.setUpdatedAt(now);
        settlement.settle(outcome);
    }

    private static GroupBatchTaskItem identity(GroupBatchTaskItem item) {
        GroupBatchTaskItem row = new GroupBatchTaskItem();
        row.setId(item.getId());
        row.setTenantId(tenantId(item));
        row.setTaskId(item.getTaskId());
        row.setGroupLinkId(item.getGroupLinkId());
        return row;
    }

    private static Long tenantId(GroupBatchTaskItem item) {
        return item.getTenantId() == null ? TenantContext.get() : item.getTenantId();
    }

    private static void copyCorrelation(GroupBatchTaskItem target, GroupBatchTaskItem source) {
        target.setTenantId(source.getTenantId());
        target.setGroupJid(source.getGroupJid());
        target.setAccountId(source.getAccountId());
        target.setCurrentCommandId(source.getCurrentCommandId());
        target.setAttemptCount(source.getAttemptCount());
        target.setCandidateCursor(source.getCandidateCursor());
        target.setResultDeadlineAt(source.getResultDeadlineAt());
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
