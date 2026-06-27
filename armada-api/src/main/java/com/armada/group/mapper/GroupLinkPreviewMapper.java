package com.armada.group.mapper;

import com.armada.group.model.entity.GroupLinkPreview;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 群链接协议预览元数据访问。 */
@Mapper
public interface GroupLinkPreviewMapper {

    int upsert(GroupLinkPreview row);

    GroupLinkPreview selectByGroupLinkId(@Param("groupLinkId") Long groupLinkId);
}
