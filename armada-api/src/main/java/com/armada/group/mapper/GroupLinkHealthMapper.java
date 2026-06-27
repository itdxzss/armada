package com.armada.group.mapper;

import com.armada.group.model.entity.GroupLinkHealth;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 群链接健康状态数据访问。 */
@Mapper
public interface GroupLinkHealthMapper {

    int upsert(GroupLinkHealth row);

    GroupLinkHealth selectByGroupLinkId(@Param("groupLinkId") Long groupLinkId);
}
