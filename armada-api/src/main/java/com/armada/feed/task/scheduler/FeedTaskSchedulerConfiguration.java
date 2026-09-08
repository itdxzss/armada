package com.armada.feed.task.scheduler;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 动态发布任务调度装配。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FeedTaskSchedulerProperties.class)
public class FeedTaskSchedulerConfiguration {
}
