package com.armada.contact.task.mapper;

import com.armada.contact.task.model.dto.ContactTaskQuery;
import com.armada.contact.task.model.entity.ContactFriendTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 通讯录营销任务主表的数据访问。 */
@Mapper
public interface ContactFriendTaskMapper {

    /**
     * 插入任务并回填主键。
     *
     * @param task 任务行
     * @return 受影响行数
     */
    int insert(ContactFriendTask task);

    /**
     * 按主键读取未软删任务。
     *
     * @param id 任务 ID
     * @return 任务行，不存在或已软删时为 null
     */
    ContactFriendTask selectById(@Param("id") Long id);

    /**
     * 分页查询任务列表。
     *
     * @param query 查询条件
     * @return 当前页任务行
     */
    List<ContactFriendTask> selectPage(@Param("query") ContactTaskQuery query);

    /**
     * 统计查询条件命中的任务总数。
     *
     * @param query 查询条件
     * @return 总数
     */
    long countPage(@Param("query") ContactTaskQuery query);

    /**
     * 更新任务表单字段。仅未开始任务允许调用，调用方负责状态校验。
     *
     * @param task 任务行，需带 id 与全部表单字段
     * @return 受影响行数
     */
    int updateForm(ContactFriendTask task);

    /**
     * 条件更新任务运行状态，防止并发动作互相覆盖。
     *
     * @param id 任务 ID
     * @param expectedRunStatus 期望的当前运行状态
     * @param nextRunStatus 目标运行状态
     * @param nextRoundAt 下一轮调度时间，可为 null
     * @param updatedAt 更新时间（epoch 毫秒）
     * @return 实际更新行数，0 表示状态已被并发改变
     */
    int updateRunStatus(@Param("id") Long id,
                        @Param("expectedRunStatus") int expectedRunStatus,
                        @Param("nextRunStatus") int nextRunStatus,
                        @Param("nextRoundAt") Long nextRoundAt,
                        @Param("updatedAt") long updatedAt);

    /**
     * 扫描到期的进行中任务。
     *
     * @param now 当前时间（epoch 毫秒）
     * @param limit 单次扫描上限
     * @return 到期任务
     */
    List<ContactFriendTask> selectDueRunningTasks(@Param("now") long now, @Param("limit") int limit);

    /**
     * 扫描已到计划开始时间、仍未开始的已启用任务。
     *
     * @param now 当前时间（epoch 毫秒）
     * @param limit 单次扫描上限
     * @return 到期待启动任务
     */
    List<ContactFriendTask> selectDueScheduledTasks(@Param("now") long now, @Param("limit") int limit);

    /**
     * 把到点的已启用未开始任务推进到进行中并排下一轮。
     *
     * @param id 任务 ID
     * @param startedAt 启动时间（epoch 毫秒）
     * @return 1 表示本次推进成功，0 表示已被并发推进
     */
    int startDueScheduledTask(@Param("id") Long id, @Param("startedAt") long startedAt);

    /**
     * 抢占一轮。并发闸门：只有一个线程能把到期任务推进到下一轮。
     *
     * @param id 任务 ID
     * @param now 当前时间（epoch 毫秒）
     * @param nextRoundAt 下一轮时间（epoch 毫秒）
     * @return 1 表示抢占成功，0 表示已被其他线程抢走
     */
    int claimDueRound(@Param("id") Long id,
                      @Param("now") long now,
                      @Param("nextRoundAt") Long nextRoundAt);

    /**
     * 只推迟下一轮，不消耗轮次号。下游积压或尚未到点时使用。
     *
     * @param id 任务 ID
     * @param now 当前时间（epoch 毫秒）
     * @param nextRoundAt 下一轮时间（epoch 毫秒）
     * @return 受影响行数
     */
    int postponeDueRound(@Param("id") Long id,
                         @Param("now") long now,
                         @Param("nextRoundAt") Long nextRoundAt);

    /**
     * 展开完成后写入计划总量与参与账号数。
     *
     * @param id 任务 ID
     * @param totalSendNum 计划发送总条数
     * @param usedAccountCount 实际参与发送的账号数
     * @param updatedAt 更新时间（epoch 毫秒）
     * @return 受影响行数
     */
    int applyExpansionTotals(@Param("id") Long id,
                             @Param("totalSendNum") int totalSendNum,
                             @Param("usedAccountCount") int usedAccountCount,
                             @Param("updatedAt") long updatedAt);

    /**
     * 累加成功送达条数。
     *
     * @param id 任务 ID
     * @param delta 增量
     * @param updatedAt 更新时间（epoch 毫秒）
     * @return 受影响行数
     */
    int incrementSuccessMessageNum(@Param("id") Long id,
                                   @Param("delta") int delta,
                                   @Param("updatedAt") long updatedAt);

    /**
     * 收件人全部落终态后把任务推进到已完成，并推导号均发量与无效账号数。
     *
     * @param id 任务 ID
     * @param finishedAt 完成时间（epoch 毫秒）
     * @return 1 表示本次完成，0 表示状态已变
     */
    int completeDrainedTask(@Param("id") Long id, @Param("finishedAt") long finishedAt);
}
