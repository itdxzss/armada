package com.armada.platform.protocol.service;

import com.armada.platform.protocol.config.ProtocolCommandDispatcherProperties;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import java.util.List;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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

    private final ProtocolCommandOutboxDispatcher dispatcher;
    private final Executor executor;
    private final ProtocolCommandDispatcherProperties properties;

    /**
     * 创建协议命令 dispatch 触发器。
     *
     * @param dispatcher dispatcher
     * @param executor   dispatch 后台执行器
     * @param properties dispatcher 配置
     */
    public ProtocolCommandDispatchTrigger(
            ProtocolCommandOutboxDispatcher dispatcher,
            @Qualifier("protocolCommandDispatchExecutor") Executor executor,
            ProtocolCommandDispatcherProperties properties) {
        this.dispatcher = dispatcher;
        this.executor = executor;
        this.properties = properties;
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
                    submitDispatch(dispatchRows);
                }
            });
            return;
        }
        log.debug("协议命令 outbox 无事务同步上下文,立即提交异步 dispatch rows={}", dispatchRows.size());
        submitDispatch(dispatchRows);
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
            log.warn("协议命令 outbox 异步 dispatch 提交失败,改为当前线程兜底 rows={} error={}",
                    rows.size(), ex.toString());
            dispatchInCurrentThread(rows);
        }
    }

    private void dispatchInCurrentThread(List<ProtocolCommandOutbox> rows) {
        try {
            dispatcher.dispatchInsertedRows(rows);
        } catch (RuntimeException ex) {
            log.error("协议命令 outbox 当前线程兜底 dispatch 失败 rows={}", rows.size(), ex);
        }
    }
}
