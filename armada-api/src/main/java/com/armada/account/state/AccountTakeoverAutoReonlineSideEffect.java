package com.armada.account.state;

import com.armada.account.model.entity.Account;
import com.armada.account.service.AccountOnlineCommandService;
import com.armada.account.service.AccountStateChangedEvent;
import com.armada.account.takeover.AccountAutoTakeoverProperties;
import com.armada.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 抢登中账号状态变化后的自动续上线副作用。
 *
 * <p>状态收敛服务只负责把账号落成“抢登中/被抢登”等事实状态。
 * 是否仍可续上线由 {@link AccountOnlineCommandService#reonlineForTakeover(Long, String, String)}
 * 再次读取账号状态、禁言状态和冷却窗口后决定。自动抢登开关开启时，在状态事件提交后
 * 使用独立事务续上线，代理分配或 outbox 失败不能回滚已经收敛的账号状态。</p>
 */
@Component
public class AccountTakeoverAutoReonlineSideEffect implements AccountStateChangedSideEffect {

    private static final Logger log = LoggerFactory.getLogger(AccountTakeoverAutoReonlineSideEffect.class);
    private static final int WA_CODE_LOGIN_REPLACED = 440;
    private static final String STATE_LOGIN_REPLACED = "LOGIN_REPLACED";
    private static final String STATE_OFFLINE = "OFFLINE";
    private static final String STATE_RATE_LIMITED = "RATE_LIMITED";
    private static final String STATE_PROXY_FAILED = "PROXY_FAILED";
    private static final String SOURCE_BATCH_OFFLINE = "batch_offline";
    private static final String SOURCE_MANUAL_OFFLINE = "manual_offline";
    private static final String SOURCE_LOGIN_REPLACED_TAKEOVER = "login_replaced_takeover";
    private static final String SOURCE_OFFLINE_TAKEOVER = "offline_takeover";
    private static final String SOURCE_RATE_LIMITED_TAKEOVER = "rate_limited_takeover";

    private final AccountOnlineCommandService onlineCommandService;
    /** 自动抢登开关，关闭时保留原同步续上线链路。 */
    private final AccountAutoTakeoverProperties properties;
    /** 提交后续上线使用新事务，避免复用尚未解绑的原事务连接。 */
    private final TransactionTemplate recoveryTransactions;

    /**
     * 创建抢登续上线副作用。
     *
     * @param onlineCommandService 账号上线命令服务
     * @param properties 自动抢登配置
     * @param transactionManager 续上线独立事务管理器
     */
    public AccountTakeoverAutoReonlineSideEffect(AccountOnlineCommandService onlineCommandService,
                                                 AccountAutoTakeoverProperties properties,
                                                 PlatformTransactionManager transactionManager) {
        this.onlineCommandService = onlineCommandService;
        this.properties = properties;
        this.recoveryTransactions = new TransactionTemplate(transactionManager);
        this.recoveryTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void afterStateChanged(Account account, AccountStateChangedEvent event, long occurredAt) {
        String source = takeoverSource(event);
        if (source == null) {
            return;
        }
        if (properties.isEnabled()) {
            reonlineAfterCommit(account.getId(), event, occurredAt);
            return;
        }
        log.info("抢登中账号自动续上线检查 accountId={} protocolAccountId={} source={} failedAttemptId={} occurredAt={}",
                account.getId(), account.getProtocolAccountId(), source, event.onlineAttemptId(), occurredAt);
        onlineCommandService.reonlineForTakeover(account.getId(), event.onlineAttemptId(), source);
    }

    private void reonlineAfterCommit(Long accountId, AccountStateChangedEvent event, long occurredAt) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    reonlineInNewTransaction(accountId, event, occurredAt);
                }
            });
            return;
        }
        reonlineInNewTransaction(accountId, event, occurredAt);
    }

    private void reonlineInNewTransaction(Long accountId, AccountStateChangedEvent event, long occurredAt) {
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(event.tenantId());
            recoveryTransactions.executeWithoutResult(status -> onlineCommandService.reonlineForTakeover(
                    accountId, event.onlineAttemptId(), SOURCE_LOGIN_REPLACED_TAKEOVER));
        } catch (RuntimeException ex) {
            log.warn("账号自动抢登续上线未完成,保留已提交状态等待补偿 accountId={} occurredAt={}",
                    accountId, occurredAt, ex);
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }

    private static String takeoverSource(AccountStateChangedEvent event) {
        if (isUserStop(event) || isProxyFailed(event)) {
            return null;
        }
        if (isLoginReplaced(event)) {
            return SOURCE_LOGIN_REPLACED_TAKEOVER;
        }
        if (STATE_RATE_LIMITED.equalsIgnoreCase(event.to()) || STATE_RATE_LIMITED.equalsIgnoreCase(event.semantic())) {
            return SOURCE_RATE_LIMITED_TAKEOVER;
        }
        if (STATE_OFFLINE.equalsIgnoreCase(event.to())) {
            return SOURCE_OFFLINE_TAKEOVER;
        }
        return null;
    }

    private static boolean isLoginReplaced(AccountStateChangedEvent event) {
        return STATE_LOGIN_REPLACED.equalsIgnoreCase(event.to())
                || STATE_LOGIN_REPLACED.equalsIgnoreCase(event.semantic())
                || (event.rawCode() != null && event.rawCode() == WA_CODE_LOGIN_REPLACED);
    }

    private static boolean isUserStop(AccountStateChangedEvent event) {
        return STATE_OFFLINE.equalsIgnoreCase(event.to())
                && (SOURCE_BATCH_OFFLINE.equalsIgnoreCase(event.source())
                || SOURCE_MANUAL_OFFLINE.equalsIgnoreCase(event.source()));
    }

    private static boolean isProxyFailed(AccountStateChangedEvent event) {
        return STATE_PROXY_FAILED.equalsIgnoreCase(event.to())
                || STATE_PROXY_FAILED.equalsIgnoreCase(event.semantic());
    }
}
