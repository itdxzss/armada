package com.armada.hyperlink.task.mapper;

import com.armada.hyperlink.task.model.entity.HyperlinkTaskExportJobEntity;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 超链详情导出复用持久作业表的最小数据访问面。 */
@Mapper
public interface HyperlinkTaskExportJobMapper {

    int insert(HyperlinkTaskExportJobEntity job);

    HyperlinkTaskExportJobEntity selectByIdForUser(@Param("id") long id,
            @Param("createdBy") long createdBy);

    HyperlinkTaskExportJobEntity selectActive(@Param("tenantId") long tenantId,
            @Param("createdBy") long createdBy, @Param("requestHash") String requestHash);

    @InterceptorIgnore(tenantLine = "true")
    List<HyperlinkTaskExportJobEntity> selectProcessable(@Param("now") long now,
            @Param("limit") int limit);

    @InterceptorIgnore(tenantLine = "true")
    int markExhausted(@Param("now") long now, @Param("errorMessage") String errorMessage);

    @InterceptorIgnore(tenantLine = "true")
    int claim(@Param("tenantId") long tenantId, @Param("id") long id,
            @Param("now") long now, @Param("leaseUntil") long leaseUntil,
            @Param("claimToken") String claimToken);

    @InterceptorIgnore(tenantLine = "true")
    int renew(@Param("tenantId") long tenantId, @Param("id") long id,
            @Param("claimToken") String claimToken, @Param("now") long now,
            @Param("leaseUntil") long leaseUntil);

    @InterceptorIgnore(tenantLine = "true")
    int markSuccess(HyperlinkTaskExportJobEntity job);

    @InterceptorIgnore(tenantLine = "true")
    int markFailed(@Param("tenantId") long tenantId, @Param("id") long id,
            @Param("claimToken") String claimToken, @Param("errorMessage") String errorMessage,
            @Param("finishedAt") long finishedAt);

    @InterceptorIgnore(tenantLine = "true")
    List<HyperlinkTaskExportJobEntity> selectExpired(@Param("now") long now,
            @Param("limit") int limit);

    @InterceptorIgnore(tenantLine = "true")
    int markExpired(@Param("tenantId") long tenantId, @Param("id") long id,
            @Param("storageKey") String storageKey, @Param("now") long now);
}
