package com.armada.hyperlink.task.mapper;

import com.armada.marketing.export.model.entity.MarketingTaskExportJob;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** H6 复用 marketing_task_export_job 的独立作业访问。 */
@Mapper
public interface HyperlinkTaskAnalysisExportMapper {
    @InterceptorIgnore(tenantLine = "true")
    int markExhausted(@Param("now") long now, @Param("errorMessage") String errorMessage);
    int insert(MarketingTaskExportJob job);
    MarketingTaskExportJob selectActive(@Param("createdBy") long createdBy,
            @Param("requestHash") String requestHash);
    @InterceptorIgnore(tenantLine = "true")
    List<MarketingTaskExportJob> selectCandidates(@Param("now") long now,
            @Param("limit") int limit);
    @InterceptorIgnore(tenantLine = "true")
    int claim(@Param("tenantId") long tenantId, @Param("id") long id,
            @Param("claimToken") String claimToken, @Param("leaseUntil") long leaseUntil,
            @Param("now") long now);
    @InterceptorIgnore(tenantLine = "true")
    int markSuccess(@Param("tenantId") long tenantId, @Param("id") long id,
            @Param("claimToken") String claimToken, @Param("storageKey") String storageKey,
            @Param("fileName") String fileName, @Param("fileSize") long fileSize,
            @Param("rowCount") int rowCount, @Param("finishedAt") long finishedAt,
            @Param("expiresAt") long expiresAt);
    @InterceptorIgnore(tenantLine = "true")
    int markFailed(@Param("tenantId") long tenantId, @Param("id") long id,
            @Param("claimToken") String claimToken, @Param("errorMessage") String errorMessage,
            @Param("finishedAt") long finishedAt);
}
