package com.armada.boot.config;

import com.armada.resource.check.IpProxyCheckProperties;
import java.util.concurrent.Executor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * IP 代理真实检测后台执行器配置。
 *
 * <p>导入接口只提交检测任务,不在 HTTP 请求线程等待慢代理连接;手动检测接口仍保持同步返回检测结果。</p>
 */
@Configuration
@EnableConfigurationProperties(IpProxyCheckProperties.class)
public class IpProxyCheckConfiguration {

    @Bean(name = "ipProxyCheckExecutor")
    public Executor ipProxyCheckExecutor(IpProxyCheckProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("ip-proxy-check-");
        executor.setCorePoolSize(properties.getExecutor().getCoreSize());
        executor.setMaxPoolSize(properties.getExecutor().getMaxSize());
        executor.setQueueCapacity(properties.getExecutor().getQueueCapacity());
        executor.initialize();
        return executor;
    }
}
