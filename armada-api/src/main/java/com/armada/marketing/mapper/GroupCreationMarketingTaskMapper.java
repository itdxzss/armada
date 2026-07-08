package com.armada.marketing.mapper;

import com.armada.marketing.model.dto.GroupCreationMarketingTaskQuery;
import com.armada.marketing.model.entity.GroupCreationMarketingItem;
import com.armada.marketing.model.entity.GroupCreationMarketingTask;
import com.armada.marketing.model.support.GroupCreationMarketingClaimRetryAccountUpdate;
import com.armada.marketing.model.support.GroupCreationMarketingItemMarketingDispatch;
import com.armada.marketing.model.support.GroupCreationMarketingNoAvailableAccountUpdate;
import com.armada.marketing.model.support.GroupCreationMarketingRetryResetUpdate;
import com.armada.marketing.model.vo.GroupCreationMarketingAccountCandidate;
import com.armada.marketing.model.vo.GroupCreationMarketingExportRow;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 建群营销任务和执行项数据访问。
 *
 * <p>任务列表、执行项调度、换号重试和结果回写都通过本 Mapper 访问真实 MySQL。
 * 后台跨租户扫描方法会显式关闭租户拦截器,其余 SQL 依赖租户插件注入 tenant_id。</p>
 */
@Mapper
public interface GroupCreationMarketingTaskMapper {

    /**
     * 插入建群营销任务主表。
     *
     * @param task 任务主表实体
     * @return 插入行数
     */
    int insertTask(GroupCreationMarketingTask task);

    /**
     * 批量插入建群营销执行项。
     *
     * @param items 执行项列表
     * @return 插入行数
     */
    int insertItems(@Param("items") List<GroupCreationMarketingItem> items);

    /**
     * 按 ID 查询未软删的建群营销任务。
     *
     * @param id 任务 ID
     * @return 任务实体;不存在时返回 null
     */
    GroupCreationMarketingTask selectTaskById(@Param("id") Long id);

    /**
     * 按 ID 查询建群营销执行项。
     *
     * @param id 执行项 ID
     * @return 执行项实体;不存在时返回 null
     */
    GroupCreationMarketingItem selectItemById(@Param("id") Long id);

    /**
     * 查询任务下的所有执行项,按文件顺序返回。
     *
     * @param taskId 任务 ID
     * @return 执行项列表
     */
    List<GroupCreationMarketingItem> selectItemsByTaskId(@Param("taskId") Long taskId);

    /**
     * 统计建群营销任务分页总数。
     *
     * @param query 查询条件
     * @return 符合条件的总行数
     */
    long countPage(@Param("q") GroupCreationMarketingTaskQuery query);

    /**
     * 分页查询建群营销任务。
     *
     * @param query 查询条件和分页参数
     * @return 当前页任务实体
     */
    List<GroupCreationMarketingTask> selectPage(@Param("q") GroupCreationMarketingTaskQuery query);

    /**
     * 查询建群营销导出行。
     *
     * @param taskIds 需要导出的任务 ID 列表
     * @return 导出统计投影
     */
    List<GroupCreationMarketingExportRow> selectExportRowsByTaskIds(@Param("taskIds") List<Long> taskIds);

    /**
     * 查询账号分组内可用于建群营销的候选账号。
     *
     * @param accountGroupId 账号分组 ID
     * @return 在线且未风控禁言的账号候选列表
     */
    List<GroupCreationMarketingAccountCandidate> selectAccountCandidatesByGroupId(@Param("accountGroupId") Long accountGroupId);

    /**
     * 查询账号分组内第一个可用候选账号。
     *
     * @param accountGroupId 账号分组 ID
     * @return 第一个可用账号;不存在时返回 null
     */
    GroupCreationMarketingAccountCandidate selectFirstAvailableAccountCandidateByGroupId(@Param("accountGroupId") Long accountGroupId);

    /**
     * 排除已尝试账号后查询账号分组内第一个可用候选账号。
     *
     * @param accountGroupId     账号分组 ID
     * @param excludedAccountIds 已尝试过的账号 ID
     * @return 第一个未尝试的可用账号;不存在时返回 null
     */
    GroupCreationMarketingAccountCandidate selectFirstAvailableAccountCandidateByGroupIdExcluding(
            @Param("accountGroupId") Long accountGroupId,
            @Param("excludedAccountIds") List<Long> excludedAccountIds);

    /**
     * 统计任务中仍可停止的执行项数量。
     *
     * @param taskId 任务 ID
     * @return 待处理、建群中、营销发送中的执行项数量
     */
    int countStoppableItems(@Param("taskId") Long taskId);

    /**
     * 将任务中未终态执行项标记为已放弃。
     *
     * @param taskId        任务 ID
     * @param reasonCode    放弃原因码
     * @param reasonMessage 放弃原因描述
     * @param finishedAt    完成时间(epoch 毫秒)
     * @return 更新行数
     */
    int stopStoppableItems(@Param("taskId") Long taskId,
                           @Param("reasonCode") String reasonCode,
                           @Param("reasonMessage") String reasonMessage,
                           @Param("finishedAt") long finishedAt);

    /**
     * 将建群营销任务主表标记为已停止。
     *
     * @param id             任务 ID
     * @param status         停止状态码
     * @param abandonedDelta 本次停止新增的放弃执行项数量
     * @param finishedAt     停止时间(epoch 毫秒)
     * @return 更新行数
     */
    int stopTask(@Param("id") Long id,
                 @Param("status") int status,
                 @Param("abandonedDelta") int abandonedDelta,
                 @Param("finishedAt") long finishedAt);

    /**
     * 跨租户扫描到期待处理执行项。
     *
     * <p>后台 worker 需要从所有租户中取待执行行,因此关闭租户拦截器,随后按执行项 tenantId
     * 重建租户上下文。</p>
     *
     * @param limit 批量上限
     * @param now   当前时间(epoch 毫秒)
     * @return 到期执行项
     */
    @InterceptorIgnore(tenantLine = "true")
    List<GroupCreationMarketingItem> selectDueItems(@Param("limit") int limit, @Param("now") long now);

    /**
     * 抢占待处理执行项并推进任务为运行中。
     *
     * @param id         执行项 ID
     * @param fromStatus 允许抢占的当前状态
     * @param toStatus   抢占后的执行状态
     * @param now        抢占时间(epoch 毫秒)
     * @return 更新行数;0 表示状态已被其他 worker 改变
     */
    int claimItem(@Param("id") Long id,
                  @Param("fromStatus") int fromStatus,
                  @Param("toStatus") int toStatus,
                  @Param("now") long now);

    /**
     * 查询单个账号的建群营销可执行快照。
     *
     * @param accountId 账号 ID
     * @return 账号候选快照;不存在时返回 null
     */
    GroupCreationMarketingAccountCandidate selectAccountCandidateByAccountId(@Param("accountId") Long accountId);

    /**
     * 建群中执行项替换账号快照。
     *
     * @param id                执行项 ID
     * @param accountId         新账号 ID
     * @param accountPhone      新账号手机号
     * @param protocolAccountId 新协议账号 ID
     * @param updatedAt         更新时间(epoch 毫秒)
     * @return 更新行数
     */
    int updateItemAccountIfCreating(@Param("id") Long id,
                                    @Param("accountId") Long accountId,
                                    @Param("accountPhone") String accountPhone,
                                    @Param("protocolAccountId") String protocolAccountId,
                                    @Param("updatedAt") long updatedAt);

    /**
     * 领取后换号重试时更新执行项账号快照和重试历史。
     *
     * @param update 账号替换参数
     * @return 更新行数
     */
    int updateItemAccountForClaimRetry(@Param("update") GroupCreationMarketingClaimRetryAccountUpdate update);

    /**
     * 换号重试时将执行项重置回待处理状态。
     *
     * @param update 重置参数
     * @return 更新行数
     */
    int resetItemForAccountRetry(@Param("update") GroupCreationMarketingRetryResetUpdate update);

    /**
     * 首次建群成功时回填普通营销任务 ID。
     *
     * @param taskId          建群营销任务 ID
     * @param marketingTaskId 普通营销任务 ID
     * @param updatedAt       更新时间(epoch 毫秒)
     * @return 更新行数
     */
    int updateTaskMarketingTaskIdIfAbsent(@Param("taskId") Long taskId,
                                          @Param("marketingTaskId") Long marketingTaskId,
                                          @Param("updatedAt") long updatedAt);

    /**
     * 建群成功并提交营销消息后,将执行项标记为营销发送中。
     *
     * @param dispatch 营销派发参数
     * @return 更新行数
     */
    int markItemMarketingSending(@Param("dispatch") GroupCreationMarketingItemMarketingDispatch dispatch);

    /**
     * 将未终态执行项标记为已放弃。
     *
     * @param id            执行项 ID
     * @param reasonCode    放弃原因码
     * @param reasonMessage 放弃原因描述
     * @param finishedAt    完成时间(epoch 毫秒)
     * @return 更新行数
     */
    int markItemAbandoned(@Param("id") Long id,
                          @Param("reasonCode") String reasonCode,
                          @Param("reasonMessage") String reasonMessage,
                          @Param("finishedAt") long finishedAt);

    /**
     * 换号重试耗尽可用账号时,将执行项标记为已放弃。
     *
     * @param update 放弃参数
     * @return 更新行数
     */
    int markItemNoAvailableAccount(@Param("update") GroupCreationMarketingNoAvailableAccountUpdate update);

    /**
     * 将执行项标记为失败并同步任务失败计数。
     *
     * @param id                    执行项 ID
     * @param reasonCode            失败原因码
     * @param reasonMessage         失败原因描述
     * @param participantResultJson 协议结果摘要 JSON
     * @param finishedAt            完成时间(epoch 毫秒)
     * @return 更新行数
     */
    int markItemFailed(@Param("id") Long id,
                       @Param("reasonCode") String reasonCode,
                       @Param("reasonMessage") String reasonMessage,
                       @Param("participantResultJson") String participantResultJson,
                       @Param("finishedAt") long finishedAt);

    /**
     * 按普通营销发送尝试 ID 将建群营销执行项标记为成功。
     *
     * @param attemptId  普通营销发送尝试 ID
     * @param finishedAt 完成时间(epoch 毫秒)
     * @return 更新行数
     */
    int markItemSuccessByMarketingAttemptId(@Param("attemptId") Long attemptId,
                                            @Param("finishedAt") long finishedAt);

    /**
     * 按普通营销发送尝试 ID 将建群营销执行项标记为失败。
     *
     * @param attemptId     普通营销发送尝试 ID
     * @param reasonCode    失败原因码
     * @param reasonMessage 失败原因描述
     * @param finishedAt    完成时间(epoch 毫秒)
     * @return 更新行数
     */
    int markItemFailedByMarketingAttemptId(@Param("attemptId") Long attemptId,
                                           @Param("reasonCode") String reasonCode,
                                           @Param("reasonMessage") String reasonMessage,
                                           @Param("finishedAt") long finishedAt);

    /**
     * 按协议命令 ID 将建群营销执行项标记为成功。
     *
     * @param itemId     执行项 ID
     * @param commandId  协议命令 ID
     * @param groupJid   结果事件回带的群 JID
     * @param messageId  协议层消息 ID
     * @param finishedAt 完成时间(epoch 毫秒)
     * @return 更新行数
     */
    int markItemSuccessByCommandId(@Param("itemId") Long itemId,
                                   @Param("commandId") String commandId,
                                   @Param("groupJid") String groupJid,
                                   @Param("messageId") String messageId,
                                   @Param("finishedAt") long finishedAt);

    /**
     * 按协议命令 ID 将建群营销执行项标记为失败。
     *
     * @param itemId        执行项 ID
     * @param commandId     协议命令 ID
     * @param reasonCode    失败原因码
     * @param reasonMessage 失败原因描述
     * @param finishedAt    完成时间(epoch 毫秒)
     * @return 更新行数
     */
    int markItemFailedByCommandId(@Param("itemId") Long itemId,
                                  @Param("commandId") String commandId,
                                  @Param("reasonCode") String reasonCode,
                                  @Param("reasonMessage") String reasonMessage,
                                  @Param("finishedAt") long finishedAt);
}
