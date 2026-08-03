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

    /**
     * 按状态前置条件与乐观锁版本推进任务生命周期。
     *
     * <p>返回 0 表示任务不在允许的前置状态或版本已过期。这是人工操作幂等的落点
     * （ADR-0009）：重复提交因前置状态不满足返回 0，Service 据此把当前状态当作
     * 成功结果返回，不得原样重试。</p>
     *
     * @param id 任务 ID
     * @param fromStatus 允许的当前状态
     * @param toStatus 目标状态
     * @param expectedVersion 读取时拿到的版本号
     * @param startedAt 首次启动时间；传 null 时不写该列，已有值不被覆盖
     * @param finishedAt 终态时间；传 null 时不写该列，已有值不被覆盖
     * @param now 本次更新时间(epoch 毫秒)
     * @return 实际更新行数；1 表示成功推进，0 表示前置校验或乐观锁失败
     */
    int updateStatusWithVersion(@Param("id") long id,
                                @Param("fromStatus") String fromStatus,
                                @Param("toStatus") String toStatus,
                                @Param("expectedVersion") Integer expectedVersion,
                                @Param("startedAt") Long startedAt,
                                @Param("finishedAt") Long finishedAt,
                                @Param("now") long now);

    /**
     * 读取任务生命周期字段，供 Service 在推进状态前取当前状态与版本号。
     *
     * @param id 任务 ID
     * @return 生命周期视图；任务不存在、已软删或不属于当前租户时为 null
     */
    PullTask selectLifecycle(@Param("id") long id);
}
