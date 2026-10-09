package com.armada.task.service.impl;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.task.scheduler.PullTaskExecutionDispatchTrigger;
import com.armada.task.scheduler.PullTaskOfflineRoleWaitProperties;
import com.armada.task.scheduler.PullTaskStickyPullerTransactionService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 拉手角色状态收敛所需的账号查询、粘性处理和提交后通知依赖。
 *
 * @param stickyPullers 粘性拉手事务服务
 * @param eventPublisher 事务后名单核实事件发布器
 * @param dispatchTrigger 提交后调度唤醒器
 * @param offlineRoleWaitProperties 任务侧离线等待开关
 * @param accountLookup 账号域实时角色可用性查询
 */
@Component
public record PullTaskPullerAccountStateResources(
        PullTaskStickyPullerTransactionService stickyPullers,
        ApplicationEventPublisher eventPublisher,
        PullTaskExecutionDispatchTrigger dispatchTrigger,
        PullTaskOfflineRoleWaitProperties offlineRoleWaitProperties,
        AccountProtocolLookupService accountLookup) {
}
