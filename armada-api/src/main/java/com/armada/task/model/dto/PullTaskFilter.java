package com.armada.task.model.dto;

import com.armada.task.model.enums.PullTaskGroupSource;
import com.armada.task.model.enums.PullTaskType;

/**
 * 拉群任务统一列表的 SQL 筛选条件。
 *
 * @param id          任务 ID 精确值
 * @param keyword     任务名称或群名称关键字
 * @param status      普通或拉群营销任务状态码
 * @param taskType    公共任务类型
 * @param groupSource 拉群营销群组来源
 * @param operator    操作员展示名关键字
 * @param hiddenTaskType 列表中需要隐藏内部状态的任务类型
 * @param hiddenStatus 该任务类型不进入列表的内部状态
 */
public record PullTaskFilter(
        Long id,
        String keyword,
        String status,
        PullTaskType taskType,
        PullTaskGroupSource groupSource,
        String operator,
        PullTaskType hiddenTaskType,
        String hiddenStatus
) {

    /** 使用普通群链接草稿隐藏规则的兼容构造。 */
    public PullTaskFilter(
            Long id,
            String keyword,
            String status,
            PullTaskType taskType,
            PullTaskGroupSource groupSource,
            String operator) {
        this(id, keyword, status, taskType, groupSource, operator,
                PullTaskType.STANDARD, com.armada.task.model.enums.PullTaskStandardStatus.DRAFT.name());
    }
}
