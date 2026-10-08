package com.armada.account.job;

import com.armada.account.takeover.AccountAutoTakeoverDispatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Kafka 部署中定时补偿被抢登和抢登中未恢复的离线账号。 */
@Service
@Profile("kafka")
@ConditionalOnProperty(prefix = "armada.account.auto-takeover.scan", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class AccountAutoTakeoverScheduler {

    private final AccountAutoTakeoverDispatcher dispatcher;

    /** @param dispatcher 有界的跨租户自动抢登扫描器 */
    public AccountAutoTakeoverScheduler(AccountAutoTakeoverDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /** 按配置间隔发起一轮补偿，总开关由扫描器复核。 */
    @Scheduled(fixedDelayString = "${armada.account.auto-takeover.scan.fixed-delay-ms:60000}")
    public void tick() {
        dispatcher.dispatchOnce(System.currentTimeMillis());
    }
}
