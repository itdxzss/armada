package com.armada.group.mapper;

import com.armada.group.model.dto.WhatsappGroupMemberCacheHeaderWrite;
import com.armada.group.model.dto.WhatsappGroupMemberStateWrite;
import com.armada.group.model.vo.WhatsappGroupMemberCacheRow;
import com.armada.group.model.vo.WhatsappGroupMemberStateVO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** WhatsApp 群成员完整快照和最新状态 Mapper。 */
@Mapper
public interface WhatsappGroupMemberCacheMapper {

    @InterceptorIgnore(tenantLine = "true")
    List<WhatsappGroupMemberCacheRow> selectByGroupJids(
            @Param("tenantId") Long tenantId,
            @Param("groupJids") List<String> groupJids);

    @InterceptorIgnore(tenantLine = "true")
    int upsertStates(
            @Param("states") List<WhatsappGroupMemberStateWrite> states,
            @Param("now") long now);

    /** 回读增量 upsert 后实际胜出的成员状态，不依赖群缓存头是否已建立。 */
    @InterceptorIgnore(tenantLine = "true")
    List<WhatsappGroupMemberStateVO> selectStatesByParticipantJids(
            @Param("tenantId") Long tenantId,
            @Param("groupJid") String groupJid,
            @Param("participantJids") List<String> participantJids);

    @InterceptorIgnore(tenantLine = "true")
    int markSnapshotMissing(
            @Param("tenantId") Long tenantId,
            @Param("groupJid") String groupJid,
            @Param("snapshotVersion") String snapshotVersion,
            @Param("snapshotAt") long snapshotAt,
            @Param("sourceEventId") String sourceEventId,
            @Param("observerAccountId") Long observerAccountId,
            @Param("now") long now);

    @InterceptorIgnore(tenantLine = "true")
    int upsertHeader(
            @Param("header") WhatsappGroupMemberCacheHeaderWrite header,
            @Param("now") long now);

    @InterceptorIgnore(tenantLine = "true")
    String selectSnapshotVersionForUpdate(
            @Param("tenantId") Long tenantId,
            @Param("groupJid") String groupJid);
}
