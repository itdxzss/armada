package com.armada.task.model.vo;

import com.armada.task.model.enums.PullTaskCreationMode;
import java.util.List;

/** 普通群链接任务 M1 最小详情。 */
public record PullTaskStandardTaskDetailVO(
        long taskId,
        String taskName,
        String status,
        PullTaskCreationMode creationMode,
        int groupCount,
        int expectedPullCount,
        Long startedAt,
        Long finishedAt,
        Long createdAt,
        String remark,
        List<PullTaskStandardExecutionSummaryVO> executions,
        PullTaskStandardTaskSummaryVO summary,
        PullTaskStandardSettingVO standardSetting,
        PullTaskStandardGroupSettingVO groupSetting) {

    /** 冻结执行行列表。 */
    public PullTaskStandardTaskDetailVO {
        executions = List.copyOf(executions);
    }

    /** M1 兼容构造；聚合尚未装配时保持未知。 */
    public PullTaskStandardTaskDetailVO(
            long taskId,
            String taskName,
            String status,
            int groupCount,
            int expectedPullCount,
            Long startedAt,
            Long finishedAt,
            Long createdAt,
            String remark,
            List<PullTaskStandardExecutionSummaryVO> executions) {
        this(taskId, taskName, status, PullTaskCreationMode.PASTED_LINK,
                groupCount, expectedPullCount, startedAt, finishedAt, createdAt, remark,
                executions, null, null, null);
    }

    /** 聚合兼容构造；设置尚未装配的旧调用保持可编译。 */
    public PullTaskStandardTaskDetailVO(
            long taskId,
            String taskName,
            String status,
            int groupCount,
            int expectedPullCount,
            Long startedAt,
            Long finishedAt,
            Long createdAt,
            String remark,
            List<PullTaskStandardExecutionSummaryVO> executions,
            PullTaskStandardTaskSummaryVO summary) {
        this(taskId, taskName, status, PullTaskCreationMode.PASTED_LINK,
                groupCount, expectedPullCount, startedAt, finishedAt, createdAt, remark,
                executions, summary, null, null);
    }
}
