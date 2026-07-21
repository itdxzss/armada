package com.armada.marketing.scheduler;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 营销调度参数通用配置。
 *
 * <p>新群检测服务不受 {@code kafka} profile 限制，因此分批参数也必须在所有运行模式下可注入。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MarketingRoundSchedulerProperties.class)
public class MarketingSchedulerPropertiesConfiguration {
}
