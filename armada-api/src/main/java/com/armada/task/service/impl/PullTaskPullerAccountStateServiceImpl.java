package com.armada.task.service.impl;

import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.model.dto.PullTaskPullerUnavailableEvent;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.scheduler.PullTaskStickyPullerTransactionService;
import com.armada.task.service.PullTaskPullerAccountStateService;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 普通拉群任务内拉手角色对账号状态事件的事务收敛实现。 */
@Service
public class PullTaskPullerAccountStateServiceImpl
        implements PullTaskPullerAccountStateService {

    private final PullTaskGroupAccountMapper accountMapper;
    private final PullTaskGroupExecutionMapper executionMapper;
    private final PullTaskStickyPullerTransactionService stickyPullers;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * @param accountMapper 任务角色账号 Mapper
     * @param executionMapper 群执行行 Mapper
     * @param stickyPullers 粘性拉手事务服务
     * @param eventPublisher 事务后名单核实事件发布器
     */
    public PullTaskPullerAccountStateServiceImpl(
            PullTaskGroupAccountMapper accountMapper,
            PullTaskGroupExecutionMapper executionMapper,
            PullTaskStickyPullerTransactionService stickyPullers,
            ApplicationEventPublisher eventPublisher) {
        this.accountMapper = accountMapper;
        this.executionMapper = executionMapper;
        this.stickyPullers = stickyPullers;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 保留历史拉手行，只更新任务内可用性并清除仍匹配的当前粘性拉手。
     *
     * @param tenantId 账号所属租户
     * @param accountId Armada 账号 ID
     * @param unavailability 账号不可用分类
     * @param occurredAt 状态发生时间(epoch 毫秒)
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markUnavailable(
            long tenantId,
            long accountId,
            Unavailability unavailability,
            long occurredAt) {
        Long previousTenant = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            List<PullTaskGroupAccount> pullers = accountMapper
                    .selectOccupiedByAccountAndRole(
                            accountId, PullTaskGroupAccountRole.PULLER.code());
            for (PullTaskGroupAccount puller : pullers) {
                markUnavailable(puller, unavailability, occurredAt);
            }
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private void markUnavailable(
            PullTaskGroupAccount puller,
            Unavailability unavailability,
            long occurredAt) {
        int availability = unavailability.removed()
                ? PullTaskGroupAccountAvailability.REMOVED.code()
                : PullTaskGroupAccountAvailability.OFFLINE.code();
        if (accountMapper.markUnavailable(
                puller.getId(), availability,
                unavailability.reasonCode(), null, occurredAt) != 1) {
            throw new IllegalStateException("账号状态事件更新拉手可用性失败");
        }
        PullTaskGroupExecution execution = executionMapper.selectById(
                puller.getGroupExecutionId());
        if (execution == null) {
            return;
        }
        stickyPullers.invalidateCurrentRole(
                execution, puller, unavailability.reasonCode(), occurredAt);
        eventPublisher.publishEvent(new PullTaskPullerUnavailableEvent(
                execution.getTenantId(), execution.getId(), puller.getId(), occurredAt));
    }

    private static void restoreTenant(Long previousTenant) {
        if (previousTenant == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenant);
        }
    }
}
