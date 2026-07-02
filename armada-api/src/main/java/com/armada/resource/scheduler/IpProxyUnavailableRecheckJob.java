package com.armada.resource.scheduler;

import com.armada.resource.service.IpProxyRecheckResult;
import com.armada.resource.service.IpProxyService;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 不可用 IP 定时重检任务。
 *
 * <p>协议层上报 PROXY_FAILED 后,armada 会把账号当前绑定 IP 标记为不可用。
 * 本任务负责周期性重检这些 IP,检测成功后复用 IP 管理检测落库逻辑恢复为空闲。</p>
 */
@Component
@EnableConfigurationProperties(IpProxyUnavailableRecheckJobProperties.class)
public class IpProxyUnavailableRecheckJob {

    private static final Logger log = LoggerFactory.getLogger(IpProxyUnavailableRecheckJob.class);

    private final IpProxyService service;
    private final IpProxyUnavailableRecheckJobProperties properties;

    /**
     * 创建不可用 IP 定时重检任务。
     *
     * @param service    IP 代理池服务
     * @param properties 调度配置
     */
    public IpProxyUnavailableRecheckJob(IpProxyService service,
                                        IpProxyUnavailableRecheckJobProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    /**
     * 执行一轮不可用 IP 重检。
     *
     * @return 本轮重检摘要
     */
    @Scheduled(fixedDelayString = "${armada.ip-proxy-unavailable-recheck.fixed-delay-ms:900000}")
    public JobResult runOnce() {
        Instant started = Instant.now();
        log.info("ip_proxy.unavailable_recheck.job.start enabled={} batchSize={}",
                properties.enabled(), properties.batchSize());
        if (!properties.enabled()) {
            log.info("ip_proxy.unavailable_recheck.job.ok scanned=0 checked=0 failed=0 costMs={} reason=disabled",
                    Duration.between(started, Instant.now()).toMillis());
            return new JobResult(0, 0, 0);
        }

        IpProxyRecheckResult result = service.recheckUnavailableProxies(properties.batchSize());
        long costMs = Duration.between(started, Instant.now()).toMillis();
        log.info("ip_proxy.unavailable_recheck.job.ok scanned={} checked={} failed={} costMs={}",
                result.scanned(), result.checked(), result.failed(), costMs);
        return new JobResult(result.scanned(), result.checked(), result.failed());
    }

    /** 一轮不可用 IP 重检结果。 */
    public record JobResult(int scanned, int checked, int failed) {
    }
}
