package com.armada.marketing.mapper;

import com.armada.marketing.model.dto.MarketingTaskQuery;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.entity.MarketingTaskTarget;
import com.armada.marketing.model.vo.MarketingAccountTreeRow;
import com.armada.marketing.model.vo.MarketingTargetCandidateRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 营销任务数据访问。tenant_id 由租户行隔离拦截器自动注入,SQL 不手写 tenant_id。
 */
@Mapper
public interface MarketingTaskMapper {

    /** 插入营销任务主表并回填 id。 */
    int insertTask(MarketingTask task);

    /** 批量插入任务目标。 */
    int insertTargets(@Param("targets") List<MarketingTaskTarget> targets);

    /** 按 ID 查未删任务。 */
    MarketingTask selectTaskById(@Param("id") Long id);

    /** 按任务 ID 查目标明细。 */
    List<MarketingTaskTarget> selectTargetsByTaskId(@Param("taskId") Long taskId);

    /** 待启动/已停止任务置为发送中,并首次补 started_at。 */
    int startTask(@Param("id") Long id, @Param("now") long now);

    /** 发送中任务置为已停止。 */
    int stopTask(@Param("id") Long id, @Param("now") long now);

    /** 统计指定任务里仍处于发送中的未删任务数量。 */
    int countSendingByIds(@Param("ids") List<Long> ids);

    /** 批量软删非发送中的任务。 */
    int batchSoftDelete(@Param("ids") List<Long> ids, @Param("deletedAt") long deletedAt);

    /** 分页总数。 */
    long countPage(@Param("q") MarketingTaskQuery query);

    /** 分页列表。 */
    List<MarketingTask> selectPage(@Param("q") MarketingTaskQuery query);

    /** 查询一个账号+群入口是否能形成发送目标。 */
    MarketingTargetCandidateRow selectTargetCandidate(@Param("accountGroupId") Long accountGroupId,
                                                      @Param("accountId") Long accountId,
                                                      @Param("groupLinkId") Long groupLinkId);

    /** 查询建营销任务用的账号×可营销群平铺行。 */
    List<MarketingAccountTreeRow> selectAccountTreeRows(@Param("groupId") Long groupId);
}
