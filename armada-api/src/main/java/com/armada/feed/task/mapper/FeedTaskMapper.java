package com.armada.feed.task.mapper;

import com.armada.feed.task.model.dto.FeedTaskQuery;
import com.armada.feed.task.model.entity.FeedTask;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 动态发布任务主表数据访问。 */
@Mapper
public interface FeedTaskMapper {

    int insert(FeedTask task);

    FeedTask selectById(@Param("id") Long id);
    List<FeedTask> selectPage(@Param("query") FeedTaskQuery query);
    long countPage(@Param("query") FeedTaskQuery query);
    int updateForm(FeedTask task);

    int updateRunStatus(@Param("id") Long id,
                        @Param("expectedStatus") int expectedStatus,
                        @Param("nextStatus") int nextStatus,
                        @Param("nextRunAt") Long nextRunAt,
                        @Param("updatedAt") long updatedAt);

    @InterceptorIgnore(tenantLine = "true")
    List<FeedTask> selectDueScheduledTasks(@Param("now") long now, @Param("limit") int limit);

    @InterceptorIgnore(tenantLine = "true")
    List<FeedTask> selectDueRunningTasks(@Param("now") long now, @Param("limit") int limit);

    int startDueScheduledTask(@Param("id") Long id, @Param("startedAt") long startedAt);
    int claimDueRound(@Param("id") Long id, @Param("now") long now, @Param("nextRunAt") long nextRunAt);
    int postpone(@Param("id") Long id, @Param("nextRunAt") long nextRunAt, @Param("updatedAt") long updatedAt);
    int incrementTotalAccountNum(@Param("id") Long id, @Param("delta") int delta, @Param("updatedAt") long updatedAt);
    int incrementSuccessAccountNum(@Param("id") Long id, @Param("updatedAt") long updatedAt);
    int incrementFailedAccountNum(@Param("id") Long id, @Param("updatedAt") long updatedAt);
    int complete(@Param("id") Long id, @Param("finishedAt") long finishedAt);
}
