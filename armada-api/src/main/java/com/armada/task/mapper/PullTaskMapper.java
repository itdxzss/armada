package com.armada.task.mapper;

import com.armada.task.model.dto.PullTaskFilter;
import com.armada.task.model.entity.PullTask;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 拉群任务公共主表数据访问层。 */
@Mapper
public interface PullTaskMapper {

    /**
     * 统计当前租户下符合条件的有效任务。
     *
     * @param filter SQL 筛选条件
     * @return 符合条件的任务数
     */
    long countPage(@Param("filter") PullTaskFilter filter);

    /**
     * 分页查询当前租户下符合条件的有效任务。
     *
     * @param filter SQL 筛选条件
     * @param offset SQL 偏移量
     * @param limit  最大返回行数
     * @return 按任务 ID 倒序的当前页
     */
    List<PullTask> selectPage(
            @Param("filter") PullTaskFilter filter,
            @Param("offset") int offset,
            @Param("limit") int limit);

    /**
     * 按任务类型和状态约束批量软删。
     *
     * @param ids       待删除任务 ID
     * @param deletedAt 软删时间(epoch 毫秒)
     * @return 实际软删行数
     */
    int batchSoftDeleteAllowed(
            @Param("ids") List<Long> ids,
            @Param("deletedAt") long deletedAt);
}
