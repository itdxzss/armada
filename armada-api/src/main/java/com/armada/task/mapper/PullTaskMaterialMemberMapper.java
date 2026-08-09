package com.armada.task.mapper;

import com.armada.task.model.dto.PullTaskFactStatusCriteria;
import com.armada.task.model.dto.PullTaskFactTransition;
import com.armada.task.model.dto.PullTaskMaterialPullResult;
import com.armada.task.model.dto.PullTaskParticipantAggregateTransition;
import com.armada.task.model.dto.PullTaskParticipantAttemptBinding;
import com.armada.task.model.dto.PullTaskParticipantPlanBinding;
import com.armada.task.model.dto.PullTaskPullWaveCandidate;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.enums.PullTaskMaterialAdminStatus;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskParticipantType;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 料子号码与逐号码结果数据访问层。 */
@Mapper
public interface PullTaskMaterialMemberMapper {

    /**
     * 解析 TXT 后批量写入去重后的有效号码。
     *
     * @param rows 料子成员，按 memberSeq 升序
     * @return 新增行数
     */
    int batchInsertInitialized(@Param("rows") List<PullTaskMaterialMember> rows);

    /** 初始化料子事实；状态选择留在 Java，XML 只持久化明确值。 */
    default int batchInsert(List<PullTaskMaterialMember> rows) {
        rows.forEach(row -> {
            row.setPullStatus(PullTaskMaterialPullStatus.UNCONSUMED.code());
            row.setAdminStatus(Integer.valueOf(1).equals(row.getAdminRequired())
                    ? PullTaskMaterialAdminStatus.PENDING.code()
                    : PullTaskMaterialAdminStatus.NOT_REQUIRED.code());
        });
        return batchInsertInitialized(rows);
    }

    /**
     * 读取执行行的全部料子，按 memberSeq 升序。
     *
     * @param groupExecutionId 执行行 ID
     * @return 料子列表
     */
    List<PullTaskMaterialMember> selectByExecution(@Param("groupExecutionId") long groupExecutionId);

    /**
     * 删除某条执行行下的全部料子成员。
     *
     * <p>只在创建页的"单行移除"与"清除全部"里使用；执行行是否允许删除由
     * {@code PullTaskGroupExecutionMapper#deleteDraftRow} 的状态守卫把关，
     * 本方法不重复判断执行行状态。</p>
     *
     * @param groupExecutionId 执行行 ID
     * @return 实际删除行数
     */
    int deleteByExecution(@Param("groupExecutionId") long groupExecutionId);

    /**
     * 取下一批尚未消费的料子。
     *
     * <p>{@code pull_call_id IS NULL} 即"未消费"，这就是料子游标本身，
     * 执行行上不再单独存游标列。</p>
     *
     * @param groupExecutionId 执行行 ID
     * @param limit 本次调用需要的料子人数
     * @return 未消费料子，按 memberSeq 升序
     */
    List<PullTaskMaterialMember> selectUnconsumedByStatus(
            @Param("groupExecutionId") long groupExecutionId,
            @Param("pullStatus") int pullStatus,
            @Param("maxFailureCount") long maxFailureCount,
            @Param("limit") int limit);

    /** 未消费状态由 Java 枚举传入 XML。 */
    default List<PullTaskMaterialMember> selectUnconsumed(long groupExecutionId, int limit) {
        return selectUnconsumedByStatus(
                groupExecutionId, PullTaskMaterialPullStatus.UNCONSUMED.code(), 4L, limit);
    }

    /** 读取并稳定排序完整初始波次候选，不做数量截断。 */
    List<PullTaskPullWaveCandidate> selectInitialWaveCandidatesByStatus(
            @Param("groupExecutionId") long groupExecutionId,
            @Param("participantType") int participantType,
            @Param("pullStatus") int pullStatus,
            @Param("maxFailureCount") long maxFailureCount);

    /** 使用普通链接料子的固定初始候选条件。 */
    default List<PullTaskPullWaveCandidate> selectInitialWaveCandidates(long groupExecutionId) {
        return selectInitialWaveCandidatesByStatus(
                groupExecutionId,
                PullTaskParticipantType.MATERIAL.code(),
                PullTaskMaterialPullStatus.UNCONSUMED.code(),
                4L);
    }

    /** 以待拉状态、失败上限和空活动指针 CAS 绑定本次 attempt。 */
    int bindPullAttemptIfEligible(
            @Param("binding") PullTaskParticipantPlanBinding binding,
            @Param("guard") PullTaskParticipantAttemptBinding.Guard guard);

    /** 使用普通链接料子的固定待拉守卫。 */
    default int bindPullAttempt(PullTaskParticipantPlanBinding binding) {
        return bindPullAttemptIfEligible(
                binding, PullTaskParticipantAttemptBinding.materialGuard());
    }

    /** 兼容旧单调用规划入口；真实拉手仍只在提交阶段写入聚合。 */
    default int bindPullAttempt(PullTaskParticipantAttemptBinding binding) {
        return bindPullAttempt(new PullTaskParticipantPlanBinding(
                binding.participantId(), binding.attemptId(),
                binding.pullCallId(), binding.now()));
    }

    /** 批次真实提交时记录最近执行拉手，必须仍由同一活动 attempt 持有。 */
    int markPullAttemptSubmitted(
            @Param("binding") PullTaskParticipantAttemptBinding binding);

    /** 只有当前活动 attempt 和精确失败计数都匹配时才推进聚合状态。 */
    int transitionPullAttempt(
            @Param("transition") PullTaskParticipantAggregateTransition transition);

    /** 单调提升为成功；旧 attempt 成功不得清除更新 attempt 的活动指针。 */
    int promotePullSuccess(
            @Param("transition") PullTaskParticipantAggregateTransition transition);

    /**
     * 把选中的料子绑定到一次拉人调用。
     *
     * <p>只更新仍未消费的行；返回行数小于入参数量说明有并发消费，调用方必须放弃
     * 本次调用并重新取料，不得按原数量提交协议命令。</p>
     *
     * @param ids 料子 ID
     * @param pullCallId 拉人调用 ID
     * @param now 更新时间(epoch 毫秒)
     * @return 实际绑定行数
     */
    int assignToCallIfStatus(
            @Param("ids") List<Long> ids,
            @Param("pullCallId") long pullCallId,
            @Param("expectedStatus") int expectedStatus,
            @Param("targetStatus") int targetStatus,
            @Param("now") long now);

    /** 料子绑定调用的前置态和目标态由 Java 枚举传入。 */
    default int assignToCall(List<Long> ids, long pullCallId, long now) {
        return assignToCallIfStatus(
                ids,
                pullCallId,
                PullTaskMaterialPullStatus.UNCONSUMED.code(),
                PullTaskMaterialPullStatus.SUBMITTED.code(),
                now);
    }

    /**
     * 回写单个号码的入群结果。
     *
     * @param result 料子结果和回写时间
     * @return 实际更新行数；0 表示料子已不在已提交状态
     */
    default int writeBackPullResult(PullTaskMaterialPullResult result) {
        return transitionPullResult(new PullTaskFactTransition(
                result.id(),
                List.of(PullTaskMaterialPullStatus.SUBMITTED.code()),
                result.pullStatus(),
                result.fact(),
                result.now()));
    }

    /** 从已提交/未知等允许状态 CAS 收敛单个号码的入群结果。 */
    int transitionPullResult(@Param("transition") PullTaskFactTransition transition);

    /** 统计一次拉人调用中仍处于指定状态的料子数量。 */
    int countByPullCallAndStatuses(
            @Param("criteria") PullTaskFactStatusCriteria criteria);

    /**
     * 取本执行行待提权的料子：带 A/a 标识、已成功入群、尚未提交提权。
     *
     * @param groupExecutionId 执行行 ID
     * @param adminRequired 需要提权标识
     * @param pullStatus 成功入群状态
     * @param adminStatus 待提权状态
     * @return 待提权料子，按 memberSeq 升序
     */
    List<PullTaskMaterialMember> selectPendingAdmin(
            @Param("groupExecutionId") long groupExecutionId,
            @Param("adminRequired") int adminRequired,
            @Param("pullStatus") int pullStatus,
            @Param("adminStatus") int adminStatus);

    /**
     * 标记提权命令已提交。
     *
     * @param id 料子 ID
     * @param expectedAdminStatus 期望的原提权状态
     * @param submittedAdminStatus 已提交状态
     * @param adminCommandId 提权协议命令 ID
     * @param now 提交时间(epoch 毫秒)
     * @return 实际更新行数
     */
    int markAdminSubmitted(@Param("id") long id,
                           @Param("expectedAdminStatus") int expectedAdminStatus,
                           @Param("submittedAdminStatus") int submittedAdminStatus,
                           @Param("adminCommandId") String adminCommandId,
                           @Param("now") long now);

    /** 以期望状态 CAS 回写提权终态，防止迟到结果覆盖既有事实。 */
    int writeBackAdminResult(@Param("id") long id,
                             @Param("expectedAdminStatus") int expectedAdminStatus,
                             @Param("adminStatus") int adminStatus,
                             @Param("reasonCode") String reasonCode,
                             @Param("now") long now);

    /** 从已提交/未知等允许状态 CAS 收敛料子提权结果。 */
    int transitionAdminResult(@Param("transition") PullTaskFactTransition transition);

    /** 外部提权动作尚未发生时，把预写的 SUBMITTED 安全退回待执行。 */
    int returnAdminToPending(@Param("id") long id,
                             @Param("expectedAdminStatus") int expectedAdminStatus,
                             @Param("pendingAdminStatus") int pendingAdminStatus,
                             @Param("reasonCode") String reasonCode,
                             @Param("now") long now);

    /**
     * 提权回调按命令 ID 定位料子行。
     *
     * @param adminCommandId 提权协议命令 ID
     * @return 料子行；不存在或不属于当前租户时为 null
     */
    PullTaskMaterialMember selectByAdminCommandId(
            @Param("adminCommandId") String adminCommandId);

    /** 取消任务下尚未分配给任何调用的料子。 */
    int cancelUnconsumedByTask(@Param("taskId") long taskId,
                               @Param("expectedStatus") int expectedStatus,
                               @Param("targetStatus") int targetStatus,
                               @Param("now") long now);

    /** 取消已经冻结到计划调用、但调用尚未提交的料子。 */
    int cancelPlannedByTask(@Param("taskId") long taskId,
                            @Param("expectedPullStatus") int expectedPullStatus,
                            @Param("plannedCallStatus") int plannedCallStatus,
                            @Param("targetPullStatus") int targetPullStatus,
                            @Param("now") long now);

    /** 取消任务下尚未提交的料子提权动作。 */
    int cancelPendingAdminByTask(@Param("taskId") long taskId,
                                 @Param("expectedStatus") int expectedStatus,
                                 @Param("targetStatus") int targetStatus,
                                 @Param("now") long now);

    /** 单群结束时取消尚未分配给调用的料子。 */
    int cancelUnconsumedByExecution(@Param("groupExecutionId") long groupExecutionId,
                                    @Param("expectedStatus") int expectedStatus,
                                    @Param("targetStatus") int targetStatus,
                                    @Param("now") long now);

    /** 单群结束时取消已经绑定计划调用、但尚未提交的料子。 */
    int cancelPlannedByExecution(@Param("groupExecutionId") long groupExecutionId,
                                 @Param("expectedPullStatus") int expectedPullStatus,
                                 @Param("plannedCallStatus") int plannedCallStatus,
                                 @Param("targetPullStatus") int targetPullStatus,
                                 @Param("now") long now);

    /** 单群结束时取消尚未提交的料子提权动作。 */
    int cancelPendingAdminByExecution(@Param("groupExecutionId") long groupExecutionId,
                                      @Param("expectedStatus") int expectedStatus,
                                      @Param("targetStatus") int targetStatus,
                                      @Param("now") long now);

    /** 取消随已取消 Outbox 批量调用提交的料子。 */
    int cancelUnpublishedSubmittedPull(
            @Param("taskId") long taskId,
            @Param("groupExecutionId") Long groupExecutionId,
            @Param("expectedStatus") int expectedStatus,
            @Param("targetStatus") int targetStatus,
            @Param("canceledOutboxStatus") int canceledOutboxStatus,
            @Param("releaseBinding") boolean releaseBinding,
            @Param("now") long now);

    /** 取消已写入命令但 Outbox 尚未发布的料子提权。 */
    int cancelUnpublishedSubmittedAdmin(
            @Param("taskId") long taskId,
            @Param("groupExecutionId") Long groupExecutionId,
            @Param("expectedStatus") int expectedStatus,
            @Param("targetStatus") int targetStatus,
            @Param("canceledOutboxStatus") int canceledOutboxStatus,
            @Param("now") long now);
}
