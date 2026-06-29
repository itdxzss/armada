package com.armada.account.job;

import com.armada.account.dispatch.AccountImportOnlineDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 账号导入自动上线派发 scheduler。
 *
 * <p>只在 kafka profile 下运行:导入上线派发会写协议 outbox,后续依赖 Kafka dispatcher 发送到协议层。</p>
 */
@Service
@Profile("kafka")
@ConditionalOnProperty(
        prefix = "armada.account.import-online-dispatcher.scheduler",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AccountImportOnlineDispatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(AccountImportOnlineDispatchScheduler.class);

    private final AccountImportOnlineDispatcher dispatcher;

    /**
     * 创建账号导入自动上线派发 scheduler。
     *
     * @param dispatcher 导入上线派发器
     */
    public AccountImportOnlineDispatchScheduler(AccountImportOnlineDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * 周期性扫描导入成功但尚未派发上线命令的明细。
     */
    @Scheduled(fixedDelayString = "${armada.account.import-online-dispatcher.scheduler.fixed-delay-ms:10000}")
    public void tick() {
        int dispatched = dispatcher.dispatchOnce();
        if (dispatched > 0) {
            log.info("账号导入自动上线 scheduler 完成 dispatched={}", dispatched);
        }
    }
}
