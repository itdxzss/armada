package com.armada.task.mapper;

import com.armada.task.model.entity.PullTaskGroupMarketingSummary;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 拉群营销任务级列表聚合数据访问层。 */
@Mapper
public interface PullTaskGroupMarketingSummaryMapper {

    /**
     * 批量读取当前租户的一页任务统计，避免列表 N+1 查询。
     *
     * @param taskIds 当前页拉群营销任务 ID
     * @return 已存在的统计行；缺失任务不补零
     */
    List<PullTaskGroupMarketingSummary> selectByTaskIds(
            @Param("taskIds") List<Long> taskIds);
}
