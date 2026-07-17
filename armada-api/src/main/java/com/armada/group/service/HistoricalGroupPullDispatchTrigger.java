package com.armada.group.service;

import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 历史群拉人执行在事务提交后的受控异步派发器。 */
@Service
public class HistoricalGroupPullDispatchTrigger {

    /** 安全日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(HistoricalGroupPullDispatchTrigger.class);

    /** 一次性拉人 worker。 */
    private final HistoricalGroupPullWorker worker;

    /** 有界后台执行器。 */
    private final Executor executor;

    /**
     * 创建历史群拉人派发器。
     *
     * @param worker   一次性拉人 worker
     * @param executor 有界后台执行器
     */
    public HistoricalGroupPullDispatchTrigger(
            HistoricalGroupPullWorker worker,
            @Qualifier("historicalGroupPullExecutor") Executor executor) {
        this.worker = worker;
        this.executor = executor;
    }

    /**
     * 在当前事务提交后派发已认领执行；无事务同步上下文时立即派发。
     *
     * @param tenantId    执行所属租户 ID
     * @param executionId 已认领执行 ID
     */
    public void dispatchAfterCommit(Long tenantId, Long executionId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit(tenantId, executionId);
                }
            });
            return;
        }
        submit(tenantId, executionId);
    }

    private void submit(Long tenantId, Long executionId) {
        try {
            executor.execute(() -> runWorker(tenantId, executionId));
        } catch (RuntimeException ex) {
            log.warn("历史群拉人异步提交失败，改由当前线程执行 executionId={} errorType={}",
                    executionId, ex.getClass().getSimpleName());
            runWorker(tenantId, executionId);
        }
    }

    private void runWorker(Long tenantId, Long executionId) {
        try {
            worker.execute(tenantId, executionId);
        } catch (RuntimeException ex) {
            // 数据库暂时不可用时由启动恢复冻结遗留 RUNNING；一次性执行不在此处重试。
            log.error("历史群拉人后台执行异常 executionId={} errorType={}",
                    executionId, ex.getClass().getSimpleName());
        }
    }
}
