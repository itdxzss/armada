package com.armada.task.mapper;

import com.armada.task.model.entity.PullTaskCreatorDeletion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 建群者注销账本；全部查询受当前租户上下文隔离。 */
@Mapper
public interface PullTaskCreatorDeletionMapper {
    /** 初次选号事务中冻结唯一创建者和操作；目标字段后续不可更新。 */
    int insert(PullTaskCreatorDeletion row);
    /** 按执行行读取原注销操作，恢复时禁止生成新 operationId。 */
    PullTaskCreatorDeletion selectByExecutionId(@Param("executionId") long executionId);
    /** 按当前页执行行批量读取，避免详情列表 N+1。 */
    java.util.List<PullTaskCreatorDeletion> selectByExecutionIds(@Param("executionIds") java.util.List<Long> executionIds);
    /** 与执行行锁同序锁定注销记录。 */
    PullTaskCreatorDeletion selectByExecutionIdForUpdate(@Param("executionId") long executionId);
    /** 唯一 RESERVED 到 SUBMITTED 的原子发送意图；后续调度永远不能再领取 POST。 */
    int claimSubmission(@Param("row") PullTaskCreatorDeletion row);
    /** 已提交操作仅记录结果/观察，永不改写冻结目标。 */
    int updateObservation(@Param("row") PullTaskCreatorDeletion row);
}
