package com.armada.task.scheduler;

import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.JoinTaskCleanupMapper;
import com.armada.task.model.dto.JoinTaskCleanupContext;
import com.armada.task.model.dto.JoinTaskCleanupWork;
import com.armada.task.service.JoinTaskCleanupProtocol;
import com.armada.task.service.JoinTaskCleanupTransactions;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 独立线程按持久化阶段执行清理，外部动作不占用 Spring 调度线程或数据库事务。 */
@Component
@ConditionalOnProperty(prefix = "armada.join-task-dispatch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JoinTaskCleanupScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(JoinTaskCleanupScheduler.class);
    private final JoinTaskCleanupMapper mapper;
    private final JoinTaskCleanupTransactions transactions;
    private final JoinTaskCleanupProtocol protocol;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "join-task-cleanup"); thread.setDaemon(true); return thread;
    });

    /** 装配单步领取、协议执行与结果保存。 */
    public JoinTaskCleanupScheduler(JoinTaskCleanupMapper mapper, JoinTaskCleanupTransactions transactions,
            JoinTaskCleanupProtocol protocol) {
        this.mapper = mapper; this.transactions = transactions; this.protocol = protocol;
    }
    /** 当前扫描未结束时不重复排队。 */
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
                try {
                    transactions.claim(candidate.resultId(), System.currentTimeMillis()).ifPresent(this::execute);
                } catch (DuplicateKeyException busy) {
                    LOG.debug("同群清理已被占用 resultId={}", candidate.resultId());
                } catch (RuntimeException ex) {
                    LOG.warn("清理阶段保存异常 resultId={} errorType={}", candidate.resultId(), ex.getClass().getSimpleName());
                } finally { TenantContext.clear(); }
            }
        } catch (RuntimeException ex) {
            LOG.warn("清理扫描失败 errorType={}", ex.getClass().getSimpleName());
        } finally { running.set(false); }
    }
    private void execute(JoinTaskCleanupWork work) {
        JoinTaskCleanupContext outcome;
        try { outcome = protocol.execute(work); }
        catch (RuntimeException ex) {
            transactions.failed(work, protocol.failureReason(ex), System.currentTimeMillis());
            return;
        }
        // 结果落库失败不能再次调用协议；在途状态到期后按结果不明停止。
        transactions.succeeded(work, outcome, System.currentTimeMillis());
    }
    /** 应用退出时停止清理线程。 */
    @PreDestroy
    public void close() { worker.shutdownNow(); }
}
