package com.armada.task.mapper;

import com.armada.task.model.dto.PullTaskCallReassignment;
import com.armada.task.model.dto.PullTaskFactResult;
import com.armada.task.model.dto.PullTaskFactTransition;
import com.armada.task.model.dto.PullTaskPullerAssignment;
import com.armada.task.model.dto.PullTaskPlannedCallPullerBinding;
import com.armada.task.model.dto.PullTaskPlannedCallPrune;
import com.armada.task.model.dto.PullTaskLegacyCallWaveBinding;
import com.armada.task.model.dto.PullTaskLegacyPullerGenerationBinding;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 单次批量加成员调用数据访问层。 */
@Mapper
public interface PullTaskPullCallMapper {

    /**
     * 在提交协议命令之前写入调用行。
     *
     * <p>必须与"把本次料子和站台的 {@code pull_call_id} 指向该调用"在同一个事务内完成，
     * 之后才投递协议命令。崩溃恢复时看到 {@code call_status=1} 的行，用原
     * {@code idempotency_key} 重投，绝不重新分配料子。</p>
     *
     * @param row 调用行；写入后回填 id
     * @return 新增行数
     */
    int insertInitialized(PullTaskPullCall row);

    /** 兼容业务入口；初始状态由 Java 枚举设置后再交给 XML 持久化。 */
    default int insertPlanned(PullTaskPullCall row) {
        row.setCallStatus(PullTaskPullCallStatus.PLANNED.code());
        return insertInitialized(row);
    }

    /**
     * 读取执行行的全部拉人调用，按调用序号升序。
     *
     * @param groupExecutionId 执行行 ID
     * @return 全部调用
     */
    List<PullTaskPullCall> selectByExecution(
            @Param("groupExecutionId") long groupExecutionId);

    /**
     * 取执行行下仍处于"计划"状态的调用，供服务重启后重投。
     *
     * @param groupExecutionId 执行行 ID
     * @return 计划中的调用，按 callSeq 升序
     */
    List<PullTaskPullCall> selectByExecutionAndStatus(
            @Param("groupExecutionId") long groupExecutionId,
            @Param("callStatus") int callStatus);

    /** 按波次和波次内序号读取被冻结调用。 */
    PullTaskPullCall selectByWaveAndSeq(
            @Param("pullWaveId") long pullWaveId,
            @Param("waveCallSeq") int waveCallSeq);

    /** 兼容业务入口；计划态由 Java 枚举传入查询。 */
    default List<PullTaskPullCall> selectPlannedByExecution(long groupExecutionId) {
        return selectByExecutionAndStatus(
                groupExecutionId, PullTaskPullCallStatus.PLANNED.code());
    }

    /**
     * 取某个拉手账号最近一次调用的提交时间，用于校验账号级拉人间隔。
     *
     * <p>拉人间隔只约束同一拉手账号的连续调用；不同拉手在同一群内轮询不设群级间隔。</p>
     *
     * @param pullerAccountId 拉手账号 ID
     * @return 最近提交时间(epoch 毫秒)；该账号尚无已提交调用时为 null
     */
    Long selectLastSubmittedAtByPuller(@Param("pullerAccountId") long pullerAccountId);

    /**
     * 标记调用命令已提交。
     *
     * @param id 调用行 ID
     * @param commandId 协议命令 ID
     * @param now 提交时间(epoch 毫秒)
     * @return 实际更新行数；0 表示该调用已不在计划状态
     */
    int transitionSubmitted(@Param("id") long id,
                            @Param("expectedStatus") int expectedStatus,
                            @Param("targetStatus") int targetStatus,
                            @Param("commandId") String commandId,
                            @Param("now") long now);

    /** 兼容业务入口；前置态和目标态由 Java 枚举传入 CAS。 */
    default int markSubmitted(long id, String commandId, long now) {
        return transitionSubmitted(
                id,
                PullTaskPullCallStatus.PLANNED.code(),
                PullTaskPullCallStatus.SUBMITTED.code(),
                commandId,
                now);
    }

    /**
     * 协议命令尚未提交时，把完整冻结计划改派给另一个实时可用拉手。
     *
     * @param id 调用行 ID
     * @param expectedPullerGroupAccountId 原拉手角色行 ID
     * @param pullerGroupAccountId 新拉手角色行 ID
     * @param pullerAccountId 新拉手账号 ID
     * @param now 更新时间(epoch 毫秒)
     * @return 1 表示改派成功；0 表示调用或原拉手已变化
     */
    int reassignPuller(@Param("reassignment") PullTaskCallReassignment reassignment);

    /** 把仍为计划态的调用绑定到选中的粘性拉手代际。 */
    int bindPlannedPuller(
            @Param("binding") PullTaskPlannedCallPullerBinding binding);

    /** 迟到成功时只从仍为计划态的调用剔除对应参与者类型的一人。 */
    int prunePlannedParticipant(
            @Param("prune") PullTaskPlannedCallPrune prune);

    /** 按 Java 端稳定排序把一条开放历史调用挂接到初始波次。 */
    int attachOpenLegacyCallsToWave(
            @Param("binding") PullTaskLegacyCallWaveBinding binding);

    /** 为粘性拉手相同的开放历史调用补齐分配代际。 */
    int bindLegacyPullerGeneration(
            @Param("binding") PullTaskLegacyPullerGenerationBinding binding);

    /** 兼容业务入口；只有计划态调用允许改派。 */
    default int reassignPlannedPuller(long id,
                                      long expectedPullerGroupAccountId,
                                      long pullerGroupAccountId,
                                      long pullerAccountId,
                                      long now) {
        return reassignPuller(new PullTaskCallReassignment(
                id,
                expectedPullerGroupAccountId,
                new PullTaskPullerAssignment(pullerGroupAccountId, pullerAccountId),
                PullTaskPullCallStatus.PLANNED.code(),
                now));
    }

    /**
     * 回写调用整体结果。
     *
     * <p>逐参与者结果分别落在 {@code pull_task_material_member} 和
     * {@code pull_task_group_account} 上，本方法只推进调用行自身的状态。</p>
     *
     * @param id 调用行 ID
     * @param callStatus 调用状态，取值见 PullTaskPullCallStatus
     * @param reasonCode 失败原因码
     * @param reasonMessage 失败原因描述(已脱敏)
     * @param now 回写时间(epoch 毫秒)
     * @return 实际更新行数；0 表示调用已不在已提交状态
     */
    default int writeBackResult(long id,
                                int callStatus,
                                String reasonCode,
                                String reasonMessage,
                                long now) {
        return transitionResult(new PullTaskFactTransition(
                id,
                List.of(PullTaskPullCallStatus.SUBMITTED.code()),
                callStatus,
                PullTaskFactResult.reason(reasonCode, reasonMessage),
                now));
    }

    /** 从已提交/未知等允许状态 CAS 收敛调用结果。 */
    int transitionResult(@Param("transition") PullTaskFactTransition transition);

    /**
     * 协议回调按命令 ID 定位调用行。
     *
     * @param commandId 协议命令 ID
     * @return 调用行；不存在或不属于当前租户时为 null
     */
    PullTaskPullCall selectByCommandId(@Param("commandId") String commandId);

    /** 跨实例至多一次认领异常批次的群成员名单查询。 */
    int claimRosterCheck(
            @Param("id") long id,
            @Param("expectedStatus") int expectedStatus,
            @Param("targetStatus") int targetStatus,
            @Param("now") long now);

    /** 完成已经认领的名单查询；CLAIMED 不允许退回 NOT_STARTED。 */
    int finishRosterCheck(
            @Param("id") long id,
            @Param("expectedStatus") int expectedStatus,
            @Param("targetStatus") int targetStatus,
            @Param("now") long now);

    /** 任务结束时取消仍处于计划态、尚未提交协议命令的调用。 */
    int cancelPlannedByTask(@Param("taskId") long taskId,
                            @Param("expectedStatus") int expectedStatus,
                            @Param("targetStatus") int targetStatus,
                            @Param("now") long now);

    /** 单群结束时取消该执行行尚未提交的拉人调用。 */
    int cancelPlannedByExecution(@Param("groupExecutionId") long groupExecutionId,
                                 @Param("expectedStatus") int expectedStatus,
                                 @Param("targetStatus") int targetStatus,
                                 @Param("now") long now);

    /** 把 Outbox 已取消但已标为提交的批量拉人调用收敛为取消。 */
    int cancelUnpublishedSubmitted(
            @Param("taskId") long taskId,
            @Param("groupExecutionId") Long groupExecutionId,
            @Param("expectedStatus") int expectedStatus,
            @Param("targetStatus") int targetStatus,
            @Param("canceledOutboxStatus") int canceledOutboxStatus,
            @Param("now") long now);
}
