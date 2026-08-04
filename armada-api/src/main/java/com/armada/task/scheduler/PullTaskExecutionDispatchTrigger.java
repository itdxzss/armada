package com.armada.task.scheduler;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 在任务启动事务提交后唤醒普通群链接共享调度器。 */
@Service
public class PullTaskExecutionDispatchTrigger {

    private final PullTaskExecutionDispatchScheduler scheduler;

    /** @param scheduler 共享调度器 */
    public PullTaskExecutionDispatchTrigger(PullTaskExecutionDispatchScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /** 当前有事务时等待提交，无事务时立即发送唤醒信号。 */
    public void dispatchAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    scheduler.trigger();
                }
            });
            return;
        }
        scheduler.trigger();
    }
}
