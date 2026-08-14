package com.armada.group.service.impl;

import com.armada.group.mapper.GroupMetadataSyncTaskMapper;
import com.armada.group.model.entity.GroupMetadataSyncTask;
import com.armada.group.model.enums.GroupMetadataSyncStatus;
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.service.GroupMetadataSyncTaskService;
import com.armada.group.service.GroupMetadataSyncLimits;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 群详情耐久同步任务状态机实现。 */
@Service
public class GroupMetadataSyncTaskServiceImpl implements GroupMetadataSyncTaskService {

    private static final int MAX_ATTEMPTS = 4;
    private static final long[] RETRY_DELAYS_MS = {60_000L, 300_000L, 1_800_000L};
    private static final int ERROR_CODE_MAX_LENGTH = 64;
    private static final int ERROR_MESSAGE_MAX_LENGTH = 512;
    private static final List<Integer> TRIGGERED_STATUSES = List.of(
            GroupMetadataSyncStatus.PENDING.code(),
            GroupMetadataSyncStatus.RETRY_WAIT.code());
    private static final List<Integer> REALTIME_REFRESH_TRIGGERS = List.of(
            GroupMetadataSyncTrigger.PARTICIPANT_CHANGED.code(),
            GroupMetadataSyncTrigger.METADATA_CHANGED.code(),
            GroupMetadataSyncTrigger.MANUAL_REFRESH.code());
    private static final List<Integer> CLAIMABLE_STATUSES = List.of(
            GroupMetadataSyncStatus.PENDING.code(),
            GroupMetadataSyncStatus.RETRY_WAIT.code());

    private final GroupMetadataSyncTaskMapper mapper;
    private final long changeDebounceMs;

    /**
     * 创建同步任务状态机。
     *
     * @param mapper 同步任务数据访问
     * @param changeDebounceMs 群变更事件合并窗口
     */
    public GroupMetadataSyncTaskServiceImpl(
            GroupMetadataSyncTaskMapper mapper,
            @Value("${armada.group-metadata-sync.change-debounce-ms:2000}") long changeDebounceMs) {
        this.mapper = mapper;
        this.changeDebounceMs = Math.max(0L, changeDebounceMs);
    }

    @Override
    @Transactional
    public void enqueue(Long groupLinkId, GroupMetadataSyncTrigger trigger, long triggeredAt) {
        if (groupLinkId == null || trigger == null) {
            return;
        }
        long nextRunAt = triggeredAt + (isChangeTrigger(trigger) ? changeDebounceMs : 0L);
        GroupMetadataSyncTask row = new GroupMetadataSyncTask();
        row.setGroupLinkId(groupLinkId);
        row.setStatus(GroupMetadataSyncStatus.PENDING.code());
        row.setTriggerSource(trigger.code());
        row.setAttemptCount(0);
        row.setNextRunAt(nextRunAt);
        row.setRerunRequested(false);
        row.setCreatedAt(triggeredAt);
        row.setUpdatedAt(triggeredAt);
        mapper.enqueue(row, GroupMetadataSyncStatus.RUNNING.code());
    }

    @Override
    @Transactional
    public void resumeDeferredForAccount(Long accountId, long now) {
        if (accountId == null) {
            return;
        }
        mapper.resumeDeferredForAccount(
                accountId,
                GroupMetadataSyncStatus.DEFERRED.code(),
                GroupMetadataSyncStatus.PENDING.code(),
                GroupMetadataSyncTrigger.ACCOUNT_ONLINE.code(),
                now);
    }

    @Override
    @Transactional
    public int recoverExpiredLeases(long now) {
        GroupMetadataSyncTask row = new GroupMetadataSyncTask();
        row.setStatus(GroupMetadataSyncStatus.PENDING.code());
        row.setNextRunAt(now);
        row.setLastErrorCode("LEASE_EXPIRED");
        row.setLastErrorMessage("运行租约已过期");
        row.setUpdatedAt(now);
        return mapper.recoverExpiredLeasesAll(row, GroupMetadataSyncStatus.RUNNING.code());
    }

    @Override
    public List<GroupMetadataSyncTask> findDue(long now, int limit) {
        int pageSize = Math.max(1, limit);
        List<GroupMetadataSyncTask> candidates = mapper.selectDueCandidates(
                TRIGGERED_STATUSES,
                GroupMetadataSyncStatus.SUCCEEDED.code(),
                now,
                pageSize);
        List<GroupMetadataSyncTask> due = new ArrayList<>();
        GroupMetadataSyncTask refreshCandidate = null;
        for (GroupMetadataSyncTask candidate : candidates) {
            if (isForegroundTask(candidate)) {
                due.add(candidate);
                if (due.size() >= pageSize) {
                    return List.copyOf(due);
                }
            } else if (refreshCandidate == null) {
                refreshCandidate = candidate;
            }
        }
        // 已有成功快照的事件、重试和账号上线恢复都属于刷新工作。协议读取在同一轮次
        // 串行执行，因此所有刷新来源合计只允许一个，避免存量积压阻塞新群首次同步。
        if (refreshCandidate != null) {
            due.add(refreshCandidate);
            return List.copyOf(due);
        }
        return List.copyOf(due);
    }

    @Override
    @Transactional
    public boolean claim(
            GroupMetadataSyncTask task,
            GroupExecutionAccount account,
            long now,
            long leaseUntil,
            GroupMetadataSyncLimits limits) {
        GroupMetadataSyncTask claim = copyIdentity(task);
        claim.setStatus(GroupMetadataSyncStatus.RUNNING.code());
        claim.setAttemptCount(valueOrZero(task.getAttemptCount()) + 1);
        claim.setExecutionAccountId(account.accountId());
        claim.setLeaseUntil(leaseUntil);
        claim.setLastStartedAt(now);
        claim.setUpdatedAt(now);
        int affected = mapper.claim(
                claim,
                CLAIMABLE_STATUSES,
                GroupMetadataSyncStatus.RUNNING.code(),
                GroupMetadataSyncStatus.SUCCEEDED.code(),
                Math.max(1, limits.tenantConcurrency()),
                Math.max(1, limits.accountConcurrency()));
        if (affected == 1) {
            task.setStatus(claim.getStatus());
            task.setAttemptCount(claim.getAttemptCount());
            task.setExecutionAccountId(claim.getExecutionAccountId());
            task.setLeaseUntil(claim.getLeaseUntil());
            task.setLastStartedAt(now);
            return true;
        }
        return false;
    }

    @Override
    @Transactional
    public void defer(GroupMetadataSyncTask task, long now) {
        if (GroupMetadataSyncStatus.SUCCEEDED.code() == valueOrZero(task.getStatus())) {
            return;
        }
        GroupMetadataSyncTask row = completion(task, GroupMetadataSyncStatus.DEFERRED, now);
        row.setAttemptCount(Math.max(0, valueOrZero(task.getAttemptCount())));
        row.setNextRunAt(null);
        row.setLastErrorCode("NO_EXECUTION_ACCOUNT");
        row.setLastErrorMessage("暂无在线且仍在群内的可用账号");
        mapper.defer(row, TRIGGERED_STATUSES);
    }

    @Override
    @Transactional
    public void succeed(GroupMetadataSyncTask task, long now) {
        GroupMetadataSyncTask row = completion(task, GroupMetadataSyncStatus.SUCCEEDED, now);
        row.setAttemptCount(0);
        row.setNextRunAt(null);
        row.setLastSuccessAt(now);
        row.setLastErrorCode(null);
        row.setLastErrorMessage(null);
        mapper.finish(row, GroupMetadataSyncStatus.RUNNING.code());
    }

    @Override
    @Transactional
    public void fail(
            GroupMetadataSyncTask task,
            String errorCode,
            String errorMessage,
            long now) {
        int attempts = valueOrZero(task.getAttemptCount());
        GroupMetadataSyncStatus status = attempts >= MAX_ATTEMPTS
                ? GroupMetadataSyncStatus.FAILED
                : GroupMetadataSyncStatus.RETRY_WAIT;
        GroupMetadataSyncTask row = completion(task, status, now);
        row.setAttemptCount(attempts);
        row.setNextRunAt(status == GroupMetadataSyncStatus.FAILED
                ? null
                : now + RETRY_DELAYS_MS[Math.max(0, attempts - 1)]);
        row.setLastErrorCode(clamp(errorCode, ERROR_CODE_MAX_LENGTH));
        row.setLastErrorMessage(clamp(errorMessage, ERROR_MESSAGE_MAX_LENGTH));
        mapper.finish(row, GroupMetadataSyncStatus.RUNNING.code());
    }

    private static boolean isChangeTrigger(GroupMetadataSyncTrigger trigger) {
        return trigger == GroupMetadataSyncTrigger.PARTICIPANT_CHANGED
                || trigger == GroupMetadataSyncTrigger.METADATA_CHANGED;
    }

    private static boolean isForegroundTask(GroupMetadataSyncTask task) {
        return task.getLastSuccessAt() == null
                || (GroupMetadataSyncStatus.PENDING.code() == valueOrZero(task.getStatus())
                && task.getTriggerSource() != null
                && REALTIME_REFRESH_TRIGGERS.contains(task.getTriggerSource()));
    }

    private static GroupMetadataSyncTask completion(
            GroupMetadataSyncTask task,
            GroupMetadataSyncStatus status,
            long now) {
        GroupMetadataSyncTask row = copyIdentity(task);
        row.setStatus(status.code());
        row.setAttemptCount(valueOrZero(task.getAttemptCount()));
        row.setUpdatedAt(now);
        return row;
    }

    private static GroupMetadataSyncTask copyIdentity(GroupMetadataSyncTask task) {
        GroupMetadataSyncTask row = new GroupMetadataSyncTask();
        row.setId(task.getId());
        row.setTenantId(task.getTenantId());
        row.setGroupLinkId(task.getGroupLinkId());
        return row;
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static String clamp(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, maxLength);
    }
}
