package com.armada.marketing.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.armada.marketing.model.dto.MarketingTaskQuery;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.entity.MarketingTaskSendAttempt;
import com.armada.marketing.model.entity.MarketingTaskTarget;
import com.armada.marketing.model.vo.MarketingAccountTreeAccountRow;
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

    /** 查询已到下一轮生成时间的发送中任务。后台调度无租户上下文,需跨租户扫描后由 worker 恢复租户。 */
    @InterceptorIgnore(tenantLine = "true")
    List<MarketingTask> selectDueSendingTasks(@Param("now") long now, @Param("limit") int limit);

    /** 抢占一个到期轮次,成功时递增 current_round_no 并推进 next_round_at。 */
    int claimDueRound(@Param("id") Long id, @Param("now") long now, @Param("nextRoundAt") long nextRoundAt);

    /** backlog 过高时只推迟下一轮,不递增轮次。 */
    int postponeDueRound(@Param("id") Long id, @Param("now") long now, @Param("nextRoundAt") long nextRoundAt);

    /** 统计尚未收到协议层结果的尝试数。 */
    long countUnfinishedAttempts(@Param("taskId") Long taskId);

    /** 批量插入一轮发送尝试。 */
    int insertSendAttempts(@Param("attempts") List<MarketingTaskSendAttempt> attempts);

    /** 协议层成功结果幂等回写。 */
    int markAttemptSuccess(@Param("attemptId") Long attemptId,
                           @Param("messageId") String messageId,
                           @Param("resultAt") long resultAt);

    /** 协议层失败结果幂等回写。 */
    int markAttemptFailed(@Param("attemptId") Long attemptId,
                          @Param("reasonCode") String reasonCode,
                          @Param("reasonMessage") String reasonMessage,
                          @Param("resultAt") long resultAt);

    /** 按协议结果增量更新任务累计计数。 */
    int incrementTaskSendCounters(@Param("taskId") Long taskId,
                                  @Param("successDelta") int successDelta,
                                  @Param("failedDelta") int failedDelta,
                                  @Param("now") long now);

    /** 待启动/已停止任务置为发送中,并首次补 started_at。 */
    int startTask(@Param("id") Long id, @Param("now") long now);

    /** 发送中任务置为已停止。 */
    int stopTask(@Param("id") Long id, @Param("now") long now);

    /** 删除模板时,将关联的待启动/发送中任务置为已停止。 */
    int stopRunnableTasksByTemplateIds(@Param("templateIds") List<Long> templateIds, @Param("now") long now);

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

    /** 查询一个账号是否能形成账号动态目标。 */
    MarketingTargetCandidateRow selectAccountTargetCandidate(@Param("accountGroupId") Long accountGroupId,
                                                             @Param("accountId") Long accountId);

    /** 查询账号动态目标在本轮可发送的当前群,已排除账号导入云控前的 baseline 群。 */
    List<MarketingTargetCandidateRow> selectDynamicTargetGroups(@Param("accountId") Long accountId);

    /** 查询建营销任务用的在线账号候选;群列表由协议实时查询。 */
    List<MarketingAccountTreeAccountRow> selectAccountTreeAccounts(@Param("groupId") Long groupId);
}
