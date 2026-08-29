package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.mapper.HyperlinkTaskAccountStatMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRoundMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRuntimeMapper;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipient;
import com.armada.hyperlink.task.model.enums.HyperlinkRecipientStatus;
import com.armada.hyperlink.task.model.vo.HyperlinkMetricsDelta;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** recipient 唯一事实到 runtime/round/account_stat 的幂等投影与 reconciliation。 */
@Service
public class HyperlinkMetricsProjectionService {
    /** 单个投影事务最多锁定的 recipient 数。 */
    public static final int BATCH_SIZE = 500;
    private final HyperlinkTaskRecipientMapper recipientMapper;
    private final HyperlinkTaskRuntimeMapper runtimeMapper;
    private final HyperlinkTaskRoundMapper roundMapper;
    private final HyperlinkTaskAccountStatMapper accountStatMapper;
    private final Clock clock;

    @Autowired
    public HyperlinkMetricsProjectionService(HyperlinkTaskRecipientMapper recipientMapper,
            HyperlinkTaskRuntimeMapper runtimeMapper, HyperlinkTaskRoundMapper roundMapper,
            HyperlinkTaskAccountStatMapper accountStatMapper) {
        this(recipientMapper, runtimeMapper, roundMapper, accountStatMapper, Clock.systemUTC());
    }

    HyperlinkMetricsProjectionService(HyperlinkTaskRecipientMapper recipientMapper,
            HyperlinkTaskRuntimeMapper runtimeMapper, HyperlinkTaskRoundMapper roundMapper,
            HyperlinkTaskAccountStatMapper accountStatMapper, Clock clock) {
        this.recipientMapper = recipientMapper;
        this.runtimeMapper = runtimeMapper;
        this.roundMapper = roundMapper;
        this.accountStatMapper = accountStatMapper;
        this.clock = clock;
    }

    /**
     * 领取并投影一个固定大小的 recipient 变化批次。
     *
     * <p>领取、三个聚合的列级增量和 projectedStatus 回写处于同一短事务，多个 worker
     * 依靠 SKIP LOCKED 处理互不重叠的事实行。</p>
     *
     * @return 本批完成投影的 recipient 数；0 表示当前没有待处理变化
     */
    @Transactional(rollbackFor = Exception.class)
    public int projectNextBatch() {
        List<HyperlinkTaskRecipient> candidates = recipientMapper
                .selectMetricsProjectionCandidates(BATCH_SIZE);
        if (candidates.isEmpty()) {
            return 0;
        }
        lockProjectionScopes(candidates);
        List<Long> candidateIds = candidates.stream().map(HyperlinkTaskRecipient::getId).toList();
        List<HyperlinkTaskRecipient> recipients = recipientMapper
                .lockMetricsProjectionBatch(candidateIds);
        if (recipients.isEmpty()) { return 0; }
        long now = clock.millis();
        ProjectionBatch batch = aggregate(recipients);
        for (HyperlinkMetricsDelta delta : batch.rounds()) {
            requireUpdated(roundMapper.incrementProjection(delta, now), "超链轮次指标投影目标不存在");
        }
        if (!batch.accounts().isEmpty()) {
            accountStatMapper.incrementProjection(batch.accounts(), now);
        }
        for (HyperlinkMetricsDelta delta : batch.tasks()) {
            requireUpdated(runtimeMapper.incrementProjection(delta, now), "超链任务指标投影目标不存在");
        }
        List<Long> recipientIds = recipients.stream().map(HyperlinkTaskRecipient::getId).toList();
        if (recipientMapper.markProjectionBatch(recipientIds, now) != recipients.size()) {
            throw new BusinessException(ErrorCode.HYPERLINK_TASK_STATE_CONFLICT,
                    "超链 recipient 指标投影状态冲突");
        }
        return recipients.size();
    }

    /** 按指定任务从 recipient 事实全量校准三个投影；不用于分钟级主路径。 */
    @Transactional(rollbackFor = Exception.class)
    public void reconcile(long taskId) {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "超链指标校准缺少租户上下文");
        }
        if (runtimeMapper.selectByTaskIdForUpdate(tenantId, taskId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "超链任务运行态不存在");
        }
        long now = clock.millis();
        roundMapper.rebuildProjection(taskId, now);
        accountStatMapper.replaceFromRecipient(taskId, now);
        runtimeMapper.rebuildProjection(taskId, now);
        recipientMapper.markProjected(taskId, now);
    }

    private void lockProjectionScopes(List<HyperlinkTaskRecipient> candidates) {
        Map<TaskKey, TreeSet<Long>> scopes = new TreeMap<>();
        for (HyperlinkTaskRecipient candidate : candidates) {
            TaskKey task = new TaskKey(candidate.getTenantId(), candidate.getHyperlinkTaskId());
            TreeSet<Long> rounds = scopes.computeIfAbsent(task, ignored -> new TreeSet<>());
            if (candidate.getHyperlinkTaskRoundId() != null) {
                rounds.add(candidate.getHyperlinkTaskRoundId());
            }
        }
        scopes.forEach((task, roundIds) -> {
            if (runtimeMapper.selectByTaskIdForUpdate(task.tenantId(), task.taskId()) == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "超链任务运行态不存在");
            }
            if (!roundIds.isEmpty()) {
                List<Long> locked = roundMapper.lockMetricsProjectionRounds(
                        task.tenantId(), task.taskId(), new ArrayList<>(roundIds));
                if (locked.size() != roundIds.size()) {
                    throw new BusinessException(ErrorCode.HYPERLINK_TASK_STATE_CONFLICT,
                            "超链轮次指标投影锁定失败");
                }
            }
        });
    }

    private ProjectionBatch aggregate(List<HyperlinkTaskRecipient> recipients) {
        Map<TaskKey, MetricsAccumulator> tasks = new TreeMap<>();
        Map<RoundKey, MetricsAccumulator> rounds = new TreeMap<>();
        Map<AccountKey, MetricsAccumulator> accounts = new TreeMap<>();
        for (HyperlinkTaskRecipient recipient : recipients) {
            RecipientDelta delta = delta(recipient);
            TaskKey taskKey = new TaskKey(recipient.getTenantId(), recipient.getHyperlinkTaskId());
            tasks.computeIfAbsent(taskKey, ignored -> new MetricsAccumulator()).add(delta);
            if (recipient.getHyperlinkTaskRoundId() != null) {
                RoundKey roundKey = new RoundKey(recipient.getTenantId(),
                        recipient.getHyperlinkTaskId(), recipient.getHyperlinkTaskRoundId());
                rounds.computeIfAbsent(roundKey, ignored -> new MetricsAccumulator()).add(delta);
            }
            if (delta.hasAccountMetrics()) {
                AccountKey accountKey = new AccountKey(recipient.getTenantId(),
                        recipient.getHyperlinkTaskId(), recipient.getAccountId());
                accounts.computeIfAbsent(accountKey, ignored -> new MetricsAccumulator()).add(delta);
            }
        }
        return new ProjectionBatch(taskDeltas(tasks), roundDeltas(rounds), accountDeltas(accounts));
    }

    private RecipientDelta delta(HyperlinkTaskRecipient recipient) {
        HyperlinkRecipientStatus oldStatus = HyperlinkRecipientStatus.fromCode(
                recipient.getMetricsProjectedStatus());
        HyperlinkRecipientStatus currentStatus = HyperlinkRecipientStatus.fromCode(
                recipient.getSendStatus());
        int assigned = recipient.getHyperlinkTaskRoundId() != null
                && recipient.getMetricsProjectedAt() == null
                && currentStatus != HyperlinkRecipientStatus.PENDING ? 1 : 0;
        int submitted = recipient.getSubmittedAt() != null
                && oldStatus == HyperlinkRecipientStatus.PENDING ? 1 : 0;
        return new RecipientDelta(assigned, submitted,
                contribution(currentStatus, HyperlinkRecipientStatus.SUCCESS)
                        - contribution(oldStatus, HyperlinkRecipientStatus.SUCCESS),
                contribution(currentStatus, HyperlinkRecipientStatus.DELIVERED)
                        - contribution(oldStatus, HyperlinkRecipientStatus.DELIVERED),
                contribution(currentStatus, HyperlinkRecipientStatus.READ)
                        - contribution(oldStatus, HyperlinkRecipientStatus.READ),
                failure(currentStatus) - failure(oldStatus),
                currentStatus == HyperlinkRecipientStatus.UNREGISTERED ? 1
                        : oldStatus == HyperlinkRecipientStatus.UNREGISTERED ? -1 : 0,
                submitted == 1 ? recipient.getSubmittedAt() : null);
    }

    private int contribution(HyperlinkRecipientStatus value, HyperlinkRecipientStatus threshold) {
        return value.rank() >= threshold.rank() ? 1 : 0;
    }

    private int failure(HyperlinkRecipientStatus value) {
        return value.terminalFailure() ? 1 : 0;
    }

    private List<HyperlinkMetricsDelta> taskDeltas(Map<TaskKey, MetricsAccumulator> source) {
        List<HyperlinkMetricsDelta> result = new ArrayList<>(source.size());
        source.forEach((key, value) -> result.add(value.toDelta(
                key.tenantId(), key.taskId(), null, null)));
        return result;
    }

    private List<HyperlinkMetricsDelta> roundDeltas(Map<RoundKey, MetricsAccumulator> source) {
        List<HyperlinkMetricsDelta> result = new ArrayList<>(source.size());
        source.forEach((key, value) -> result.add(value.toDelta(
                key.tenantId(), key.taskId(), key.roundId(), null)));
        return result;
    }

    private List<HyperlinkMetricsDelta> accountDeltas(Map<AccountKey, MetricsAccumulator> source) {
        List<HyperlinkMetricsDelta> result = new ArrayList<>(source.size());
        source.forEach((key, value) -> result.add(value.toDelta(
                key.tenantId(), key.taskId(), null, key.accountId())));
        return result;
    }

    private void requireUpdated(int updated, String message) {
        if (updated != 1) {
            throw new BusinessException(ErrorCode.HYPERLINK_TASK_STATE_CONFLICT, message);
        }
    }

    private record ProjectionBatch(List<HyperlinkMetricsDelta> tasks,
            List<HyperlinkMetricsDelta> rounds, List<HyperlinkMetricsDelta> accounts) {
    }

    private record RecipientDelta(int assigned, int sent, int success, int delivered,
            int read, int failed, int fail404, Long submittedAt) {
        private boolean hasAccountMetrics() {
            return sent != 0 || success != 0 || delivered != 0 || read != 0
                    || failed != 0 || fail404 != 0;
        }
    }

    private record TaskKey(long tenantId, long taskId) implements Comparable<TaskKey> {
        @Override public int compareTo(TaskKey other) {
            int tenant = Long.compare(tenantId, other.tenantId);
            return tenant != 0 ? tenant : Long.compare(taskId, other.taskId);
        }
    }

    private record RoundKey(long tenantId, long taskId, long roundId)
            implements Comparable<RoundKey> {
        @Override public int compareTo(RoundKey other) {
            int task = new TaskKey(tenantId, taskId).compareTo(
                    new TaskKey(other.tenantId, other.taskId));
            return task != 0 ? task : Long.compare(roundId, other.roundId);
        }
    }

    private record AccountKey(long tenantId, long taskId, Long accountId)
            implements Comparable<AccountKey> {
        @Override public int compareTo(AccountKey other) {
            int task = new TaskKey(tenantId, taskId).compareTo(
                    new TaskKey(other.tenantId, other.taskId));
            if (task != 0) { return task; }
            return Long.compare(accountId == null ? 0L : accountId,
                    other.accountId == null ? 0L : other.accountId);
        }
    }

    private static final class MetricsAccumulator {
        private int assigned;
        private long sent;
        private long success;
        private long delivered;
        private long read;
        private long failed;
        private long fail404;
        private Long firstSendAt;
        private Long lastSendAt;

        private void add(RecipientDelta delta) {
            assigned += delta.assigned();
            sent += delta.sent();
            success += delta.success();
            delivered += delta.delivered();
            read += delta.read();
            failed += delta.failed();
            fail404 += delta.fail404();
            if (delta.submittedAt() != null) {
                firstSendAt = firstSendAt == null ? delta.submittedAt()
                        : Math.min(firstSendAt, delta.submittedAt());
                lastSendAt = lastSendAt == null ? delta.submittedAt()
                        : Math.max(lastSendAt, delta.submittedAt());
            }
        }

        private HyperlinkMetricsDelta toDelta(long tenantId, long taskId,
                Long roundId, Long accountId) {
            return new HyperlinkMetricsDelta(tenantId, taskId, roundId, accountId,
                    assigned, sent, success, delivered, read, failed, fail404,
                    firstSendAt, lastSendAt);
        }
    }
}
