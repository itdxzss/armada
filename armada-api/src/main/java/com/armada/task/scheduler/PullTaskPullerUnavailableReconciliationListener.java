package com.armada.task.scheduler;

import com.armada.task.model.dto.PullTaskPullerUnavailableEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 拉手不可用事务提交后立即唤醒名单核实，避免继续等待全局 60 秒结果窗口。 */
@Component
public class PullTaskPullerUnavailableReconciliationListener {

    private final PullTaskUnknownResultReconciliationScheduler scheduler;

    /** @param scheduler 独立未知结果收敛线程 */
    public PullTaskPullerUnavailableReconciliationListener(
            PullTaskUnknownResultReconciliationScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /** 事务外事件也允许触发，便于非事务补偿入口复用。 */
    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true)
    public void onPullerUnavailable(PullTaskPullerUnavailableEvent event) {
        scheduler.trigger();
    }
}
