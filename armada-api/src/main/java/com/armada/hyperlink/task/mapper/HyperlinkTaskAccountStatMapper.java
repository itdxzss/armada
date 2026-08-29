package com.armada.hyperlink.task.mapper;

import com.armada.hyperlink.task.model.entity.HyperlinkTaskAccountStat;
import com.armada.hyperlink.task.model.query.HyperlinkAccountStatCriteria;
import com.armada.hyperlink.task.model.vo.HyperlinkAccountStatRow;
import com.armada.hyperlink.task.model.vo.HyperlinkMetricsDelta;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 任务账号查询投影 Mapper。 */
@Mapper
public interface HyperlinkTaskAccountStatMapper {
    int replaceFromRecipient(@Param("taskId") long taskId, @Param("now") long now);
    /** 批量合并 task+account（含 NULL 未分配桶）的累计指标净增量。 */
    @InterceptorIgnore(tenantLine = "true")
    int incrementProjection(@Param("deltas") List<HyperlinkMetricsDelta> deltas,
            @Param("now") long now);
    long countAccountStats(@Param("criteria") HyperlinkAccountStatCriteria criteria);
    List<HyperlinkAccountStatRow> selectAccountStats(
            @Param("criteria") HyperlinkAccountStatCriteria criteria);
    List<HyperlinkTaskAccountStat> selectByTaskId(@Param("taskId") long taskId);
    int deleteByTask(@Param("taskId") long taskId);
}
