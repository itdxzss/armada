package com.armada.marketing.grouppull.service;

import com.armada.marketing.grouppull.mapper.GroupPullMarketingMapper;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingExecution;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingTask;
import com.armada.marketing.grouppull.model.enums.GroupPullResourceStatus;
import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.enums.MarketingBusinessType;
import com.armada.marketing.service.impl.MarketingAccountOccupancyService;
import com.armada.marketing.service.impl.MarketingGroupOccupancyService;
import com.armada.platform.protocol.mapper.ProtocolCommandOutboxMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 拉群营销任务结束后的安全资源释放服务。
 *
 * <p>尚未正式建群的执行可以直接取消并归还预留；已经冻结群名、
 * 进入正式建群流程的执行必须先完成收口。营销发送侧只取消尚未被 publisher
 * 抢占的 PENDING 命令，LOCKED/SENT 命令等待最终回执后再释放账号与分组。</p>
 */
@Service
public class GroupPullMarketingReleaseService {

    /** 安全日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(GroupPullMarketingReleaseService.class);

    /** 拉群任务、执行和料子数据访问。 */
    private final GroupPullMarketingMapper mapper;

    /** 统一营销发送尝试数据访问。 */
    private final MarketingTaskMapper marketingTaskMapper;

    /** 协议命令 outbox 数据访问。 */
    private final ProtocolCommandOutboxMapper outboxMapper;

    /** 建群账号任务占用服务。 */
    private final MarketingAccountOccupancyService accountOccupancyService;

    /** 营销分组整组占用服务。 */
    private final MarketingGroupOccupancyService groupOccupancyService;

    /**
     * 创建拉群营销安全释放服务。
     *
     * @param mapper 拉群任务、执行和料子数据访问
     * @param marketingTaskMapper 统一营销发送尝试数据访问
     * @param outboxMapper 协议命令 outbox 数据访问
     * @param accountOccupancyService 建群账号任务占用服务
     * @param groupOccupancyService 营销分组整组占用服务
     */
    public GroupPullMarketingReleaseService(
            GroupPullMarketingMapper mapper,
            MarketingTaskMapper marketingTaskMapper,
            ProtocolCommandOutboxMapper outboxMapper,
            MarketingAccountOccupancyService accountOccupancyService,
            MarketingGroupOccupancyService groupOccupancyService) {
        this.mapper = mapper;
        this.marketingTaskMapper = marketingTaskMapper;
        this.outboxMapper = outboxMapper;
        this.accountOccupancyService = accountOccupancyService;
        this.groupOccupancyService = groupOccupancyService;
    }

    /**
     * 尝试完成一次安全释放。
     *
     * @param taskId 统一营销任务 ID
     * @return 已全部释放时返回 true；仍有正式建群或已发消息在途时返回 false
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean tryRelease(Long taskId) {
        MarketingTask task = mapper.selectTaskForUpdate(taskId);
        GroupPullMarketingTask pullTask = mapper.selectTaskById(taskId);
        if (task == null || pullTask == null
                || !Integer.valueOf(GroupPullResourceStatus.RELEASING.code())
                        .equals(pullTask.getResourceStatus())) {
            return false;
        }
        long now = System.currentTimeMillis();
        CancellationResult cancellation = cancelPreGroupExecutions(taskId, now);
        if (!cancellation.stable()) {
            log.debug(
                    "拉群营销取消准备执行时状态发生变化，延后释放 taskId={} canceledPreparations={}",
                    taskId,
                    cancellation.canceled());
            return false;
        }
        int canceledExecutions = cancellation.canceled();
        long activeFormalExecutions = mapper.countActiveFormalExecutions(taskId);
        if (activeFormalExecutions > 0) {
            log.debug(
                    "拉群营销等待正式建群执行收口 tenantId={} taskId={} canceledPreparations={} "
                            + "activeFormalExecutions={}",
                    task.getTenantId(),
                    taskId,
                    canceledExecutions,
                    activeFormalExecutions);
            return false;
        }

        int canceledCommands = outboxMapper.cancelPendingMarketingTaskCommands(task.getTenantId(), taskId, now);
        int skippedAttempts = marketingTaskMapper.markCanceledOutboxAttemptsSkipped(
                task.getTenantId(), taskId, now);
        int failedAttempts = marketingTaskMapper.markDeadOutboxAttemptsFailed(
                task.getTenantId(), taskId, now);
        long unfinishedAttempts = marketingTaskMapper.countUnfinishedAttempts(taskId);
        if (unfinishedAttempts > 0) {
            log.debug(
                    "拉群营销等待发送回执 tenantId={} taskId={} canceledCommands={} skippedAttempts={} "
                            + "failedAttempts={} unfinishedAttempts={}",
                    task.getTenantId(),
                    taskId,
                    canceledCommands,
                    skippedAttempts,
                    failedAttempts,
                    unfinishedAttempts);
            return false;
        }

        int releasedAccounts = accountOccupancyService.releaseGroupPullResidualAccounts(taskId);
        mapper.markTaskExecutionsReleased(taskId, now);
        boolean groupReleased = groupOccupancyService.release(
                task.getAccountGroupId(), MarketingBusinessType.GROUP_PULL, taskId, now);
        if (!groupReleased && !groupOccupancyService.isFree(task.getAccountGroupId())) {
            log.error(
                    "拉群营销释放分组锁归属不一致 taskId={} groupId={}",
                    taskId,
                    task.getAccountGroupId());
            return false;
        }
        if (mapper.markResourceReleased(taskId, now) != 1) {
            log.warn(
                    "拉群营销资源状态未能更新为已释放 tenantId={} taskId={}",
                    task.getTenantId(),
                    taskId);
            return false;
        }
        log.info(
                "拉群营销资源释放完成 tenantId={} taskId={} groupId={} releasedAccounts={}",
                task.getTenantId(),
                taskId,
                task.getAccountGroupId(),
                releasedAccounts);
        return true;
    }

    /**
     * 取消尚未冻结群名的准备执行，并归还其料子、营销额度和建群账号占用。
     *
     * @param taskId 统一营销任务 ID
     * @param now 当前时间（epoch 毫秒）
     * @return 已取消数量及候选状态是否保持稳定
     */
    private CancellationResult cancelPreGroupExecutions(Long taskId, long now) {
        int canceled = 0;
        for (GroupPullMarketingExecution execution
                : mapper.selectCancelableExecutions(taskId)) {
            if (mapper.cancelPreGroupExecution(execution.getId(), now) != 1) {
                return new CancellationResult(canceled, false);
            }
            mapper.releaseExecutionMaterials(execution.getId(), now);
            mapper.cancelMarketingQuota(taskId, execution.getMarketingAccountId(), now);
            accountOccupancyService.releaseTaskAccount(
                    taskId, execution.getBuilderAccountId());
            mapper.markExecutionReleased(execution.getId(), now);
            canceled++;
        }
        return new CancellationResult(canceled, true);
    }

    /** 准备执行条件取消结果。 */
    private record CancellationResult(int canceled, boolean stable) {
    }
}
