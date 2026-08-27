package com.armada.account.recovery;

import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.model.AccountProxyFailedRecoveryCandidate;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.service.AccountOnlineAttemptLogService;
import com.armada.account.service.AccountProxyFailureContext;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 周期扫描 durable OFFLINE/PROXY_FAILED 状态并持续触发 B/C 恢复。 */
@Service
public class ProxyFailedRecoveryDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ProxyFailedRecoveryDispatcher.class);
    private static final String STATE_PROXY_FAILED = "PROXY_FAILED";
    private static final int BATCH_SIZE = 1_000;
    private static final long IMMEDIATE_RECOVERY_GRACE_MS = 5_000L;

    private final AccountStateMapper stateMapper;
    private final AccountOnlineAttemptLogService attemptLogService;
    private final ProxyFailedRecoveryCoordinator coordinator;

    public ProxyFailedRecoveryDispatcher(AccountStateMapper stateMapper,
                                         AccountOnlineAttemptLogService attemptLogService,
                                         ProxyFailedRecoveryCoordinator coordinator) {
        this.stateMapper = stateMapper;
        this.attemptLogService = attemptLogService;
        this.coordinator = coordinator;
    }

    public int dispatchOnce() {
        return dispatchOnce(System.currentTimeMillis());
    }

    int dispatchOnce(long now) {
        long eligibleBefore = now - IMMEDIATE_RECOVERY_GRACE_MS;
        List<AccountProxyFailedRecoveryCandidate> candidates =
                stateMapper.selectProxyFailedRecoveryCandidates(
                        AccountLoginStateCode.OFFLINE,
                        STATE_PROXY_FAILED,
                        AccountLoginStateCode.OFFLINE,
                        eligibleBefore,
                        BATCH_SIZE);
        Long previousTenant = TenantContext.get();
        int attempted = 0;
        try {
            for (AccountProxyFailedRecoveryCandidate candidate : candidates) {
                if (candidate.ownerUserId() == null) {
                    log.error("账号代理失败补偿拒绝执行:账号缺少数据归属 tenantId={} accountId={}",
                            candidate.tenantId(), candidate.accountId());
                    continue;
                }
                TenantContext.set(candidate.tenantId());
                attempted++;
                try (DataScopeContext.Scope ignored = DataScopeContext.open(
                        DataScope.self(candidate.ownerUserId()))) {
                    AccountProxyFailureContext failureContext = latestFailureContext(candidate.accountId());
                    String failedAttemptId = failureContext == null ? null : failureContext.onlineAttemptId();
                    Long failedProxyId = failureContext == null ? null : failureContext.proxyId();
                    coordinator.recover(
                            candidate.tenantId(), candidate.accountId(), failedAttemptId, failedProxyId);
                } catch (RuntimeException ex) {
                    log.warn("账号代理失败单账号补偿异常,保留状态等待下一轮 tenantId={} accountId={}",
                            candidate.tenantId(), candidate.accountId(), ex);
                }
            }
        } finally {
            restoreTenant(previousTenant);
        }
        if (attempted > 0) {
            log.info("账号代理失败持续补偿完成 candidateCount={} attempted={}", candidates.size(), attempted);
        }
        return attempted;
    }

    private AccountProxyFailureContext latestFailureContext(Long accountId) {
        try {
            return attemptLogService.latestProxyFailure(accountId);
        } catch (RuntimeException ex) {
            log.warn("账号代理失败补偿读取诊断上下文失败,本轮仍尝试重上线 accountId={}", accountId, ex);
            return null;
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
