package com.armada.contact.task.scheduler;

import com.armada.contact.task.mapper.ContactFriendTaskAccountMapper;
import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.mapper.ContactFriendTaskRecipientMapper;
import com.armada.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 通讯录营销任务的计划启动与自动完成推进器。
 *
 * <p>后台调度器跨租户扫描到期任务后，由本类恢复租户上下文再执行单任务状态流转——
 * 不设 TenantContext，MyBatis 租户拦截器就拦不住，会跨租户串数据。</p>
 */
@Component
@Profile("kafka")
public class ContactTaskLifecycleWorker {

    private static final Logger log = LoggerFactory.getLogger(ContactTaskLifecycleWorker.class);

    private final ContactFriendTaskMapper taskMapper;
    private final ContactFriendTaskAccountMapper accountMapper;
    private final ContactFriendTaskRecipientMapper recipientMapper;
    private final Clock clock;

    /**
     * 创建生命周期推进器。
     *
     * @param taskMapper 任务主表数据访问
     * @param accountMapper 任务账号读模型数据访问
     * @param recipientMapper 收件人明细数据访问
     * @param clock 系统时钟
     */
    public ContactTaskLifecycleWorker(ContactFriendTaskMapper taskMapper,
                                      ContactFriendTaskAccountMapper accountMapper,
                                      ContactFriendTaskRecipientMapper recipientMapper,
                                      Clock clock) {
        this.taskMapper = taskMapper;
        this.accountMapper = accountMapper;
        this.recipientMapper = recipientMapper;
        this.clock = clock;
    }

    /**
     * 到达计划开始时间后把已启用未开始任务推进到进行中。
     *
     * @param tenantId 租户 ID
     * @param taskId 任务 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void startDueScheduledTask(Long tenantId, Long taskId) {
        Long previous = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            long now = clock.millis();
            int updated = taskMapper.startDueScheduledTask(taskId, now);
            if (updated > 0) {
                log.info("通讯录任务到达计划开始时间并启动 tenantId={} taskId={} startedAt={}",
                        tenantId, taskId, now);
            }
        } finally {
            restore(previous);
        }
    }

    /**
     * 收件人全部落终态后收敛账号状态并把任务推进到已完成。
     *
     * @param tenantId 租户 ID
     * @param taskId 任务 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void completeDrainedTask(Long tenantId, Long taskId) {
        Long previous = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            long now = clock.millis();
            // 先收敛账号终态，再算任务汇总：invalid_account_num 读的就是收敛后的 FAILED 行
            accountMapper.settleDrainedAccounts(taskId, now);
            if (recipientMapper.countUnfinished(taskId) > 0) {
                return;
            }
            int completed = taskMapper.completeDrainedTask(taskId, now);
            if (completed > 0) {
                log.info("通讯录任务全部收件人落终态并完成 tenantId={} taskId={} finishedAt={}",
                        tenantId, taskId, now);
            }
        } finally {
            restore(previous);
        }
    }

    private static void restore(Long previous) {
        if (previous == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previous);
        }
    }
}
