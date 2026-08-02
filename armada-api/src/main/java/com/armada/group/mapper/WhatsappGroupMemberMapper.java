package com.armada.group.mapper;

import com.armada.group.model.entity.WhatsappGroupMember;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** WhatsApp 群全成员最新关系事实 Mapper。 */
@Mapper
public interface WhatsappGroupMemberMapper {

    /** 以 group_link 行作为同群成员写入互斥锁，避免多个观察账号交叉更新。 */
    Long lockGroupLink(@Param("groupLinkId") Long groupLinkId);

    /** 插入或按事实时间更新一个成员状态。 */
    int upsertMember(WhatsappGroupMember row);

    /** 幂等追加一个可按时间回放的成员事实。 */
    int insertMemberFact(WhatsappGroupMember row);

    /** 查询完整快照中已经缺失、但本地仍标记在群的成员。 */
    List<WhatsappGroupMember> selectMissingCurrentMembers(
            @Param("groupJid") String groupJid,
            @Param("memberJids") List<String> memberJids,
            @Param("statusUpdatedAt") long statusUpdatedAt,
            @Param("sourceEventId") String sourceEventId);

    /** 把完整快照中缺失的当前成员标记为退出方式未知。 */
    int markMissingMembers(
            @Param("ids") List<Long> ids,
            @Param("statusUpdatedAt") long statusUpdatedAt,
            @Param("updatedAt") long updatedAt,
            @Param("observerAccountId") Long observerAccountId,
            @Param("sourceEventId") String sourceEventId);

    /** 记录一次成员数已核对一致的完整快照水位。 */
    int insertCompleteSnapshot(
            @Param("groupLinkId") Long groupLinkId,
            @Param("groupJid") String groupJid,
            @Param("memberCount") int memberCount,
            @Param("snapshotAt") long snapshotAt,
            @Param("sourceEventId") String sourceEventId,
            @Param("observerAccountId") Long observerAccountId,
            @Param("announceOnly") Boolean announceOnly,
            @Param("observerAdmin") Boolean observerAdmin,
            @Param("createdAt") long createdAt);
}
