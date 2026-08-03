package com.armada.marketing.export.service;

import com.armada.marketing.export.service.impl.MarketingTaskWhatsAppMemberProvider;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/** 营销任务导出的运行时依赖，统一管理调度器、业务时钟和本地持久化目录。 */
@Component
public class MarketingTaskExportRuntime {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final TaskScheduler taskScheduler;
    private final Clock clock;
    private final Path storageRoot;
    private final MarketingTaskWhatsAppMemberProvider whatsAppMemberProvider;

    /**
     * 使用应用共享调度器和配置目录构建生产运行时。
     *
     * @param taskScheduler 应用共享任务调度器
     * @param storageDir 导出文件本地持久化目录
     */
    @Autowired
    public MarketingTaskExportRuntime(
            @Qualifier("taskScheduler") TaskScheduler taskScheduler,
            @Value("${armada.marketing.export.storage-dir:/app/data/marketing-exports}") String storageDir,
            MarketingTaskWhatsAppMemberProvider whatsAppMemberProvider) {
        this(taskScheduler, Clock.system(BUSINESS_ZONE), Path.of(storageDir), whatsAppMemberProvider);
    }

    /**
     * 构建可注入固定时钟和隔离目录的运行时，供单元测试使用。
     *
     * @param taskScheduler 任务调度器
     * @param clock 业务时钟
     * @param storageRoot 导出文件根目录
     */
    public MarketingTaskExportRuntime(
            TaskScheduler taskScheduler,
            Clock clock,
            Path storageRoot,
            MarketingTaskWhatsAppMemberProvider whatsAppMemberProvider) {
        this.taskScheduler = taskScheduler;
        this.clock = clock;
        this.storageRoot = storageRoot.toAbsolutePath().normalize();
        this.whatsAppMemberProvider = whatsAppMemberProvider;
    }

    /** @return 导出租约心跳使用的共享调度器 */
    public TaskScheduler taskScheduler() {
        return taskScheduler;
    }

    /** @return 导出快照、租约和文件命名使用的业务时钟 */
    public Clock clock() {
        return clock;
    }

    /** @return 规范化后的导出文件根目录 */
    public Path storageRoot() {
        return storageRoot;
    }

    /** @return WhatsApp 实时成员与退群事实提供器 */
    public MarketingTaskWhatsAppMemberProvider whatsAppMemberProvider() {
        return whatsAppMemberProvider;
    }
}
