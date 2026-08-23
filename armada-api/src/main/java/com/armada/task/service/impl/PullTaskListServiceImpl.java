package com.armada.task.service.impl;

import com.armada.shared.response.PageResult;
import com.armada.task.mapper.PullTaskGroupMarketingSummaryMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskStandardReadMapper;
import com.armada.task.model.dto.PullTaskFilter;
import com.armada.task.model.dto.PullTaskQuery;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupMarketingSummary;
import com.armada.task.model.enums.PullTaskListAction;
import com.armada.task.model.enums.PullTaskMarketingStatus;
import com.armada.task.model.enums.PullTaskResourceShortageType;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import com.armada.task.model.vo.PullTaskListVO;
import com.armada.task.model.vo.PullTaskStandardTaskAggregate;
import com.armada.task.service.PullTaskListService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 普通拉群与拉群营销任务统一一级列表读服务实现。
 *
 * <p>分页、筛选和排序由任务主表 SQL 完成，当前页拉群营销统计再通过一次批量查询装配，避免列表
 * N+1 查询。统计行缺失表示业务结果未知，各统计分组保持为空，不使用零值或任务配置代替。</p>
 */
@Service
public class PullTaskListServiceImpl implements PullTaskListService {

    /** 有效成功率保留的小数位数。 */
    private static final int RATE_SCALE = 1;

    /** 将成功人数比例换算成百分比的倍率。 */
    private static final int PERCENT_FACTOR = 100;

    /** 拉群任务公共主表分页查询入口，租户条件由 MyBatis 租户拦截器注入。 */
    private final PullTaskMapper taskMapper;

    /** 拉群营销任务级聚合统计批量查询入口。 */
    private final PullTaskGroupMarketingSummaryMapper summaryMapper;

    /** 普通群链接任务的执行、料子和资源事实批量聚合入口。 */
    private final PullTaskStandardReadMapper standardReadMapper;

    /**
     * 装配拉群任务统一列表读服务。
     *
     * @param taskMapper    公共任务主表 Mapper
     * @param summaryMapper 拉群营销统计 Mapper
     */
    public PullTaskListServiceImpl(
            PullTaskMapper taskMapper,
            PullTaskGroupMarketingSummaryMapper summaryMapper,
            PullTaskStandardReadMapper standardReadMapper) {
        this.taskMapper = taskMapper;
        this.summaryMapper = summaryMapper;
        this.standardReadMapper = standardReadMapper;
    }

    /**
     * 分页查询当前租户的普通拉群与拉群营销任务。
     *
     * <p>查询条件全部下推数据库，任务默认按 ID 倒序。仅为当前页中的拉群营销任务批量读取统计；
     * 没有统计行时响应中的六个统计分组均为空，以便前端展示 {@code --}。</p>
     *
     * @param query 分页和筛选条件；为空时使用 {@link PullTaskQuery} 的默认分页参数
     * @return 当前租户的九列任务分页数据
     */
    @Override
    public PageResult<PullTaskListVO> list(PullTaskQuery query) {
        PullTaskQuery safeQuery = query == null ? new PullTaskQuery() : query;
        PullTaskFilter filter = safeQuery.toFilter();
        long total = taskMapper.countPage(filter);
        if (total == 0) {
            return PageResult.of(List.of(), safeQuery.getPage(), safeQuery.getPageSize(), 0);
        }
        List<PullTask> tasks = taskMapper.selectPage(
                filter, safeQuery.getOffset(), safeQuery.getPageSize());
        Map<Long, PullTaskGroupMarketingSummary> summaries = loadSummaries(tasks);
        Map<Long, PullTaskStandardTaskAggregate> standardAggregates =
                loadStandardAggregates(tasks);
        List<PullTaskListVO> rows = tasks.stream()
                .map(task -> toVO(
                        task, summaries.get(task.getId()), standardAggregates.get(task.getId())))
                .toList();
        return PageResult.of(rows, safeQuery.getPage(), safeQuery.getPageSize(), total);
    }

    /**
     * 批量读取当前页拉群营销任务的聚合统计。
     *
     * <p>普通任务不会访问营销统计表；数据库缺失的统计行不会补零。</p>
     *
     * @param tasks 当前页公共任务
     * @return 以任务 ID 为键的已存在统计；当前页没有营销任务时返回空映射
     */
    private Map<Long, PullTaskGroupMarketingSummary> loadSummaries(List<PullTask> tasks) {
        List<Long> marketingTaskIds = tasks.stream()
                .filter(task -> PullTaskType.GROUP_MARKETING == task.getTaskType())
                .map(PullTask::getId)
                .toList();
        if (marketingTaskIds.isEmpty()) {
            return Map.of();
        }
        return summaryMapper.selectByTaskIds(marketingTaskIds).stream()
                .collect(Collectors.toMap(
                        PullTaskGroupMarketingSummary::getTaskId, Function.identity()));
    }

    /** 当前页普通群链接任务一次批量读取，避免按任务查询明细形成 N+1。 */
    private Map<Long, PullTaskStandardTaskAggregate> loadStandardAggregates(
            List<PullTask> tasks) {
        List<Long> taskIds = tasks.stream()
                .filter(PullTaskListServiceImpl::normalLink)
                .map(PullTask::getId)
                .toList();
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        return standardReadMapper.selectTaskAggregates(
                        com.armada.task.model.dto.PullTaskStandardAggregateCriteria
                                .fromEnums(taskIds)).stream()
                .collect(Collectors.toMap(
                        PullTaskStandardTaskAggregate::getTaskId, Function.identity()));
    }

    /**
     * 将公共任务与可选的营销聚合统计装配为统一列表行。
     *
     * @param task 公共任务主表记录
     * @param summary 拉群营销统计记录；缺失时为 {@code null}
     * @return 九列列表响应行
     */
    private static PullTaskListVO toVO(
            PullTask task,
            PullTaskGroupMarketingSummary summary,
            PullTaskStandardTaskAggregate standard) {
        return new PullTaskListVO(
                task.getId(), task.getTaskName(), task.getGroupName(), task.getMode(),
                task.getCreationMode(), task.getTaskType(), task.getGroupSource(),
                task.getStatus(), task.getPrimaryStage(),
                task.getBlockingReason(), task.getOperatorName(), task.getGroupCount(),
                task.getExpectedPullCount(), task.getRemark(),
                standard == null ? groupProgress(summary) : groupProgress(standard),
                standard == null ? pullResult(summary) : pullResult(standard),
                marketingProgress(summary), messageStats(summary),
                standard == null ? exceptionStats(summary) : exceptionStats(standard),
                standard == null ? resourceStats(summary) : resourceStats(standard),
                task.getCreatedAt(),
                standard == null ? task.getLastBusinessExecutedAt() : standard.getLastExecutedAt(),
                allowedActions(task));
    }

    /**
     * 计算已经形成转移终态的群组进度。
     *
     * <p>已处理数只统计成功、待收口、部分完成和失败，不把执行中或等待执行计入。</p>
     *
     * @param summary 拉群营销统计记录；缺失时为 {@code null}
     * @return 群组处理进度；统计行缺失时返回 {@code null}
     */
    private static PullTaskListVO.GroupProgress groupProgress(
            PullTaskGroupMarketingSummary summary) {
        if (summary == null) {
            return null;
        }
        int processed = summary.getTransferSuccessCount()
                + summary.getTransferPendingCloseCount()
                + summary.getTransferPartialCount()
                + summary.getTransferFailedCount();
        return new PullTaskListVO.GroupProgress(
                processed, summary.getTargetGroupCount(), summary.getTransferSuccessCount(),
                summary.getTransferPendingCloseCount(), summary.getTransferPartialCount(),
                summary.getTransferFailedCount(), summary.getTransferRunningCount(),
                summary.getTransferWaitingCount());
    }

    /**
     * 装配拉人最终结果和有效成功率。
     *
     * @param summary 拉群营销统计记录；缺失时为 {@code null}
     * @return 拉人结果；统计行缺失时返回 {@code null}
     */
    private static PullTaskListVO.PullResult pullResult(
            PullTaskGroupMarketingSummary summary) {
        if (summary == null) {
            return null;
        }
        return new PullTaskListVO.PullResult(
                summary.getPlannedTargetCount(), summary.getEffectiveTargetCount(),
                summary.getJoinedSuccessCount(), summary.getAlreadyInGroupCount(),
                summary.getPrivacyRestrictedCount(), summary.getInvalidNumberCount(),
                summary.getUnregisteredCount(),
                summary.getPrivacyRestrictedCount() + summary.getInvalidNumberCount()
                        + summary.getUnregisteredCount(),
                summary.getPullResultUnknownCount(),
                summary.getRemainingTargetCount(), successRate(summary));
    }

    /**
     * 按“新增成功人数 ÷ 有效目标人数”计算有效成功率。
     *
     * @param summary 拉群营销统计记录
     * @return 一位小数的百分比；有效目标人数不大于 0 时返回 {@code null} 表示未知
     */
    private static BigDecimal successRate(PullTaskGroupMarketingSummary summary) {
        if (summary.getEffectiveTargetCount() <= 0) {
            return null;
        }
        return BigDecimal.valueOf(summary.getJoinedSuccessCount())
                .multiply(BigDecimal.valueOf(PERCENT_FACTOR))
                .divide(BigDecimal.valueOf(summary.getEffectiveTargetCount()),
                        RATE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 装配营销群组状态统计。
     *
     * @param summary 拉群营销统计记录；缺失时为 {@code null}
     * @return 营销进度；统计行缺失时返回 {@code null}
     */
    private static PullTaskListVO.MarketingProgress marketingProgress(
            PullTaskGroupMarketingSummary summary) {
        if (summary == null) {
            return null;
        }
        return new PullTaskListVO.MarketingProgress(
                summary.getMarketingWaitingCount(), summary.getMarketingRunningCount(),
                summary.getMarketingPausedCount(), summary.getMarketingCompletedCount(),
                summary.getMarketingAbnormalStoppedCount());
    }

    /**
     * 装配消息最终发送结果统计。
     *
     * @param summary 拉群营销统计记录；缺失时为 {@code null}
     * @return 消息发送统计；统计行缺失时返回 {@code null}
     */
    private static PullTaskListVO.MessageStats messageStats(
            PullTaskGroupMarketingSummary summary) {
        if (summary == null) {
            return null;
        }
        return new PullTaskListVO.MessageStats(
                summary.getMessageSuccessCount(), summary.getMessageFailedCount(),
                summary.getMessageUnknownCount());
    }

    /**
     * 装配异常群组和封禁账号统计。
     *
     * <p>缺少拉手群组属于异常群组子集，只透传明细，不与异常群组数相加。</p>
     *
     * @param summary 拉群营销统计记录；缺失时为 {@code null}
     * @return 异常统计；统计行缺失时返回 {@code null}
     */
    private static PullTaskListVO.ExceptionStats exceptionStats(
            PullTaskGroupMarketingSummary summary) {
        if (summary == null) {
            return null;
        }
        return new PullTaskListVO.ExceptionStats(
                summary.getAbnormalGroupCount(), null, summary.getPullerShortageGroupCount(), null,
                summary.getBannedAccountCount());
    }

    /**
     * 装配当前剩余资源和明确的资源不足类型。
     *
     * @param summary 拉群营销统计记录；缺失时为 {@code null}
     * @return 剩余资源统计；统计行缺失时返回 {@code null}
     */
    private static PullTaskListVO.ResourceStats resourceStats(
            PullTaskGroupMarketingSummary summary) {
        if (summary == null) {
            return null;
        }
        List<PullTaskListVO.ResourceShortage> shortages = new ArrayList<>();
        addShortage(shortages, summary.isTargetDataShortage(),
                PullTaskResourceShortageType.TARGET_DATA);
        addShortage(shortages, summary.isPullerShortage(), PullTaskResourceShortageType.PULLER);
        addShortage(shortages, summary.isWaterArmyShortage(),
                PullTaskResourceShortageType.WATER_ARMY);
        addShortage(shortages, summary.isAdminShortage(), PullTaskResourceShortageType.ADMIN);
        addShortage(shortages, summary.isMarketingAdminShortage(),
                PullTaskResourceShortageType.MARKETING_ADMIN);
        return new PullTaskListVO.ResourceStats(
                summary.getRemainingTargetCount(), summary.getAvailablePullerCount(),
                List.copyOf(shortages));
    }

    /**
     * 将已确认不足的资源类型加入响应标签集合。
     *
     * @param shortages 当前任务的资源不足标签集合
     * @param shortage 是否确认该类资源不足
     * @param type 资源不足类型
     */
    private static void addShortage(
            List<PullTaskListVO.ResourceShortage> shortages,
            boolean shortage,
            PullTaskResourceShortageType type) {
        if (shortage) {
            shortages.add(new PullTaskListVO.ResourceShortage(type));
        }
    }

    private static PullTaskListVO.GroupProgress groupProgress(
            PullTaskStandardTaskAggregate aggregate) {
        int terminal = aggregate.getCompletedGroupCount()
                + aggregate.getFailedGroupCount() + aggregate.getAbandonedGroupCount();
        return new PullTaskListVO.GroupProgress(
                terminal, aggregate.getTotalGroupCount(), aggregate.getCompletedGroupCount(),
                null, null,
                aggregate.getFailedGroupCount() + aggregate.getAbandonedGroupCount(),
                aggregate.getExecutingGroupCount(), aggregate.getWaitingGroupCount());
    }

    private static PullTaskListVO.PullResult pullResult(
            PullTaskStandardTaskAggregate aggregate) {
        int total = aggregate.getTotalMemberCount();
        int success = aggregate.getSuccessfulMemberCount();
        BigDecimal rate = total == 0 ? null : BigDecimal.valueOf(success)
                .multiply(BigDecimal.valueOf(PERCENT_FACTOR))
                .divide(BigDecimal.valueOf(total), RATE_SCALE, RoundingMode.HALF_UP);
        return new PullTaskListVO.PullResult(
                total, total, success, null, null, null, null,
                aggregate.getFailedMemberCount(), aggregate.getUnknownMemberCount(),
                aggregate.getUnconsumedMemberCount(), rate);
    }

    private static PullTaskListVO.ExceptionStats exceptionStats(
            PullTaskStandardTaskAggregate aggregate) {
        int abnormal = aggregate.getFailedGroupCount() + aggregate.getAbandonedGroupCount()
                + aggregate.getManagerShortageGroupCount()
                + aggregate.getPullerShortageGroupCount()
                + aggregate.getStationShortageGroupCount();
        return new PullTaskListVO.ExceptionStats(
                abnormal, aggregate.getManagerShortageGroupCount(),
                aggregate.getPullerShortageGroupCount(),
                aggregate.getStationShortageGroupCount(), null);
    }

    private static PullTaskListVO.ResourceStats resourceStats(
            PullTaskStandardTaskAggregate aggregate) {
        List<PullTaskListVO.ResourceShortage> shortages = new ArrayList<>();
        addShortage(shortages, aggregate.getManagerShortageGroupCount() > 0,
                PullTaskResourceShortageType.ADMIN);
        addShortage(shortages, aggregate.getPullerShortageGroupCount() > 0,
                PullTaskResourceShortageType.PULLER);
        addShortage(shortages, aggregate.getStationShortageGroupCount() > 0,
                PullTaskResourceShortageType.STATION);
        return new PullTaskListVO.ResourceStats(
                aggregate.getUnconsumedMemberCount(), aggregate.getAvailablePullerCount(),
                List.copyOf(shortages));
    }

    /**
     * 返回当前后端真实支持且符合任务状态的列表行操作。
     *
     * <p>普通群链接任务的生命周期动作只按后端真实状态返回；旧普通任务和营销任务
     * 不借用这组接口。</p>
     *
     * @param task 公共任务主表记录
     * @return 至少包含详情操作的不可变操作集合
     */
    private static List<PullTaskListAction> allowedActions(PullTask task) {
        if (normalLink(task)) {
            if (PullTaskStandardStatus.WAIT_START.name().equals(task.getStatus())) {
                return List.of(PullTaskListAction.DETAIL,
                        PullTaskListAction.START, PullTaskListAction.DELETE);
            }
            if (PullTaskStandardStatus.EXECUTING.name().equals(task.getStatus())) {
                return List.of(PullTaskListAction.DETAIL,
                        PullTaskListAction.PAUSE, PullTaskListAction.END);
            }
            if (PullTaskStandardStatus.PAUSED.name().equals(task.getStatus())) {
                return List.of(PullTaskListAction.DETAIL,
                        PullTaskListAction.RESUME, PullTaskListAction.END);
            }
            if (PullTaskStandardStatus.WAIT_GROUP_RESOURCE.name().equals(task.getStatus())) {
                return List.of(PullTaskListAction.DETAIL,
                        PullTaskListAction.RESUME, PullTaskListAction.END);
            }
        }
        if (deletable(task)) {
            return List.of(PullTaskListAction.DETAIL, PullTaskListAction.DELETE);
        }
        return List.of(PullTaskListAction.DETAIL);
    }

    private static boolean normalLink(PullTask task) {
        return task.getTaskType() == PullTaskType.STANDARD
                && "NORMAL_LINK".equals(task.getMode());
    }

    /**
     * 判断任务是否满足类型专属删除规则。
     *
     * <p>该判断用于列表展示；Mapper 更新 SQL 会再次校验相同规则，防止状态并发变化。</p>
     *
     * @param task 公共任务主表记录
     * @return 拉群营销草稿或普通任务允许删除状态时返回 {@code true}
     */
    private static boolean deletable(PullTask task) {
        if (PullTaskType.GROUP_MARKETING == task.getTaskType()) {
            return PullTaskMarketingStatus.DRAFT.name().equals(task.getStatus());
        }
        return PullTaskStandardStatus.WAIT_START.name().equals(task.getStatus())
                || PullTaskStandardStatus.COMPLETED.name().equals(task.getStatus())
                || PullTaskStandardStatus.ENDED.name().equals(task.getStatus());
    }
}
