package com.armada.group.service.impl;

import com.armada.group.mapper.GroupMetadataSyncTaskMapper;
import com.armada.group.model.entity.GroupMetadataSyncTask;
import com.armada.group.model.enums.GroupMetadataSyncStatus;
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.service.GroupMetadataSyncTaskService;
import com.armada.group.service.GroupMetadataSyncLimits;
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

    private final GroupMetadataSyncTaskMapper mapper;
    private final long changeDebounceMs;

    /** 创建同步任务状态机。 */
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
        return mapper.selectDueCandidates(
                List.of(GroupMetadataSyncStatus.PENDING.code(), GroupMetadataSyncStatus.RETRY_WAIT.code()),
                now,
                Math.max(1, limit));
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
                List.of(GroupMetadataSyncStatus.PENDING.code(), GroupMetadataSyncStatus.RETRY_WAIT.code()),
                GroupMetadataSyncStatus.RUNNING.code(),
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
        GroupMetadataSyncTask row = completion(task, GroupMetadataSyncStatus.DEFERRED, now);
        row.setAttemptCount(Math.max(0, valueOrZero(task.getAttemptCount())));
        row.setNextRunAt(null);
        row.setLastErrorCode("NO_EXECUTION_ACCOUNT");
        row.setLastErrorMessage("暂无在线且仍在群内的可用账号");
        mapper.defer(row, List.of(
                GroupMetadataSyncStatus.PENDING.code(),
                GroupMetadataSyncStatus.RETRY_WAIT.code()));
    }

    @Override
    @Transactional
    public void succeed(GroupMetadataSyncTask task, long now) {
        GroupMetadataSyncTask row = completion(task, GroupMetadataSyncStatus.SUCCEEDED, now);
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
