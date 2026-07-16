package com.armada.platform.protocol.port;

/** 群组设置协议端口。 */
public interface GroupSettingsPort {

    /**
     * 设置群消息自动消失时长。
     *
     * @param protocolAccountId 协议层账号 ID
     * @param groupJid          WhatsApp 群 JID
     * @param durationSeconds   自动消失秒数,0 表示关闭
     */
    void setEphemeralDuration(
            String protocolAccountId,
            String groupJid,
            int durationSeconds);

    /** 设置普通成员是否可以编辑群资料。 */
    void setEditGroupSettingsAllowed(
            String protocolAccountId, String groupJid, boolean enabled);

    /** 设置普通成员是否可以发送消息。 */
    void setSendMessagesAllowed(
            String protocolAccountId, String groupJid, boolean enabled);

    /** 设置普通成员是否可以添加成员。 */
    void setAddMembersAllowed(
            String protocolAccountId, String groupJid, boolean enabled);

    /** 设置是否允许通过邀请链接入群。 */
    void setInviteViaLinkAllowed(
            String protocolAccountId, String groupJid, boolean enabled);

    /** 设置是否启用管理员入群审批。 */
    void setJoinApprovalEnabled(
            String protocolAccountId, String groupJid, boolean enabled);
}
