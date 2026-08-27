package com.armada.account.recovery;

import com.armada.account.service.AccountOnlineCommandService;
import com.armada.resource.service.IpProxyService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeAccess;
import com.armada.shared.security.DataScopeMode;
import com.armada.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 在账号状态事务提交后编排 PROXY_FAILED 的 B/C 两个独立事务。
 *
 * <p>本类故意不启事务。B 精确标记失败代理不可用并解绑，C 条件抢占恢复资格并换 IP 写 outbox；
 * 两步任一失败都只记录，不能反向回滚已提交的状态，也不能把有效 Kafka 状态事件送入 DLT。</p>
 */
@Service
public class ProxyFailedRecoveryCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ProxyFailedRecoveryCoordinator.class);

    private final IpProxyService ipProxyService;
    private final AccountOnlineCommandService onlineCommandService;

    public ProxyFailedRecoveryCoordinator(IpProxyService ipProxyService,
                                          AccountOnlineCommandService onlineCommandService) {
        this.ipProxyService = ipProxyService;
        this.onlineCommandService = onlineCommandService;
    }

    /**
     * 尝试完成一次代理失败恢复；失败状态由 account_state 保留，后台调度会继续调用本方法。
     */
    public void recover(Long tenantId,
                        Long accountId,
                        String failedOnlineAttemptId,
                        Long failedProxyId) {
        DataScope scope = DataScopeAccess.requireCurrent();
        if (scope.mode() == DataScopeMode.SYSTEM) {
            throw new BusinessException(
                    ErrorCode.ACCESS_DENIED, "后台范围不能直接恢复用户私有账号");
        }
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(tenantId);
            markFailedProxyUnavailable(accountId, failedProxyId);
            reonline(accountId, failedOnlineAttemptId, failedProxyId);
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private void markFailedProxyUnavailable(Long accountId, Long failedProxyId) {
        if (failedProxyId == null) {
            log.warn("账号代理失败标记不可用跳过,事件未携带 proxyId accountId={}", accountId);
            return;
        }
        try {
            ipProxyService.markFailedProxyUnavailable(accountId, failedProxyId);
        } catch (RuntimeException ex) {
            log.error("账号代理失败标记不可用异常,保留状态等待补偿 accountId={} failedProxyId={}",
                    accountId, failedProxyId, ex);
        }
    }

    private void reonline(Long accountId, String failedOnlineAttemptId, Long failedProxyId) {
        try {
            onlineCommandService.reonlineAfterProxyFailure(
                    accountId, failedOnlineAttemptId, failedProxyId);
        } catch (RuntimeException ex) {
            if (ex instanceof BusinessException) {
                log.warn("账号代理失败换IP重上线未完成,保留 PROXY_FAILED 等待补偿 accountId={} "
                                + "failedProxyId={} reason={}",
                        accountId, failedProxyId, ex.getMessage());
            } else {
                log.error("账号代理失败换IP重上线异常,保留 PROXY_FAILED 等待补偿 accountId={} failedProxyId={}",
                        accountId, failedProxyId, ex);
            }
        }
    }

    private static void restoreTenant(Long previousTenant) {
        if (previousTenant == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenant);
        }
    }
}
