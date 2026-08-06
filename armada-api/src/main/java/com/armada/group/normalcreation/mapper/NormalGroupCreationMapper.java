package com.armada.group.normalcreation.mapper;

import com.armada.group.normalcreation.model.NormalGroupCreationRecords.ItemInsert;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.ItemIdentity;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.ItemWork;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.MemberInsert;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.MemberWork;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.TaskInsert;
import com.armada.group.normalcreation.model.vo.NormalGroupCreationItemVO;
import com.armada.group.normalcreation.model.vo.NormalGroupCreationTaskVO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 新建普群任务数据访问。 */
@Mapper
public interface NormalGroupCreationMapper {

    /** 首次使用时创建租户级准入锁行。 */
    int ensureAdmissionLock(@Param("tenantId") long tenantId, @Param("now") long now);

    /** 在当前创建事务内锁定租户准入行，直至任务和冻结明细提交。 */
    Long lockAdmission(@Param("tenantId") long tenantId);

    /** 锁定并返回当前租户活动任务群数，使用当前读避免 RR 旧快照。 */
    List<Integer> selectActiveGroupCountsForUpdate();

    /** 插入任务。 */
    int insertTask(@Param("row") TaskInsert row);

    /** 按当前租户幂等键查询任务 ID。 */
    Long selectTaskIdByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    /** 唯一键并发冲突后，以当前读取得已提交任务 ID。 */
    Long selectTaskIdByIdempotencyKeyForUpdate(
            @Param("idempotencyKey") String idempotencyKey);

    /** 批量插入计划群。 */
    int insertItems(@Param("rows") List<ItemInsert> rows);

    /** 一次回查任务下全部计划群主键，避免逐群查询。 */
    List<ItemIdentity> selectItemIdentities(@Param("taskId") Long taskId);

    /** 批量插入计划群成员。 */
    int insertMembers(@Param("rows") List<MemberInsert> rows);

    /** 查询任务摘要。 */
    NormalGroupCreationTaskVO selectTask(@Param("taskId") Long taskId);

    /** 查询任务的计划群明细。 */
    List<NormalGroupCreationItemVO> selectItems(@Param("taskId") Long taskId);

    /** 查询一个计划群的冻结执行事实。 */
    ItemWork selectItemWork(@Param("itemId") Long itemId);

    /** 查询一个计划群的冻结成员。 */
    List<MemberWork> selectMemberWorks(@Param("itemId") Long itemId);

    /** 原子领取当前阶段。 */
    int claimStage(@Param("itemId") Long itemId,
                   @Param("expectedStep") String expectedStep,
                   @Param("eventId") String eventId,
                   @Param("attemptColumn") String attemptColumn,
                   @Param("now") long now);

    /** 标记成员双向联系人保存结果。 */
    int updateContactStatus(@Param("memberId") Long memberId,
                            @Param("creatorSaved") String creatorSaved,
                            @Param("memberSaved") String memberSaved,
                            @Param("errorCode") String errorCode,
                            @Param("errorMessage") String errorMessage,
                            @Param("now") long now);

    /** 联系人准备完成并切换到建群阶段。 */
    int completePrepare(@Param("itemId") Long itemId,
                        @Param("eventId") String eventId,
                        @Param("now") long now);

    /** 记录逐成员建群结果。 */
    int updateParticipantStatus(@Param("memberId") Long memberId,
                                @Param("status") String status,
                                @Param("rawStatus") String rawStatus,
                                @Param("now") long now);

    /** 协议返回后先持久化群 JID，保持当前 CREATE 租约，不提前暴露后处理。 */
    int persistCreatedGroup(@Param("itemId") Long itemId,
                            @Param("groupJid") String groupJid,
                            @Param("createPartial") boolean createPartial,
                            @Param("eventId") String eventId,
                            @Param("now") long now);

    /** 全部逐成员回执保存后切换到后处理阶段。 */
    int completeCreate(@Param("itemId") Long itemId,
                       @Param("eventId") String eventId,
                       @Param("now") long now);

    /** 保存群列表入口。 */
    int updateGroupLink(@Param("itemId") Long itemId,
                        @Param("groupLinkId") Long groupLinkId,
                        @Param("now") long now);

    /** 群后处理完成。 */
    int completePostProcess(@Param("itemId") Long itemId,
                            @Param("settingsStatus") String settingsStatus,
                            @Param("leaveStatus") String leaveStatus,
                            @Param("eventId") String eventId,
                            @Param("now") long now);

    /** 把当前明细收敛为失败或结果未知。 */
    int failItem(@Param("itemId") Long itemId,
                 @Param("status") String status,
                 @Param("errorCode") String errorCode,
                 @Param("errorMessage") String errorMessage,
                 @Param("eventId") String eventId,
                 @Param("now") long now);

    /** 把明确失败项恢复到其冻结阶段，供人工重试。 */
    int resetItemForRetry(@Param("taskId") Long taskId,
                          @Param("itemId") Long itemId,
                          @Param("now") long now);

    /** 下一阶段消息发送成功。 */
    int markDispatched(@Param("itemId") Long itemId,
                       @Param("stage") String stage,
                       @Param("now") long now);

    /** 临时故障交回 Kafka 重试，并为低频补偿保留待发布状态。 */
    int releaseStageForRetry(@Param("itemId") Long itemId,
                             @Param("expectedStep") String expectedStep,
                             @Param("eventId") String eventId,
                             @Param("maxAttempts") int maxAttempts,
                             @Param("errorCode") String errorCode,
                             @Param("errorMessage") String errorMessage,
                             @Param("nextDispatchAt") long nextDispatchAt,
                             @Param("now") long now);

    /** 按租户回收一个已过期的执行租约；建群阶段收敛为结果未知。 */
    int recoverExpiredProcessing(@Param("itemId") Long itemId,
                                 @Param("expectedStep") String expectedStep,
                                 @Param("processingBefore") long processingBefore,
                                 @Param("maxAttempts") int maxAttempts,
                                 @Param("now") long now);

    /** 根据计划群终态汇总任务。 */
    int refreshTaskSummary(@Param("taskId") Long taskId, @Param("now") long now);

}
