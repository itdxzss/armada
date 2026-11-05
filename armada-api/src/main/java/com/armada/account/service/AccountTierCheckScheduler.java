package com.armada.account.service;

import com.armada.account.mapper.AccountTierCheckMapper;
import com.armada.account.model.dto.AccountTierCheckClaim;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.result.ProtocolAccountIntegrity;
import com.armada.platform.protocol.port.AccountIntegrityPort;
import com.armada.shared.tenant.TenantContext;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 默认关闭；tick 只认领，网络调用在有界线程池中且每个工作线程恢复租户上下文。 */
@Component
@ConditionalOnProperty(prefix = "armada.tier-check", name = "enabled", havingValue = "true", matchIfMissing = false)
public class AccountTierCheckScheduler {
    private static final Logger log = LoggerFactory.getLogger(AccountTierCheckScheduler.class);
    private final AccountTierCheckMapper mapper;
    private final AccountTierCheckTransactions transactions;
    private final AccountIntegrityPort port;
    private final AccountTierCheckProperties config;
    private final ThreadPoolExecutor workers;
    private final Semaphore slots;
    /** 有界线程池/配额只限制同时执行量，不把业务事实放进内存。 */
    public AccountTierCheckScheduler(AccountTierCheckMapper mapper, AccountTierCheckTransactions transactions,
            AccountIntegrityPort port, AccountTierCheckProperties config) {
        this.mapper = mapper; this.transactions = transactions; this.port = port; this.config = config;
        slots = new Semaphore(config.getPoolSize());
        var sequence = new AtomicInteger();
        workers = new ThreadPoolExecutor(config.getPoolSize(), config.getPoolSize(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.getPoolSize()), job -> {
                    var thread = new Thread(job, "tier-check-query-" + sequence.incrementAndGet());
                    thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }
    /** 同步 HTTP 不占用 Scheduled 线程；满载时不再认领新条目。 */
    @Scheduled(fixedDelayString = "${armada.tier-check.tick-ms:500}")
    public void tick() {
        if (!config.isEnabled()) return;
        Long previous = TenantContext.get();
        long now = System.currentTimeMillis();
        try {
            for (var work : mapper.recoverable(now, config.getScanLimit())) {
                TenantContext.set(work.tenantId());
                try { transactions.recover(work, now); }
                catch (RuntimeException ex) { log.warn("档位认领恢复失败 itemId={} type={}", work.itemId(), ex.getClass().getSimpleName()); }
            }
            for (var work : mapper.scan(now, config.getScanLimit())) {
                if (!slots.tryAcquire()) break;
                TenantContext.set(work.tenantId());
                boolean submitted = false;
                try {
                    var claim = transactions.claim(work, now);
                    if (claim.isPresent()) { workers.execute(() -> run(claim.get())); submitted = true; }
                } catch (RuntimeException ex) {
                    // 拒绝入池的认领由租约回收，不在 tick 退化成 CallerRunsPolicy。
                    log.warn("档位认领失败 itemId={} type={}", work.itemId(), ex.getClass().getSimpleName());
                } finally { if (!submitted) slots.release(); }
            }
        } finally { restore(previous); }
    }
    private void run(AccountTierCheckClaim claim) {
        Long previous = TenantContext.get();
        TenantContext.set(claim.work().tenantId());
        try {
            ProtocolAccountIntegrity result;
            try { result = config.isEnabled() ? port.query(claim.executor(), claim.phone()) : ProtocolAccountIntegrity.failure("FEATURE_DISABLED"); }
            catch (ProtocolException ex) { result = ProtocolAccountIntegrity.failure(ex.errorCode().name()); }
            catch (RuntimeException ex) { result = ProtocolAccountIntegrity.failure("PROTOCOL_ERROR"); }
            transactions.apply(claim, result, System.currentTimeMillis());
        } catch (RuntimeException ex) {
            log.warn("档位结果结算失败 itemId={} type={}", claim.work().itemId(), ex.getClass().getSimpleName());
        } finally { restore(previous); slots.release(); }
    }
    private static void restore(Long previous) {
        if (previous == null) TenantContext.clear(); else TenantContext.set(previous);
    }
    /** 停机停止接新工作，未完成数据库认领由下一进程按租约恢复。 */
    @PreDestroy public void close() { workers.shutdownNow(); }
}
