package com.armada.task.mapper;

import com.armada.task.model.dto.GroupDataPackageTaskFact;
import com.armada.task.model.entity.PullTaskGroupExecution;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 只读取任务聚合自己的数据包来源及执行事实，不穿透资源表。 */
@Mapper
public interface GroupDataPackageTaskProjectionMapper {
    /** 锁住每个逻辑执行单元的最新行；SQL 显式约束租户，避免插件把 ORDER BY 移到 FOR UPDATE 后。 */
    @com.baomidou.mybatisplus.annotation.InterceptorIgnore(tenantLine = "true")
    List<PullTaskGroupExecution> lockLatestExecutionsByStatus(@Param("packageIds") List<Long> packageIds,
            @Param("draftStatus") int draftStatus, @Param("tenantId") Long tenantId);
    /** 草稿不占用资源，无需参与结果投影。 */
    default List<PullTaskGroupExecution> lockLatestExecutions(List<Long> packageIds) {
        return lockLatestExecutionsByStatus(packageIds,
                com.armada.task.model.enums.PullTaskExecutionStatus.DRAFT.code(),
                com.armada.shared.tenant.TenantContext.get());
    }
    /** 在执行行锁内批量读取物料事实；未领取的草稿不会进入。 */
    List<GroupDataPackageTaskFact> selectFactsByStatus(@Param("executionIds") List<Long> executionIds,
            @Param("uncertainStatuses") List<Integer> uncertainStatuses,
            @Param("consumedStatuses") List<Integer> consumedStatuses,
            @Param("memberIds") List<Long> memberIds,
            @Param("materialType") int materialType,
            @Param("unknownOutcome") String unknownOutcome,
            @Param("notStartedState") String notStartedState);
    /** 历史状态集合由任务枚举定义。 */
    default List<GroupDataPackageTaskFact> selectFacts(List<Long> executionIds) {
        return selectMemberFacts(executionIds, null);
    }
    /** 可选成员范围用于高频回调，禁止扫描整个包。 */
    default List<GroupDataPackageTaskFact> selectMemberFacts(List<Long> executionIds, List<Long> memberIds) {
        return selectFactsByStatus(executionIds, List.of(
                com.armada.task.model.enums.PullTaskMaterialPullStatus.SUBMITTED.code(),
                com.armada.task.model.enums.PullTaskMaterialPullStatus.UNKNOWN.code()), List.of(
                com.armada.task.model.enums.PullTaskMaterialPullStatus.SUCCESS.code(),
                com.armada.task.model.enums.PullTaskMaterialPullStatus.FAILED.code()), memberIds,
                com.armada.task.model.enums.PullTaskParticipantType.MATERIAL.code(),
                com.armada.task.model.enums.PullTaskBatchParticipantProtocolOutcome.UNKNOWN.name(),
                com.armada.task.model.enums.PullTaskParticipantExecutionState.NOT_STARTED.name());
    }
    /** 与协议结果、换群使用同一执行行锁；原文件执行不需要资源投影。 */
    PullTaskGroupExecution lockSourceExecution(@Param("executionId") long executionId);
    /** 任务结束和删除只锁本任务的最新执行，不扩展到同包其他任务。 */
    @com.baomidou.mybatisplus.annotation.InterceptorIgnore(tenantLine = "true")
    List<PullTaskGroupExecution> lockLatestTaskExecutionsByStatus(@Param("taskId") long taskId,
            @Param("draftStatus") int draftStatus, @Param("tenantId") Long tenantId);
    /** 草稿无资源占用；显式租户范围与数据包范围同步一致。 */
    default List<PullTaskGroupExecution> lockLatestTaskExecutions(long taskId) {
        return lockLatestTaskExecutionsByStatus(taskId,
                com.armada.task.model.enums.PullTaskExecutionStatus.DRAFT.code(),
                com.armada.shared.tenant.TenantContext.get());
    }
    /** 只查询明确失败且已被归一化为隐私拒绝的号码。 */
    List<String> selectPrivacyRejectedPhones(@Param("phones") List<String> phones,
            @Param("cutoff") long cutoff, @Param("failedStatus") int failedStatus,
            @Param("reasonCodes") List<String> reasonCodes);
    /** 判断包是否仍被非终态正式任务引用。 */
    boolean hasActiveUsage(@Param("packageId") long packageId,
            @Param("inactiveStatuses") List<String> inactiveStatuses);
}
