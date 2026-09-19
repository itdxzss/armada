package com.armada.task.scheduler;

import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.JoinTaskApprovalMapper;
import com.armada.task.model.dto.JoinTaskApprovalWork;
import com.armada.task.model.enums.JoinTaskApprovalStage;
import com.armada.task.service.JoinTaskApprovalProtocol;
import com.armada.task.service.JoinTaskApprovalTransactions;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 与现有管理员阶段一致的单步调度，网络请求不占用数据库事务。 */
@Component
@ConditionalOnProperty(prefix = "armada.join-task-dispatch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JoinTaskApprovalScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(JoinTaskApprovalScheduler.class);
    private final JoinTaskApprovalMapper mapper;
    private final JoinTaskApprovalTransactions transactions;
    private final JoinTaskApprovalProtocol protocol;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "join-task-approval"); thread.setDaemon(true); return thread;
    });
    /** 装配扫描、短事务和单步协议执行。 */
    public JoinTaskApprovalScheduler(JoinTaskApprovalMapper mapper, JoinTaskApprovalTransactions transactions,
            JoinTaskApprovalProtocol protocol) {
        this.mapper = mapper; this.transactions = transactions; this.protocol = protocol;
    }
    /** 扫描未结束时不重复排队。 */
    @Scheduled(fixedDelay = 1000)
    public void tick() {
        if (!running.compareAndSet(false, true)) return;
        try { worker.execute(this::runOnce); }
        catch (RuntimeException ex) { running.set(false); throw ex; }
    }
    private void runOnce() {
        try {
            for (var candidate : mapper.scan(System.currentTimeMillis())) {
                TenantContext.set(candidate.tenantId());
                try { transactions.claim(candidate.resultId(), System.currentTimeMillis()).ifPresent(this::execute); }
                catch (RuntimeException ex) {
                    LOG.warn("审核阶段保存异常 resultId={} errorType={}", candidate.resultId(), ex.getClass().getSimpleName());
                } finally { TenantContext.clear(); }
            }
        } catch (RuntimeException ex) {
            LOG.warn("审核阶段扫描异常 errorType={}", ex.getClass().getSimpleName());
        } finally { running.set(false); }
    }
    private void execute(JoinTaskApprovalWork work) {
        JoinTaskApprovalStage next;
        try { next = protocol.execute(work); }
        catch (RuntimeException ex) {
            transactions.failed(work, protocol.failure(JoinTaskApprovalStage.of(work.approval().getStage()), ex),
                    protocol.uncertain(ex), System.currentTimeMillis());
            return;
        }
        // 保存失败后交给租约恢复，不能将协议已成功误当失败并再次发送。
        transactions.succeeded(work, next, System.currentTimeMillis());
    }
    /** 关闭应用时停止后台线程。 */
    @PreDestroy
    public void close() { worker.shutdownNow(); }
}
