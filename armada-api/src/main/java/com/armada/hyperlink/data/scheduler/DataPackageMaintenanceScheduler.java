package com.armada.hyperlink.data.scheduler;

import com.armada.hyperlink.data.service.DataPackageMaintenanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 数据包号码保留期清理与导入处理中超时恢复调度入口。 */
@Component
@ConditionalOnProperty(
        prefix = "armada.hyperlink.data-package.maintenance",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DataPackageMaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(DataPackageMaintenanceScheduler.class);

    private final DataPackageMaintenanceService service;

    public DataPackageMaintenanceScheduler(DataPackageMaintenanceService service) {
        this.service = service;
    }

    /** 每批独立事务清理，直到本轮固定截止时间下没有更多到期号码。 */
    @Scheduled(fixedDelayString =
            "${armada.hyperlink.data-package.maintenance.cleanup-fixed-delay-ms:3600000}")
    public void purgeExpiredPhones() {
        long deleted = 0L;
        int batch;
        do {
            batch = service.purgeExpiredPhoneBatch();
            deleted += batch;
        } while (batch > 0);
        if (deleted > 0) {
            log.info("数据包过期号码清理完成 deleted={}", deleted);
        }
    }

    /** 分批收敛进程中断遗留的超时 PROCESSING 导入审计。 */
    @Scheduled(fixedDelayString =
            "${armada.hyperlink.data-package.maintenance.recovery-fixed-delay-ms:60000}")
    public void recoverStaleImports() {
        long recovered = 0L;
        int batch;
        do {
            batch = service.recoverStaleImportBatch();
            recovered += batch;
        } while (batch > 0);
        if (recovered > 0) {
            log.warn("数据包超时导入审计已恢复 recovered={}", recovered);
        }
    }
}
