package com.armada.account.contact.service;

import com.armada.account.contact.config.AccountContactProperties;
import com.armada.account.contact.model.ContactSyncSource;
import com.armada.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;

/**
 * 账号上线后的通讯录同步附属任务。
 *
 * <p>与既有「ONLINE 后恢复群邀请码」同一范式：投递到独立线程池执行，
 * <b>任何失败都在内部吞掉只打 warn</b>，绝不反向阻塞账号状态 Kafka 分区。</p>
 */
public class AccountContactOnlineHook {

    private static final Logger log = LoggerFactory.getLogger(AccountContactOnlineHook.class);

    private final AccountContactSyncService syncService;
    private final AccountContactProperties properties;
    private final Executor executor;

    /**
     * 创建上线同步钩子。
     *
     * @param syncService 通讯录采集服务
     * @param properties 通讯录采集配置
     * @param executor 附属任务线程池
     */
    public AccountContactOnlineHook(
            AccountContactSyncService syncService,
            AccountContactProperties properties,
            Executor executor) {
        this.syncService = syncService;
        this.properties = properties;
        this.executor = executor;
    }

    /**
     * 账号进入 ONLINE 后触发一次按 TTL 的通讯录同步。
     *
     * @param tenantId 租户 ID
     * @param accountId 账号 ID
     */
    public void onAccountOnline(Long tenantId, Long accountId) {
        if (!properties.syncOnOnlineOrDefault()) {
            return;
        }
        try {
            executor.execute(() -> runSync(tenantId, accountId));
        } catch (RuntimeException ex) {
            log.warn("账号上线后通讯录同步任务投递失败,账号状态事件继续完成 tenantId={} accountId={} errorType={}",
                    tenantId, accountId, ex.getClass().getSimpleName(), ex);
        }
    }

    private void runSync(Long tenantId, Long accountId) {
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(tenantId);
            syncService.syncIfStale(accountId, ContactSyncSource.ONLINE_EVENT);
        } catch (RuntimeException ex) {
            log.warn("账号上线后通讯录同步失败,账号状态事件继续完成 tenantId={} accountId={} errorType={}",
                    tenantId, accountId, ex.getClass().getSimpleName(), ex);
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }
}
