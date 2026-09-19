package com.armada.task.scheduler;

import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.JoinTaskAdminMapper;
import com.armada.task.service.JoinTaskAdminFacts;
import com.armada.task.service.JoinTaskAdminTransactions;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 独立线程推进管理员阶段，避免成员查询阻塞 Spring 公共调度线程。 */
@Component
@ConditionalOnProperty(prefix = "armada.join-task-dispatch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JoinTaskAdminScheduler {
    private static final Logger log = LoggerFactory.getLogger(JoinTaskAdminScheduler.class);
    private final JoinTaskAdminMapper admins;
    private final JoinTaskAdminTransactions transactions;
    private final JoinTaskAdminFacts facts;
    private final long timeoutMs;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "join-task-admin"); thread.setDaemon(true); return thread;
    });

    /** 管理员缺失或结果不明默认最多核实五分钟，可由部署配置调整。 */
    public JoinTaskAdminScheduler(JoinTaskAdminMapper admins, JoinTaskAdminTransactions transactions,
            JoinTaskAdminFacts facts, @Value("${armada.join-task-admin.timeout-ms:300000}") long timeoutMs) {
        this.admins = admins; this.transactions = transactions; this.facts = facts;
        this.timeoutMs = Math.max(30_000L, timeoutMs);
    }
    /** 投递一轮有界扫描；当前轮未结束不重复排队。 */
    @Scheduled(fixedDelay = 1000)
    public void tick() {
        if (!running.compareAndSet(false, true)) return;
        try { worker.execute(this::runOnce); }
        catch (RuntimeException ex) { running.set(false); throw ex; }
    }
    private void runOnce() {
        try {
            for (var candidate : admins.scan(System.currentTimeMillis(), 20)) {
                TenantContext.set(candidate.tenantId());
                try {
                    transactions.claim(candidate.resultId(), System.currentTimeMillis(), timeoutMs)
                             .ifPresent(work -> {
                                com.armada.task.model.dto.JoinTaskAdminObservation observation;
                                try { observation = facts.inspect(work); }
                                catch (RuntimeException ex) {
                                    log.warn("进群管理员查询未确认 resultId={} errorType={}",
                                            candidate.resultId(), ex.getClass().getSimpleName());
                                    observation = new com.armada.task.model.dto.JoinTaskAdminObservation(
                                            com.armada.task.model.dto.JoinTaskAdminObservation.Kind.WAIT,
                                            null, "群成员查询异常，等待重查");
                                }
                                transactions.observe(work, observation, System.currentTimeMillis());
                            });
                } catch (RuntimeException ex) {
                    log.warn("进群管理员阶段处理失败 resultId={}", candidate.resultId(), ex);
                } finally { TenantContext.clear(); }
            }
        } finally { running.set(false); }
    }
    /** 关闭查询工作线程。 */
    @PreDestroy
    public void close() { worker.shutdownNow(); }
}
