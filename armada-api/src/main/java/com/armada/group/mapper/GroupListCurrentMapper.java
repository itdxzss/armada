package com.armada.group.mapper;

import com.armada.group.model.dto.GroupLinkQuery;
import com.armada.group.model.vo.GroupLinkVoRow;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 基于六表当前事实、保留 legacy 行句柄的群列表影子读取。 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface GroupListCurrentMapper {

    /** 按现有列表筛选口径统计 legacy 行句柄数量。 */
    long count(@Param("tenantId") Long tenantId, @Param("query") GroupLinkQuery query);

    /** 先分页 legacy 行句柄，再按本页群批量补齐六表当前事实。 */
    List<GroupLinkVoRow> selectPage(
            @Param("tenantId") Long tenantId,
            @Param("query") GroupLinkQuery query);
}
