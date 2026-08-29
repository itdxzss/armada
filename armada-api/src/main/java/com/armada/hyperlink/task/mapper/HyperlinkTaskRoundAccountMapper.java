package com.armada.hyperlink.task.mapper;

import com.armada.hyperlink.task.model.entity.HyperlinkTaskRoundAccount;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 轮次账号稳定集合 Mapper。 */
@Mapper
public interface HyperlinkTaskRoundAccountMapper {
    int insertIgnore(HyperlinkTaskRoundAccount entity);
    List<HyperlinkTaskRoundAccount> selectByRoundId(@Param("roundId") long roundId);
    int deleteUnconsumedByTask(@Param("taskId") long taskId);
    int countByRoundId(@Param("roundId") long roundId);
    int countAvailableByRoundId(@Param("roundId") long roundId);
    int syncUnavailableFromUsage(@Param("roundId") long roundId, @Param("now") long now);
}
