package com.armada.platform.kafka.dispatch;

import com.armada.platform.kafka.config.ProtocolCommandDispatcherProperties;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 协议命令 dispatch 触发器。
 *
 * <p>outbox 写入事务提交后再异步发送刚插入的 rows,避免 Kafka 发送线程读到未提交 outbox 行。
 * 如果当前没有事务同步上下文,则直接异步提交 dispatch 任务。</p>
 *
 * <p>本类不扫描 outbox,只负责把本次内存 rows 交给后台执行器。执行器提交失败通常意味着队列已满,
 * 此时改由当前线程同步发送本批 rows,避免已提交的 PENDING 行只能等待低频兜底扫描。</p>
 */
@Service
public class ProtocolCommandDispatchTrigger {

    private static final Logger log = LoggerFactory.getLogger(ProtocolCommandDispatchTrigger.class);

    private final ProtocolCommandDispatcher dispatcher;
    private final Executor executor;
    private final ProtocolCommandDispatcherProperties properties;
    private final TaskScheduler scheduler;

    /**
     * 创建协议命令 dispatch 触发器。
     *
     * @param dispatcher dispatcher
     * @param executor   dispatch 后台执行器
     * @param properties dispatcher 配置
     * @param scheduler  应用现有的任务调度器
     */
    public ProtocolCommandDispatchTrigger(
            ProtocolCommandDispatcher dispatcher,
            @Qualifier("protocolCommandDispatchExecutor") Executor executor,
            ProtocolCommandDispatcherProperties properties,
            @Qualifier("taskScheduler") TaskScheduler scheduler) {
        this.dispatcher = dispatcher;
        this.executor = executor;
        this.properties = properties;
        this.scheduler = scheduler;
    }

    /**
     * 在当前事务提交后异步发送刚插入的 outbox rows。
     *
     * <p>批量 enqueue 只调用一次本方法,避免每行 outbox 都提交一个异步任务。rows 会复制成不可变快照,
     * 防止调用方后续修改集合影响异步线程。</p>
     *
     * @param rows 本次事务刚插入的 outbox rows
     */
    public void dispatchAfterCommit(List<ProtocolCommandOutbox> rows) {
        if (!properties.isImmediateEnabled()) {
            log.info("协议命令 outbox afterCommit dispatch 已关闭 rows={}", rows == null ? 0 : rows.size());
            return;
        }
        List<ProtocolCommandOutbox> dispatchRows = rows == null ? List.of() : List.copyOf(rows);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            log.debug("协议命令 outbox 注册 afterCommit dispatch rows={}", dispatchRows.size());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatchOrSchedule(dispatchRows);
                }
            });
            return;
        }
        log.debug("协议命令 outbox 无事务同步上下文,立即提交异步 dispatch rows={}", dispatchRows.size());
        dispatchOrSchedule(dispatchRows);
    }

    private void dispatchOrSchedule(List<ProtocolCommandOutbox> rows) {
        long now = System.currentTimeMillis();
        Map<Long, List<ProtocolCommandOutbox>> rowsByDueAt = new TreeMap<>();
        for (ProtocolCommandOutbox row : rows) {
            long dueAt = row.getNextRetryAt() == null ? 0L : Math.max(0L, row.getNextRetryAt());
            rowsByDueAt.computeIfAbsent(dueAt, ignored -> new ArrayList<>()).add(row);
        }
        for (Map.Entry<Long, List<ProtocolCommandOutbox>> entry : rowsByDueAt.entrySet()) {
            List<ProtocolCommandOutbox> dueRows = List.copyOf(entry.getValue());
            if (entry.getKey() <= now) {
                submitDispatch(dueRows);
            } else {
                scheduleDispatch(entry.getKey(), dueRows);
            }
        }
    }

    private void scheduleDispatch(long dueAt, List<ProtocolCommandOutbox> rows) {
        try {
            if (scheduler.schedule(() -> submitDispatch(rows), Instant.ofEpochMilli(dueAt)) == null) {
                log.warn("协议命令 outbox 定时发送未创建任务 dueAt={} rows={}，等待周期扫描兜底",
                        dueAt, rows.size());
            }
        } catch (RuntimeException ex) {
            log.warn("协议命令 outbox 定时发送调度失败 dueAt={} rows={}，等待周期扫描兜底",
                    dueAt, rows.size(), ex);
        }
    }

    private void submitDispatch(List<ProtocolCommandOutbox> rows) {
        try {
            log.debug("协议命令 outbox 提交异步 dispatch 任务 rows={}", rows.size());
            executor.execute(() -> {
                try {
                    dispatcher.dispatchInsertedRows(rows);
                } catch (RuntimeException ex) {
                    log.error("协议命令 outbox 异步 dispatch 失败", ex);
                }
            });
        } catch (RuntimeException ex) {
            // 不能改用当前线程同步发送：调用方通常是拉群/进群调度线程，
            // 同步发送会把整个调度轮次连同 Kafka 往返一起阻塞。
            // 这些行已提交为 PENDING，周期兜底扫描会把它们捡起来。
            log.warn("协议命令 outbox 异步 dispatch 提交失败,等待周期兜底扫描 rows={} error={}",
                    rows.size(), ex.toString());
        }
    }
}
