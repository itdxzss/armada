package com.armada.group.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 新群模型当前邀请码的数据访问。 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface GroupCurrentInviteMapper {

    Long selectGroupId(
            @Param("tenantId") Long tenantId,
            @Param("groupJid") String groupJid);

    int insertGroup(
            @Param("tenantId") Long tenantId,
            @Param("groupJid") String groupJid,
            @Param("now") long now);

    Long selectGroupIdForUpdate(
            @Param("tenantId") Long tenantId,
            @Param("groupJid") String groupJid);

    int lockProfile(
            @Param("tenantId") Long tenantId,
            @Param("groupId") Long groupId,
            @Param("now") long now);

    int upsertInvite(
            @Param("tenantId") Long tenantId,
            @Param("groupId") Long groupId,
            @Param("inviteCode") String inviteCode,
            @Param("observedAt") long observedAt,
            @Param("now") long now);

    Long selectInviteIdForUpdate(
            @Param("tenantId") Long tenantId,
            @Param("inviteCode") String inviteCode);

    int updateCurrentInvite(
            @Param("tenantId") Long tenantId,
            @Param("groupId") Long groupId,
            @Param("inviteId") Long inviteId,
            @Param("observedAt") long observedAt,
            @Param("now") long now);
}
