package com.armada.group.mapper;

import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Context;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Existing;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.GroupId;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.SelfMembershipWrite;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.SyncStateWrite;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Write;
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

    int upsertProfiles(@Param("rows") List<Write> rows);

    int upsertParticipants(@Param("rows") List<Write> rows);

    int upsertSelfParticipant(@Param("row") SelfMembershipWrite row);

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
            @Param("row") SelfMembershipWrite row);

    int upsertSyncState(@Param("row") SyncStateWrite row);
}
