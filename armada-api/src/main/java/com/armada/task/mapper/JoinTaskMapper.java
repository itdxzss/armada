package com.armada.task.mapper;

import com.armada.task.model.dto.JoinTaskFilter;
import com.armada.task.model.entity.JoinTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 进群任务数据访问层，映射 {@code join_task} 表。
 *
 * <p>plain {@code @Mapper}，不继承 BaseMapper；SQL 全部写在 XML，不用注解 SQL。
 * tenant_id 由租户拦截器自动注入，所有方法禁止手写 tenant_id 条件。</p>
 */
@Mapper
public interface JoinTaskMapper {

    /**
     * 插入一条进群任务，自动回填 {@code id}。
     *
     * @param task 任务实体（tenant_id 由拦截器注入，不填）
     * @return 受影响行数
     */
    int insert(JoinTask task);

    /**
     * 按主键查询当前租户下有效任务（{@code deleted_at IS NULL}）。
     *
     * @param id 任务主键
     * @return 任务实体；未找到或已软删返回 null
     */
    JoinTask selectByTenantAndId(@Param("id") Long id);

    /**
     * 按筛选条件统计当前租户下符合条件的任务总数，用于分页 total。
     *
     * @param f 筛选条件（null 字段不参与筛选）
     * @return 符合条件的行数
     */
    long countPage(@Param("f") JoinTaskFilter f);

    /**
     * 按筛选条件分页查询当前租户下的任务列表，按 id DESC 排序。
     *
     * @param f      筛选条件（null 字段不参与筛选）
     * @param offset SQL OFFSET，从 0 起
     * @param limit  SQL LIMIT，每页行数
     * @return 当前页任务列表
     */
    List<JoinTask> selectPage(@Param("f") JoinTaskFilter f,
                              @Param("offset") int offset,
                              @Param("limit") int limit);

    /**
     * 查询当前租户下所有非空 {@code interval_label} 的去重值，供筛选下拉使用。
     *
     * @return 去重后的间隔标签列表，按字母升序
     */
    List<String> selectDistinctIntervals();

    /**
     * 覆盖更新任务配置列和计数器（{@code total/pending}），仅更新未软删行。
     *
     * <p>引擎执行计数器（executed/success/failed）由引擎切片另行更新，不在此覆盖。</p>
     *
     * @param task 包含最新字段值的实体（必须携带 id）
     * @return 受影响行数；0 表示 id 不存在或已软删
     */
    int update(JoinTask task);

    /**
     * 批量软删除进群任务（幂等）：仅 {@code deleted_at IS NULL} 的行会被置删，
     * 已删行不重复更新。
     *
     * @param ids       待删除任务 ID 列表，不得为空
     * @param deletedAt 软删时间（epoch 毫秒），同时写入 updated_at
     * @return 实际被置删的行数
     */
    int batchSoftDelete(@Param("ids") List<Long> ids, @Param("deletedAt") long deletedAt);
}
