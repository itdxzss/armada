package com.armada.resource.check;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * IP 代理真实检测后台执行器配置。
 *
 * <p>导入接口只提交检测任务,不在 HTTP 请求线程等待慢代理连接;手动检测接口仍保持同步返回检测结果。</p>
 */
@Configuration
public class IpProxyCheckConfiguration {

    private static final int CORE_POOL_SIZE = 2;
    private static final int MAX_POOL_SIZE = 4;
    private static final int QUEUE_CAPACITY = 1_000;

    @Bean(name = "ipProxyCheckExecutor")
    public Executor ipProxyCheckExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("ip-proxy-check-");
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.initialize();
        return executor;
    }
}
