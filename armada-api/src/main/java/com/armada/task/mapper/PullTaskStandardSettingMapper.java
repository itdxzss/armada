package com.armada.task.mapper;

import com.armada.task.model.entity.PullTaskStandardSetting;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 普通群链接任务冻结执行配置数据访问层。 */
@Mapper
public interface PullTaskStandardSettingMapper {

    /**
     * 在任务由草稿冻结为待启动的同一事务中写入执行配置。
     *
     * @param row 冻结配置
     * @return 新增行数
     */
    int insert(PullTaskStandardSetting row);

    /**
     * 读取任务的冻结执行配置。
     *
     * @param taskId 拉群任务 ID
     * @return 冻结配置；不存在或不属于当前租户时为 null
     */
    PullTaskStandardSetting selectByTaskId(@Param("taskId") long taskId);

    /**
     * 任务启动时冻结要求管理员人数 N。
     *
     * <p>N 必须落在任务级：执行行受并发槽位控制、启动时刻不同，逐行冻结会得到
     * 互不相同的 N，导致各群的"缺少管理员人数"口径不一致。</p>
     *
     * @param taskId 拉群任务 ID
     * @param requiredManagerCount 当前需求冻结为一个管理员
     * @param now 更新时间(epoch 毫秒)
     * @return 实际更新行数
     */
    int freezeRequiredManagerCount(@Param("taskId") long taskId,
                                   @Param("requiredManagerCount") int requiredManagerCount,
                                   @Param("now") long now);
}
