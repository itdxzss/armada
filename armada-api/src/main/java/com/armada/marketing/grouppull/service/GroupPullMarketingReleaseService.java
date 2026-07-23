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

/** 拉群任务结束后的安全资源释放。 */
@Service
public class GroupPullMarketingReleaseService {

    private static final Logger log = LoggerFactory.getLogger(GroupPullMarketingReleaseService.class);

    private final GroupPullMarketingMapper mapper;
    private final MarketingTaskMapper marketingTaskMapper;
    private final ProtocolCommandOutboxMapper outboxMapper;
    private final MarketingAccountOccupancyService accountOccupancyService;
    private final MarketingGroupOccupancyService groupOccupancyService;

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
     * @return 已全部释放时返回 true；仍有正式建群或已发消息在途时返回 false
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean tryRelease(Long taskId) {
        MarketingTask task = mapper.selectTaskForUpdate(taskId);
        GroupPullMarketingTask pullTask = mapper.selectTaskByIdForUpdate(taskId);
        if (task == null || pullTask == null
                || !Integer.valueOf(GroupPullResourceStatus.RELEASING.code())
                        .equals(pullTask.getResourceStatus())) {
            return false;
        }
        long now = System.currentTimeMillis();
        cancelPreGroupExecutions(taskId, now);
        if (mapper.countActiveFormalExecutions(taskId) > 0) {
            return false;
        }

        outboxMapper.cancelPendingMarketingTaskCommands(taskId, now);
        marketingTaskMapper.markCanceledOutboxAttemptsSkipped(taskId, now);
        marketingTaskMapper.markDeadOutboxAttemptsFailed(taskId, now);
        if (marketingTaskMapper.countUnfinishedAttempts(taskId) > 0) {
            return false;
        }

        accountOccupancyService.releaseGroupPullResidualAccounts(taskId);
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
            return false;
        }
        log.info("拉群营销资源释放完成 taskId={} groupId={}", taskId, task.getAccountGroupId());
        return true;
    }

    private void cancelPreGroupExecutions(Long taskId, long now) {
        for (GroupPullMarketingExecution execution
                : mapper.selectCancelableExecutionsForUpdate(taskId)) {
            if (mapper.cancelPreGroupExecution(execution.getId(), now) != 1) {
                continue;
            }
            mapper.releaseExecutionMaterials(execution.getId(), now);
            mapper.cancelMarketingQuota(taskId, execution.getMarketingAccountId(), now);
            accountOccupancyService.releaseTaskAccount(
                    taskId, execution.getBuilderAccountId());
            mapper.markExecutionReleased(execution.getId(), now);
        }
    }
}
