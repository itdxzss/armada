package com.armada.hyperlink.task.mapper;

import com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipientClaim;
import com.armada.hyperlink.task.model.vo.HyperlinkProvisionCandidate;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 数据包 recipient 分批领取 Mapper。 */
@Mapper
public interface HyperlinkTaskRecipientClaimMapper {
    @InterceptorIgnore(tenantLine = "true")
    List<HyperlinkProvisionCandidate> selectProvisionCandidates(@Param("limit") int limit);
    @InterceptorIgnore(tenantLine = "true")
    List<HyperlinkProvisionCandidate> selectCleanupCandidates(@Param("limit") int limit);
    int insert(HyperlinkTaskRecipientClaim entity);
    @InterceptorIgnore(tenantLine = "true")
    HyperlinkTaskRecipientClaim selectByTaskId(
            @Param("tenantId") long tenantId, @Param("taskId") long taskId);
    /** 重新报价只读已持有 claim，不占用事务行锁。 */
    @InterceptorIgnore(tenantLine = "true")
    HyperlinkTaskRecipientClaim selectSnapshotByTaskId(
            @Param("tenantId") long tenantId, @Param("taskId") long taskId);
    int advance(@Param("id") long id, @Param("expectedVersion") int expectedVersion,
            @Param("cursor") long cursor, @Param("claimedDelta") int claimedDelta,
            @Param("completed") boolean completed, @Param("now") long now);
    int markReleasing(@Param("taskId") long taskId, @Param("now") long now);
    int markReleased(@Param("taskId") long taskId, @Param("now") long now);
    int resetForClaim(@Param("entity") HyperlinkTaskRecipientClaim entity,
            @Param("expectedVersion") int expectedVersion);
}
