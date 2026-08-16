package com.armada.group.mapper;

import com.armada.group.model.entity.GroupLinkPreview;
import java.util.List;
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
     * 幂等绑定原群入口与 WhatsApp 群 JID，不修改当前邀请码和健康状态。
     *
     * @param row 群入口、群 JID 与观察时间
     * @return 影响行数
     */
    int upsertGroupJidBinding(GroupLinkPreview row);

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
     * 批量查询有 WhatsApp 真实群名称的预览快照。
     *
     * @param groupLinkIds 群链接 ID；null 或空集合安全返回空列表
     * @return 当前租户内群名非空白的预览快照，按群链接 ID 升序
     */
    List<GroupLinkPreview> selectByGroupLinkIds(
            @Param("groupLinkIds") List<Long> groupLinkIds);

    /**
     * 按当前邀请码查询仍活跃的原群入口，避免链接轮换后登记出重复群。
     *
     * @param inviteCode 当前邀请码
     * @return 原群入口 ID；未匹配时返回 null
     */
    Long selectActiveGroupLinkIdByInviteCode(@Param("inviteCode") String inviteCode);

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

    /** 写入已由实时回读确认的群资料编辑权限。 */
    int updateAdminOnlyEditInfo(@Param("groupLinkId") Long groupLinkId,
                                @Param("adminOnly") boolean adminOnly,
                                @Param("updatedAt") long updatedAt);

    /** 写入已由实时回读确认的群发言模式。 */
    int updateAnnounceOnly(@Param("groupLinkId") Long groupLinkId,
                           @Param("announceOnly") boolean announceOnly,
                           @Param("updatedAt") long updatedAt);

    /** 写入已由实时回读确认的普通成员添加成员权限。 */
    int updateMemberAddMode(@Param("groupLinkId") Long groupLinkId,
                            @Param("memberAddMode") boolean memberAddMode,
                            @Param("updatedAt") long updatedAt);

    /** 写入已由实时回读确认的普通成员链接邀请权限。 */
    int updateMemberLinkMode(@Param("groupLinkId") Long groupLinkId,
                             @Param("memberLinkMode") boolean memberLinkMode,
                             @Param("updatedAt") long updatedAt);

    /** 写入已由实时回读确认的新成员审批权限。 */
    int updateJoinApprovalMode(@Param("groupLinkId") Long groupLinkId,
                               @Param("joinApprovalMode") boolean joinApprovalMode,
                               @Param("updatedAt") long updatedAt);
}
