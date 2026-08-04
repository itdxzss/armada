package com.armada.task.mapper;

import com.armada.task.model.dto.PullTaskStandardAggregateCriteria;
import com.armada.task.model.dto.PullTaskStandardExecutionAggregateCriteria;
import com.armada.task.model.dto.PullTaskStandardExecutionFilter;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.vo.PullTaskStandardExecutionAggregate;
import com.armada.task.model.vo.PullTaskStandardTaskAggregate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 普通群链接任务批量聚合和详情分页读 Mapper。 */
@Mapper
public interface PullTaskStandardReadMapper {

    /** 按当前页任务 ID 批量聚合真实执行、料子与角色资源事实。 */
    List<PullTaskStandardTaskAggregate> selectTaskAggregates(
            @Param("criteria") PullTaskStandardAggregateCriteria criteria);

    /** 统计符合详情工作台条件的执行行数量。 */
    long countExecutions(@Param("filter") PullTaskStandardExecutionFilter filter);

    /** 服务端筛选并分页读取执行行，避免前端加载整任务后切片。 */
    List<PullTaskGroupExecution> selectExecutionPage(
            @Param("filter") PullTaskStandardExecutionFilter filter,
            @Param("offset") int offset,
            @Param("limit") int limit);

    /** 批量聚合当前页执行行的料子和三类角色资源。 */
    List<PullTaskStandardExecutionAggregate> selectExecutionAggregates(
            @Param("criteria") PullTaskStandardExecutionAggregateCriteria criteria);
}
