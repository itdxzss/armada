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
     * 按观察时间原子写入完整群详情快照；晚到的旧观察不得覆盖新快照。
     *
     * @param row 群详情快照及各 nullable 字段观察标记
     * @return 影响行数
     */
    int upsertMetadataSnapshot(GroupLinkPreview row);

    /**
     * 写入公开邀请页识别出的群名/头像,不覆盖协议层预览专属字段。
     *
     * @param row 公开页元数据
     * @return 影响行数
     */
    int upsertInvitePageMetadata(GroupLinkPreview row);

    /**
     * 按独立观察时间保存当前邀请码，晚到事件不得覆盖新事实。
     *
     * @param row 群入口、群 JID、邀请码与观察时间
     * @return 影响行数
     */
    int upsertInviteLinkChange(GroupLinkPreview row);

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

    /**
     * 按群 JID 保存最新邀请码,不覆盖其它群预览字段。
     *
     * @param groupJid WhatsApp 群 JID
     * @param inviteCode 当前邀请码
     * @param updatedAt 更新时间(epoch 毫秒)
     * @return 影响行数
     */
    int updateInviteCodeByGroupJid(@Param("groupJid") String groupJid,
                                   @Param("inviteCode") String inviteCode,
                                   @Param("updatedAt") long updatedAt);
}
