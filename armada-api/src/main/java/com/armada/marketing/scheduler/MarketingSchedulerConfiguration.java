package com.armada.marketing.scheduler;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 营销任务调度基础依赖。
 */
@Configuration(proxyBeanMethods = false)
@Profile("kafka")
public class MarketingSchedulerConfiguration {

    /**
     * 提供统一系统时钟;worker 通过注入时钟稳定测试跨越任务结束时间的边界。
     *
     * @return UTC 系统时钟
     */
    @Bean
    public Clock marketingTaskClock() {
        return Clock.systemUTC();
    }
}
