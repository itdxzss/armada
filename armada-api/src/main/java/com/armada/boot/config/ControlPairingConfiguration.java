package com.armada.boot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 控台配对独立后台执行器，避免代理和 WhatsApp 握手占用创建请求。 */
@Configuration
public class ControlPairingConfiguration {
    /** 有界并发且不在提交线程执行；过载由服务层明确结束会话并释放资源。 */
    @Bean(name = "controlPairingExecutor")
    public ThreadPoolTaskExecutor controlPairingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("control-pairing-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(16);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(70);
        return executor;
    }
}
