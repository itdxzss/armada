package com.armada.marketing.grouppull.service;

import com.armada.marketing.grouppull.mapper.GroupPullMarketingMapper;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingExecution;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingTask;
import com.armada.marketing.grouppull.model.enums.GroupPullExecutionStatus;
import com.armada.marketing.grouppull.model.vo.GroupPullAccountRefRow;
import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.model.entity.MarketingTaskTarget;
import com.armada.marketing.model.enums.MarketingTargetScope;
import com.armada.marketing.service.MarketingNewGroupImmediateSendService;
import com.armada.marketing.service.impl.MarketingAccountOccupancyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 单次拉群执行的幂等结果结算器。 */
@Service
public class GroupPullMarketingFinalizer {

    private static final Logger log = LoggerFactory.getLogger(GroupPullMarketingFinalizer.class);

    private final GroupPullMarketingMapper mapper;
    private final MarketingTaskMapper marketingTaskMapper;
    private final MarketingAccountOccupancyService occupancyService;
    private final MarketingNewGroupImmediateSendService immediateSendService;

    public GroupPullMarketingFinalizer(
            GroupPullMarketingMapper mapper,
            MarketingTaskMapper marketingTaskMapper,
            MarketingAccountOccupancyService occupancyService,
            MarketingNewGroupImmediateSendService immediateSendService) {
        this.mapper = mapper;
        this.marketingTaskMapper = marketingTaskMapper;
        this.occupancyService = occupancyService;
        this.immediateSendService = immediateSendService;
    }

    /** 按实际料子进群数决定最终成功或失败。 */
    @Transactional(rollbackFor = Exception.class)
    public void finalizeAfterStages(Long executionId) {
        GroupPullMarketingExecution execution = mapper.selectExecutionByIdForUpdate(executionId);
        if (!active(execution)) {
            return;
        }
        GroupPullMarketingTask task = mapper.selectTaskByIdForUpdate(execution.getTaskId());
        long joined = mapper.countSuccessfulMaterialEntries(executionId);
        if (joined >= task.getMaterialPerGroup()) {
            finish(execution, task, GroupPullExecutionStatus.SUCCEEDED, null);
            return;
        }
        finish(
                execution,
                task,
                GroupPullExecutionStatus.FAILED,
                "料子实际进群数量不足，要求" + task.getMaterialPerGroup() + "，实际" + joined);
    }

    /** 正式建群后的关键步骤失败。 */
    @Transactional(rollbackFor = Exception.class)
    public void fail(Long executionId, String reason) {
        GroupPullMarketingExecution execution = mapper.selectExecutionByIdForUpdate(executionId);
        if (!active(execution)) {
            return;
        }
        finish(
                execution,
                mapper.selectTaskByIdForUpdate(execution.getTaskId()),
                GroupPullExecutionStatus.FAILED,
                reason);
    }

    /** 尚未正式创建群组时停止当前匹配，不计建群失败。 */
    @Transactional(rollbackFor = Exception.class)
    public void skipBeforeGroup(Long executionId, String reason) {
        GroupPullMarketingExecution execution = mapper.selectExecutionByIdForUpdate(executionId);
        if (!active(execution)) {
            return;
        }
        finish(
                execution,
                mapper.selectTaskByIdForUpdate(execution.getTaskId()),
                GroupPullExecutionStatus.PRE_GROUP_SKIPPED,
                reason);
    }

    private void finish(
            GroupPullMarketingExecution execution,
            GroupPullMarketingTask task,
            GroupPullExecutionStatus outcome,
            String reason) {
        long now = System.currentTimeMillis();
        if (mapper.markExecutionTerminal(execution.getId(), outcome.code(), reason, now) != 1) {
            return;
        }

        if (outcome == GroupPullExecutionStatus.SUCCEEDED) {
            mapper.completeSuccessfulMaterials(execution.getId(), now);
            createMarketingTarget(execution, now);
        } else {
            mapper.completeFailedJoinedMaterials(execution.getId(), now);
            mapper.releaseUnjoinedMaterials(execution.getId(), now);
            mapper.cancelMarketingQuota(
                    execution.getTaskId(), execution.getMarketingAccountId(), now);
        }

        Long targetGroupId = outcome == GroupPullExecutionStatus.SUCCEEDED
                ? task.getSuccessGroupId()
                : task.getFailureGroupId();
        if (targetGroupId != null
                && mapper.moveBuilderAccount(
                        execution.getBuilderAccountId(), targetGroupId, now) != 1) {
            mapper.appendExecutionFailureReason(
                    execution.getId(), "建群账号未转入目标分组", now);
        }

        if (occupancyService.releaseTaskAccount(
                execution.getTaskId(), execution.getBuilderAccountId())) {
            mapper.markExecutionReleased(execution.getId(), now);
        } else {
            log.warn(
                    "拉群执行结算时建群账号占用未释放 taskId={} executionId={} accountId={}",
                    execution.getTaskId(), execution.getId(), execution.getBuilderAccountId());
        }
    }

    private void createMarketingTarget(
            GroupPullMarketingExecution execution,
            long now) {
        GroupPullAccountRefRow marketer = mapper.selectAccountRef(execution.getMarketingAccountId());
        if (marketer == null || execution.getGroupLinkId() == null) {
            throw new IllegalStateException("成功群缺少营销账号或群入口");
        }
        MarketingTaskTarget target = new MarketingTaskTarget();
        target.setMarketingTaskId(execution.getTaskId());
        target.setAccountId(marketer.getAccountId());
        target.setAccountPhone(marketer.getWsPhone());
        target.setTargetScope(MarketingTargetScope.GROUP_FIXED.code());
        target.setGroupLinkId(execution.getGroupLinkId());
        target.setGroupJid(execution.getGroupJid());
        target.setGroupLinkUrl(execution.getGroupInviteUrl() == null
                ? "wa://group/" + execution.getGroupJid()
                : execution.getGroupInviteUrl());
        target.setGroupName(execution.getGroupName());
        target.setStatus(1);
        target.setSentMessageCount(0);
        target.setFailedMessageCount(0);
        target.setRetryCount(0);
        target.setCreatedAt(now);
        target.setUpdatedAt(now);
        if (marketingTaskMapper.insertTarget(target) != 1 || target.getId() == null) {
            throw new IllegalStateException("拉群营销固定目标创建失败");
        }
        if (mapper.bindMarketingTarget(execution.getId(), target.getId(), now) != 1) {
            throw new IllegalStateException("拉群执行绑定营销目标失败");
        }
        mapper.initializeMarketingRound(execution.getTaskId(), now);
        immediateSendService.enqueueFixedTarget(execution.getTaskId(), target.getId(), now);
    }

    private static boolean active(GroupPullMarketingExecution execution) {
        return execution != null
                && (Integer.valueOf(GroupPullExecutionStatus.PREPARING.code())
                        .equals(execution.getExecutionStatus())
                || Integer.valueOf(GroupPullExecutionStatus.EXECUTING.code())
                        .equals(execution.getExecutionStatus()));
    }
}
