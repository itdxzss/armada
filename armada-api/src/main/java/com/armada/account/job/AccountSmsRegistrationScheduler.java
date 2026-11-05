package com.armada.account.job;

import com.armada.account.service.impl.SmsRegistrationWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 显式启用后才推进接码订单的 Go 注册；默认关闭，普通启动不会访问 Go 或号商。 */
@Component
@ConditionalOnProperty(prefix = "armada.account.sms-registration", name = "enabled", havingValue = "true")
public class AccountSmsRegistrationScheduler {
    /** 注册推进。 */
    private final SmsRegistrationWorker worker;

    /** 装配推进器。 */
    public AccountSmsRegistrationScheduler(SmsRegistrationWorker worker) { this.worker = worker; }

    /**
     * 固定延迟：上一轮（含其中全部并发步骤）结束后再等待间隔，因此同一条记录不会被两轮同时处理，不需要锁。
     */
    @Scheduled(fixedDelayString = "${armada.account.sms-registration.fixed-delay-ms:5000}")
    public void tick() { worker.tick(); }
}
