package com.armada.group.mapper;

import com.armada.group.model.entity.WhatsappGroupMemberSnapshot;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** WhatsApp 群最后一次完整成员快照数据访问。 */
@Mapper
public interface WhatsappGroupMemberSnapshotMapper {

    /**
     * 批量写入同一次完整成员快照。
     *
     * @param rows 已规范化成员行
     * @return 写入行数
     */
    int insertBatch(@Param("rows") List<WhatsappGroupMemberSnapshot> rows);

    /**
     * 删除当前租户指定群入口的旧快照。
     *
     * @param groupLinkId 群入口 ID
     * @return 删除行数
     */
    int deleteByGroupLinkId(@Param("groupLinkId") Long groupLinkId);

    /**
     * 查询当前租户指定群入口的最后一次完整成员快照。
     *
     * @param groupLinkId 群入口 ID
     * @return 群主、管理员优先的成员列表
     */
    List<WhatsappGroupMemberSnapshot> selectByGroupLinkId(
            @Param("groupLinkId") Long groupLinkId);

    /**
     * 按显式租户和群 JID 批量查询可供其他业务复用的完整成员快照。
     *
     * @param tenantId 租户 ID
     * @param groupJids 已规范化群 JID
     * @return 所有匹配群入口的快照成员；调用方负责选择最新完整快照
     */
    @InterceptorIgnore(tenantLine = "true")
    List<WhatsappGroupMemberSnapshot> selectByGroupJids(
            @Param("tenantId") Long tenantId,
            @Param("groupJids") List<String> groupJids);
}
