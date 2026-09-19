package com.armada.group.mapper;

import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Context;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.ControlledExisting;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.ControlledWrite;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Existing;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.GroupId;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.LegacyGroupHandle;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.LegacyGroupReference;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.MembershipExitWrite;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.ParticipantIdentityMergeWrite;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.ParticipantIdentityRow;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.ParticipantPresenceWrite;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.SyncStateWrite;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Write;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 新群模型账号可见群快照的集合化数据访问。 */
@Mapper
public interface AccountGroupCurrentSnapshotMapper {

    Context selectContext(@Param("accountId") Long accountId);

    /** 显式限定租户，一次读取同群受控账号及各自 baseline 上下文。 */
    @InterceptorIgnore(tenantLine = "true")
    List<Context> selectContexts(
            @Param("tenantId") Long tenantId,
            @Param("accountIds") List<Long> accountIds);

    /** 群 PRIMARY 锁已持有后，按 G→P→B 批量读取并锁定各账号当前事实。 */
    @InterceptorIgnore(tenantLine = "true")
    List<ControlledExisting> selectControlledExistingAfterGroupLock(
            @Param("tenantId") Long tenantId,
            @Param("groupId") Long groupId,
            @Param("rows") List<ControlledWrite> rows);

    /** 在账号群回报写事务内锁定账号绑定与已接受完整快照水位。 */
    default Context selectContextForUpdate(Long accountId) {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        return selectContextForUpdateByTenant(tenantId, accountId);
    }

    /** 显式租户条件避免租户插件把 MySQL 的 LIMIT ... FOR UPDATE 改为非法语序。 */
    @InterceptorIgnore(tenantLine = "true")
    Context selectContextForUpdateByTenant(
            @Param("tenantId") Long tenantId,
            @Param("accountId") Long accountId);

    /**
     * 写入建群时间，只在当前为空时填充。
     *
     * @param groupId       群主键
     * @param waCreatedAt   WhatsApp 建群时间(epoch 毫秒)
     * @param now           当前时间(epoch 毫秒)
     * @return 实际更新行数
     */
    int fillGroupCreatedAt(@Param("groupId") Long groupId,
                           @Param("waCreatedAt") Long waCreatedAt,
                           @Param("now") long now);

    List<Existing> selectExisting(
            @Param("accountId") Long accountId,
            @Param("pnJid") String pnJid,
            @Param("groupJids") List<String> groupJids);

    /** G 主键锁已持有后，仅在该闭包内按 G→P→B 读取并锁定账号当前群事实。 */
    @InterceptorIgnore(tenantLine = "true")
    List<Existing> selectExistingAfterGroupLock(
            @Param("tenantId") Long tenantId,
            @Param("accountId") Long accountId,
            @Param("pnJid") String pnJid,
            @Param("groupIds") List<Long> groupIds);

    Existing selectSelfMembershipExisting(
            @Param("accountId") Long accountId,
            @Param("pnJid") String pnJid,
            @Param("groupJid") String groupJid);

    /** 按显式租户普通读取单群账号自身成员事实，不提前锁定 G/P/B。 */
    @InterceptorIgnore(tenantLine = "true")
    Existing selectSelfMembershipExistingByTenant(
            @Param("tenantId") Long tenantId,
            @Param("accountId") Long accountId,
            @Param("pnJid") String pnJid,
            @Param("groupJid") String groupJid);

    @InterceptorIgnore(tenantLine = "true")
    int insertMissingGroups(
            @Param("tenantId") Long tenantId,
            @Param("rows") List<Write> rows);

    /** 显式租户条件避免租户插件把 MySQL 的 ORDER BY ... FOR UPDATE 改成非法语序。 */
    @InterceptorIgnore(tenantLine = "true")
    List<GroupId> selectGroupIds(
            @Param("tenantId") Long tenantId,
            @Param("groupJids") List<String> groupJids);

    @InterceptorIgnore(tenantLine = "true")
    List<GroupId> selectGroupIdsWithoutLock(
            @Param("tenantId") Long tenantId,
            @Param("groupJids") List<String> groupJids);

    /** 按 wa_group PRIMARY 升序查询现有群，写入由 SQL 条件和唯一键约束。 */
    @InterceptorIgnore(tenantLine = "true")
    List<GroupId> selectGroupIdsByIds(
            @Param("tenantId") Long tenantId,
            @Param("groupIds") List<Long> groupIds);

    /** 普通读解析当前已存在、尚未关联 canonical 群的内部旧句柄。 */
    @InterceptorIgnore(tenantLine = "true")
    List<LegacyGroupHandle> selectUnboundLegacyGroupHandlesWithoutLock(
            @Param("tenantId") Long tenantId,
            @Param("groupJids") List<String> groupJids);

    /** 按 PRIMARY 升序普通读取已解析的旧句柄。 */
    @InterceptorIgnore(tenantLine = "true")
    List<Long> selectLegacyGroupHandleIdsByIds(
            @Param("tenantId") Long tenantId,
            @Param("groupLinkIds") List<Long> groupLinkIds);

    /** 按已解析的群入口 ID 顺序补 canonical 引用。 */
    @InterceptorIgnore(tenantLine = "true")
    int updateSelectedLegacyGroupReferences(
            @Param("tenantId") Long tenantId,
            @Param("rows") List<LegacyGroupReference> rows);

    int upsertProfiles(@Param("rows") List<Write> rows);

    int upsertParticipants(@Param("rows") List<Write> rows);

    int upsertParticipantFacts(@Param("rows") List<ParticipantPresenceWrite> rows);

    /** 对已解析的成员按租户、群和主键补身份并更新事实，避免经唯一键冲突转入更新。 */
    @InterceptorIgnore(tenantLine = "true")
    int updateParticipantFactsById(
            @Param("tenantId") Long tenantId,
            @Param("participantId") Long participantId,
            @Param("row") ParticipantPresenceWrite row);

    /** 按本次明确给出的 PN/LID/phone 普通读取现有成员，供有证据的双行定点归并。 */
    @InterceptorIgnore(tenantLine = "true")
    List<ParticipantIdentityRow> selectParticipantIdentityRows(
            @Param("tenantId") Long tenantId,
            @Param("rows") List<ParticipantPresenceWrite> rows);

    /** 按事实时间和来源优先级把 PN 重复行事实并入 LID canonical 行。 */
    @InterceptorIgnore(tenantLine = "true")
    int mergeSplitParticipantFacts(@Param("row") ParticipantIdentityMergeWrite row);

    /** 将账号群绑定从待删除 PN 行改指 LID canonical 行。 */
    @InterceptorIgnore(tenantLine = "true")
    int repointSplitParticipantBindings(@Param("row") ParticipantIdentityMergeWrite row);

    /** 删除事实和绑定均已迁移的 PN 重复行。 */
    @InterceptorIgnore(tenantLine = "true")
    int deleteSplitParticipantDuplicate(@Param("row") ParticipantIdentityMergeWrite row);

    /** 在 PN 重复行删除后补齐 LID canonical 行的完整身份。 */
    @InterceptorIgnore(tenantLine = "true")
    int completeSplitParticipantIdentity(@Param("row") ParticipantIdentityMergeWrite row);

    /**
     * 只把同一个人的 PN/LID 身份与号码补进同一行，不改 presence 与 role。
     *
     * <p>协议 modify 事件表示成员身份形态变化，并没有观察到在群与否和角色，所以不能走
     * {@link #upsertParticipantFacts}——那条语句的 presence 没有"未观察"档，会把已知的在群态
     * 覆盖成未知。新行按 presence_status=0、role=0 落地，两者都表示未知。</p>
     *
     * <p>调用方必须先确认同一个人在库里只有一行：已经分裂成 PN 行和 LID 行时，本语句会同时命中
     * 两个唯一键而报重复键错误。跨行归并不在本语句职责内。</p>
     *
     * @param rows 只有 groupId/pnJid/lidJid/phone/now 有意义的写入行
     * @return 受影响行数
     */
    int mergeParticipantIdentities(@Param("rows") List<ParticipantPresenceWrite> rows);

    int upsertGroupMetadata(
            @Param("groupId") Long groupId,
            @Param("row") GroupLinkPreview row,
            @Param("waCreatedAt") Long waCreatedAt,
            @Param("metadataObservedAt") long metadataObservedAt,
            @Param("now") long now);

    int upsertParticipantSnapshotHeader(
            @Param("groupId") Long groupId,
            @Param("memberCount") int memberCount,
            @Param("snapshotAt") long snapshotAt,
            @Param("snapshotVersion") String snapshotVersion,
            @Param("now") long now);

    String selectParticipantSnapshotVersionForUpdate(@Param("groupId") Long groupId);

    int markParticipantSnapshotMissing(
            @Param("groupId") Long groupId,
            @Param("snapshotAt") long snapshotAt,
            @Param("snapshotVersion") String snapshotVersion,
            @Param("eventId") String eventId,
            @Param("now") long now);

    int markMissingParticipants(
            @Param("participantIds") List<Long> participantIds,
            @Param("syncAt") long syncAt,
            @Param("eventId") String eventId,
            @Param("now") long now);

    @InterceptorIgnore(tenantLine = "true")
    int upsertBindings(
            @Param("tenantId") Long tenantId,
            @Param("accountId") Long accountId,
            @Param("rows") List<Write> rows);

    @InterceptorIgnore(tenantLine = "true")
    int upsertSelfBinding(
            @Param("tenantId") Long tenantId,
            @Param("accountId") Long accountId,
            @Param("row") ParticipantPresenceWrite row);

    /** 按账号独立保留事实接受、baseline 和周期规则，批量补写同群账号绑定。 */
    @InterceptorIgnore(tenantLine = "true")
    int upsertControlledBindings(
            @Param("tenantId") Long tenantId,
            @Param("rows") List<ControlledWrite> rows);

    /** 仅按每个账号、群及仍被接受的退出来源和时间清空对应在群周期。 */
    @InterceptorIgnore(tenantLine = "true")
    int clearControlledMembershipActiveSinceForAcceptedExits(
            @Param("tenantId") Long tenantId,
            @Param("rows") List<ControlledWrite> rows);

    /** 仅当 participant 当前仍是指定退出事实时，清空这些群的当前在群周期起点。 */
    @InterceptorIgnore(tenantLine = "true")
    int clearMembershipActiveSinceForAcceptedExit(
            @Param("tenantId") Long tenantId,
            @Param("exit") MembershipExitWrite exit);

    int upsertSyncState(@Param("row") SyncStateWrite row);
}
