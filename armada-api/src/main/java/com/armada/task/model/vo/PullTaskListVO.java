package com.armada.task.model.vo;

import com.armada.task.model.enums.PullTaskGroupSource;
import com.armada.task.model.enums.PullTaskListAction;
import com.armada.task.model.enums.PullTaskResourceShortageType;
import com.armada.task.model.enums.PullTaskType;
import java.math.BigDecimal;
import java.util.List;

/**
 * 拉群任务九列一级列表行。
 *
 * <p>营销统计分组为 {@code null} 表示尚无统计行；分组存在且数量为零表示真实零值。</p>
 */
public record PullTaskListVO(
        Long id,
        String taskName,
        String groupName,
        String mode,
        PullTaskType taskType,
        PullTaskGroupSource groupSource,
        String status,
        String primaryStage,
        String blockingReason,
        String operatorName,
        Integer groupCount,
        Integer expectedPullCount,
        String remark,
        GroupProgress groupProgress,
        PullResult pullResult,
        MarketingProgress marketingProgress,
        MessageStats messageStats,
        ExceptionStats exceptionStats,
        ResourceStats resourceStats,
        Long createdAt,
        Long lastExecutedAt,
        List<PullTaskListAction> allowedActions
) {

    /** 群组转移默认展示和悬浮明细。 */
    public record GroupProgress(
            Integer processedGroupCount,
            Integer targetGroupCount,
            Integer transferSuccessCount,
            Integer transferPendingCloseCount,
            Integer transferPartialCount,
            Integer transferFailedCount,
            Integer transferRunningCount,
            Integer transferWaitingCount
    ) {
    }

    /** 拉人结果默认展示和悬浮明细。 */
    public record PullResult(
            Integer plannedTargetCount,
            Integer effectiveTargetCount,
            Integer joinedSuccessCount,
            Integer alreadyInGroupCount,
            Integer privacyRestrictedCount,
            Integer invalidNumberCount,
            Integer unregisteredCount,
            Integer failedCount,
            Integer unknownCount,
            Integer remainingTargetCount,
            BigDecimal effectiveSuccessRate
    ) {
    }

    /** 营销群组进度。 */
    public record MarketingProgress(
            Integer waitingCount,
            Integer runningCount,
            Integer pausedCount,
            Integer completedCount,
            Integer abnormalStoppedCount
    ) {
    }

    /** 消息最终结果统计。 */
    public record MessageStats(
            Integer successCount,
            Integer failedCount,
            Integer unknownCount
    ) {
    }

    /** 异常群组和封禁账号统计。 */
    public record ExceptionStats(
            Integer abnormalGroupCount,
            Integer managerShortageGroupCount,
            Integer pullerShortageGroupCount,
            Integer stationShortageGroupCount,
            Integer bannedAccountCount
    ) {
    }

    /** 一项明确的资源不足类型。 */
    public record ResourceShortage(PullTaskResourceShortageType type) {
    }

    /** 剩余目标、可用拉手和不足标签。 */
    public record ResourceStats(
            Integer remainingTargetCount,
            Integer availablePullerCount,
            List<ResourceShortage> shortages
    ) {
    }
}
