package com.armada.task.mapper;

import com.armada.task.model.entity.PullTaskMemberQuery;
import com.armada.task.model.dto.PullTaskMemberQuerySettlement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 普通拉群异步成员查询数据访问层。 */
@Mapper
public interface PullTaskMemberQueryMapper {

    int insertInitialized(PullTaskMemberQuery row);

    PullTaskMemberQuery selectById(@Param("id") long id);

    PullTaskMemberQuery selectByCommandId(@Param("commandId") String commandId);

    PullTaskMemberQuery selectLatestByBusinessKey(
            @Param("groupExecutionId") long groupExecutionId,
            @Param("businessKey") String businessKey);

    int selectNextAttemptNo(
            @Param("groupExecutionId") long groupExecutionId,
            @Param("businessKey") String businessKey);

    int bindCommandId(
            @Param("id") long id,
            @Param("expectedStatus") int expectedStatus,
            @Param("commandId") String commandId,
            @Param("now") long now);

    int expirePending(
            @Param("id") long id,
            @Param("expectedStatus") int expectedStatus,
            @Param("targetStatus") int targetStatus,
            @Param("now") long now,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage);

    int cancelPending(
            @Param("taskId") long taskId,
            @Param("groupExecutionId") Long groupExecutionId,
            @Param("expectedStatus") int expectedStatus,
            @Param("targetStatus") int targetStatus,
            @Param("now") long now,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage);

    /** 只收敛 commandId 仍匹配的当前 PENDING 尝试。 */
    int settlePending(@Param("settlement") PullTaskMemberQuerySettlement settlement);
}
