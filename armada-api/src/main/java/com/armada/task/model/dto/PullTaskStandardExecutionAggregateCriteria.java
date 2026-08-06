package com.armada.task.model.dto;

import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountAdminStatus;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import java.util.List;

/** RD-02 当前页执行行的资源和料子聚合口径。 */
public record PullTaskStandardExecutionAggregateCriteria(
        List<Long> executionIds,
        PullTaskStandardAggregateCriteria.Material material,
        Account account) {

    /** 固化执行行范围并拒绝生成空 IN。 */
    public PullTaskStandardExecutionAggregateCriteria {
        executionIds = List.copyOf(executionIds);
        if (executionIds.isEmpty()) {
            throw new IllegalArgumentException("聚合执行行不能为空");
        }
    }

    /** @return 使用当前领域枚举生成的详情聚合口径 */
    public static PullTaskStandardExecutionAggregateCriteria fromEnums(
            List<Long> executionIds) {
        return new PullTaskStandardExecutionAggregateCriteria(
                executionIds,
                new PullTaskStandardAggregateCriteria.Material(
                        PullTaskMaterialPullStatus.UNCONSUMED.code(),
                        PullTaskMaterialPullStatus.SUBMITTED.code(),
                        PullTaskMaterialPullStatus.SUCCESS.code(),
                        PullTaskMaterialPullStatus.FAILED.code(),
                        PullTaskMaterialPullStatus.UNKNOWN.code(),
                        PullTaskMaterialPullStatus.CANCELED.code()),
                new Account(
                        PullTaskGroupAccountRole.MANAGER.code(),
                        PullTaskGroupAccountRole.PULLER.code(),
                        PullTaskGroupAccountRole.STATION.code(),
                        PullTaskGroupAccountAvailability.AVAILABLE.code(),
                        PullTaskGroupAccountMembershipStatus.IN_GROUP.code(),
                        PullTaskGroupAccountAdminStatus.SUCCESS.code()));
    }

    /** 角色类型及“当前有效”事实口径。 */
    public record Account(
            int managerRole,
            int pullerRole,
            int stationRole,
            int available,
            int inGroup,
            int adminSuccess) {
    }
}
