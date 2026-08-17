package com.armada.group.mapper;

import com.armada.group.model.dto.GroupCurrentLocalProfileWrite;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 将兼容群入口上的本地操作写入当前群模型。 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface GroupCurrentLocalMapper {

    int updateResolvedGroupProfile(
            @Param("tenantId") Long tenantId,
            @Param("row") GroupCurrentLocalProfileWrite row);

    int updateUnresolvedInviteProfile(
            @Param("tenantId") Long tenantId,
            @Param("row") GroupCurrentLocalProfileWrite row);

    int updateInviteLabel(
            @Param("tenantId") Long tenantId,
            @Param("groupLinkIds") List<Long> groupLinkIds,
            @Param("labelId") Long labelId,
            @Param("updatedAt") long updatedAt);

    int updateGroupFolder(
            @Param("tenantId") Long tenantId,
            @Param("groupLinkIds") List<Long> groupLinkIds,
            @Param("folderId") Long folderId,
            @Param("updatedAt") long updatedAt);

    int softDeleteGroupsWithoutActiveAlias(
            @Param("tenantId") Long tenantId,
            @Param("groupLinkIds") List<Long> groupLinkIds,
            @Param("deletedAt") long deletedAt);

    int softDeleteGroupsWithoutActiveAliasByLabel(
            @Param("tenantId") Long tenantId,
            @Param("labelIds") List<Long> labelIds,
            @Param("deletedAt") long deletedAt);

    int clearGroupFolders(
            @Param("tenantId") Long tenantId,
            @Param("folderIds") List<Long> folderIds,
            @Param("updatedAt") long updatedAt);

}
