package com.armada.account.job;

import com.armada.account.service.impl.AccountRegistrationWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 显式启用且Kafka可用时推进任务；普通应用启动不会购买或注册。 */
@Component
@Profile("kafka")
@ConditionalOnProperty(prefix = "armada.account.registration.scheduler", name = "enabled", havingValue = "true")
public class AccountRegistrationScheduler {
    /** 注册工作流。 */
    private final AccountRegistrationWorker worker;
    /** 装配工作流服务。 */
    public AccountRegistrationScheduler(AccountRegistrationWorker worker) { this.worker = worker; }
    /** 以固定延迟逐步推进，实际互斥同时由Redis和数据库租约保证。 */
    @Scheduled(fixedDelayString = "${armada.account.registration.scheduler.fixed-delay-ms:5000}")
    public void tick() { worker.tick(); }
}
