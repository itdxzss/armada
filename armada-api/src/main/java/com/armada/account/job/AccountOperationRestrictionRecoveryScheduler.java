package com.armada.account.job;

import com.armada.account.service.AccountOperationRestrictionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 每 10 分钟恢复已到期的账号消息发送/拉人操作限制。 */
@Service
@Profile("kafka")
@ConditionalOnProperty(
        prefix = "armada.account.operation-restriction-recovery.scheduler",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AccountOperationRestrictionRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(
            AccountOperationRestrictionRecoveryScheduler.class);

    private final AccountOperationRestrictionService restrictionService;

    /** @param restrictionService 账号操作限制统一服务 */
    public AccountOperationRestrictionRecoveryScheduler(
            AccountOperationRestrictionService restrictionService) {
        this.restrictionService = restrictionService;
    }

    /** 扫描数据库到期状态；不调用协议层，也不发送试探性拉人命令。 */
    @Scheduled(fixedDelayString =
            "${armada.account.operation-restriction-recovery.scheduler.fixed-delay-ms:600000}")
    public void tick() {
        int restored = restrictionService.restoreExpired(System.currentTimeMillis());
        if (restored > 0) {
            log.info("账号操作限制到期恢复完成 restored={}", restored);
        }
    }
}
