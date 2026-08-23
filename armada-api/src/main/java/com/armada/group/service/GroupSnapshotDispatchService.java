package com.armada.group.service;

import com.armada.group.mapper.GroupMetadataSyncTaskMapper;
import com.armada.group.model.entity.GroupMetadataSyncTask;
import com.armada.group.model.enums.GroupMetadataSyncStatus;
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.observability.GroupSnapshotMetrics;
import com.armada.platform.protocol.model.command.ProtocolGroupSnapshotCommandRequest;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在同一事务内领取群资料任务、写 Outbox 并冻结 commandId 关联。 */
@Service
public class GroupSnapshotDispatchService {

    public static final int SCOPE_METADATA = 1;
    public static final int SCOPE_INVITE_CODE = 2;

    private final GroupMetadataSyncTaskService taskService;
    private final GroupMetadataSyncTaskMapper taskMapper;
    private final ProtocolCommandOutboxService outboxService;
    private final GroupSnapshotMetrics metrics;

    public GroupSnapshotDispatchService(
            GroupMetadataSyncTaskService taskService,
            GroupMetadataSyncTaskMapper taskMapper,
            ProtocolCommandOutboxService outboxService,
            GroupSnapshotMetrics metrics) {
        this.taskService = taskService;
        this.taskMapper = taskMapper;
        this.outboxService = outboxService;
        this.metrics = metrics;
    }

    /** 返回 false 表示并发条件下任务未领取；成功返回时任务行与 Outbox 必已同时写入。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean dispatchMetadataTask(
            GroupMetadataSyncTask task,
            GroupExecutionAccount account,
            long now,
            long resultDeadlineAt,
            GroupMetadataSyncLimits limits) {
        return dispatchMetadataTask(task, account, now, resultDeadlineAt, limits, null);
    }

    /** 支持候选轮换显式覆盖 source；仍与任务关联、Outbox 写入共用当前事务。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean dispatchMetadataTask(
            GroupMetadataSyncTask task,
            GroupExecutionAccount account,
            long now,
            long resultDeadlineAt,
            GroupMetadataSyncLimits limits,
            String sourceOverride) {
        if (!taskService.claim(task, account, now, resultDeadlineAt, limits)) {
            return false;
        }
        int completedMask = valueOrZero(task.getCompletedScopeMask());
        List<String> scopes = (completedMask & SCOPE_METADATA) != 0
                ? List.of("INVITE_CODE")
                : List.of("METADATA", "INVITE_CODE");
        int requestedMask = scopes.size() == 1
                ? SCOPE_INVITE_CODE
                : SCOPE_METADATA | SCOPE_INVITE_CODE;
        int cursor = Math.max(0, valueOrZero(task.getCandidateCursor()));
        ProtocolCommandOutboxEnqueueResult result = outboxService.enqueueGroupSnapshotCommands(List.of(
                new ProtocolGroupSnapshotCommandRequest(
                        task.getTenantId(), account.accountId(), task.getGroupLinkId(), task.getGroupJid(),
                        scopes, sourceOverride == null ? source(task.getTriggerSource()) : sourceOverride,
                        "GROUP_METADATA_SYNC", task.getId(),
                        cursor + 1, account.protocolAccountId(), account.wsPhone(), account.protocolRef().backend())));
        if (result.inserted() != 1 || result.commandIds().size() != 1) {
            throw new IllegalStateException("群快照 Outbox 写入结果不完整");
        }
        GroupMetadataSyncTask awaiting = new GroupMetadataSyncTask();
        awaiting.setId(task.getId());
        awaiting.setTenantId(task.getTenantId());
        awaiting.setCurrentCommandId(result.commandIds().get(0));
        awaiting.setRequestedScopeMask(requestedMask);
        awaiting.setCompletedScopeMask(completedMask);
        awaiting.setCandidateCursor(cursor);
        awaiting.setResultDeadlineAt(resultDeadlineAt);
        awaiting.setUpdatedAt(now);
        if (taskMapper.markAwaitingResult(awaiting, GroupMetadataSyncStatus.RUNNING.code()) != 1) {
            throw new IllegalStateException("群快照任务 commandId 关联 CAS 失败");
        }
        task.setCurrentCommandId(awaiting.getCurrentCommandId());
        task.setRequestedScopeMask(requestedMask);
        task.setCompletedScopeMask(completedMask);
        task.setCandidateCursor(cursor);
        task.setResultDeadlineAt(resultDeadlineAt);
        metrics.recordCommand(account.protocolRef().backend().name(), requestedMask);
        return true;
    }

    private static String source(Integer triggerCode) {
        if (triggerCode != null && triggerCode == GroupMetadataSyncTrigger.MANUAL_REFRESH.code()) {
            return "MANUAL_INFO_REFRESH";
        }
        if (triggerCode != null && triggerCode == GroupMetadataSyncTrigger.BACKFILL.code()) {
            return "BACKFILL";
        }
        return "REPAIR";
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
