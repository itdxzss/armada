package com.armada.account.state;

import com.armada.account.model.entity.Account;
import com.armada.account.service.AccountOnlineAttemptLogService;
import com.armada.account.service.AccountStateChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 在状态事务内保留代理失败上下文，支持重启或资源不足后的定时补偿。 */
@Component
public class ProxyFailureContextSideEffect implements AccountStateChangedSideEffect {

    private static final Logger log = LoggerFactory.getLogger(ProxyFailureContextSideEffect.class);
    private final AccountOnlineAttemptLogService attemptLogService;

    public ProxyFailureContextSideEffect(AccountOnlineAttemptLogService attemptLogService) {
        this.attemptLogService = attemptLogService;
    }

    @Override
    public void afterStateChanged(Account account, AccountStateChangedEvent event, long occurredAt) {
        if (!"PROXY_FAILED".equalsIgnoreCase(event.to())
                && !"PROXY_FAILED".equalsIgnoreCase(event.semantic())) {
            return;
        }
        if (event.onlineAttemptId() == null || event.onlineAttemptId().isBlank()) {
            log.warn("代理失败事件缺少上线尝试 ID,无法保存恢复上下文 accountId={} occurredAt={}",
                    account.getId(), occurredAt);
            return;
        }
        attemptLogService.recordProxyFailure(event, occurredAt);
    }
}
