package com.armada.hyperlink.task.mapper;

import com.armada.hyperlink.task.model.entity.HyperlinkTaskRuntime;
import com.armada.hyperlink.task.model.vo.HyperlinkMetricsDelta;
import com.armada.hyperlink.task.model.vo.HyperlinkProvisionCandidate;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 超链任务运行状态 Mapper。 */
@Mapper
public interface HyperlinkTaskRuntimeMapper {
    int incrementVisitFacts(@Param("taskId") long taskId,
            @Param("firstVisit") boolean firstVisit, @Param("now") long now);
    int insert(HyperlinkTaskRuntime entity);
    HyperlinkTaskRuntime selectByTaskId(@Param("taskId") long taskId);
    @InterceptorIgnore(tenantLine = "true")
    HyperlinkTaskRuntime selectByTaskIdForShare(@Param("tenantId") long tenantId,
            @Param("taskId") long taskId);
    @InterceptorIgnore(tenantLine = "true")
    HyperlinkTaskRuntime selectByTaskIdForUpdate(@Param("tenantId") long tenantId,
            @Param("taskId") long taskId);
    int transition(@Param("taskId") long taskId, @Param("expectedEnabled") boolean expectedEnabled,
            @Param("expectedRunStatus") int expectedRunStatus, @Param("enabled") boolean enabled,
            @Param("runStatus") int runStatus, @Param("provisionStatus") int provisionStatus,
            @Param("now") long now);
    int markProvisionFailed(@Param("taskId") long taskId, @Param("failureCode") int failureCode,
            @Param("failureReason") String failureReason, @Param("now") long now);
    int resumeProvisioning(@Param("taskId") long taskId, @Param("now") long now);
    int markReady(@Param("taskId") long taskId, @Param("recipientTotal") int recipientTotal,
            @Param("roundId") long roundId, @Param("now") long now);
    int rebuildProjection(@Param("taskId") long taskId, @Param("now") long now);
    /** 合并一批 recipient 对任务累计指标的净增量。 */
    @InterceptorIgnore(tenantLine = "true")
    int incrementProjection(@Param("delta") HyperlinkMetricsDelta delta,
            @Param("now") long now);
    int beginRebuild(@Param("taskId") long taskId, @Param("targetEnabled") boolean targetEnabled,
            @Param("now") long now);
    int finishCleanupAsDraft(@Param("taskId") long taskId, @Param("now") long now);
    int startRound(@Param("taskId") long taskId, @Param("roundId") long roundId,
            @Param("roundNo") long roundNo, @Param("actualConcurrency") int actualConcurrency,
            @Param("now") long now);
    int updateCurrentRound(@Param("taskId") long taskId, @Param("roundId") long roundId,
            @Param("roundNo") long roundNo, @Param("actualConcurrency") int actualConcurrency,
            @Param("now") long now);
    int markCompletedIfIdle(@Param("taskId") long taskId, @Param("now") long now);
    int stopAtDeadline(@Param("taskId") long taskId, @Param("now") long now);
    @InterceptorIgnore(tenantLine = "true")
    List<HyperlinkProvisionCandidate> selectCompletionCandidates(@Param("limit") int limit);
}
