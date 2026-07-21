package com.armada.marketing.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.armada.marketing.model.dto.MarketingTaskQuery;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.entity.MarketingTaskSendAttempt;
import com.armada.marketing.model.entity.MarketingTaskTarget;
import com.armada.marketing.model.support.MarketingSendAttemptResult;
import com.armada.marketing.model.vo.MarketingAccountTreeAccountRow;
import com.armada.marketing.model.vo.MarketingTaskAccountGroupStatRow;
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

    /** 插入单个任务目标并回填 id。 */
    int insertTarget(MarketingTaskTarget target);

    /** 按 ID 查未删任务。 */
    MarketingTask selectTaskById(@Param("id") Long id);

    /** 按任务 ID 查目标明细。 */
    List<MarketingTaskTarget> selectTargetsByTaskId(@Param("taskId") Long taskId);

    /**
     * 查询账号当前占用的发送中账号动态 target。
     *
     * @param accountId 账号 ID
     * @param now       检测时间(epoch 毫秒)
     * @return 当前占用任务的账号动态 target；没有匹配时返回 null
     */
    MarketingTaskTarget selectOwnedSendingDynamicTarget(@Param("accountId") Long accountId,
                                                        @Param("now") long now);

    /** 从真实发送记录按账号+群组聚合营销明细。 */
    List<MarketingTaskAccountGroupStatRow> selectAccountGroupStatsByTaskId(@Param("taskId") Long taskId);

    /** 查询已到下一轮生成时间的发送中任务。后台调度无租户上下文,需跨租户扫描后由 worker 恢复租户。 */
    @InterceptorIgnore(tenantLine = "true")
    List<MarketingTask> selectDueSendingTasks(@Param("now") long now, @Param("limit") int limit);

    /** 查询已到计划开始时间的等待任务。后台调度无租户上下文,需跨租户扫描后由 worker 恢复租户。 */
    @InterceptorIgnore(tenantLine = "true")
    List<MarketingTask> selectDueWaitingTasks(@Param("now") long now, @Param("limit") int limit);

    /** 查询已到计划结束时间的未启动/执行中/已暂停任务。后台调度无租户上下文,需跨租户扫描后由 worker 恢复租户。 */
    @InterceptorIgnore(tenantLine = "true")
    List<MarketingTask> selectExpiredRunnableTasks(@Param("now") long now, @Param("limit") int limit);

    /** 到达计划开始时间后,等待任务进入发送中。 */
    int startDueWaitingTask(@Param("id") Long id, @Param("now") long now);

    /** 到达计划结束时间后，未启动/执行中/已暂停任务进入已完成。 */
    int endExpiredTask(@Param("id") Long id, @Param("now") long now);

    /** 修正开始时间尚未到达却处于发送中的任务,退回等待并取消轮次调度。 */
    int deferEarlySendingTask(@Param("id") Long id, @Param("now") long now);

    /** 抢占一个到期轮次,成功时递增 current_round_no 并推进 next_round_at。 */
    int claimDueRound(@Param("id") Long id, @Param("now") long now, @Param("nextRoundAt") long nextRoundAt);

    /** backlog 过高时只推迟下一轮,不递增轮次。 */
    int postponeDueRound(@Param("id") Long id, @Param("now") long now, @Param("nextRoundAt") long nextRoundAt);

    /** 统计尚未收到协议层结果的尝试数。 */
    long countUnfinishedAttempts(@Param("taskId") Long taskId);

    /** 批量插入一轮发送尝试。 */
    int insertSendAttempts(@Param("attempts") List<MarketingTaskSendAttempt> attempts);

    /** 插入单个发送尝试并回填 id。 */
    int insertSendAttempt(MarketingTaskSendAttempt attempt);

    /** 按 ID 查询发送尝试，供即时发送结果判断是否仍可重试。 */
    MarketingTaskSendAttempt selectSendAttemptById(@Param("attemptId") Long attemptId);

    /** 按 ID 查询目标及账号当前协议路由事实。 */
    MarketingTaskTarget selectTargetById(@Param("targetId") Long targetId);

    /** 把首次即时 attempt 原子切换为第二次提交，并替换当前 commandId。 */
    int resubmitImmediateAttempt(@Param("attemptId") Long attemptId,
                                 @Param("expectedCommandId") String expectedCommandId,
                                 @Param("newCommandId") String newCommandId,
                                 @Param("submittedAt") long submittedAt);

    /** 在即时 attempt 已切换到第二次提交后累计 target 重试次数。 */
    int incrementTargetRetryCount(@Param("targetId") Long targetId,
                                  @Param("attemptId") Long attemptId,
                                  @Param("updatedAt") long updatedAt);

    /** 协议层成功结果幂等回写。 */
    int markAttemptSuccess(MarketingSendAttemptResult result);

    /** 协议层失败结果幂等回写。 */
    int markAttemptFailed(MarketingSendAttemptResult result);

    /**
     * 读取成功发送尝试最终持久化的非空群 JID。
     *
     * @param taskId    普通营销任务 ID
     * @param attemptId 发送尝试 ID
     * @return 去除首尾空格后的群 JID；无有效值时返回 null
     */
    String selectSuccessfulAttemptGroupJid(@Param("taskId") Long taskId,
                                           @Param("attemptId") Long attemptId);

    /**
     * 从成功发送尝试原子写入任务与群的去重事实，唯一键冲突时不重复写入。
     *
     * @param tenantId  当前租户 ID
     * @param taskId    普通营销任务 ID
     * @param attemptId 首次成功发送尝试 ID
     * @param now       当前时间(epoch毫秒)
     * @return 本次实际插入行数，首次成功为 1，已统计过为 0
     */
    int insertSuccessfulGroupFromAttempt(@Param("tenantId") Long tenantId,
                                         @Param("taskId") Long taskId,
                                         @Param("attemptId") Long attemptId,
                                         @Param("now") long now);

    /**
     * 在新成功群事实落库后，将任务累计成功群数原子加一；软删任务仍接收已投递消息的迟到结果。
     *
     * @param taskId 普通营销任务 ID
     * @param now    当前时间(epoch毫秒)
     * @return 实际更新任务行数
     */
    int incrementTaskSuccessfulGroupCount(@Param("taskId") Long taskId,
                                          @Param("now") long now);

    /** 按协议结果增量更新任务累计计数。 */
    int incrementTaskSendCounters(@Param("taskId") Long taskId,
                                  @Param("successDelta") int successDelta,
                                  @Param("failedDelta") int failedDelta,
                                  @Param("now") long now);

    /** 协议层成功结果幂等落地后,把本次 attempt 的真实群快照和计数汇总到 target 明细。 */
    int markTargetSuccessFromAttempt(@Param("targetId") Long targetId,
                                     @Param("attemptId") Long attemptId,
                                     @Param("resultAt") long resultAt);

    /** 协议层失败结果幂等落地后,把本次 attempt 的真实群快照、失败计数和原因汇总到 target 明细。 */
    int markTargetFailedFromAttempt(@Param("targetId") Long targetId,
                                    @Param("attemptId") Long attemptId,
                                    @Param("reasonCode") String reasonCode,
                                    @Param("reasonMessage") String reasonMessage,
                                    @Param("resultAt") long resultAt);

    /** 在计划执行窗口内将未启动任务切换为执行中。 */
    int startPendingTask(@Param("id") Long id, @Param("now") long now);

    /** 将执行中任务切换为已暂停，并停止生成后续轮次。 */
    int pauseSendingTask(@Param("id") Long id, @Param("now") long now);

    /** 在计划执行窗口内恢复已暂停任务。 */
    int resumePausedTask(@Param("id") Long id, @Param("now") long now);

    /** 将未启动、执行中或已暂停任务手动关闭。 */
    int closeActiveTask(@Param("id") Long id, @Param("now") long now);

    /** 建群营销兼容入口：发送中普通营销任务置为暂停；普通营销接口不再暴露本方法。 */
    int stopTask(@Param("id") Long id, @Param("now") long now);

    /** 删除模板时，将关联的未启动、执行中或已暂停任务按异常终止置为已完成。 */
    int completeActiveTasksByTemplateIds(@Param("templateIds") List<Long> templateIds, @Param("now") long now);

    /** 统计指定任务里仍处于未启动、执行中或已暂停的任务数量。 */
    int countActiveByIds(@Param("ids") List<Long> ids);

    /** 批量软删已完成或已关闭任务。 */
    int batchSoftDelete(@Param("ids") List<Long> ids, @Param("deletedAt") long deletedAt);

    /** 分页总数。 */
    long countPage(@Param("q") MarketingTaskQuery query);

    /** 分页列表。 */
    List<MarketingTask> selectPage(@Param("q") MarketingTaskQuery query);

    /** 查询一个账号+群入口是否满足普通营销允许状态并能形成发送目标。 */
    MarketingTargetCandidateRow selectTargetCandidate(@Param("accountGroupId") Long accountGroupId,
                                                      @Param("accountId") Long accountId,
                                                      @Param("groupLinkId") Long groupLinkId,
                                                      @Param("selectableAccountStates")
                                                      List<Integer> selectableAccountStates);

    /** 查询一个账号是否满足普通营销允许状态并能形成账号动态目标。 */
    MarketingTargetCandidateRow selectAccountTargetCandidate(@Param("accountGroupId") Long accountGroupId,
                                                             @Param("accountId") Long accountId,
                                                             @Param("selectableAccountStates")
                                                             List<Integer> selectableAccountStates);

    /** 查询账号动态目标在发送时间边界内的当前群。 */
    List<MarketingTargetCandidateRow> selectDynamicTargetGroups(@Param("accountId") Long accountId,
                                                                @Param("accountGroupSendAt") Long accountGroupSendAt);

    /** 查询固定群组目标在发送前是否仍是账号当前可发送群。 */
    MarketingTargetCandidateRow selectCurrentTargetGroup(@Param("accountId") Long accountId,
                                                         @Param("groupLinkId") Long groupLinkId);

    /** 查询建营销任务用的账号树账号;包含分组下全部账号和库内可营销群数量。 */
    List<MarketingAccountTreeAccountRow> selectAccountTreeAccounts(@Param("groupId") Long groupId);

    /** 查询单个账号树账号;不校验账号分组,由服务层根据状态判断是否可展开查群。 */
    MarketingAccountTreeAccountRow selectAccountTreeAccount(@Param("accountId") Long accountId);
}
