package com.armada.account.service.impl;

import com.armada.account.recovery.ProxyFailedRecoveryCoordinator;
import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.service.AccountStateChangedEvent;
import com.armada.account.service.AccountStateEventService;
import com.armada.platform.kafka.consumer.account.ProtocolAccountStateChangedEvent;
import com.armada.platform.kafka.consumer.account.ProtocolAccountStateChangedSink;
import com.armada.group.service.GroupMetadataSyncTaskService;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import com.armada.shared.tenant.TenantContext;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 协议账号状态变更事件到 account 域服务的 adapter。
 *
 * <p>Kafka consumer 位于 platform.kafka,不直接依赖账号域实现细节。
 * 本 adapter 由 account 域实现 platform 定义的 sink 接口,把平台层事件转换为账号域入参。</p>
 *
 * <p>账号状态落库是主链；ONLINE 后恢复缺失邀请码的任务会投递到独立线程池，
 * 附属动作缓慢或失败都不得阻塞 Kafka 账号状态消费。</p>
 */
@Service
public class AccountStateChangedSinkAdapter implements ProtocolAccountStateChangedSink {

    private static final Logger log = LoggerFactory.getLogger(AccountStateChangedSinkAdapter.class);

    private final AccountStateEventService service;
    private final AccountMapper accountMapper;
    private final ProxyFailedRecoveryCoordinator recoveryCoordinator;
    private final GroupMetadataSyncTaskService metadataSyncTaskService;
    private final Executor inviteRecoveryExecutor;

    /**
     * 创建账号状态变更 adapter。
     *
     * @param service             账号状态事件落库服务
     * @param recoveryCoordinator 状态提交后的代理失败恢复编排器
     * @param metadataSyncTaskService 群详情同步任务服务
     * @param inviteRecoveryExecutor 群邀请码恢复后台执行器
     */
    public AccountStateChangedSinkAdapter(AccountStateEventService service,
                                          AccountMapper accountMapper,
                                          ProxyFailedRecoveryCoordinator recoveryCoordinator,
                                          GroupMetadataSyncTaskService metadataSyncTaskService,
                                          @Qualifier("accountStateInviteRecoveryExecutor")
                                          Executor inviteRecoveryExecutor) {
        this.service = service;
        this.accountMapper = accountMapper;
        this.recoveryCoordinator = recoveryCoordinator;
        this.metadataSyncTaskService = metadataSyncTaskService;
        this.inviteRecoveryExecutor = inviteRecoveryExecutor;
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
        if (applied && (isProxyFailed(event) || "ONLINE".equalsIgnoreCase(event.to()))) {
            Account account = trustedAccount(event);
            if (account == null || account.getOwnerUserId() == null) {
                log.error("账号状态附属动作拒绝执行:账号缺少可信归属 tenantId={} accountId={}",
                        event.tenantId(), event.accountId());
                return;
            }
            if (isProxyFailed(event)) {
                try (DataScopeContext.Scope ignored = DataScopeContext.open(
                        DataScope.self(account.getOwnerUserId()))) {
                    recoveryCoordinator.recover(
                            event.tenantId(), event.accountId(), event.onlineAttemptId(), event.proxyId());
                }
            }
            if ("ONLINE".equalsIgnoreCase(event.to())) {
                submitDeferredInviteResume(event, account.getOwnerUserId());
            }
        }
    }

    private Account trustedAccount(ProtocolAccountStateChangedEvent event) {
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(event.tenantId());
            Account account = accountMapper.selectActiveById(event.accountId());
            if (account == null
                    || event.protocolAccountId() == null
                    || !event.protocolAccountId().equals(account.getProtocolAccountId())) {
                return null;
            }
            return account;
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private void submitDeferredInviteResume(
            ProtocolAccountStateChangedEvent event,
            Long ownerUserId) {
        try {
            inviteRecoveryExecutor.execute(() -> resumeDeferredInviteTasks(event, ownerUserId));
        } catch (RuntimeException ex) {
            // 后台队列饱和或停机时仅记录；不能让附属任务反向触发账号状态事件重试。
            log.warn("账号上线后群邀请码恢复任务投递失败,账号状态事件继续完成 tenantId={} accountId={} "
                            + "occurredAt={} errorType={}",
                    event.tenantId(), event.accountId(), event.occurredAt(),
                    ex.getClass().getSimpleName(), ex);
        }
    }

    private void resumeDeferredInviteTasks(
            ProtocolAccountStateChangedEvent event,
            Long ownerUserId) {
        Long previousTenant = TenantContext.get();
        try (DataScopeContext.Scope ignored = DataScopeContext.open(
                DataScope.self(ownerUserId))) {
            TenantContext.set(event.tenantId());
            metadataSyncTaskService.resumeDeferredInviteCodeForAccount(
                    event.accountId(), event.occurredAt());
        } catch (RuntimeException ex) {
            // 群详情同步是 ONLINE 后的补充任务，失败不能反向阻塞关键账号状态 Kafka 分区。
            log.warn("账号上线后恢复群邀请码任务失败,账号状态事件继续完成 tenantId={} accountId={} "
                            + "occurredAt={} errorType={}",
                    event.tenantId(), event.accountId(), event.occurredAt(),
                    ex.getClass().getSimpleName(), ex);
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private static void restoreTenant(Long previousTenant) {
        if (previousTenant == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenant);
        }
    }

    private static boolean isProxyFailed(ProtocolAccountStateChangedEvent event) {
        return "PROXY_FAILED".equalsIgnoreCase(event.to())
                || "PROXY_FAILED".equalsIgnoreCase(event.semantic());
    }
}
