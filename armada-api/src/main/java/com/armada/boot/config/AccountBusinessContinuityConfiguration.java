package com.armada.boot.config;

import com.armada.account.takeover.AccountAutoTakeoverProperties;
import com.armada.task.scheduler.PullTaskOfflineRoleWaitProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 在所有运行环境中注册抢登账号业务连续性配置，供账号与任务服务共同使用。 */
@Configuration
@EnableConfigurationProperties({
        AccountAutoTakeoverProperties.class,
        PullTaskOfflineRoleWaitProperties.class
})
public class AccountBusinessContinuityConfiguration {
}
