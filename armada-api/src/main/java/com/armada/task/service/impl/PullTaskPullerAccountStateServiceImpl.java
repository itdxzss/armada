package com.armada.task.service.impl;

import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.model.PullTaskPullerSlotPolicy;
import com.armada.task.model.dto.PullTaskPullerUnavailableEvent;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.scheduler.PullTaskExecutionDispatchTrigger;
import com.armada.task.scheduler.PullTaskOfflineRoleWaitProperties;
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
    private final PullTaskExecutionDispatchTrigger dispatchTrigger;
    /** 任务侧关闭时不执行新增角色唤醒 SQL。 */
    private final PullTaskOfflineRoleWaitProperties offlineRoleWaitProperties;

    /**
     * @param accountMapper 任务角色账号 Mapper
     * @param executionMapper 群执行行 Mapper
     * @param stickyPullers 粘性拉手事务服务
     * @param eventPublisher 事务后名单核实事件发布器
     * @param dispatchTrigger 提交后调度唤醒器
     * @param offlineRoleWaitProperties 任务侧离线等待开关
     */
    public PullTaskPullerAccountStateServiceImpl(
            PullTaskGroupAccountMapper accountMapper,
            PullTaskGroupExecutionMapper executionMapper,
            PullTaskStickyPullerTransactionService stickyPullers,
            ApplicationEventPublisher eventPublisher,
            PullTaskExecutionDispatchTrigger dispatchTrigger,
            PullTaskOfflineRoleWaitProperties offlineRoleWaitProperties) {
        this.accountMapper = accountMapper;
        this.executionMapper = executionMapper;
        this.stickyPullers = stickyPullers;
        this.eventPublisher = eventPublisher;
        this.dispatchTrigger = dispatchTrigger;
        this.offlineRoleWaitProperties = offlineRoleWaitProperties;
    }

    /**
     * 保留历史拉手行和临时离线账号占用；仅封禁、解绑终态清除当前粘性拉手。
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
        if (unavailability == Unavailability.OFFLINE) {
            accountMapper.markTemporarilyOffline(puller, occurredAt,
                    PullTaskGroupAccountAvailability.AVAILABLE.code(),
                    PullTaskGroupAccountAvailability.OFFLINE.code(), unavailability.reasonCode());
            return;
        }
        removeRole(puller, unavailability.reasonCode(), occurredAt);
    }

    /** 与资源恢复共用事务，角色移出、粘性失效和后续推进必须一起提交。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void expireOfflineRole(PullTaskGroupAccount row, long now) {
        if (row == null || row.getTenantId() == null || row.getId() == null) {
            throw new IllegalArgumentException("离线拉手角色身份不完整");
        }
        Long previousTenant = TenantContext.get();
        TenantContext.set(row.getTenantId());
        try {
            removeRole(row, Unavailability.OFFLINE_TIMEOUT.reasonCode(), now);
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private void removeRole(PullTaskGroupAccount puller, String reasonCode, long occurredAt) {
        if (accountMapper.markUnavailable(
                puller.getId(), PullTaskGroupAccountAvailability.REMOVED.code(),
                reasonCode, null, occurredAt) != 1) {
            throw new IllegalStateException("账号状态事件更新拉手可用性失败");
        }
        PullTaskGroupExecution execution = executionMapper.selectById(
                puller.getGroupExecutionId());
        if (execution == null) {
            return;
        }
        stickyPullers.invalidateCurrentRole(
                execution, puller, reasonCode, occurredAt);
        eventPublisher.publishEvent(new PullTaskPullerUnavailableEvent(
                execution.getTenantId(), execution.getId(), puller.getId(), occurredAt));
    }

    /** 恢复原角色并只提前因离线产生的资源检查，不改变业务计划和在途请求。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markOnline(long tenantId, long accountId, long occurredAt) {
        Long previousTenant = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            boolean wake = false;
            for (PullTaskGroupAccount puller : accountMapper.selectOccupiedByAccountAndRole(
                    accountId, PullTaskGroupAccountRole.PULLER.code())) {
                if (!PullTaskPullerSlotPolicy.waitingForOnline(puller)) {
                    continue;
                }
                int restored = accountMapper.restoreOccupiedOfflinePuller(puller, occurredAt,
                        PullTaskGroupAccountAvailability.OFFLINE.code(),
                        PullTaskGroupAccountAvailability.AVAILABLE.code());
                if (restored == 1) {
                    wake |= executionMapper.wakeForOnlinePuller(puller, occurredAt,
                            List.of(PullTaskExecutionStatus.EXECUTING.code(),
                                    PullTaskExecutionStatus.WAIT_RESOURCE.code()),
                            PullTaskStandardStatus.EXECUTING.name(),
                            Unavailability.OFFLINE.reasonCode()) == 1;
                }
            }
            if (wake) {
                dispatchTrigger.dispatchAfterCommit();
            }
        } finally {
            restoreTenant(previousTenant);
        }
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void wakeRoleWaiters(long tenantId, long accountId, long occurredAt) {
        if (!offlineRoleWaitProperties.isEnabled()) {
            return;
        }
        Long previousTenant = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            if (executionMapper.wakeForReconnectedRole(accountId, occurredAt) > 0) {
                dispatchTrigger.dispatchAfterCommit();
            }
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private static void restoreTenant(Long previousTenant) {
        if (previousTenant == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenant);
        }
    }
}
