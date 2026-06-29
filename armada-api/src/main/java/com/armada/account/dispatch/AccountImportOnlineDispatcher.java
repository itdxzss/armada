package com.armada.account.dispatch;

import com.armada.account.mapper.AccountImportDetailMapper;
import com.armada.account.model.entity.ImportResult;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 账号导入自动上线派发器。
 *
 * <p>本类只负责跨租户扫描和上下文恢复;单租户事务派发交给
 * {@link AccountImportOnlineDispatchWorker},避免 Spring self-invocation 导致事务失效。</p>
 */
@Service
public class AccountImportOnlineDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AccountImportOnlineDispatcher.class);

    /** 单轮最多扫描的租户数,防止一次 tick 长时间占用后台线程。 */
    private static final int TENANT_SCAN_LIMIT = 100;

    private final AccountImportDetailMapper detailMapper;
    private final AccountImportOnlineDispatchWorker worker;

    /**
     * 创建账号导入自动上线派发器。
     *
     * @param detailMapper 导入明细 mapper
     * @param worker       单租户事务派发 worker
     */
    public AccountImportOnlineDispatcher(AccountImportDetailMapper detailMapper,
                                         AccountImportOnlineDispatchWorker worker) {
        this.detailMapper = detailMapper;
        this.worker = worker;
    }

    /**
     * 扫描并派发一轮待上线导入明细。
     *
     * <p>单租户单轮最多派发 500 个账号,与账号批量上线和协议 outbox 批次上限保持一致。</p>
     *
     * @return 本轮成功推进到 DISPATCHED 的明细行数
     */
    public int dispatchOnce() {
        List<Long> tenantIds = detailMapper.selectQueuedTenantIds(
                AccountImportOnlineDispatchWorker.QUEUED_PHASE,
                ImportResult.SUCCESS.getCode(),
                TENANT_SCAN_LIMIT);
        if (tenantIds.isEmpty()) {
            return 0;
        }

        Long previousTenant = TenantContext.get();
        int dispatched = 0;
        try {
            for (Long tenantId : tenantIds) {
                TenantContext.set(tenantId);
                try {
                    dispatched += worker.dispatchTenantBatch(tenantId);
                } catch (RuntimeException ex) {
                    log.warn("账号导入自动上线租户派发失败,保留 QUEUED 等待重试 tenantId={}", tenantId, ex);
                }
            }
        } finally {
            restoreTenant(previousTenant);
        }
        log.info("账号导入自动上线派发完成 tenantCount={} dispatched={}", tenantIds.size(), dispatched);
        return dispatched;
    }

    private static void restoreTenant(Long previousTenant) {
        if (previousTenant == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenant);
        }
    }
}
