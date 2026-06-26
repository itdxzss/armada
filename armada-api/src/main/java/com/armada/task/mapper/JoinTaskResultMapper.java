package com.armada.task.mapper;

import com.armada.task.model.entity.JoinTaskResult;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 进群任务明细数据访问层，映射 {@code join_task_result} 表（账号×链接计划行）。
 *
 * <p>plain {@code @Mapper}，SQL 全部写在 XML，不用注解 SQL。
 * tenant_id 由租户拦截器自动注入，所有方法禁止手写 tenant_id 条件。</p>
 *
 * <p>引擎列（group_jid/is_admin/promoted_at）由引擎切片另行更新，此处不涉及。</p>
 */
@Mapper
public interface JoinTaskResultMapper {

    /**
     * 批量插入计划行。调用方须先为每行设置 {@code joinTaskId}、{@code createdAt}、{@code updatedAt}。
     * 引擎列（group_jid/is_admin/promoted_at）使用 DB 默认值（''/0/NULL）。
     *
     * @param rows 待插入的计划行列表，不得为空
     * @return 实际插入行数
     */
    int insertResults(@Param("rows") List<JoinTaskResult> rows);

    /**
     * 查询指定任务的全部计划行，按 id 升序（保持建任务时的分配顺序）。
     *
     * @param joinTaskId 进群任务 ID
     * @return 该任务下所有计划行；任务不存在或无行时返回空列表
     */
    List<JoinTaskResult> selectResultsByTask(@Param("joinTaskId") Long joinTaskId);

    /**
     * 物理删除指定任务的全部计划行，供编辑重建时清空旧行使用。
     *
     * @param joinTaskId 进群任务 ID
     * @return 被删除的行数
     */
    int deleteResultsByTask(@Param("joinTaskId") Long joinTaskId);
}
