package com.armada.group.mapper;

import com.armada.group.model.dto.GroupCurrentLocalProfileWrite;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 旧群入口本地字段向新群模型双写的数据访问。 */
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

}
