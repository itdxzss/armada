package com.armada.group.normalcreation.mapper;

import com.armada.group.normalcreation.model.NormalGroupCreationRecords.ItemInsert;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.ItemIdentity;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.ItemWork;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.MemberInsert;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.MemberReplacement;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.MemberWork;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.TaskInsert;
import com.armada.group.normalcreation.model.vo.NormalGroupCreationContactFailureVO;
import com.armada.group.normalcreation.model.vo.NormalGroupCreationItemVO;
import com.armada.group.normalcreation.model.vo.NormalGroupCreationTaskVO;
import java.util.List;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 新建普群任务数据访问。 */
@Mapper
public interface NormalGroupCreationMapper {

    /** 首次使用时创建租户级准入锁行。 */
    int ensureAdmissionLock(@Param("tenantId") long tenantId, @Param("now") long now);

    /** 在当前创建事务内锁定租户准入行，直至任务和冻结明细提交。 */
    @InterceptorIgnore(tenantLine = "true")
    Long lockAdmission(@Param("tenantId") long tenantId);

    /** 锁定并返回当前租户活动任务群数，使用当前读避免 RR 旧快照。 */
    @InterceptorIgnore(tenantLine = "true")
    List<Integer> selectActiveGroupCountsForUpdate(@Param("tenantId") long tenantId);

    /** 插入任务。 */
    int insertTask(@Param("row") TaskInsert row);

    /** 按显式租户和幂等键查询任务 ID。 */
    @InterceptorIgnore(tenantLine = "true")
    Long selectTaskIdByIdempotencyKey(
            @Param("tenantId") long tenantId,
            @Param("idempotencyKey") String idempotencyKey);

    /** 唯一键并发冲突后，以当前读取得已提交任务 ID。 */
    @InterceptorIgnore(tenantLine = "true")
    Long selectTaskIdByIdempotencyKeyForUpdate(
            @Param("tenantId") long tenantId,
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

    /** 查询任务下存在未成功加好友方向的成员，逐方向返回保留的失败原因。 */
    List<NormalGroupCreationContactFailureVO> selectContactFailures(@Param("taskId") Long taskId);

    /** 查询一个计划群的冻结执行事实。 */
    ItemWork selectItemWork(@Param("itemId") Long itemId);

    /** 查询任务冻结的成员账号分组，用于联系人准备失败后的成员替换。 */
    Long selectMemberAccountGroupId(@Param("taskId") Long taskId);

    /** 锁定一个计划群及任务冻结事实，串行处理统一结果 Topic 的并发回执。 */
    @InterceptorIgnore(tenantLine = "true")
    ItemWork selectItemWorkForUpdate(@Param("tenantId") Long tenantId,
                                     @Param("itemId") Long itemId);

    /** 查询一个计划群的冻结成员。 */
    List<MemberWork> selectMemberWorks(@Param("itemId") Long itemId);

    /** 明确失败的联系人准备重试前，替换一个当前不可执行的成员账号。 */
    int replaceMember(@Param("row") MemberReplacement row);

    /** 锁定一条成员冻结事实。 */
    @InterceptorIgnore(tenantLine = "true")
    MemberWork selectMemberWorkForUpdate(@Param("tenantId") Long tenantId,
                                         @Param("itemId") Long itemId,
                                         @Param("memberId") Long memberId);

    /** 绑定联系人准备单方向的真实 Outbox commandId。 */
    int bindContactCommand(@Param("memberId") Long memberId,
                           @Param("direction") String direction,
                           @Param("expectedStatus") String expectedStatus,
                           @Param("commandId") String commandId,
                           @Param("now") long now);

    /** 全部联系人命令入库后把计划群置为运行中。 */
    int markContactPrepareSubmitted(@Param("itemId") Long itemId, @Param("now") long now);

    /** 按 commandId 幂等应用单方向联系人结果。 */
    int applyContactResult(@Param("memberId") Long memberId,
                           @Param("direction") String direction,
                           @Param("commandId") String commandId,
                           @Param("status") String status,
                           @Param("errorCode") String errorCode,
                           @Param("errorMessage") String errorMessage,
                           @Param("now") long now);

    /** 查询尚未回执的联系人方向数；加好友是尽力而为动作，FAILED/UNKNOWN 也算已落定。 */
    int countPendingContactDirections(@Param("itemId") Long itemId);

    /** 联系人方向全部落定后绑定 GROUP_CREATE 命令并推进阶段，不要求加好友成功。 */
    int startGroupCreate(@Param("itemId") Long itemId,
                         @Param("commandId") String commandId,
                         @Param("now") long now);

    /** GROUP_CREATE 成功后保存群 JID，绑定权限命令并推进阶段。 */
    int startGroupSettings(@Param("itemId") Long itemId,
                           @Param("createCommandId") String createCommandId,
                           @Param("settingsCommandId") String settingsCommandId,
                           @Param("groupJid") String groupJid,
                           @Param("groupSubject") String groupSubject,
                           @Param("now") long now);

    /** 建群成功时把冻结成员统一标记为已在群内。 */
    int markParticipantsCreated(@Param("itemId") Long itemId, @Param("now") long now);

    /** 权限成功后绑定退群命令并推进阶段。 */
    int startGroupLeave(@Param("itemId") Long itemId,
                        @Param("settingsCommandId") String settingsCommandId,
                        @Param("leaveCommandId") String leaveCommandId,
                        @Param("now") long now);

    /** 所有必需协议动作和 Armada 本地收尾成功后完成计划群。 */
    int completeProtocolFlow(@Param("itemId") Long itemId,
                             @Param("expectedStep") String expectedStep,
                             @Param("commandId") String commandId,
                             @Param("leaveStatus") String leaveStatus,
                             @Param("eventId") String eventId,
                             @Param("now") long now);

    /** 统一结果明确失败/未知时，按当前 action + commandId 收敛，禁止串阶段结果推进。 */
    int failProtocolAction(@Param("itemId") Long itemId,
                           @Param("expectedStep") String expectedStep,
                           @Param("commandId") String commandId,
                           @Param("status") String status,
                           @Param("errorCode") String errorCode,
                           @Param("errorMessage") String errorMessage,
                           @Param("groupJid") String groupJid,
                           @Param("eventId") String eventId,
                           @Param("now") long now);

    /** 明确失败项生成新 commandId 后恢复当前 Kafka action。 */
    int retryProtocolAction(@Param("itemId") Long itemId,
                            @Param("expectedStep") String expectedStep,
                            @Param("commandId") String commandId,
                            @Param("now") long now);

    /** 保存群列表入口。 */
    int updateGroupLink(@Param("itemId") Long itemId,
                        @Param("groupLinkId") Long groupLinkId,
                        @Param("now") long now);

    /** 根据计划群终态汇总任务。 */
    int refreshTaskSummary(@Param("taskId") Long taskId, @Param("now") long now);

}
