package com.armada.account.job;

import com.armada.account.recovery.ProxyFailedRecoveryDispatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 在 Kafka 部署中持续补偿 OFFLINE/PROXY_FAILED 账号，直至状态变化或上线命令成功入队。 */
@Service
@Profile("kafka")
@ConditionalOnProperty(
        prefix = "armada.account.proxy-failed-recovery.scheduler",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ProxyFailedRecoveryScheduler {

    private final ProxyFailedRecoveryDispatcher dispatcher;

    public ProxyFailedRecoveryScheduler(ProxyFailedRecoveryDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${armada.account.proxy-failed-recovery.scheduler.fixed-delay-ms:5000}")
    public void tick() {
        dispatcher.dispatchOnce();
    }
}
