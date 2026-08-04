package com.armada.task.model.dto;

import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskWaitResourceType;
import java.util.List;

/** RD-01 普通群链接任务批量聚合条件；所有业务状态均由 Java 传给 Mapper。 */
public record PullTaskStandardAggregateCriteria(
        List<Long> taskIds,
        Execution execution,
        Material material,
        Account account) {

    /** 固化任务范围并拒绝生成空 IN。 */
    public PullTaskStandardAggregateCriteria {
        taskIds = List.copyOf(taskIds);
        if (taskIds.isEmpty()) {
            throw new IllegalArgumentException("聚合任务不能为空");
        }
    }

    /** @return 使用当前普通拉群领域枚举生成的完整聚合口径 */
    public static PullTaskStandardAggregateCriteria fromEnums(List<Long> taskIds) {
        return new PullTaskStandardAggregateCriteria(
                taskIds,
                new Execution(
                        PullTaskExecutionStatus.COMPLETED.code(),
                        PullTaskExecutionStatus.FAILED.code(),
                        PullTaskExecutionStatus.ABANDONED.code(),
                        PullTaskExecutionStatus.EXECUTING.code(),
                        PullTaskExecutionStatus.WAIT_START.code(),
                        PullTaskExecutionStatus.WAIT_RESOURCE.code(),
                        PullTaskWaitResourceType.MANAGER.code(),
                        PullTaskWaitResourceType.PULLER.code(),
                        PullTaskWaitResourceType.STATION.code()),
                new Material(
                        PullTaskMaterialPullStatus.UNCONSUMED.code(),
                        PullTaskMaterialPullStatus.SUBMITTED.code(),
                        PullTaskMaterialPullStatus.SUCCESS.code(),
                        PullTaskMaterialPullStatus.FAILED.code(),
                        PullTaskMaterialPullStatus.UNKNOWN.code(),
                        PullTaskMaterialPullStatus.CANCELED.code()),
                new Account(
                        PullTaskGroupAccountRole.PULLER.code(),
                        PullTaskGroupAccountAvailability.AVAILABLE.code()));
    }

    /** 执行行状态及资源等待分类。 */
    public record Execution(
            int completed,
            int failed,
            int abandoned,
            int executing,
            int waitStart,
            int waitResource,
            int managerResource,
            int pullerResource,
            int stationResource) {
    }

    /** 料子逐号码结果状态。 */
    public record Material(
            int unconsumed,
            int submitted,
            int success,
            int failed,
            int unknown,
            int canceled) {
    }

    /** 任务级剩余拉手资源口径。 */
    public record Account(int pullerRole, int available) {
    }
}
