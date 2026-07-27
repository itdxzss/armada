package com.armada.marketing.grouppull.service;

import com.armada.marketing.grouppull.mapper.GroupPullMarketingMapper;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingExecution;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingTask;
import com.armada.marketing.grouppull.model.enums.GroupPullExecutionStage;
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

/**
 * 单次拉群执行的幂等结果结算器。
 *
 * <p>统一负责最终结果、料子去向、营销额度、建群账号流转及固定营销目标创建。
 * 执行记录必须通过条件更新首次进入终态，重复恢复不会重复累计统计或
 * 重复创建营销目标。</p>
 */
@Service
public class GroupPullMarketingFinalizer {

    /** 安全日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(GroupPullMarketingFinalizer.class);

    /** 新建固定营销目标的待发送状态码。 */
    private static final int MARKETING_TARGET_PENDING_STATUS = 1;

    /** 拉群任务、执行、料子及营销额度数据访问。 */
    private final GroupPullMarketingMapper mapper;

    /** 公共营销目标数据访问。 */
    private final MarketingTaskMapper marketingTaskMapper;

    /** 建群账号任务占用服务。 */
    private final MarketingAccountOccupancyService occupancyService;

    /** 新成功群首次即时营销入口。 */
    private final MarketingNewGroupImmediateSendService immediateSendService;

    /**
     * 创建单次拉群执行结果结算器。
     *
     * @param mapper 拉群任务、执行、料子及营销额度数据访问
     * @param marketingTaskMapper 公共营销目标数据访问
     * @param occupancyService 建群账号任务占用服务
     * @param immediateSendService 新成功群首次即时营销入口
     */
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

    /**
     * 在全部配置步骤完成后，按实际料子进群数决定最终成功或失败。
     *
     * @param executionId 单群执行 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void finalizeAfterStages(Long executionId) {
        GroupPullMarketingExecution execution = mapper.selectExecutionById(executionId);
        if (!active(execution)) {
            return;
        }
        GroupPullMarketingTask task = mapper.selectTaskById(execution.getTaskId());
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

    /**
     * 将正式建群后的关键步骤失败结算为建群失败。
     *
     * @param executionId 单群执行 ID
     * @param reason 已脱敏的业务失败原因
     */
    @Transactional(rollbackFor = Exception.class)
    public void fail(Long executionId, String reason) {
        GroupPullMarketingExecution execution = mapper.selectExecutionById(executionId);
        if (!active(execution)) {
            return;
        }
        finish(
                execution,
                mapper.selectTaskById(execution.getTaskId()),
                GroupPullExecutionStatus.FAILED,
                reason);
    }

    /**
     * 尚未正式创建群组时停止当前匹配，不计入建群失败。
     *
     * @param executionId 单群执行 ID
     * @param reason 已脱敏的业务跳过原因
     */
    @Transactional(rollbackFor = Exception.class)
    public void skipBeforeGroup(Long executionId, String reason) {
        GroupPullMarketingExecution execution = mapper.selectExecutionById(executionId);
        if (!active(execution)) {
            return;
        }
        finish(
                execution,
                mapper.selectTaskById(execution.getTaskId()),
                GroupPullExecutionStatus.PRE_GROUP_SKIPPED,
                reason);
    }

    /**
     * 首次把活动执行收口到指定终态，并同步处理关联资源。
     *
     * @param execution 条件终态更新前读取的活动执行
     * @param task 拉群任务配置
     * @param outcome 最终执行结果
     * @param reason 失败或跳过原因；成功时可空
     */
    private void finish(
            GroupPullMarketingExecution execution,
            GroupPullMarketingTask task,
            GroupPullExecutionStatus outcome,
            String reason) {
        long now = System.currentTimeMillis();
        int terminalStage = outcome == GroupPullExecutionStatus.SUCCEEDED
                ? GroupPullExecutionStage.COMPLETED.code()
                : execution.getCurrentStage();
        if (mapper.markExecutionTerminal(
                execution.getId(),
                execution.getExecutionStatus(),
                execution.getCurrentStage(),
                outcome.code(),
                terminalStage,
                reason,
                now) != 1) {
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
        log.info(
                "拉群执行结算完成 taskId={} executionId={} outcome={}",
                execution.getTaskId(),
                execution.getId(),
                outcome);
    }

    /**
     * 为成功群创建唯一固定营销目标，并交给现有营销引擎首次发送。
     *
     * @param execution 已成功的单群执行
     * @param now 结算时间（epoch 毫秒）
     */
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
        target.setStatus(MARKETING_TARGET_PENDING_STATUS);
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

    /**
     * 判断执行是否仍允许首次进入终态。
     *
     * @param execution 待结算执行
     * @return 准备中或执行中返回 true，其他状态返回 false
     */
    private static boolean active(GroupPullMarketingExecution execution) {
        return execution != null
                && (Integer.valueOf(GroupPullExecutionStatus.PREPARING.code())
                        .equals(execution.getExecutionStatus())
                || Integer.valueOf(GroupPullExecutionStatus.EXECUTING.code())
                        .equals(execution.getExecutionStatus()));
    }
}
