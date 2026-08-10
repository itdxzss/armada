package com.armada.group.service;

import com.armada.group.model.dto.GroupInviteLinkChangedEvent;

/** 当前群邀请链接事实服务。 */
public interface GroupInviteLinkService {

    /**
     * 按群 JID 幂等保存协议观察到的当前邀请码。
     *
     * @param event 已校验的邀请链接变更事实
     */
    void apply(GroupInviteLinkChangedEvent event);

    /**
     * 读取群入口当前邀请码；尚未观察到新链接时回退任务冻结值。
     *
     * @param groupLinkId 群入口 ID，可空
     * @param frozenInviteCode 任务创建时冻结的邀请码
     * @return 当前可用于协议进群的邀请码
     */
    String resolveCurrentInviteCode(Long groupLinkId, String frozenInviteCode);
}
