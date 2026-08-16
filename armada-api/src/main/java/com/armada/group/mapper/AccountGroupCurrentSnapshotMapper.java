package com.armada.group.mapper;

import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Context;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Existing;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.GroupId;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.LegacyGroupReference;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.ParticipantPresenceWrite;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.SyncStateWrite;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Write;
import com.armada.group.model.entity.GroupLinkPreview;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 新群模型账号可见群快照的集合化数据访问。 */
@Mapper
public interface AccountGroupCurrentSnapshotMapper {

    Context selectContext(@Param("accountId") Long accountId);

    List<Existing> selectExisting(
            @Param("accountId") Long accountId,
            @Param("pnJid") String pnJid,
            @Param("groupJids") List<String> groupJids);

    Existing selectSelfMembershipExisting(
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

    /**
     * 仅为尚未绑定的旧数值句柄回写已解析的新群 ID，供旧 API 和任务稳定寻址。
     * 已绑定的 canonical 引用不可在快照重放时改写，避免重复锁定兼容句柄。
     */
    @InterceptorIgnore(tenantLine = "true")
    int updateLegacyGroupReferences(
            @Param("tenantId") Long tenantId,
            @Param("groupJids") List<String> groupJids);

    /** 按旧流程已锁定的群入口 ID 顺序补 canonical 引用，避免在热事务里扩锁同群别名。 */
    @InterceptorIgnore(tenantLine = "true")
    int updateSelectedLegacyGroupReferences(
            @Param("tenantId") Long tenantId,
            @Param("rows") List<LegacyGroupReference> rows);

    int upsertProfiles(@Param("rows") List<Write> rows);

    int upsertParticipants(@Param("rows") List<Write> rows);

    int upsertParticipantFacts(@Param("rows") List<ParticipantPresenceWrite> rows);

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

    int upsertSyncState(@Param("row") SyncStateWrite row);
}
