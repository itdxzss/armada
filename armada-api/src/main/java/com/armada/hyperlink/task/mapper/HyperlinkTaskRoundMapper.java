package com.armada.hyperlink.task.mapper;

import com.armada.hyperlink.task.model.entity.HyperlinkTaskRound;
import com.armada.hyperlink.task.model.vo.HyperlinkMetricsDelta;
import com.armada.hyperlink.task.model.vo.HyperlinkProvisionCandidate;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 任务轮次 Mapper。 */
@Mapper
public interface HyperlinkTaskRoundMapper {
    @InterceptorIgnore(tenantLine = "true")
    List<HyperlinkProvisionCandidate> selectDispatchCandidates(@Param("now") long now,
            @Param("limit") int limit);
    @InterceptorIgnore(tenantLine = "true")
    List<HyperlinkProvisionCandidate> selectStartCandidates(@Param("now") long now,
            @Param("limit") int limit);
    @InterceptorIgnore(tenantLine = "true")
    List<HyperlinkProvisionCandidate> selectLifecycleCandidates(@Param("now") long now,
            @Param("limit") int limit);
    int insert(HyperlinkTaskRound entity);
    HyperlinkTaskRound selectActive(@Param("taskId") long taskId);
    /** 派发在 runtime fence 之后显式锁定当前轮次。 */
    @InterceptorIgnore(tenantLine = "true")
    HyperlinkTaskRound selectActiveForUpdate(@Param("tenantId") long tenantId,
            @Param("taskId") long taskId);
    /** 指标投影在 runtime 之后按稳定主键顺序锁定候选轮次。 */
    @InterceptorIgnore(tenantLine = "true")
    List<Long> lockMetricsProjectionRounds(@Param("tenantId") long tenantId,
            @Param("taskId") long taskId, @Param("roundIds") List<Long> roundIds);
    /** 未开始编辑只允许重排尚未消费且 READY/NO_ACCOUNT 的首轮。 */
    int rescheduleUnconsumedFirstRound(@Param("taskId") long taskId,
            @Param("scheduledAt") long scheduledAt, @Param("now") long now);
    int scheduleNow(@Param("taskId") long taskId, @Param("now") long now);
    int markSelected(@Param("id") long id, @Param("selectedCount") int selectedCount,
            @Param("actualConcurrency") int actualConcurrency, @Param("roundStatus") int roundStatus,
            @Param("now") long now);
    int rebuildProjection(@Param("taskId") long taskId, @Param("now") long now);
    /** 合并一批 recipient 对单轮累计指标的净增量。 */
    @InterceptorIgnore(tenantLine = "true")
    int incrementProjection(@Param("delta") HyperlinkMetricsDelta delta,
            @Param("now") long now);
    int cancelUnconsumed(@Param("taskId") long taskId, @Param("now") long now);
    int deleteUnconsumed(@Param("taskId") long taskId);
    int markStarted(@Param("id") long id, @Param("now") long now);
    int beginSelection(@Param("id") long id, @Param("expectedStatus") int expectedStatus,
            @Param("now") long now);
    int updateSelection(@Param("id") long id, @Param("selectedCount") int selectedCount,
            @Param("actualConcurrency") int actualConcurrency, @Param("roundStatus") int roundStatus,
            @Param("nextDispatchAt") long nextDispatchAt, @Param("now") long now);
    int markDispatching(@Param("id") long id, @Param("now") long now);
    int markWaitingResult(@Param("id") long id, @Param("now") long now);
    int markCompleted(@Param("id") long id, @Param("now") long now);
    int pauseActive(@Param("taskId") long taskId, @Param("now") long now);
    int resumePaused(@Param("taskId") long taskId, @Param("now") long now);
}
