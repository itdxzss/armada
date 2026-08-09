package com.armada.task.mapper;

import com.armada.task.model.dto.PullTaskPullWaveTransition;
import com.armada.task.model.dto.PullTaskPullWaveDispatchAdvance;
import com.armada.task.model.entity.PullTaskPullWave;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 普通群链接拉人波次数据访问层。 */
@Mapper
public interface PullTaskPullWaveMapper {

    /**
     * 写入已经初始化业务字段的波次并回填主键。
     *
     * @param row 波次行
     * @return 新增行数
     */
    int insertInitialized(PullTaskPullWave row);

    /**
     * 按主键读取当前租户的波次。
     *
     * @param id 波次主键
     * @return 波次；不存在或不属于当前租户时为 null
     */
    PullTaskPullWave selectById(@Param("id") long id);

    /**
     * 读取执行行当前唯一活动波次。
     *
     * @param groupExecutionId 执行行 ID
     * @param statuses 活动态集合
     * @return 活动波次；不存在时为 null
     */
    PullTaskPullWave selectActiveByExecution(
            @Param("groupExecutionId") long groupExecutionId,
            @Param("statuses") List<Integer> statuses);

    /**
     * 以状态和版本 CAS 转换波次进度。
     *
     * @param transition 转换前置条件与完整目标进度
     * @return 实际更新行数
     */
    int transition(@Param("transition") PullTaskPullWaveTransition transition);

    /**
     * 一次调用提交后，以波次版本和当前游标 CAS 推进下一派发检查点。
     *
     * @param advance 波次与执行行共享的派发推进参数
     * @return 实际更新行数
     */
    int advanceDispatch(@Param("advance") PullTaskPullWaveDispatchAdvance advance);

    /**
     * 只唤醒收集态波次，派发中或终态波次不得改变时钟。
     *
     * @param id 波次主键
     * @param expectedStatus 收集态编码
     * @param now 唤醒时间(epoch 毫秒)
     * @return 实际更新行数
     */
    int wakeCollecting(
            @Param("id") long id,
            @Param("groupExecutionId") long groupExecutionId,
            @Param("expectedStatus") int expectedStatus,
            @Param("now") long now);

    /**
     * 取消任务下尚未结算的活动波次。
     *
     * @param taskId 拉群任务 ID
     * @param activeStatuses 允许取消的活动状态
     * @param targetStatus 取消状态
     * @param now 取消时间(epoch 毫秒)
     * @return 实际更新行数
     */
    int cancelByTask(
            @Param("taskId") long taskId,
            @Param("activeStatuses") List<Integer> activeStatuses,
            @Param("targetStatus") int targetStatus,
            @Param("now") long now);

    /**
     * 取消单个执行行尚未结算的活动波次。
     *
     * @param groupExecutionId 执行行 ID
     * @param activeStatuses 允许取消的活动状态
     * @param targetStatus 取消状态
     * @param now 取消时间(epoch 毫秒)
     * @return 实际更新行数
     */
    int cancelByExecution(
            @Param("groupExecutionId") long groupExecutionId,
            @Param("activeStatuses") List<Integer> activeStatuses,
            @Param("targetStatus") int targetStatus,
            @Param("now") long now);
}
