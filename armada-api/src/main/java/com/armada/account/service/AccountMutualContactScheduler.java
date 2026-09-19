package com.armada.account.service;
import com.armada.account.mapper.AccountMutualContactMapper;
import com.armada.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
/** 有界扫描数据库事实，重启后自然恢复；只投递命令，不阻塞等待协议。 */
@Component
@ConditionalOnProperty(prefix = "armada.mutual-contact", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AccountMutualContactScheduler {
    private static final Logger log = LoggerFactory.getLogger(AccountMutualContactScheduler.class);
    private static final int SCAN_LIMIT = 32;
    private static final long RESULT_TIMEOUT_MS = 300_000L;
    private final AccountMutualContactMapper mapper;
    private final AccountMutualContactTransactions transactions;
    public AccountMutualContactScheduler(
            AccountMutualContactMapper mapper, AccountMutualContactTransactions transactions) {
        this.mapper = mapper;
        this.transactions = transactions;
    }
    /** 0 秒间隔仍受有限批次调度与实际回执耗时约束。 */
    @Scheduled(fixedDelay = 500)
    public void tick() {
        Long previous = TenantContext.get();
        long now = System.currentTimeMillis();
        try {
            for (var w : mapper.expired(now - RESULT_TIMEOUT_MS, SCAN_LIMIT)) {
                TenantContext.set(w.tenantId());
                try {
                    transactions.expire(w.taskId(), now - RESULT_TIMEOUT_MS, now);
                } catch (RuntimeException ex) {
                    log.warn("互存超时收敛失败 taskId={}", w.taskId(), ex);
                }
            }
            for (var w : mapper.scan(SCAN_LIMIT, now)) {
                TenantContext.set(w.tenantId());
                try {
                    transactions.dispatch(w, now);
                } catch (RuntimeException ex) {
                    log.warn("互存派发失败 taskId={}", w.taskId(), ex);
                }
            }
        } finally {
            if (previous == null)
                TenantContext.clear();
            else
                TenantContext.set(previous);
        }
    }
}
