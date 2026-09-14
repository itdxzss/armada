package com.armada.account.recovery;

import com.armada.account.service.AccountOnlineCommandService;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.resource.service.IpProxyService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 在账号状态事务提交后编排 PROXY_FAILED 的 B/C 两个独立事务。
 *
 * <p>本类故意不启事务。B 精确标记失败代理不可用并解绑，C 条件抢占恢复资格并换 IP 写 outbox；
 * B 未完成时不进入 C，避免把尚未隔离的失败代理释放回空闲池。
 * 失败只记录，不能反向回滚已提交的状态，也不能把有效 Kafka 状态事件送入 DLT。</p>
 */
@Service
public class ProxyFailedRecoveryCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ProxyFailedRecoveryCoordinator.class);

    private final IpProxyService ipProxyService;
    private final AccountOnlineCommandService onlineCommandService;
    private final ProtocolCommandOutboxService outboxService;

    public ProxyFailedRecoveryCoordinator(IpProxyService ipProxyService,
                                          AccountOnlineCommandService onlineCommandService,
                                          ProtocolCommandOutboxService outboxService) {
        this.ipProxyService = ipProxyService;
        this.onlineCommandService = onlineCommandService;
        this.outboxService = outboxService;
    }

    /**
     * 尝试完成一次代理失败恢复；失败状态由 account_state 保留，后台调度会继续调用本方法。
     */
    public void recover(Long tenantId,
                        Long accountId,
                        String failedOnlineAttemptId,
                        Long failedProxyId,
                        Long failedAt) {
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(tenantId);
            if (failedAt == null) {
                log.warn("账号代理失败恢复缺少失败时间水位 accountId={}", accountId);
                return;
            }
            Optional<Long> resolvedProxyId = resolveFailedProxyId(accountId, failedOnlineAttemptId, failedProxyId);
            if (resolvedProxyId.isEmpty()) {
                log.warn("账号代理失败恢复等待原始代理上下文 accountId={} attemptId={}",
                        accountId, failedOnlineAttemptId);
                return;
            }
            if (markFailedProxyUnavailable(accountId, resolvedProxyId.get(), failedAt)) {
                reonline(accountId, failedOnlineAttemptId, resolvedProxyId.get(), failedAt);
            }
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private Optional<Long> resolveFailedProxyId(Long accountId, String attemptId, Long failedProxyId) {
        if (failedProxyId != null && failedProxyId > 0) {
            return Optional.of(failedProxyId);
        }
        try {
            return outboxService.findOnlineAttemptProxyId(accountId, attemptId);
        } catch (RuntimeException ex) {
            log.warn("账号代理失败追溯原始代理异常,保留状态等待补偿 accountId={} attemptId={}",
                    accountId, attemptId, ex);
            return Optional.empty();
        }
    }

    private boolean markFailedProxyUnavailable(Long accountId, Long failedProxyId, long failedAt) {
        try {
            return ipProxyService.markFailedProxyUnavailable(accountId, failedProxyId, failedAt);
        } catch (RuntimeException ex) {
            log.error("账号代理失败标记不可用异常,保留状态等待补偿 accountId={} failedProxyId={}",
                    accountId, failedProxyId, ex);
            return false;
        }
    }

    private void reonline(Long accountId, String failedOnlineAttemptId, Long failedProxyId, long failedAt) {
        try {
            onlineCommandService.reonlineAfterProxyFailure(
                    accountId, failedOnlineAttemptId, failedProxyId, failedAt);
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
