package com.armada.task.mapper;

import com.armada.task.model.dto.PullTaskGroupMarketingCandidateQuery;
import com.armada.task.model.vo.PullTaskGroupMarketingCandidateAccountRow;
import com.armada.task.model.vo.PullTaskGroupMarketingCandidateRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 拉群营销候选群组跨表只读 Mapper。 */
@Mapper
public interface PullTaskGroupMarketingCandidateMapper {

    /**
     * 统计符合筛选条件且按 JID 去重后的群组数量。
     *
     * @param query 候选筛选条件
     * @return 去重后的群组数量
     */
    long countPage(@Param("query") PullTaskGroupMarketingCandidateQuery query);

    /**
     * 分页查询候选群组聚合事实。
     *
     * @param query 候选筛选条件
     * @param offset SQL 偏移量
     * @param limit 最大返回数
     * @return 按 JID 排序的候选群组
     */
    List<PullTaskGroupMarketingCandidateRow> selectPage(
            @Param("query") PullTaskGroupMarketingCandidateQuery query,
            @Param("offset") int offset,
            @Param("limit") int limit);

    /**
     * 批量读取当前页各群全部可操作管理员账号。
     *
     * @param groupJids 当前页群 JID
     * @return 在线优先的账号行
     */
    List<PullTaskGroupMarketingCandidateAccountRow> selectAccountsByGroupJids(
            @Param("groupJids") List<String> groupJids);

    /**
     * 按 JID 重新读取加入等待池所需的候选事实。
     *
     * @param groupJids 待重新校验群 JID
     * @return 仍存在本地快照的群组
     */
    List<PullTaskGroupMarketingCandidateRow> selectByGroupJids(
            @Param("groupJids") List<String> groupJids);
}
