package com.armada.task.mapper;

import com.armada.task.model.entity.PullTaskPullCall;
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
    int insertPlanned(PullTaskPullCall row);

    /**
     * 取执行行下仍处于"计划"状态的调用，供服务重启后重投。
     *
     * @param groupExecutionId 执行行 ID
     * @return 计划中的调用，按 callSeq 升序
     */
    List<PullTaskPullCall> selectPlannedByExecution(
            @Param("groupExecutionId") long groupExecutionId);

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
    int markSubmitted(@Param("id") long id,
                      @Param("commandId") String commandId,
                      @Param("now") long now);

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
     * @return 实际更新行数
     */
    int writeBackResult(@Param("id") long id,
                        @Param("callStatus") int callStatus,
                        @Param("reasonCode") String reasonCode,
                        @Param("reasonMessage") String reasonMessage,
                        @Param("now") long now);

    /**
     * 协议回调按命令 ID 定位调用行。
     *
     * @param commandId 协议命令 ID
     * @return 调用行；不存在或不属于当前租户时为 null
     */
    PullTaskPullCall selectByCommandId(@Param("commandId") String commandId);
}
