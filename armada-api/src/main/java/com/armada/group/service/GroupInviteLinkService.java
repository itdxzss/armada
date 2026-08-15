package com.armada.group.service;

import com.armada.group.model.dto.GroupInviteLinkObservation;
import java.util.Optional;

/** 当前群邀请链接事实服务。 */
public interface GroupInviteLinkService {

    /**
     * 幂等保存任一可信来源观察到的当前邀请码，并同步恢复链接健康状态。
     *
     * @param observation 已校验的当前邀请码事实
     */
    void applyCurrentInvite(GroupInviteLinkObservation observation);

    /**
     * 管理员成功进群后绑定原群入口与 WhatsApp 群 JID，供随后链接变更事件定位原记录。
     *
     * @param groupLinkId 原群入口 ID
     * @param groupJid WhatsApp 群 JID
     * @param observedAt 成功进群时间(epoch 毫秒)
     */
    void bindGroupJid(Long groupLinkId, String groupJid, long observedAt);

    /** 保存导入校验已接受的公开邀请页资料。 */
    void applyPublicPreview(
            Long groupLinkId, Long labelId, GroupInvitePageMetadata metadata, long observedAt);

    /**
     * 读取群入口当前邀请码；尚未观察到新链接时回退任务冻结值。
     *
     * @param groupLinkId 群入口 ID，可空
     * @param frozenInviteCode 任务创建时冻结的邀请码
     * @return 当前可用于协议进群的邀请码
     */
    String resolveCurrentInviteCode(Long groupLinkId, String frozenInviteCode);

    /**
     * 为失效链接寻找当前可重试的邀请码。
     *
     * <p>优先返回 WhatsApp 被动推送已经写入的不同 code；本地尚无替代值时再由实现查询群管理员。</p>
     *
     * @param groupLinkId 群入口 ID
     * @param groupJid 已知群 JID，可空
     * @param attemptedInviteCode 本次失败使用或任务冻结的邀请码
     * @return 与失败邀请码不同的当前 code；无法取得时为空
     */
    Optional<String> refreshCurrentInviteCode(
            Long groupLinkId, String groupJid, String attemptedInviteCode);
}
