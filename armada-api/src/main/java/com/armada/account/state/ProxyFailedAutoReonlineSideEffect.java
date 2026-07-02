package com.armada.account.state;

import com.armada.account.model.entity.Account;
import com.armada.account.service.AccountOnlineCommandService;
import com.armada.account.service.AccountStateChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * PROXY_FAILED 后自动重新分配 IP 并投递上线命令。
 */
@Component
public class ProxyFailedAutoReonlineSideEffect implements AccountStateChangedSideEffect {

    private static final Logger log = LoggerFactory.getLogger(ProxyFailedAutoReonlineSideEffect.class);
    private static final String STATE_PROXY_FAILED = "PROXY_FAILED";

    private final AccountOnlineCommandService onlineCommandService;

    public ProxyFailedAutoReonlineSideEffect(AccountOnlineCommandService onlineCommandService) {
        this.onlineCommandService = onlineCommandService;
    }

    @Override
    public void afterStateChanged(Account account, AccountStateChangedEvent event, long occurredAt) {
        if (!isProxyFailed(event)) {
            return;
        }
        log.info("账号代理失败自动重上线开始 accountId={} protocolAccountId={} occurredAt={}",
                account.getId(), account.getProtocolAccountId(), occurredAt);
        onlineCommandService.reonlineAfterProxyFailure(account.getId());
    }

    private static boolean isProxyFailed(AccountStateChangedEvent event) {
        return STATE_PROXY_FAILED.equalsIgnoreCase(event.to())
                || STATE_PROXY_FAILED.equalsIgnoreCase(event.semantic());
    }
}
