package com.armada.account.contact.config;

import com.armada.account.contact.mapper.AccountContactMapper;
import com.armada.account.contact.mapper.AccountContactSyncMapper;
import com.armada.account.contact.service.AccountContactNormalizer;
import com.armada.account.contact.service.AccountContactOnlineHook;
import com.armada.account.contact.service.AccountContactSyncService;
import com.armada.account.contact.service.impl.AccountContactSnapshotSink;
import com.armada.account.contact.service.impl.AccountContactSyncServiceImpl;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.platform.protocol.port.ContactListPort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/** 账号通讯录采集装配。 */
@Configuration
@EnableConfigurationProperties(AccountContactProperties.class)
public class AccountContactConfiguration {

    /** 后台同步并发数；与账号状态 Kafka 消费线程彻底隔离。 */
    private static final int POOL_SIZE = 2;

    /** 覆盖一次全量账号上线的等待队列容量。 */
    private static final int QUEUE_CAPACITY = 1024;

    /** 优雅停机最多等待秒数。 */
    private static final int SHUTDOWN_AWAIT_SECONDS = 30;

    /**
     * 创建通讯录同步专用线程池。
     *
     * <p>刻意不复用 accountStateInviteRecoveryExecutor：通讯录同步会做同步 HTTP 调用，
     * 与邀请码恢复共池会互相拖慢。</p>
     *
     * @return 通讯录同步后台执行器
     */
    @Bean(name = "accountContactSyncExecutor")
    public Executor accountContactSyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("account-contact-sync-");
        executor.setCorePoolSize(POOL_SIZE);
        executor.setMaxPoolSize(POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(SHUTDOWN_AWAIT_SECONDS);
        executor.initialize();
        return executor;
    }

    /**
     * 装配通讯录采集服务。
     *
     * @param contactListPort 通讯录读取协议端口
     * @param contactMapper 联系人快照数据访问
     * @param syncMapper 同步状态数据访问
     * @param accountStateMapper 账号状态数据访问
     * @param normalizer 协议快照归一化器
     * @param properties 通讯录采集配置
     * @param protocolLookupService 账号协议引用查询服务
     * @return 通讯录采集服务
     */
    @Bean
    public AccountContactSyncService accountContactSyncService(
            ContactListPort contactListPort,
            AccountContactMapper contactMapper,
            AccountContactSyncMapper syncMapper,
            AccountStateMapper accountStateMapper,
            AccountContactNormalizer normalizer,
            AccountContactProperties properties,
            AccountProtocolLookupService protocolLookupService) {
        return new AccountContactSyncServiceImpl(
                contactListPort,
                contactMapper,
                syncMapper,
                accountStateMapper,
                normalizer,
                properties,
                accountId -> protocolLookupService.findActiveProtocolRef(accountId)
                        .orElseThrow(() -> new BusinessException(
                                ErrorCode.NOT_FOUND, "账号无可用协议引用: " + accountId)),
                TenantContext::get,
                System::currentTimeMillis);
    }

    /**
     * 装配协议通讯录快照落库处理器。
     *
     * @param contactMapper 联系人快照数据访问
     * @param syncMapper 同步状态数据访问
     * @param accountStateMapper 账号状态数据访问
     * @param normalizer 协议快照归一化器
     * @return 快照落库处理器
     */
    @Bean
    public AccountContactSnapshotSink accountContactSnapshotSink(
            AccountContactMapper contactMapper,
            AccountContactSyncMapper syncMapper,
            AccountStateMapper accountStateMapper,
            AccountContactNormalizer normalizer) {
        return new AccountContactSnapshotSink(
                contactMapper, syncMapper, accountStateMapper, normalizer,
                System::currentTimeMillis);
    }

    /**
     * 装配账号上线后的通讯录同步钩子。
     *
     * @param syncService 通讯录采集服务
     * @param properties 通讯录采集配置
     * @param executor 通讯录同步后台执行器
     * @return 上线同步钩子
     */
    @Bean
    public AccountContactOnlineHook accountContactOnlineHook(
            AccountContactSyncService syncService,
            AccountContactProperties properties,
            @Qualifier("accountContactSyncExecutor") Executor executor) {
        return new AccountContactOnlineHook(syncService, properties, executor);
    }
}
