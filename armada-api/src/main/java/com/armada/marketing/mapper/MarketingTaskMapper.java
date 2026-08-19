package com.armada.marketing.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.armada.marketing.model.dto.MarketingTaskQuery;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.entity.MarketingTaskSendAttempt;
import com.armada.marketing.model.entity.MarketingTaskTarget;
import com.armada.marketing.model.support.MarketingSendAttemptResult;
import com.armada.marketing.model.support.MarketingTargetResultSnapshot;
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

    /** 锁定任务主记录，作为暂停、关闭、结束与延迟到期提交的统一串行化边界。 */
    MarketingTask selectTaskByIdForUpdate(@Param("id") Long id);

    /** 按任务 ID 查目标明细。 */
    List<MarketingTaskTarget> selectTargetsByTaskId(@Param("taskId") Long taskId);

    /**
     * 查询账号当前占用且可登记新群的账号动态 target。
     *
     * <p>发送中任务始终可匹配；暂停任务仅在新群延迟开启时匹配，避免暂停期间即时发送。</p>
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

    /** 拉群营销到期后结束主任务并进入资源释放中。 */
    int endExpiredGroupPullTask(@Param("id") Long id, @Param("now") long now);

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

    /** 跨租户扫描已到计划时间且任务仍在发送中的新群等待记录。 */
    @InterceptorIgnore(tenantLine = "true")
    List<MarketingTaskSendAttempt> selectDueWaitingNewGroupAttempts(@Param("now") long now,
                                                                    @Param("limit") int limit);

    /** 在当前租户事务中锁定指定的等待记录，供多实例幂等提交。 */
    List<MarketingTaskSendAttempt> selectWaitingAttemptsForUpdate(
            @Param("taskId") Long taskId,
            @Param("attemptIds") List<Long> attemptIds,
            @Param("now") long now);

    /** 判断同一动态目标和群是否已被普通轮次成功提交或发送成功。 */
    int countOrdinarySubmittedOrSuccessfulAttempts(@Param("targetId") Long targetId,
                                                    @Param("groupJid") String groupJid);

    /** 记录普通或即时 attempt 已被 Outbox 接受的不可变事实。 */
    int markAttemptOutboxAccepted(@Param("attemptId") Long attemptId,
                                  @Param("commandId") String commandId,
                                  @Param("acceptedAt") long acceptedAt);

    /** Outbox 接受后把等待记录原子切换为已提交。 */
    int markWaitingAttemptSubmitted(@Param("attemptId") Long attemptId,
                                    @Param("commandId") String commandId,
                                    @Param("submittedAt") long submittedAt);

    /** 把等待记录收口为业务跳过。 */
    int markWaitingAttemptSkipped(@Param("attemptId") Long attemptId,
                                  @Param("reasonCode") String reasonCode,
                                  @Param("reasonMessage") String reasonMessage,
                                  @Param("resultAt") long resultAt);

    /** 把等待阶段的本地提交失败收口为失败。 */
    int markWaitingAttemptFailed(@Param("attemptId") Long attemptId,
                                 @Param("reasonCode") String reasonCode,
                                 @Param("reasonMessage") String reasonMessage,
                                 @Param("resultAt") long resultAt);

    /** 任务关闭或结束时批量跳过尚未写入 Outbox 的等待记录。 */
    int markTaskWaitingAttemptsSkipped(@Param("taskId") Long taskId,
                                       @Param("reasonCode") String reasonCode,
                                       @Param("reasonMessage") String reasonMessage,
                                       @Param("resultAt") long resultAt);

    /** 按 ID 查询目标及账号当前协议路由事实。 */
    MarketingTaskTarget selectTargetById(@Param("targetId") Long targetId);

    /** 拉群营销首次发送前确认目标营销账号仍正常在线。 */
    int countSendableGroupPullTarget(@Param("targetId") Long targetId);

    /** 拉群营销正常轮次或即时重试前确认营销分组仍由本任务持有。 */
    int countOwnedGroupPullMarketingGroup(@Param("taskId") Long taskId);

    /** 发送结果明确群封禁时同步拉群群明细状态。 */
    int markGroupPullExecutionBannedByTargetId(@Param("targetId") Long targetId,
                                               @Param("now") long now);

    /** 批量读取拉群营销本轮账号正常在线且群正常的固定目标 ID。 */
    List<Long> selectSendableGroupPullTargetIds(@Param("taskId") Long taskId);

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

    /**
     * 释放任务时把当前租户下已取消 outbox 对应的提交中 attempt 标记为业务跳过。
     *
     * @param tenantId 当前租户 ID
     * @param taskId 营销任务 ID
     * @param now 当前时间（epoch 毫秒）
     * @return 实际更新的发送尝试数
     */
    @InterceptorIgnore(tenantLine = "true")
    int markCanceledOutboxAttemptsSkipped(@Param("tenantId") Long tenantId,
                                          @Param("taskId") Long taskId,
                                          @Param("now") long now);

    /**
     * 释放任务时把当前租户下死信 outbox 对应的提交中 attempt 标记为失败。
     *
     * @param tenantId 当前租户 ID
     * @param taskId 营销任务 ID
     * @param now 当前时间（epoch 毫秒）
     * @return 实际更新的发送尝试数
     */
    @InterceptorIgnore(tenantLine = "true")
    int markDeadOutboxAttemptsFailed(@Param("tenantId") Long tenantId,
                                     @Param("taskId") Long taskId,
                                     @Param("now") long now);

    /**
     * 协议层成功结果幂等落地后，把本次 attempt 的真实群快照和计数汇总到 target 明细。
     *
     * <p>先锁定 target 行，再通过普通查询解析共享群元数据，最后执行 target 单表更新；
     * 调用签名和字段优先级保持不变。</p>
     */
    default int markTargetSuccessFromAttempt(Long targetId, Long attemptId, long resultAt) {
        MarketingTaskTarget target = selectTargetForResultUpdate(targetId);
        if (target == null) {
            return 0;
        }
        MarketingTargetResultSnapshot snapshot = selectTargetResultSnapshot(target, attemptId);
        return snapshot == null ? 0 : updateTargetSuccessFromSnapshot(targetId, snapshot, resultAt);
    }

    /**
     * 协议层失败结果幂等落地后，把本次 attempt 的真实群快照、失败计数和原因汇总到 target 明细。
     *
     * <p>先锁定 target 行，再通过普通查询解析共享群元数据，最后执行 target 单表更新；
     * 调用签名和字段优先级保持不变。</p>
     */
    default int markTargetFailedFromAttempt(Long targetId,
                                            Long attemptId,
                                            String reasonCode,
                                            String reasonMessage,
                                            long resultAt) {
        MarketingTaskTarget target = selectTargetForResultUpdate(targetId);
        if (target == null) {
            return 0;
        }
        MarketingTargetResultSnapshot snapshot = selectTargetResultSnapshot(target, attemptId);
        return snapshot == null
                ? 0
                : updateTargetFailedFromSnapshot(targetId, snapshot, reasonCode, reasonMessage, resultAt);
    }

    /** 单表锁定并读取待回填 target，防止锁前快照覆盖并发结果。 */
    MarketingTaskTarget selectTargetForResultUpdate(@Param("targetId") Long targetId);

    /** 按已锁定 target 与 attempt 绑定关系读取结果回填群快照；普通查询不锁共享群表。 */
    MarketingTargetResultSnapshot selectTargetResultSnapshot(@Param("target") MarketingTaskTarget target,
                                                              @Param("attemptId") Long attemptId);

    /** 使用已解析快照单表更新成功 target。 */
    int updateTargetSuccessFromSnapshot(@Param("targetId") Long targetId,
                                        @Param("snapshot") MarketingTargetResultSnapshot snapshot,
                                        @Param("resultAt") long resultAt);

    /** 使用已解析快照单表更新失败 target。 */
    int updateTargetFailedFromSnapshot(@Param("targetId") Long targetId,
                                       @Param("snapshot") MarketingTargetResultSnapshot snapshot,
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

    /** 统计仍在使用指定模板的活动拉群营销任务。 */
    int countActiveGroupPullTasksByTemplateIds(@Param("templateIds") List<Long> templateIds);

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
    List<MarketingTargetCandidateRow> selectDynamicTargetGroups(@Param("targetId") Long targetId,
                                                                @Param("accountId") Long accountId,
                                                                @Param("accountGroupSendAt") Long accountGroupSendAt);

    /** 查询固定群组目标在发送前是否仍是账号当前可发送群。 */
    MarketingTargetCandidateRow selectCurrentTargetGroup(@Param("accountId") Long accountId,
                                                         @Param("groupLinkId") Long groupLinkId);

    /** 查询建营销任务用的账号树账号;包含分组下全部账号和库内可营销群数量。 */
    List<MarketingAccountTreeAccountRow> selectAccountTreeAccounts(@Param("groupId") Long groupId);

    /** 查询单个账号树账号;不校验账号分组,由服务层根据状态判断是否可展开查群。 */
    MarketingAccountTreeAccountRow selectAccountTreeAccount(@Param("accountId") Long accountId);
}
