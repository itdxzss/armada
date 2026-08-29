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
}
