package com.armada.hyperlink.task.mapper;

import com.armada.hyperlink.task.model.entity.HyperlinkTaskExportJob;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 公共超链导出作业 Mapper；当前 Worker 仅领取 RECIPIENTS。 */
@Mapper
public interface HyperlinkTaskExportMapper {
    int insertJob(HyperlinkTaskExportJob job);

    HyperlinkTaskExportJob selectJobByIdForUser(
            @Param("id") long id, @Param("createdBy") long createdBy);

    HyperlinkTaskExportJob selectActiveJob(
            @Param("tenantId") long tenantId,
            @Param("createdBy") long createdBy,
            @Param("requestHash") String requestHash);

    @InterceptorIgnore(tenantLine = "true")
    List<HyperlinkTaskExportJob> selectProcessableRecipientJobs(
            @Param("now") long now, @Param("limit") int limit);

    @InterceptorIgnore(tenantLine = "true")
    int markExhaustedRecipientJobs(
            @Param("now") long now, @Param("errorMessage") String errorMessage);

    @InterceptorIgnore(tenantLine = "true")
    int claimRecipientJob(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("now") long now,
            @Param("leaseUntil") long leaseUntil,
            @Param("claimToken") String claimToken);

    @InterceptorIgnore(tenantLine = "true")
    int renewRecipientJobLease(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("claimToken") String claimToken,
            @Param("now") long now,
            @Param("leaseUntil") long leaseUntil);

    @InterceptorIgnore(tenantLine = "true")
    int markRecipientJobSuccess(HyperlinkTaskExportJob job);

    @InterceptorIgnore(tenantLine = "true")
    int markRecipientJobFailed(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("claimToken") String claimToken,
            @Param("errorMessage") String errorMessage,
            @Param("finishedAt") long finishedAt);

    @InterceptorIgnore(tenantLine = "true")
    List<HyperlinkTaskExportJob> selectExpiredRecipientFiles(
            @Param("now") long now, @Param("limit") int limit);

    @InterceptorIgnore(tenantLine = "true")
    int clearExpiredRecipientStorage(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("storageKey") String storageKey,
            @Param("now") long now);
}
