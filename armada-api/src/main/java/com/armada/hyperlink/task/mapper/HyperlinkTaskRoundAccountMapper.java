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
    /** 有效执行账号含暂被在途额度占满的账号，封禁/受限账号的旧消息不占执行名额。 */
    int countExecutingByRoundId(@Param("roundId") long roundId);
    int syncUnavailableFromUsage(@Param("roundId") long roundId, @Param("now") long now);
    /** 无在途且剩余目标全部拒绝过的账号轮换出当前轮(assignment_status=5)，不修改账号可用状态。 */
    int releaseRejectedPairsOnly(@Param("roundId") long roundId, @Param("now") long now);
}
