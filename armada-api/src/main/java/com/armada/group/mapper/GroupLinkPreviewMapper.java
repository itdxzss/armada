package com.armada.group.mapper;

import com.armada.group.model.entity.GroupLinkPreview;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 群链接协议预览元数据访问。 */
@Mapper
public interface GroupLinkPreviewMapper {

    /**
     * 写入或刷新协议层预览快照。
     *
     * @param row 预览快照
     * @return 影响行数
     */
    int upsert(GroupLinkPreview row);

    /**
     * 写入公开邀请页识别出的群名/头像,不覆盖协议层预览专属字段。
     *
     * @param row 公开页元数据
     * @return 影响行数
     */
    int upsertInvitePageMetadata(GroupLinkPreview row);

    /**
     * 仅更新运营侧头像 URL,不覆盖协议层预览快照字段。
     *
     * @param groupLinkId 群链接 ID
     * @param avatarUrl   头像 URL;null 表示清空
     * @param updatedAt   更新时间(epoch毫秒)
     * @return 影响行数
     */
    int upsertAvatarUrl(@Param("groupLinkId") Long groupLinkId,
                        @Param("avatarUrl") String avatarUrl,
                        @Param("updatedAt") long updatedAt);

    /**
     * 按群链接 ID 查询预览快照。
     *
     * @param groupLinkId 群链接 ID
     * @return 预览快照;不存在时返回 null
     */
    GroupLinkPreview selectByGroupLinkId(@Param("groupLinkId") Long groupLinkId);
}
