package com.armada.admin.service;

import com.armada.account.service.AccountExportService;
import com.armada.admin.mapper.AccountExportMaintenanceMapper;
import com.armada.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 到期凭据产物自动销毁，浏览器关闭也不会永久保留导出预占。 */
@Component
public class AccountExportExpiryScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(AccountExportExpiryScheduler.class);
    private final AccountExportMaintenanceMapper mapper;
    private final AccountExportService exports;

    /** 维护通过账号 Service 执行，不跨业务域直接修改账号表。 */
    public AccountExportExpiryScheduler(AccountExportMaintenanceMapper mapper, AccountExportService exports) {
        this.mapper = mapper;
        this.exports = exports;
    }

    /** 每分钟处理至多 100 个到期文件；数据库事务保证多实例重复扫描幂等。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void expire() {
        Long previous = TenantContext.get();
        try {
            for (var candidate : mapper.expired(System.currentTimeMillis())) {
                TenantContext.set(candidate.tenantId());
                try {
                    exports.expire(candidate.id());
                } catch (RuntimeException ex) {
                    LOG.warn("账号导出到期清理失败 jobId={}", candidate.id(), ex);
                }
            }
        } finally {
            if (previous == null) TenantContext.clear(); else TenantContext.set(previous);
        }
    }
}
