package com.armada.hyperlink.task.mapper;

import com.armada.hyperlink.task.model.entity.HyperlinkTaskAccountUsage;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 任务账号容量 Mapper。 */
@Mapper
public interface HyperlinkTaskAccountUsageMapper {
    List<com.armada.hyperlink.task.model.vo.HyperlinkBanReasonRow> selectBanReasonStats(
            @Param("taskId") long taskId);
    int insertIgnore(HyperlinkTaskAccountUsage entity);
    List<HyperlinkTaskAccountUsage> selectAvailable(@Param("taskId") long taskId,
            @Param("roundId") long roundId, @Param("now") long now,
            @Param("maxInFlight") int maxInFlight, @Param("limit") int limit);
    HyperlinkTaskAccountUsage selectByTaskAndAccount(@Param("taskId") long taskId,
            @Param("accountId") long accountId);
    HyperlinkTaskAccountUsage selectByTaskAndAccountForUpdate(@Param("taskId") long taskId,
            @Param("accountId") long accountId);
    int reserveSlot(@Param("id") long id, @Param("expectedVersion") int expectedVersion,
            @Param("maxInFlight") int maxInFlight, @Param("now") long now);
    int scheduleNextSend(@Param("id") long id, @Param("nextSendAt") long nextSendAt,
            @Param("now") long now);
    int completeSlot(@Param("id") long id, @Param("successful") boolean successful,
            @Param("now") long now);
    int deleteUnusedByTask(@Param("taskId") long taskId);
    int markInvalid(@Param("id") long id, @Param("usageStatus") int usageStatus,
            @Param("invalidCode") String invalidCode, @Param("invalidReason") String invalidReason,
            @Param("now") long now);
    int countInFlight(@Param("taskId") long taskId);
    int markSelectedRound(@Param("id") long id, @Param("roundNo") long roundNo,
            @Param("now") long now);
}
