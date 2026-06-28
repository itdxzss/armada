package com.armada.platform.kafka.outbox;

import com.armada.platform.protocol.mapper.ProtocolCommandOutboxMapper;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.platform.protocol.model.enums.ProtocolCommandOutboxStatus;
import com.armada.shared.exception.BusinessException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 协议命令 Outbox dispatcher。
 *
 * <p>本类按批次短事务抢占 PENDING 行,随后在事务外调用 Kafka publisher,最后按发送结果
 * 标记 SENT/PENDING/DEAD。</p>
 *
 * <p>正常路径来自 {@link ProtocolCommandDispatchTrigger}:outbox 写入事务提交后,直接把本次刚插入
 * 的 rows 传进来。dispatcher 只按这些 command_id 做状态抢占,不再先全局扫描 outbox。
 * 全局扫描只保留给低频 scheduler,用于服务重启、异步任务提交失败、失败重试到期等兜底场景。</p>
 */
@Service
public class ProtocolCommandOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ProtocolCommandOutboxDispatcher.class);
    private static final String DEFAULT_PUBLISHER_ID_PREFIX = "protocol-command-publisher-";
    private static final String LOCK_EXPIRED_ERROR = "publisher lock expired";
    private static final int MAX_ERROR_LENGTH = 1024;

    private final ProtocolCommandOutboxMapper mapper;
    private final ProtocolCommandPublisher publisher;
    private final ProtocolCommandDispatcherProperties properties;
    private final String publisherId;

    /**
     * 创建协议命令 Outbox dispatcher。
     *
     * @param mapper     outbox mapper
     * @param publisher  Kafka publisher
     * @param properties dispatcher 配置
     */
    public ProtocolCommandOutboxDispatcher(ProtocolCommandOutboxMapper mapper,
                                           ProtocolCommandPublisher publisher,
                                           ProtocolCommandDispatcherProperties properties) {
        this.mapper = mapper;
        this.publisher = publisher;
        this.properties = properties;
        this.publisherId = resolvePublisherId(properties);
    }

    /**
     * 发送刚插入并已提交的 outbox rows。
     *
     * <p>该方法是正常 afterCommit 主路径:不先全局扫描 outbox,只按本次 command_id 批量抢占。
     * 抢占全量成功时直接发送内存 rows,少一次 SELECT。只有同一批 command 被重复触发、并发实例
     * 已抢走部分行等异常并发场景,才回查当前 dispatcher 实际抢到的行,避免误发别人的锁。</p>
     *
     * @param rows 本次事务刚插入的 outbox rows
     * @return dispatch 结果
     */
    public ProtocolCommandDispatchResult dispatchInsertedRows(List<ProtocolCommandOutbox> rows) {
        if (rows == null || rows.isEmpty()) {
            return ProtocolCommandDispatchResult.empty();
        }

        long lockedAt = now();
        String batchId = rows.get(0).getBatchId();
        List<String> commandIds = rows.stream()
                .map(ProtocolCommandOutbox::getCommandId)
                .toList();
        int locked = mapper.markLockedByCommandIds(commandIds, publisherId, lockedAt);
        if (locked == 0) {
            log.warn("协议命令 outbox 主路径未抢到锁 batchId={} requested={} publisherId={}",
                    batchId, rows.size(), publisherId);
            return new ProtocolCommandDispatchResult(rows.size(), 0, 0, 0, 0);
        }

        List<ProtocolCommandOutbox> lockedRows = rows;
        if (locked != rows.size()) {
            log.warn("协议命令 outbox 主路径部分抢占 batchId={} requested={} locked={} publisherId={}",
                    batchId, rows.size(), locked, publisherId);
            lockedRows = mapper.selectLockedByCommandIds(commandIds, publisherId, lockedAt);
        } else {
            // 主路径不回查 DB,必须把本次抢占的锁上下文补到内存 row,后续状态回写才能校验同一把锁。
            fillLockContext(rows, lockedAt);
        }
        RowDispatchCounts counts = sendLockedRows(lockedRows);
        ProtocolCommandDispatchResult result = new ProtocolCommandDispatchResult(
                rows.size(),
                lockedRows.size(),
                counts.sent(),
                counts.retried(),
                counts.dead());
        logDispatchResult(result);
        return result;
    }

    /**
     * 兜底 drain 到期待发送 PENDING 命令。
     *
     * <p>单次最多处理 {@code maxBatchesPerDrain * batchSize} 行,避免一个触发长期占用后台线程。
     * 该方法需要扫描 outbox,只给低频 scheduler 或后续人工触发兜底使用。</p>
     *
     * @return dispatch 结果
     */
    public ProtocolCommandDispatchResult dispatchPendingNow() {
        ProtocolCommandDispatchResult total = ProtocolCommandDispatchResult.empty();
        // 一次兜底触发不能无限 drain,否则积压大时会长期占用唯一 dispatch 线程。
        int maxBatches = positiveOrDefault(
                properties.getMaxBatchesPerDrain(),
                ProtocolCommandDispatcherProperties.DEFAULT_MAX_BATCHES_PER_DRAIN);
        for (int i = 0; i < maxBatches; i++) {
            // 每轮只短事务抢占一批到期 PENDING,随后事务外发送 Kafka 并回写状态。
            // 这里是兜底路径,允许扫描全局 outbox;正常 afterCommit 主路径不走这个方法。
            ProtocolCommandDispatchResult batch = dispatchOneBatch();
            total = total.plus(batch);
            // selected 未满一批表示当前已没有更多到期 PENDING;locked=0 表示并发实例已抢走候选行。
            if (batch.selected() < batchSize() || batch.locked() == 0) {
                break;
            }
        }
        // 空转不打日志,避免兜底扫描无任务时产生噪声;有实际处理才输出汇总。
        logDispatchResult(total);
        return total;
    }

    /**
     * 释放过期 LOCKED 行,供低频兜底 scheduler 调用。
     *
     * @return 恢复成 PENDING 的行数
     */
    public int recoverExpiredLocks() {
        long now = now();
        long lockedBefore = now - positiveOrDefault(
                properties.getLockedTimeoutMs(),
                ProtocolCommandDispatcherProperties.DEFAULT_LOCKED_TIMEOUT_MS);
        int recovered = mapper.releaseExpiredLocks(lockedBefore, now, LOCK_EXPIRED_ERROR, batchSize());
        if (recovered > 0) {
            log.warn("协议命令 outbox 兜底恢复过期 LOCKED recovered={} lockedBefore={} publisherId={}",
                    recovered, lockedBefore, publisherId);
        }
        return recovered;
    }

    private ProtocolCommandDispatchResult dispatchOneBatch() {
        long lockedAt = now();
        // 兜底扫描按 status + next_retry_at 索引读取到期 PENDING;主路径不会调用本方法。
        List<ProtocolCommandOutbox> candidates = mapper.selectDispatchable(
                ProtocolCommandOutboxStatus.PENDING.code(), lockedAt, batchSize());
        if (candidates.isEmpty()) {
            return ProtocolCommandDispatchResult.empty();
        }

        List<Long> ids = candidates.stream()
                .map(ProtocolCommandOutbox::getId)
                .toList();
        int locked = mapper.markLocked(ids, publisherId, lockedAt);
        if (locked == 0) {
            log.warn("协议命令 outbox 兜底扫描未抢到锁 selected={} publisherId={}",
                    candidates.size(), publisherId);
            return new ProtocolCommandDispatchResult(candidates.size(), 0, 0, 0, 0);
        }

        List<ProtocolCommandOutbox> lockedRows = mapper.selectLockedBy(ids, publisherId, lockedAt);
        RowDispatchCounts counts = sendLockedRows(lockedRows);
        return new ProtocolCommandDispatchResult(
                candidates.size(),
                lockedRows.size(),
                counts.sent(),
                counts.retried(),
                counts.dead());
    }

    private RowDispatchCounts sendLockedRows(List<ProtocolCommandOutbox> rows) {
        int sent = 0;
        int retried = 0;
        int dead = 0;
        for (ProtocolCommandOutbox row : rows) {
            try {
                publisher.publish(row);
                sent += markSent(row);
            } catch (BusinessException ex) {
                log.warn("协议命令 outbox payload 不可发送 commandId={} batchId={} accountId={} error={}",
                        row.getCommandId(), row.getBatchId(), row.getAggregateId(), safeError(ex));
                dead += markDead(row, ex);
            } catch (RuntimeException ex) {
                if (shouldMarkDead(row)) {
                    log.warn("协议命令 outbox 发送失败且重试耗尽 commandId={} batchId={} accountId={} retryCount={} error={}",
                            row.getCommandId(), row.getBatchId(), row.getAggregateId(), row.getRetryCount(),
                            safeError(ex));
                    dead += markDead(row, ex);
                } else {
                    log.warn("协议命令 outbox 发送失败等待重试 commandId={} batchId={} accountId={} retryCount={} "
                                    + "retryDelayMs={} error={}",
                            row.getCommandId(), row.getBatchId(), row.getAggregateId(), row.getRetryCount(),
                            retryDelayMs(), safeError(ex));
                    retried += markRetry(row, ex);
                }
            }
        }
        return new RowDispatchCounts(sent, retried, dead);
    }

    private int markSent(ProtocolCommandOutbox row) {
        long now = now();
        int updated = mapper.markSent(row, now);
        if (updated == 0) {
            log.warn("协议命令 outbox SENT 回写未命中 commandId={} batchId={} accountId={} rowId={} "
                            + "lockedBy={} lockedAt={}",
                    row.getCommandId(), row.getBatchId(), row.getAggregateId(), row.getId(),
                    row.getLockedBy(), row.getLockedAt());
        }
        return updated;
    }

    private int markRetry(ProtocolCommandOutbox row, RuntimeException ex) {
        long now = now();
        // 失败重试会释放 LOCKED,并把 next_retry_at 往后推;后续触发或 scheduler 到期后再发送。
        int updated = mapper.markRetry(row, now + retryDelayMs(), safeError(ex), now);
        if (updated == 0) {
            log.warn("协议命令 outbox RETRY 回写未命中 commandId={} batchId={} accountId={} rowId={} "
                            + "lockedBy={} lockedAt={} error={}",
                    row.getCommandId(), row.getBatchId(), row.getAggregateId(), row.getId(),
                    row.getLockedBy(), row.getLockedAt(), safeError(ex));
        }
        return updated;
    }

    private int markDead(ProtocolCommandOutbox row, RuntimeException ex) {
        long now = now();
        int updated = mapper.markDead(row, safeError(ex), now);
        if (updated == 0) {
            log.warn("协议命令 outbox DEAD 回写未命中 commandId={} batchId={} accountId={} rowId={} "
                            + "lockedBy={} lockedAt={} error={}",
                    row.getCommandId(), row.getBatchId(), row.getAggregateId(), row.getId(),
                    row.getLockedBy(), row.getLockedAt(), safeError(ex));
        }
        return updated;
    }

    private boolean shouldMarkDead(ProtocolCommandOutbox row) {
        int retryCount = row.getRetryCount() == null ? 0 : row.getRetryCount();
        int maxRetryCount = positiveOrDefault(
                properties.getMaxRetryCount(),
                ProtocolCommandDispatcherProperties.DEFAULT_MAX_RETRY_COUNT);
        // retry_count 是已失败次数;当前这次失败也要计入阈值判断。
        return retryCount + 1 >= maxRetryCount;
    }

    private long retryDelayMs() {
        return positiveOrDefault(
                properties.getRetryDelayMs(),
                ProtocolCommandDispatcherProperties.DEFAULT_RETRY_DELAY_MS);
    }

    private int batchSize() {
        return positiveOrDefault(
                properties.getBatchSize(),
                ProtocolCommandDispatcherProperties.DEFAULT_BATCH_SIZE);
    }

    private String safeError(RuntimeException ex) {
        String message = ex.getMessage();
        String value = message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
        if (value.length() <= MAX_ERROR_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_ERROR_LENGTH);
    }

    private long now() {
        return System.currentTimeMillis();
    }

    private void fillLockContext(List<ProtocolCommandOutbox> rows, long lockedAt) {
        for (ProtocolCommandOutbox row : rows) {
            row.setLockedBy(publisherId);
            row.setLockedAt(lockedAt);
        }
    }

    private void logDispatchResult(ProtocolCommandDispatchResult result) {
        if (result.hasWork()) {
            log.info("协议命令 outbox dispatch 完成 selected={} locked={} sent={} retried={} dead={} publisherId={}",
                    result.selected(), result.locked(), result.sent(), result.retried(), result.dead(), publisherId);
        }
    }

    private static String resolvePublisherId(ProtocolCommandDispatcherProperties properties) {
        String configured = properties.getPublisherId();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return DEFAULT_PUBLISHER_ID_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }

    private static int positiveOrDefault(int value, int defaultValue) {
        return value > 0 ? value : defaultValue;
    }

    private static long positiveOrDefault(long value, long defaultValue) {
        return value > 0 ? value : defaultValue;
    }

    private record RowDispatchCounts(int sent, int retried, int dead) {
    }
}
