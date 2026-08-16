package com.armada.group.mapper;

import com.armada.group.model.dto.GroupLinkQuery;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.entity.WhatsappGroupMemberSnapshot;
import com.armada.group.model.vo.GroupLinkVoRow;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 基于六表当前事实、保留现有群链接行句柄的群列表读取。 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface GroupListCurrentMapper {

    /**
     * 按现有列表筛选口径统计群链接行句柄数量。
     *
     * @param tenantId 租户 ID
     * @param query 现有列表筛选参数
     * @return 符合条件的行数
     */
    long count(@Param("tenantId") Long tenantId, @Param("query") GroupLinkQuery query);

    /**
     * 先分页群链接行句柄，再按本页群批量补齐六表当前事实。
     *
     * @param tenantId 租户 ID
     * @param query 现有列表筛选及分页参数
     * @return 当前页列表行
     */
    List<GroupLinkVoRow> selectPage(
            @Param("tenantId") Long tenantId,
            @Param("query") GroupLinkQuery query);

    /** 批量读取群入口对应的 WhatsApp 当前真实群名。 */
    List<GroupLinkVoRow> selectWhatsAppGroupNames(
            @Param("tenantId") Long tenantId,
            @Param("groupLinkIds") List<Long> groupLinkIds);

    /** 读取群详情当前资料；沿用现有详情投影，避免改变业务层字段语义。 */
    GroupLinkPreview selectGroupDetail(
            @Param("tenantId") Long tenantId,
            @Param("groupLinkId") Long groupLinkId);

    /** 读取当前仍在群的成员，结果字段保持现有详情成员快照口径。 */
    List<WhatsappGroupMemberSnapshot> selectGroupDetailMembers(
            @Param("tenantId") Long tenantId,
            @Param("groupLinkId") Long groupLinkId);
}
