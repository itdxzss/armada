package com.armada.account.service.impl;

import com.armada.account.recovery.ProxyFailedRecoveryCoordinator;
import com.armada.account.service.AccountStateChangedEvent;
import com.armada.account.service.AccountStateEventService;
import com.armada.platform.kafka.consumer.account.ProtocolAccountStateChangedEvent;
import com.armada.platform.kafka.consumer.account.ProtocolAccountStateChangedSink;
import com.armada.group.service.GroupMetadataSyncTaskService;
import com.armada.shared.tenant.TenantContext;
import org.springframework.stereotype.Service;

/**
 * 协议账号状态变更事件到 account 域服务的 adapter。
 *
 * <p>Kafka consumer 位于 platform.kafka,不直接依赖账号域实现细节。
 * 本 adapter 由 account 域实现 platform 定义的 sink 接口,把平台层事件转换为账号域入参。</p>
 */
@Service
public class AccountStateChangedSinkAdapter implements ProtocolAccountStateChangedSink {

    private final AccountStateEventService service;
    private final ProxyFailedRecoveryCoordinator recoveryCoordinator;
    private final GroupMetadataSyncTaskService metadataSyncTaskService;

    /**
     * 创建账号状态变更 adapter。
     *
     * @param service             账号状态事件落库服务
     * @param recoveryCoordinator 状态提交后的代理失败恢复编排器
     * @param metadataSyncTaskService 群详情同步任务服务
     */
    public AccountStateChangedSinkAdapter(AccountStateEventService service,
                                          ProxyFailedRecoveryCoordinator recoveryCoordinator,
                                          GroupMetadataSyncTaskService metadataSyncTaskService) {
        this.service = service;
        this.recoveryCoordinator = recoveryCoordinator;
        this.metadataSyncTaskService = metadataSyncTaskService;
    }

    /**
     * 处理协议账号状态变更事件。
     *
     * @param event platform.kafka 已解析的状态变更事件
     */
    @Override
    public void handleStateChanged(ProtocolAccountStateChangedEvent event) {
        boolean applied = service.applyStateChanged(new AccountStateChangedEvent(
                event.tenantId(),
                event.accountId(),
                event.protocolAccountId(),
                event.from(),
                event.to(),
                event.occurredAt(),
                event.semantic(),
                event.rawCode(),
                event.source(),
                event.onlineAttemptId()));
        if (applied && isProxyFailed(event)) {
            recoveryCoordinator.recover(
                    event.tenantId(), event.accountId(), event.onlineAttemptId(), event.proxyId());
        }
        if (applied && "ONLINE".equalsIgnoreCase(event.to())) {
            resumeDeferredMetadataTasks(event);
        }
    }

    private void resumeDeferredMetadataTasks(ProtocolAccountStateChangedEvent event) {
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(event.tenantId());
            metadataSyncTaskService.resumeDeferredForAccount(event.accountId(), event.occurredAt());
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }

    private static boolean isProxyFailed(ProtocolAccountStateChangedEvent event) {
        return "PROXY_FAILED".equalsIgnoreCase(event.to())
                || "PROXY_FAILED".equalsIgnoreCase(event.semantic());
    }
}
