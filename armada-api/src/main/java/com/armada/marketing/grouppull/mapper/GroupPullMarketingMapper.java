package com.armada.marketing.grouppull.mapper;

import com.armada.marketing.grouppull.model.dto.GroupPullMarketingGroupQuery;
import com.armada.marketing.grouppull.model.dto.GroupPullMarketingTaskQuery;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingAccountStat;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingExecution;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingExecutionMaterial;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingMaterial;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingTask;
import com.armada.marketing.grouppull.model.vo.GroupPullAccountRefRow;
import com.armada.marketing.grouppull.model.vo.GroupPullExecutionDispatchRow;
import com.armada.marketing.grouppull.model.vo.GroupPullMarketingGroupVO;
import com.armada.marketing.grouppull.model.vo.GroupPullMarketingTaskDetailVO;
import com.armada.marketing.grouppull.model.vo.GroupPullMarketingTaskVO;
import com.armada.marketing.grouppull.model.vo.GroupPullTaskDispatchRow;
import com.armada.marketing.model.entity.MarketingTask;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 拉群营销五张业务表的数据访问入口。 */
@Mapper
public interface GroupPullMarketingMapper {

    /** 插入一条拉群营销扩展配置。 */
    int insertTask(GroupPullMarketingTask row);

    /** 按上传文件稳定顺序批量插入有效料子。 */
    int insertMaterials(@Param("rows") List<GroupPullMarketingMaterial> rows);

    /** 按统一营销任务 ID 查询拉群扩展配置。 */
    GroupPullMarketingTask selectTaskById(@Param("taskId") Long taskId);

    /** 统计拉群营销一级列表总数。 */
    long countTasks(@Param("q") GroupPullMarketingTaskQuery query);

    /** 一次聚合查询当前页任务及统计。 */
    List<GroupPullMarketingTaskVO> selectTasks(@Param("q") GroupPullMarketingTaskQuery query);

    /** 查询任务配置与汇总，不展开明细。 */
    GroupPullMarketingTaskDetailVO selectTaskDetail(@Param("taskId") Long taskId);

    /**
     * 统计任务已经进入正式建群流程的执行数。
     *
     * @param taskId 统一营销任务 ID
     * @return 已冻结群名的正式建群执行数
     */
    long countTaskGroups(@Param("taskId") Long taskId);

    /**
     * 按执行 ID 升序分页查询任务正式建群明细。
     *
     * @param taskId 统一营销任务 ID
     * @param query 分页参数
     * @return 当前页正式建群执行明细
     */
    List<GroupPullMarketingGroupVO> selectTaskGroups(
            @Param("taskId") Long taskId,
            @Param("query") GroupPullMarketingGroupQuery query);

    /** 锁定读取一条拉群营销公共任务，串行化生命周期操作。 */
    MarketingTask selectTaskForUpdate(@Param("taskId") Long taskId);

    /** 待启动任务进入执行中。 */
    int startTask(@Param("taskId") Long taskId, @Param("now") long now);

    /** 启动成功后保存营销分组资源快照。 */
    int markResourcesLocked(@Param("taskId") Long taskId,
                            @Param("marketingAccountTotalCount") int marketingAccountTotalCount,
                            @Param("now") long now);

    /** 执行中任务暂停。 */
    int pauseTask(@Param("taskId") Long taskId, @Param("now") long now);

    /** 已暂停任务恢复执行。 */
    int resumeTask(@Param("taskId") Long taskId, @Param("now") long now);

    /** 人工结束执行中或已暂停任务。 */
    int requestRelease(@Param("taskId") Long taskId, @Param("now") long now);

    /** 把已锁定资源标记为安全释放中。 */
    int markResourceReleasing(@Param("taskId") Long taskId, @Param("now") long now);

    /** 更新一次资源检查得到的阻塞原因。 */
    int updateBlockReason(@Param("taskId") Long taskId,
                          @Param("blockReason") int blockReason,
                          @Param("now") long now);

    /** 统计任务当前仍可抽取的料子数量。 */
    long countAvailableMaterials(@Param("taskId") Long taskId);

    /** 仅软删除待启动公共任务。 */
    int softDeletePendingTask(@Param("taskId") Long taskId, @Param("now") long now);

    /** 删除待启动任务尚未使用的料子。 */
    int deleteTaskMaterials(@Param("taskId") Long taskId);

    /** 删除待启动任务扩展配置。 */
    int deleteTaskExtension(@Param("taskId") Long taskId);

    /** 锁定读取任务扩展配置，供一次短事务分配。 */
    GroupPullMarketingTask selectTaskByIdForUpdate(@Param("taskId") Long taskId);

    /** 统计任务当前准备中或正式执行中的建群数。 */
    long countInflightExecutions(@Param("taskId") Long taskId);

    /** 锁定选择一个尚未被任何任务领取的建群账号。 */
    GroupPullAccountRefRow selectBuilderCandidateForUpdate(@Param("taskId") Long taskId,
                                                           @Param("builderGroupId") Long builderGroupId);

    /** 按账号创建时间倒序锁定选择一个仍有群额度的营销账号。 */
    GroupPullAccountRefRow selectMarketerCandidateForUpdate(
            @Param("taskId") Long taskId,
            @Param("marketingGroupId") Long marketingGroupId,
            @Param("limit") int limit);

    /** 营销账号确认进群后，把一次预留转为永久调用次数。 */
    int confirmMarketingQuota(@Param("taskId") Long taskId,
                              @Param("accountId") Long accountId,
                              @Param("now") long now);

    /** 正式建群前取消时归还一次营销账号预留额度。 */
    int cancelMarketingQuota(@Param("taskId") Long taskId,
                             @Param("accountId") Long accountId,
                             @Param("now") long now);

    /** 按文件顺序锁定读取指定数量的可用料子。 */
    List<GroupPullMarketingMaterial> selectAvailableMaterialsForUpdate(
            @Param("taskId") Long taskId,
            @Param("limit") int limit);

    /** 把仍为可用状态的料子预留给一次建群执行。 */
    int reserveMaterials(@Param("ids") List<Long> ids,
                         @Param("executionId") Long executionId,
                         @Param("now") long now);

    /** 插入一条建群账号执行记录并回填主键。 */
    int insertExecution(GroupPullMarketingExecution row);

    /** 批量保存本次执行抽取的料子历史。 */
    int insertExecutionMaterials(@Param("rows") List<GroupPullMarketingExecutionMaterial> rows);

    /** 按主键查询单次建群执行。 */
    GroupPullMarketingExecution selectExecutionById(@Param("id") Long id);

    /** 按抽取顺序查询执行关联的料子历史。 */
    List<GroupPullMarketingExecutionMaterial> selectExecutionMaterials(
            @Param("executionId") Long executionId);

    /** 锁定读取营销账号在当前任务内的群额度。 */
    GroupPullMarketingAccountStat selectAccountStatForUpdate(
            @Param("taskId") Long taskId,
            @Param("accountId") Long accountId);

    /** 插入营销账号在当前任务内的群额度记录。 */
    int insertAccountStat(GroupPullMarketingAccountStat row);

    /** 在未达到单账号上限时原子预留一个群额度。 */
    int reserveMarketingQuota(@Param("taskId") Long taskId,
                              @Param("accountId") Long accountId,
                              @Param("limit") int limit,
                              @Param("now") long now);

    /** 读取账号最新协议事实和账号状态。 */
    GroupPullAccountRefRow selectAccountRef(@Param("accountId") Long accountId);

    /** 条件抢占当前执行阶段的短租约。 */
    int tryLeaseExecution(@Param("id") Long id,
                          @Param("executionStatus") int executionStatus,
                          @Param("expectedStage") int expectedStage,
                          @Param("now") long now,
                          @Param("leaseUntil") long leaseUntil);

    /** 账号离线或系统依赖异常时延后原阶段。 */
    int delayExecution(@Param("id") Long id,
                       @Param("executionStatus") int executionStatus,
                       @Param("expectedStage") int expectedStage,
                       @Param("nextExecuteAt") long nextExecuteAt,
                       @Param("reason") String reason,
                       @Param("now") long now);

    /** 条件推进到下一阶段并清零阶段重试次数。 */
    int advanceExecutionStage(@Param("id") Long id,
                              @Param("executionStatus") int executionStatus,
                              @Param("expectedStage") int expectedStage,
                              @Param("nextStage") int nextStage,
                              @Param("nextExecutionStatus") int nextExecutionStatus,
                              @Param("nextExecuteAt") long nextExecuteAt,
                              @Param("now") long now);

    /** 锁定公共任务后统计已经进入正式建群边界的执行数。 */
    long countNamedExecutions(@Param("taskId") Long taskId);

    /** 正式调用建群前只写一次群名称。 */
    int saveGroupNameIfAbsent(@Param("id") Long id,
                              @Param("groupName") String groupName,
                              @Param("now") long now);

    /** 建群成功后保存群 JID 并推进到加营销号或加料子阶段。 */
    int markGroupCreated(@Param("id") Long id,
                         @Param("expectedStage") int expectedStage,
                         @Param("groupJid") String groupJid,
                         @Param("nextStage") int nextStage,
                         @Param("createdAt") long createdAt);

    /** 建群结果无法确认时转人工处理，禁止自动重建。 */
    int markExecutionManualReview(@Param("id") Long id,
                                  @Param("expectedStage") int expectedStage,
                                  @Param("reason") String reason,
                                  @Param("now") long now);

    /** 写入料子单向好友结果。 */
    int updateMaterialFriendResult(@Param("id") Long id,
                                   @Param("status") int status,
                                   @Param("reason") String reason,
                                   @Param("now") long now);

    /** 写入料子实际进群结果。 */
    int updateMaterialEntryResult(@Param("id") Long id,
                                  @Param("status") int status,
                                  @Param("reason") String reason,
                                  @Param("now") long now);

    /** 写入营销账号管理员状态。 */
    int updateMarketerAdminStatus(@Param("id") Long id,
                                  @Param("status") int status,
                                  @Param("now") long now);

    /** 写入建群账号退出状态。 */
    int updateBuilderExitStatus(@Param("id") Long id,
                                @Param("status") int status,
                                @Param("now") long now);

    /** 保存群入口、邀请链接和一次成员数快照。 */
    int saveGroupInfo(@Param("id") Long id,
                      @Param("groupLinkId") Long groupLinkId,
                      @Param("inviteUrl") String inviteUrl,
                      @Param("memberCount") Integer memberCount,
                      @Param("reason") String reason,
                      @Param("now") long now);

    /** 追加不改变执行结果的失败原因。 */
    int appendExecutionFailureReason(@Param("id") Long id,
                                     @Param("reason") String reason,
                                     @Param("now") long now);

    /** 统计本次执行实际成功进入群组的料子数。 */
    long countSuccessfulMaterialEntries(@Param("executionId") Long executionId);

    /** 锁定单次执行，保证结果只结算一次。 */
    GroupPullMarketingExecution selectExecutionByIdForUpdate(@Param("id") Long id);

    /**
     * 把活动执行条件更新为最终结果，同时保存成功完成阶段或失败发生阶段。
     *
     * @param id 单群执行 ID
     * @param terminalStatus 最终执行状态码
     * @param terminalStage 最终展示阶段码；失败时保留原失败阶段
     * @param reason 最终失败原因；成功时可空
     * @param finishedAt 收口时间（epoch 毫秒）
     * @return 实际更新行数
     */
    int markExecutionTerminal(@Param("id") Long id,
                              @Param("terminalStatus") int terminalStatus,
                              @Param("terminalStage") int terminalStage,
                              @Param("reason") String reason,
                              @Param("finishedAt") long finishedAt);

    /** 成功群中实际进群料子转为完成数据。 */
    int completeSuccessfulMaterials(@Param("executionId") Long executionId,
                                    @Param("now") long now);

    /** 失败群中实际进群料子永久标记为已使用。 */
    int completeFailedJoinedMaterials(@Param("executionId") Long executionId,
                                      @Param("now") long now);

    /** 未实际进群料子取消预留，回到任务数据池。 */
    int releaseUnjoinedMaterials(@Param("executionId") Long executionId,
                                 @Param("now") long now);

    /** 仅当目标分组有效、不同于当前分组且未被营销任务锁定时迁移账号。 */
    int moveBuilderAccount(@Param("accountId") Long accountId,
                           @Param("targetGroupId") Long targetGroupId,
                           @Param("now") long now);

    /** 账号级任务占用释放后记录释放时间。 */
    int markExecutionReleased(@Param("id") Long id, @Param("now") long now);

    /** 协议明确返回群封禁或终止时更新当前群状态。 */
    int markGroupBanned(@Param("id") Long id, @Param("now") long now);

    /** 成功执行绑定唯一的现有营销固定目标。 */
    int bindMarketingTarget(@Param("id") Long id,
                            @Param("targetId") Long targetId,
                            @Param("now") long now);

    /** 第一个成功群初始化正常营销轮次时间，后续成功群不推迟。 */
    int initializeMarketingRound(@Param("taskId") Long taskId, @Param("now") long now);

    /** 跨租户扫描仍可分配新群的执行中任务。 */
    @InterceptorIgnore(tenantLine = "true")
    List<GroupPullTaskDispatchRow> selectAllocatableTaskDispatches(
            @Param("now") long now, @Param("limit") int limit);

    /** 跨租户扫描到期且按任务状态允许继续推进的执行。 */
    @InterceptorIgnore(tenantLine = "true")
    List<GroupPullExecutionDispatchRow> selectDueExecutionDispatches(
            @Param("now") long now, @Param("limit") int limit);

    /** 跨租户扫描资源释放中的任务。 */
    @InterceptorIgnore(tenantLine = "true")
    List<GroupPullTaskDispatchRow> selectReleasingTaskDispatches(@Param("limit") int limit);

    /** 锁定读取尚未正式建群、可由释放流程取消的执行。 */
    List<GroupPullMarketingExecution> selectCancelableExecutionsForUpdate(
            @Param("taskId") Long taskId);

    /** 释放流程取消一条尚未正式建群的执行。 */
    int cancelPreGroupExecution(@Param("id") Long id, @Param("now") long now);

    /** 释放一条取消执行尚未实际进群的全部预留料子。 */
    int releaseExecutionMaterials(@Param("executionId") Long executionId,
                                  @Param("now") long now);

    /** 统计已经进入正式建群流程且尚未收口的执行。 */
    long countActiveFormalExecutions(@Param("taskId") Long taskId);

    /** 资源总清理后把任务全部执行记录标记为账号占用已释放。 */
    int markTaskExecutionsReleased(@Param("taskId") Long taskId, @Param("now") long now);

    /** 营销分组确认释放后把资源状态置为已释放。 */
    int markResourceReleased(@Param("taskId") Long taskId, @Param("now") long now);
}
